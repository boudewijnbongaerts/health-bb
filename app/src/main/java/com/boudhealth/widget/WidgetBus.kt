package com.boudhealth.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Widget refresh entry points.
 *
 *  - [updateAll] (suspend): redraws every Health BB widget instance: Inner, Cover and Rings. Refresh.publish calls
 *    `DashboardWidget().updateAll(app)`, which is the same thing (all three receivers share the DashboardWidget class),
 *    so every sync (RefreshWorker → Refresh.run → publish) and every prefs edit (palette, theme, goals, widget layouts
 *    via the prefs editor → Refresh.publish) reaches all three.
 *  - [request]: fire-and-forget version for callers without a coroutine.
 *
 * System light/dark switches need nothing here: every image is sent as a day/night pair and the launcher picks one.
 */
object WidgetBus {
    private val _tick = MutableStateFlow(0L)
    /** Bumped on every redraw request; a running widget session re-renders when it changes. */
    val tick: StateFlow<Long> = _tick.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Redraws all widget instances of all three kinds (running sessions via [tick], idle ones via Glance updateAll). */
    suspend fun updateAll(context: Context) {
        val app = context.applicationContext
        _tick.update { it + 1 }
        runCatching { DashboardWidget().updateAll(app) }
    }

    /** Non-suspending [updateAll]. */
    fun request(context: Context) {
        val app = context.applicationContext
        scope.launch { updateAll(app) }
    }
}
