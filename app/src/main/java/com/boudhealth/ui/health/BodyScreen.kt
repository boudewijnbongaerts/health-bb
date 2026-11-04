package com.boudhealth.ui.health

import androidx.compose.runtime.Composable
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.nav.Nav

/**
 * V2: `Route.Body` is the Body tab ([BodySectionScreen], V2_PLAN §2.4). The v3–v6 Body page (and its retired
 * duplicate of the Health tab's Body section) is gone; this entry stays for today's AppShell until T5 routes
 * `Route.Body -> BodySectionScreen(nav)` directly.
 */
@Composable
fun BodyScreen(nav: Nav) = BodySectionScreen(nav)

/** Saves a waist reading now and offers Undo for 5 s (FOUNDATION §8.6). */
fun saveWaistWithUndo(cm: Double, edit: com.boudhealth.ui.nav.PrefsEditor, snackbar: com.boudhealth.ui.components.HBSnackbarState) {
    val t = System.currentTimeMillis()
    edit.addWaist(cm, t)
    snackbar.show("Waist ${Fmt.num(cm, 1)} cm saved", Glyph.Check, onAction = { edit.removeWaist(t) })
}
