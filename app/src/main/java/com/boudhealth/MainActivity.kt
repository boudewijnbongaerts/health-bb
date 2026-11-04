package com.boudhealth

import kotlinx.coroutines.flow.catch
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import com.boudhealth.data.HcReader
import com.boudhealth.data.Prefs
import com.boudhealth.data.ThemeMode
import com.boudhealth.data.prefs
import com.boudhealth.ui.nav.AppShell
import com.boudhealth.ui.nav.AppStatus
import com.boudhealth.ui.nav.LocalAppStatus
import com.boudhealth.ui.nav.LocalPrefs
import com.boudhealth.ui.nav.LayoutStore
import com.boudhealth.ui.nav.Layouts
import com.boudhealth.ui.nav.LocalLayouts
import com.boudhealth.ui.nav.SlotLayout
import com.boudhealth.ui.nav.LocalStore
import com.boudhealth.ui.nav.Route
import com.boudhealth.ui.nav.rememberNav
import com.boudhealth.ui.theme.AppTheme
import com.boudhealth.ui.theme.hbColors
import com.boudhealth.ui.theme.isDark
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import com.boudhealth.ui.theme.LocalHB
import com.boudhealth.work.Refresh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val app get() = application as App
    private val resumeTick = mutableIntStateOf(0)
    /** Route requested by a widget tap / intent (consumed by the shell). */
    private val deepLink = mutableStateOf<Route?>(null)
    /** Tile id from a widget tile tap (hb_metric): opened like a Home tile tap, incl. its period hand-off (V2-027). */
    private val deepTile = mutableStateOf<String?>(null)

    override fun onResume() { super.onResume(); resumeTick.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) takeLink(intent) // not again after a recreate
        // v3.1: no runBlocking. The window is painted in the last saved theme's surface right away (cached in a tiny
        // SharedPreferences, plus the per-app night mode the system already applied), and the UI is composed only once
        // the real prefs have loaded, so there is no wrong-theme or wrong-palette first frame.
        val bg = windowBackground()
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        setContent { Root(bg) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeLink(intent)
    }

    private fun takeLink(i: Intent?) {
        // "<tile id>" or "<tile id>@<size>" (the size lets Heart points L/XL open the week, V2-027)
        val tile = i?.getStringExtra(EXTRA_METRIC)?.trim()?.takeIf { it.isNotEmpty() }
        if (tile != null && Route.forTile(tile) != null) { deepTile.value = tile; return }
        routeOf(i)?.let { deepLink.value = it }
    }

    private fun routeOf(i: Intent?): Route? {
        i ?: return null
        i.getStringExtra(EXTRA_METRIC)?.let { id -> return Route.fromLink("metric:$id") }
        i.getStringExtra(EXTRA_ROUTE)?.let { link -> return Route.fromLink(link) }
        // legacy section widget: RINGS / WORKOUTS / BODY / VITALS
        return i.getStringExtra(EXTRA_SECTION)?.let { Route.fromLink(it) }
    }

    @Composable
    private fun Root(bg: Int) {
        val store = app.store
        val prefsFlow = remember { store.prefs }
        val loaded by prefsFlow.collectAsState(initial = null as Prefs?)
        // any read failure: defaults (never a blank start frame)
        val layoutsFlow = remember { LayoutStore.stored(this).catch { emit(emptyMap()) } }
        val storedLayouts by layoutsFlow.collectAsState(initial = null as Map<String, SlotLayout>?)
        val prefs = loaded
        val stored = storedLayouts
        if (prefs == null || stored == null) {
            // a few ms while DataStore reads: just the window colour of the saved theme
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(bg)),
            )
            return
        }
        // Theme override (v3 D1): Compose resolves light/dark from prefs.themeMode in AppTheme; the same choice is also
        // handed to the system so status bar, system dialogs and the window background follow it. Only with real prefs.
        LaunchedEffect(prefs.themeMode) { applyNightMode(prefs.themeMode) }
        AppTheme(prefs) {
            SystemBars()
            val nav = rememberNav()
            val pending by deepLink
            LaunchedEffect(pending) {
                pending?.let { nav.open(it); deepLink.value = null }
            }
            val pendingTile by deepTile
            LaunchedEffect(pendingTile) {
                pendingTile?.let { nav.openTileLink(it); deepTile.value = null }
            }
            val status = remember { AppStatus() }
            HealthConnectSync(status)
            CompositionLocalProvider(
                LocalPrefs provides prefs,
                LocalLayouts provides Layouts(stored, prefs.ui),
                LocalStore provides store,
                LocalAppStatus provides status,
            ) {
                AppShell(nav)
            }
        }
    }

    /**
     * Mirror the app theme setting into the per-app night mode (API 31+). Only called when the value differs from the
     * last one applied (kept in a tiny SharedPreferences), because a change recreates the activity.
     */
    private fun applyNightMode(mode: ThemeMode) {
        val want = when (mode) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        runCatching {
            val sp = getSharedPreferences("hb_ui_mode", Context.MODE_PRIVATE)
            if (sp.getString("theme", null) != mode.name) sp.edit().putString("theme", mode.name).apply()
            if (sp.getInt("applied", UiModeManager.MODE_NIGHT_AUTO) == want) return
            sp.edit().putInt("applied", want).apply()
            getSystemService(UiModeManager::class.java)?.setApplicationNightMode(want)
        }
    }

    /** Surface colour of the last saved theme (window background before the first frame). */
    private fun windowBackground(): Int {
        val mode = runCatching {
            getSharedPreferences("hb_ui_mode", Context.MODE_PRIVATE).getString("theme", null)?.let { ThemeMode.valueOf(it) }
        }.getOrNull() ?: ThemeMode.SYSTEM
        val dark = isDark(this, mode)
        return runCatching { hbColors(this, dark).surface.toArgb() }.getOrDefault(if (dark) 0xFF1C1C16.toInt() else 0xFFFBF9F1.toInt())
    }

    /** Status/navigation bar icons follow the app theme (which may differ from the system one). */
    @Composable
    private fun SystemBars() {
        val dark = LocalHB.current.dark
        DisposableEffect(dark) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
            )
            // V2_VISUAL_QA #14: no system contrast scrim under the tab bar (it tinted a band below it)
            if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            onDispose {}
        }
    }

    /** Permission check + foreground refresh on every resume (as before), exposed through [AppStatus]. */
    @Composable
    private fun HealthConnectSync(status: AppStatus) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        /** [force] = user pull / permission result; a plain resume skips the sync when the last one is < 2 min old. */
        suspend fun refresh(force: Boolean = true) {
            status.hcAvailable = withContext(Dispatchers.IO) { HcReader.available(context) }
            if (!status.hcAvailable) return
            val granted: Set<String>? = try {
                HcReader(context).grantedPermissions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // Read permissions + "past data" (where supported) are required: without past data Health Connect hides
            // everything older than 30 days before the first grant. Background stays optional.
            status.missingPermissions = if (granted == null) HcReader.PERMISSIONS else HcReader.requiredPermissions(context) - granted
            // any change in the granted set (incl. optional ones like background, e.g. back from Health Connect
            // settings) bypasses the resume debounce
            val prev = lastGranted
            val permsChanged = granted != null && prev != null && granted != prev
            if (granted != null) lastGranted = granted
            if (status.refreshing) return
            val now = System.currentTimeMillis()
            if (!force && !permsChanged && now - lastForegroundSync < RESUME_SYNC_DEBOUNCE_MS) return
            status.refreshing = true
            try {
                // V2_SYNC_PERF: the indicator covers the Health Connect sync only; snapshot + widgets + history read
                // continue in the background. Screens never wait for this: they show the cache and re-query on the bump.
                status.lastSyncOk = withContext(Dispatchers.IO) { Refresh.runForeground(context) }
                lastForegroundSync = System.currentTimeMillis()
            } finally {
                status.refreshing = false
            }
        }

        val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            scope.launch { refresh() }
        }
        status.requestPermissionsAction = { launcher.launch(HcReader.PERMISSIONS) }
        status.refreshAction = { scope.launch { refresh() } }

        val tick by resumeTick
        LaunchedEffect(tick) { refresh(force = false) }
    }

    companion object {
        /** Resume (incl. coming back from another app) re-syncs only when the last foreground sync is older than this. */
        private const val RESUME_SYNC_DEBOUNCE_MS = 2 * 60_000L
        /** Process-wide, so a recreated activity doesn't sync again either. */
        @Volatile private var lastForegroundSync = 0L
        /** Granted Health Connect permissions at the last check (process-wide). */
        @Volatile private var lastGranted: Set<String>? = null

        /** Intent extra: a tile id from a widget tile tap; opens like a Home tile tap (Route.forTile, V2_PLAN 2.1). */
        const val EXTRA_METRIC = "hb_metric"
        /** Intent extra: a route link, see Route.fromLink ("vitals", "all_metrics", "metric:rhr", "session:<ms>", …). */
        const val EXTRA_ROUTE = "hb_route"
        /** LEGACY: section name from the old section widget (RINGS / WORKOUTS / BODY / VITALS). */
        const val EXTRA_SECTION = "hb_section"
    }
}
