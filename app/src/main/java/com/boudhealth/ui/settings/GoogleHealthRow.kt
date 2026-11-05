package com.boudhealth.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.boudhealth.data.GhAccount
import com.boudhealth.data.GoogleHealth
import com.boudhealth.ui.components.ButtonKind
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.LocalSnackbar
import com.boudhealth.ui.components.SettingsRow
import com.boudhealth.ui.nav.LocalStore
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Settings → Data & sync: the one "Google Health" row (VO₂ max read directly from the Google Health API).
 * Not connected: "Connect". Grant expired: "Connected as {email}" + "Reconnect" (R5 #39). Connected: "Connected as {email}"
 * on line 1 (ellipsis), the last fetch on line 2 (R5 #49) + "Disconnect".
 * Texts: vo2/TEXTS_NEW.md (owner approval). Errors: the existing alert toast.
 */
@Composable
internal fun GoogleHealthRow() {
    val ctx = LocalContext.current
    val store = LocalStore.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val accountFlow = remember(store) { store.googleHealth }
    val account by accountFlow.collectAsState(initial = GhAccount())
    val busy by GoogleHealth.busy.collectAsState()

    LaunchedEffect(Unit) { GoogleHealth.events.collect { snackbar.toast(it, Glyph.Alert) } }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r: ActivityResult ->
        GoogleHealth.onConsentResult(ctx, r.resultCode, r.data)
    }
    fun connect() {
        scope.launch { GoogleHealth.beginConnect(ctx)?.let { consent.launch(IntentSenderRequest.Builder(it).build()) } }
    }
    // the reconnect notification needs this permission; asked once on Connect, Connect goes on either way
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _: Boolean -> connect() }

    val ok = account.connected && !account.needsReconnect
    val reconnect = account.connected && account.needsReconnect
    val subtitle = if (account.connected) account.email?.let { "Connected as $it" } else null
    // same format as "Last synced"
    val time = if (!ok) null else account.lastFetch?.let { t -> Fmt.timestamp(t).let { s -> if (Fmt.date(t) == LocalDate.now()) "Today $s" else s } }

    SettingsRow(GoogleHealth.TEXT_TITLE, subtitle = subtitle ?: time, subtitleLine2 = time.takeIf { subtitle != null }, leadingGlyph = Glyph.Vo2Max) {
        if (ok) {
            HBButton("Disconnect", { GoogleHealth.disconnect(ctx) }, kind = ButtonKind.Tonal, small = true, enabled = !busy)
        } else {
            HBButton(if (reconnect) GoogleHealth.TEXT_RECONNECT_BUTTON else "Connect", {
                if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else connect()
            }, kind = ButtonKind.Tonal, small = true, enabled = !busy)
        }
    }
}
