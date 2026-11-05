package com.boudhealth.widget

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import com.boudhealth.R

/**
 * V2-122/126: the clock part of "Health BB · Clock" as RemoteViews. TextClocks tick on their own (the app never wakes up
 * per minute). One layout per font (D21: Light, Bold, Serif, Mono; system families, see ClockFont). Sizes and margins
 * come from [ClockGeometry] in dp (not sp: the box is measured in dp, a user font scale must not push the digits off
 * the ring line). The RemoteViews box itself is placed by DashboardWidget at [ClockGeometry.clock].
 */
object ClockViews {
    fun layoutOf(f: ClockFont): Int = when (f) {
        ClockFont.LIGHT -> R.layout.hb_clock_light
        ClockFont.BOLD -> R.layout.hb_clock_bold
        ClockFont.SERIF -> R.layout.hb_clock_serif
        ClockFont.MONO -> R.layout.hb_clock_mono
    }

    /**
     * [inkDay]/[inkNight] = text colour per launcher theme (white with shadow on a clear background; theme text on a
     * solid one), [accentDay]/[accentNight] = Bold minutes / Mono date (the workout ring colour).
     */
    fun build(pkg: String, f: ClockFont, g: ClockGeometry, showDate: Boolean, inkDay: Int, inkNight: Int, accentDay: Int, accentNight: Int): RemoteViews {
        val dp = TypedValue.COMPLEX_UNIT_DIP
        return RemoteViews(pkg, layoutOf(f)).apply {
            setInt(R.id.hb_clock_root, "setGravity", if (g.wide) (Gravity.START or Gravity.TOP) else (Gravity.CENTER_HORIZONTAL or Gravity.TOP))
            setTextViewTextSize(R.id.hb_clock_time, dp, g.timeSize)
            setColorInt(R.id.hb_clock_time, "setTextColor", inkDay, inkNight)
            if (f.stacked) {
                setTextViewTextSize(R.id.hb_clock_min, dp, g.timeSize)
                setViewLayoutMargin(R.id.hb_clock_min, RemoteViews.MARGIN_TOP, g.line2Top, dp)
                setColorInt(R.id.hb_clock_min, "setTextColor", accentDay, accentNight)
            }
            setTextViewTextSize(R.id.hb_clock_date, dp, g.dateSize)
            setViewLayoutMargin(R.id.hb_clock_date, RemoteViews.MARGIN_TOP, g.dateTop, dp)
            // wide: indent the date by the time font's side bearing so its left edge meets the digits' ink (0 otherwise)
            setViewLayoutMargin(R.id.hb_clock_date, RemoteViews.MARGIN_START, ClockPaint.dateIndent(g, ClockPaint.measure(f)), dp)
            if (f.dateAccent) setColorInt(R.id.hb_clock_date, "setTextColor", accentDay, accentNight)
            else setColorInt(R.id.hb_clock_date, "setTextColor", inkDay, inkNight)
            setViewVisibility(R.id.hb_clock_date, if (showDate) View.VISIBLE else View.GONE)
        }
    }
}
