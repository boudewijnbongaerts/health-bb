package com.boudhealth.ui.nav

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.boudhealth.data.Goals
import com.boudhealth.data.Prefs
import com.boudhealth.data.Store
import com.boudhealth.data.ThemeMode
import com.boudhealth.data.TileSpec
import com.boudhealth.data.TileSurface
import com.boudhealth.data.resetLayout
import com.boudhealth.data.setBirthYear
import com.boudhealth.data.setHomeHintSeen
import com.boudhealth.data.setLayout
import com.boudhealth.data.setPalette
import com.boudhealth.data.setShowTodayPill
import com.boudhealth.data.setThemeMode
import com.boudhealth.ui.components.EmptyState
import com.boudhealth.ui.components.ErrorState
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.theme.LocalHB
import com.boudhealth.work.Refresh
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import com.boudhealth.data.prefs
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Current preferences (theme, palette, layouts, goals, manual height). Provided by MainActivity; recomposes on change. */
val LocalPrefs = compositionLocalOf { Prefs() }

/** The app's Store (goals, waist, height, snapshot, UI prefs). */
val LocalStore = staticCompositionLocalOf<Store> { error("No Store provided") }

/** Health Connect / sync status for connect cards, pull-to-refresh and ↻ buttons. Provided by MainActivity. */
val LocalAppStatus = staticCompositionLocalOf { AppStatus() }

@Stable
class AppStatus {
    /** Health Connect installed and usable. */
    var hcAvailable by mutableStateOf(true)
        internal set
    /** Required permissions not granted; null = not checked yet. */
    var missingPermissions by mutableStateOf<Set<String>?>(null)
        internal set
    /** A foreground Refresh.run is in progress. (Backfill progress: collect Sync.state.) */
    var refreshing by mutableStateOf(false)
        internal set
    /** Last foreground sync result (false = Health Connect read failed; stale data is shown). */
    var lastSyncOk by mutableStateOf(true)
        internal set

    internal var requestPermissionsAction: () -> Unit = {}
    internal var refreshAction: () -> Unit = {}

    val needsConnect: Boolean get() = !hcAvailable || missingPermissions?.isNotEmpty() == true

    /** Opens the Health Connect permission dialog (all read types + history + background). */
    fun requestPermissions() = requestPermissionsAction()
    /** Foreground refresh: Sync + publish (same as on resume). */
    fun refresh() = refreshAction()
}

/**
 * The "Connect Health Connect" card: shows only when Health Connect is missing or permissions are missing.
 * Place it at the top of tab screens (Home / Activity / Health).
 */
@Composable
fun ConnectCard(modifier: Modifier = Modifier) {
    val status = LocalAppStatus.current
    val hb = LocalHB.current
    when {
        !status.hcAvailable -> ErrorState(
            modifier.fillMaxWidth(),
            title = "Health Connect is not available",
            body = "Install or update Health Connect, then reopen Health BB.",
        )
        status.missingPermissions?.isNotEmpty() == true -> EmptyState(
            Glyph.Shield, hb.colors.accent,
            title = "Connect Health Connect",
            body = "Health BB only reads your data. Allow all items, including \"past data\" (long-term trends) and " +
                "\"access in the background\" (keeps the widget fresh).",
            modifier = modifier,
            actionLabel = "Allow",
            onAction = { status.requestPermissions() },
        )
    }
}

/**
 * Save failures (v3.1): a failed preference / manual-entry save is logged and surfaced as an error toast by AppShell,
 * instead of crashing the process through the default uncaught-exception path.
 */
object SaveErrors {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events
    internal fun report(message: String) { _events.tryEmit(message) }
}

private val saveErrorHandler = CoroutineExceptionHandler { _, e ->
    android.util.Log.e("HealthBB", "save failed", e)
    SaveErrors.report(
        if (e is IllegalArgumentException && e.message?.contains("range") == true) "Couldn't save: value out of range"
        else "Couldn't save that change. Try again.",
    )
}

/** Process-wide scope so saves finish even if the screen that started them is gone. */
private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + saveErrorHandler)

/**
 * Saves preferences / manual entries and republishes the widgets when needed (Refresh.publish: no HC calls).
 *
 *     val edit = rememberPrefsEditor()
 *     edit.setPalette("glacier"); edit.updateGoals { it.copy(steps = 9000) }; edit.setLayout(TileSurface.APP, tiles)
 */
class PrefsEditor(context: Context, private val store: Store) {
    private val ctx = context.applicationContext

    private fun run(publish: Boolean, block: suspend () -> Unit) {
        appScope.launch {
            block()
            if (publish) Refresh.publish(ctx)
        }
    }

    fun setThemeMode(mode: ThemeMode) = run(true) { store.setThemeMode(mode) }
    fun setPalette(id: String) = run(true) { store.setPalette(id) }
    fun setShowTodayPill(show: Boolean) = run(false) { store.setShowTodayPill(show) }
    fun setHomeHintSeen() = run(false) { store.setHomeHintSeen() }
    fun setLayout(surface: TileSurface, tiles: List<TileSpec>) = run(surface != TileSurface.APP) { store.setLayout(surface, tiles) }
    fun resetLayout(surface: TileSurface) = run(surface != TileSurface.APP) { store.resetLayout(surface) }

    /**
     * v4 (Q18): save a slot layout ("home", "w1", "w2", "w3"; see Layouts.kt for the shared key contract) with its
     * column count (4 or 8). Widget slots republish the widgets.
     */
    fun setLayout(slot: String, tiles: List<TileSpec>, columns: Int) =
        run(slot != LayoutSlots.HOME) { LayoutStore.update(ctx, slot, uiNow()) { it.copy(columns = columns, tiles = tiles) } }
    /** v5 W3: widget background opacity (0–100 %) and height in rows (0 = fit tiles). */
    fun setWidgetLook(slot: String, opacity: Int, rows: Int) =
        run(true) { LayoutStore.update(ctx, slot, uiNow()) { it.copy(opacity = opacity, rows = rows) } }
    private suspend fun uiNow(): com.boudhealth.data.UiPrefs =
        runCatching { store.prefs.first().ui }.getOrDefault(com.boudhealth.data.UiPrefs())
    /** Tiles and columns back to the default; a widget keeps its opacity and rows. */
    fun resetLayout(slot: String) = run(slot != LayoutSlots.HOME) {
        if (slot == LayoutSlots.HOME) LayoutStore.reset(ctx, slot)
        else { val ui = uiNow(); val d = LayoutSlots.default(slot, ui); LayoutStore.update(ctx, slot, ui) { it.copy(columns = d.columns, tiles = d.tiles) } }
    }
    fun setName(name: String?) = run(false) { store.updateGoals { it.copy(name = name?.trim()?.takeIf { n -> n.isNotEmpty() }?.take(40)) } }
    fun setMaxHr(bpm: Int?) = run(true) { store.updateGoals { it.copy(maxHr = bpm) } }
    fun setPaletteId(id: String) = run(true) { store.setPalette(id) }
    fun updateGoals(transform: (Goals) -> Goals) = run(true) { store.updateGoals(transform) }
    fun setBirthYear(year: Int?) = run(true) { store.setBirthYear(year) }
    /** Manual height (cm, 100–250). Health Connect height wins in calculations when present. */
    fun setHeightCm(cm: Double) = run(true) { store.setHeightCm(cm) }
    fun addWaist(cm: Double, time: Long = System.currentTimeMillis()) = run(true) { store.addWaist(cm, time) }
    fun removeWaist(time: Long) = run(true) { store.removeWaist(time) }
}

@Composable
fun rememberPrefsEditor(): PrefsEditor {
    val ctx = LocalContext.current
    val store = LocalStore.current
    return remember(store) { PrefsEditor(ctx, store) }
}
