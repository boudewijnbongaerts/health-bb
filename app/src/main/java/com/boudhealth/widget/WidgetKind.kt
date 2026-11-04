package com.boudhealth.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.boudhealth.ui.nav.LayoutSlots

/**
 * The receiver a widget was placed from. V2 (D12, V2-032): the picker shows exactly two entries, "Health BB"
 * (DashboardWidgetReceiver, family TILES) and "Health BB · Clock" (RingsWidgetReceiver, family CLOCK). The other six
 * receivers stay in the manifest (hide_from_picker | reconfigurable; a removed receiver class would kill placed widgets)
 * and their placed widgets are migrated by kind into one of the two families (WidgetMigration, V2_PLAN 2.8).
 * All share one GlanceAppWidget class ([DashboardWidget]), so `DashboardWidget().updateAll(context)` reaches every instance.
 *
 * | Kind     | Receiver                | Picker  | Family after migration                          |
 * |----------|-------------------------|---------|-------------------------------------------------|
 * | W1       | DashboardWidgetReceiver | Health BB          | TILES (own v6 tiles, else slot "w1")  |
 * | W2 / W3  | Cover / Widget3Receiver | hidden  | TILES (own v6 tiles, else slot "w2" / "w3")     |
 * | RINGS    | RingsWidgetReceiver     | Health BB · Clock  | CLOCK                                 |
 * | WEEKLY   | WeeklyWidgetReceiver    | hidden  | TILES weekly_goals L                            |
 * | CARDIO   | CardioWidgetReceiver    | hidden  | TILES zone_min L                                |
 * | RECENT   | RecentWidgetReceiver    | hidden  | TILES yesterday_workouts L                      |
 * | WORKOUTS | WorkoutsWidgetReceiver  | hidden  | TILES week_workouts M                           |
 */
enum class WidgetKind(val slot: String?, val editRoute: String?) {
    W1(LayoutSlots.W1, "edit_widget_1"),
    W2(LayoutSlots.W2, "edit_widget_2"),
    W3(LayoutSlots.W3, "edit_widget_3"),
    RINGS(null, null),
    WEEKLY(null, null),
    CARDIO(null, null),
    RECENT(null, null),
    WORKOUTS(null, null);

    /** V2: the family a widget of this kind belongs to (CLOCK only for the Rings receiver). */
    val family: WidgetFamily get() = if (this == RINGS) WidgetFamily.CLOCK else WidgetFamily.TILES

    val receiverClassName: String
        get() = when (this) {
            W1 -> DashboardWidgetReceiver::class.java.name
            W2 -> CoverWidgetReceiver::class.java.name
            W3 -> Widget3Receiver::class.java.name
            RINGS -> RingsWidgetReceiver::class.java.name
            WEEKLY -> WeeklyWidgetReceiver::class.java.name
            CARDIO -> CardioWidgetReceiver::class.java.name
            RECENT -> RecentWidgetReceiver::class.java.name
            WORKOUTS -> WorkoutsWidgetReceiver::class.java.name
        }

    companion object {
        /** The kind of a placed widget, from the receiver (provider) it belongs to; [fallback] if the launcher doesn't say. */
        fun of(context: Context, id: GlanceId, fallback: WidgetKind): WidgetKind {
            val cls = runCatching {
                val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
                AppWidgetManager.getInstance(context).getAppWidgetInfo(appWidgetId)?.provider?.className
            }.getOrNull() ?: return fallback
            return entries.firstOrNull { it.receiverClassName == cls } ?: fallback
        }
    }
}
