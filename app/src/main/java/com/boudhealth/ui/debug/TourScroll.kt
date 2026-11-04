package com.boudhealth.ui.debug

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * V2-155 (T8, debug tour, V2_VISUAL_QA §4.1): the vertical [ScrollState] of the page on top, so the screenshot tour can
 * start each page at the top and walk it down. Every scrolling root registers itself with one call
 * ([RegisterTourScroll]: SectionScreen, DetailScaffold, HomeScreen, SessionScreen, widget setup). Outside a tour run
 * this only stores a reference; nothing changes for the user.
 */
object TourScroll {
    /** The scroll state of the most recently shown scrolling page. */
    @Volatile var current: ScrollState? = null

    /** The activity that page lives in (the main activity, or the widget setup activity). */
    @Volatile var activity: android.app.Activity? = null

    /** True while the tour runs: pinned overlays (sticky period bar, collapsed header) stay hidden so parts stitch. */
    var active by mutableStateOf(false)
}

@Composable
fun RegisterTourScroll(s: ScrollState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(s) {
        TourScroll.current = s
        TourScroll.activity = ctx.activityOrNull()
        onDispose { if (TourScroll.current === s) { TourScroll.current = null; TourScroll.activity = null } }
    }
}

internal tailrec fun android.content.Context.activityOrNull(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.activityOrNull()
    else -> null
}
