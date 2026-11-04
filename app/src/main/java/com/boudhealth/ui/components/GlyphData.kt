package com.boudhealth.ui.components

/*
 * Glyph data (DESIGN §5.15), Compose-free (V2-029): 24-unit grid, stroke 2 (2.2 inside glyph circles, 2.6 at ≤ 14 dp),
 * round caps/joins, no fills except dots. Path data copied from design2/lib.js GLYPH (+ A_tiles.js / C_kit.js additions).
 * Compose draws it via Glyphs.kt (GlyphIcon); widgets and render/ via render/ring/SvgPath (android.graphics only).
 */

internal fun gCirc(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0z"

internal fun gRRect(x: Float, y: Float, w: Float, h: Float, r: Float): String {
    val rr = minOf(r, w / 2, h / 2)
    return "M${x + rr},${y}H${x + w - rr}A$rr,$rr 0 0,1 ${x + w},${y + rr}V${y + h - rr}A$rr,$rr 0 0,1 ${x + w - rr},${y + h}" +
        "H${x + rr}A$rr,$rr 0 0,1 $x,${y + h - rr}V${y + rr}A$rr,$rr 0 0,1 ${x + rr},${y}z"
}

/** One drawable part: stroked path, or a filled dot. */
class GPart(val d: String, val fill: Boolean = false)

private fun s(d: String) = GPart(d)
private fun dot(cx: Float, cy: Float, r: Float) = GPart(gCirc(cx, cy, r), fill = true)


enum class Glyph(val parts: List<GPart>) {
    Back(listOf(s("M15 5l-7 7 7 7"))),
    ChevR(listOf(s("M9 5l7 7-7 7"))),
    ChevL(listOf(s("M15 5l-7 7 7 7"))),
    ChevD(listOf(s("M6 9l6 6 6-6"))),
    ChevU(listOf(s("M6 15l6-6 6 6"))),
    Plus(listOf(s("M12 5v14M5 12h14"))),
    Minus(listOf(s("M5 12h14"))),
    Check(listOf(s("M5 12.5l4.5 4.5L19 7.5"))),
    Close(listOf(s("M6 6l12 12M18 6L6 18"))),
    More(listOf(dot(5f, 12f, 1.6f), dot(12f, 12f, 1.6f), dot(19f, 12f, 1.6f))),
    /** v3 (D2): a real 8-tooth gear (the old circle + rays read as a sun / theme toggle). */
    Settings(listOf(
        s("M9.77 4.94L10.25 2.56A9.6 9.6 0 0 1 13.75 2.56L14.23 4.94A7.4 7.4 0 0 1 15.42 5.44L17.44 4.09A9.6 9.6 0 0 1 19.91 6.56" +
            "L18.56 8.58A7.4 7.4 0 0 1 19.06 9.77L21.44 10.25A9.6 9.6 0 0 1 21.44 13.75L19.06 14.23A7.4 7.4 0 0 1 18.56 15.42" +
            "L19.91 17.44A9.6 9.6 0 0 1 17.44 19.91L15.42 18.56A7.4 7.4 0 0 1 14.23 19.06L13.75 21.44A9.6 9.6 0 0 1 10.25 21.44" +
            "L9.77 19.06A7.4 7.4 0 0 1 8.58 18.56L6.56 19.91A9.6 9.6 0 0 1 4.09 17.44L5.44 15.42A7.4 7.4 0 0 1 4.94 14.23" +
            "L2.56 13.75A9.6 9.6 0 0 1 2.56 10.25L4.94 9.77A7.4 7.4 0 0 1 5.44 8.58L4.09 6.56A9.6 9.6 0 0 1 6.56 4.09" +
            "L8.58 5.44A7.4 7.4 0 0 1 9.77 4.94Z"),
        s(gCirc(12f, 12f, 3.1f)),
    )),
    Calendar(listOf(s(gRRect(4f, 5.5f, 16f, 14.5f, 4f)), s("M4 10h16M8.5 3.5v4M15.5 3.5v4"))),
    Flame(listOf(s("M12 21c-3.9 0-6.5-2.6-6.5-6.2 0-3.4 2.5-5.4 3.6-8.3.5 1.8 1.6 2.8 2.6 3.2C12 7.2 13.4 4.6 15.6 3c-.3 3 3 5.6 3 10.3C18.5 17.9 15.9 21 12 21z"))),
    Zone(listOf(s(gCirc(12f, 13f, 7.5f)), s("M12 13l3.2-3.2M10 3h4"))),
    Steps(listOf(s(gRRect(4.5f, 2.5f, 6f, 10.5f, 3f)), s(gCirc(7.5f, 17f, 2f)), s(gRRect(13.5f, 6f, 6f, 10.5f, 3f)), s(gCirc(16.5f, 20.2f, 1.6f)))),
    Heart(listOf(s("M12 20s-7.5-4.6-7.5-10.2C4.5 7 6.5 5 9 5c1.4 0 2.4.7 3 1.7.6-1 1.6-1.7 3-1.7 2.5 0 4.5 2 4.5 4.8C19.5 15.4 12 20 12 20z"))),
    Pulse(listOf(s("M3 12h4l2-5 4 10 2.5-5H21"))),
    Hrv(listOf(s("M3 13h3l1.5-3 3 7 2.5-10 2.5 8 1.5-2H21"))),
    Scale(listOf(s(gRRect(3.5f, 3.5f, 17f, 17f, 5f)), s("M8.5 10a5 5 0 0 1 7 0M12 10.5l1.5-2"))),
    Tape(listOf(s(gCirc(10f, 11f, 6.5f)), s(gCirc(10f, 11f, 2f)), s("M16.5 11v6.5H21"))),
    Moon(listOf(s("M19.5 14.5A8 8 0 0 1 9.5 4.5a8 8 0 1 0 10 10z"))),
    Lungs(listOf(s("M12 4v7M12 11l-3 2M12 11l3 2M8.5 7C5.5 7 4 12 4 16.5c0 2 1.5 3 3.5 2.5L9 18.5V9M15.5 7C18.5 7 20 12 20 16.5c0 2-1.5 3-3.5 2.5L15 18.5V9"))),
    Drop(listOf(s("M12 3.5s-6 6.6-6 10.8a6 6 0 0 0 12 0C18 10.1 12 3.5 12 3.5z"))),
    Dumbbell(listOf(s("M8.5 12h7M2.5 10v4M21.5 10v4"), s(gRRect(4.5f, 6.5f, 4f, 11f, 1.6f)), s(gRRect(15.5f, 6.5f, 4f, 11f, 1.6f)))),
    Run(listOf(s(gCirc(15f, 4.5f, 2f)), s("M13 9l-4.5 1.5-2 3.5M13 9l-2 5.5 4 2.5-1 4.5M11 14.5L8 20H4.5M13 9l2.5 3.5H19"))),
    Bike(listOf(s(gCirc(6f, 16f, 3.8f)), s(gCirc(18f, 16f, 3.8f)), s("M6 16l3.5-7h6L18 16M9.5 9L12.5 16H6M14 6h3"))),
    Walk(listOf(s(gCirc(13f, 4.5f, 2f)), s("M12.5 8.5l-1.5 6.5-3 6M11 15l3 2.5.5 4M12.5 8.5l-3.5 2.5-.5 3.5M12.5 8.5l2 3.5 3 1"))),
    Sync(listOf(s("M19 9A7.5 7.5 0 0 0 5.5 8M5 15a7.5 7.5 0 0 0 13.5 1"), s("M19 4.5V9h-4.5M5 19.5V15h4.5"))),
    Info(listOf(s(gCirc(12f, 12f, 8.5f)), s("M12 11v5.5"), dot(12f, 7.8f, 1.1f))),
    Alert(listOf(s("M12 4L21 19.5H3z"), s("M12 10v4.5"), dot(12f, 17f, 1f))),
    CloudOff(listOf(s("M7.5 18.5h9.5a3.5 3.5 0 0 0 .6-7A5.5 5.5 0 0 0 7.2 10 4.3 4.3 0 0 0 7.5 18.5zM4 4l16 16"))),
    Bluetooth(listOf(s("M7 7.5l10 9-5 4.5v-18l5 4.5-10 9"))),
    Edit(listOf(s("M15.5 5.5l3 3L9 18H6v-3z"))),
    Trash(listOf(s("M5 7h14M10 4h4M7 7l1 12.5h8L17 7"))),
    Target(listOf(s(gCirc(12f, 12f, 8.5f)), s(gCirc(12f, 12f, 4.5f)), dot(12f, 12f, 1f))),
    Grid(listOf(s(gRRect(4f, 4f, 7f, 7f, 2.2f)), s(gRRect(13f, 4f, 7f, 7f, 2.2f)), s(gRRect(4f, 13f, 7f, 7f, 2.2f)), s(gRRect(13f, 13f, 7f, 7f, 2.2f)))),
    // A_tiles.js additions
    Bolt(listOf(s("M13.5 3L5.5 13.5h6l-1 7.5 8-10.5h-6z"))),
    Percent(listOf(s("M18 6L6 18"), s(gCirc(7.5f, 7.5f, 2.5f)), s(gCirc(16.5f, 16.5f, 2.5f)))),
    Layers(listOf(s("M12 4l8 4-8 4-8-4z"), s("M4 12l8 4 8-4M4 16l8 4 8-4"))),
    Gauge(listOf(s("M4.6 17.5a8 8 0 1 1 14.8 0"), s("M12 14l3.5-4"))),
    Rings(listOf(s(gCirc(12f, 12f, 8.5f)), s(gCirc(12f, 12f, 4.5f)))),
    Drag(listOf(dot(9f, 6f, 1.4f), dot(15f, 6f, 1.4f), dot(9f, 12f, 1.4f), dot(15f, 12f, 1.4f), dot(9f, 18f, 1.4f), dot(15f, 18f, 1.4f))),
    Sparkle(listOf(s("M12 3v5M12 16v5M3 12h5M16 12h5"))),
    Widget(listOf(s(gRRect(3.5f, 5f, 17f, 14f, 4f)), s("M3.5 11h17M12 11v8"))),
    Search(listOf(s(gCirc(11f, 11f, 6.5f)), s("M16 16l4 4"))),
    // C_kit.js additions
    Shield(listOf(s("M12 3.5l7 2.8v5.4c0 4.3-3 7.6-7 8.8-4-1.2-7-4.5-7-8.8V6.3z"), s("M9 12l2.2 2.2L15.5 10"))),
    Palette(listOf(s("M12 20.5a8.5 8.5 0 1 1 8.5-8.5c0 2.3-1.7 3.4-3.6 3.4h-1.7a1.8 1.8 0 0 0-1.3 3.1 1.2 1.2 0 0 1-.9 2z"), dot(8f, 11f, 1.2f), dot(12f, 7.5f, 1.2f), dot(16f, 11f, 1.2f))),
    Phone(listOf(s(gRRect(6.5f, 3f, 11f, 18f, 3f)), s("M10.5 17.5h3"))),
    Fold(listOf(s(gRRect(3f, 4.5f, 18f, 15f, 3f)), s("M12 4.5v15"))),
    User(listOf(s(gCirc(12f, 8.5f, 3.8f)), s("M4.8 20c.8-3.8 3.7-5.8 7.2-5.8s6.4 2 7.2 5.8"))),
    History(listOf(s("M4.5 12a7.5 7.5 0 1 0 2.2-5.3"), s("M4.5 4.5v3.7h3.7M12 8v4.5l3 2"))),
    Key(listOf(s(gCirc(8f, 12f, 4f)), s("M12 12h8.5M17 12v3M20 12v2.5"))),
    Share(listOf(s("M12 15V4M8 7.5L12 4l4 3.5M6 12v6.5a1.5 1.5 0 0 0 1.5 1.5h9a1.5 1.5 0 0 0 1.5-1.5V12"))),
    Open(listOf(s("M14 4.5h5.5V10M19.5 4.5L11 13M17 14v4.5a1.5 1.5 0 0 1-1.5 1.5h-10A1.5 1.5 0 0 1 4 18.5v-10A1.5 1.5 0 0 1 5.5 7H10"))),
    // foundation additions (same grid / stroke)
    Home(listOf(s("M4 10.5L12 4l8 6.5V18a2 2 0 0 1-2 2h-3.5v-5.5h-5V20H6a2 2 0 0 1-2-2z"))),
    Undo(listOf(s("M9 14L4 9l5-5"), s("M4 9h10.5a5.5 5.5 0 0 1 0 11H11"))),
    ArrowUp(listOf(s("M12 19V5M6 11l6-6 6 6"))),
    ArrowDown(listOf(s("M12 5v14M6 13l6 6 6-6"))),
    // V2.0 (T4, V2-030 / V2-094–096): new glyphs, same 24 grid, stroke 2, round caps
    /** All metrics (top bar, next to Settings): three rows with bullets. */
    List(listOf(s("M9 6.5h11M9 12h11M9 17.5h11"), dot(4.8f, 6.5f, 1.4f), dot(4.8f, 12f, 1.4f), dot(4.8f, 17.5f, 1.4f))),
    /** Body tab: a standing figure. */
    Body(listOf(s(gCirc(12f, 4.6f, 2.2f)), s("M6 9.3c2 .7 4 1 6 1s4-.3 6-1M12 10.3v4.4M12 14.7l-2.6 6M12 14.7l2.6 6"))),
    /** Heart points: heart with a plus (Heart rate keeps the plain heart). */
    HeartPlus(listOf(s("M12 20s-7.5-4.6-7.5-10.2C4.5 7 6.5 5 9 5c1.4 0 2.4.7 3 1.7.6-1 1.6-1.7 3-1.7 2.5 0 4.5 2 4.5 4.8C19.5 15.4 12 20 12 20z"), s("M12 9.6v5.6M9.2 12.4h5.6"))),
    /** Workout minutes: stopwatch (the glyph Steps wrongly showed in v6). */
    Stopwatch(listOf(s(gCirc(12f, 13f, 7.5f)), s("M12 13l3.2-3.2M10 3h4M18.6 6.4l1.4-1.4"))),
    /** Weekly goals: calendar with a check. */
    WeekCheck(listOf(s("M8 3.5v4M16 3.5v4M4 10h16M8.5 15l2.3 2.3L15.5 12.6"), s(gRRect(4f, 5.5f, 16f, 14.5f, 4f)))),
    /** VO₂ max: gauge with a base line (reads apart from Breathing's lungs and Workouts' runner). */
    Vo2Max(listOf(s("M4.6 17.5a8 8 0 1 1 14.8 0M12 14l3.5-4M9.5 20.5h5"))),
    ;

    /** Aliases used in the brief / specs. */
    companion object {
        val Gear = Settings
        val Refresh = Sync
        val Spo2 = Drop
        val Waist = Tape
        /** V2: VO₂ max has its own gauge glyph (was the runner). */
        val Vo2 = Vo2Max
        val Sleep = Moon
        val Calories = Flame
        val Breathing = Lungs
        /** V2 metric glyphs, one per metric (V2-094/095/096): never swap these. */
        val Footsteps = Steps
        val WorkoutMinutes = Stopwatch
        val HeartPoints = HeartPlus
        val HeartRate = Heart
        val AllMetrics = List
        val WeeklyGoals = WeekCheck

        /** The one glyph per metric / tile id (tiles, cards, All metrics rows). */
        fun forMetric(id: String): Glyph = when (id) {
            "steps" -> Steps
            "exercise_min" -> Stopwatch
            "zone_min", "weekly_cardio" -> HeartPlus
            "heart_zones" -> Pulse
            "hr" -> Heart
            "rhr" -> Heart   // design (QA 14.1): resting HR = heart, HRV = its own wave
            "hrv" -> Hrv
            "vo2" -> Vo2Max
            "sleep" -> Moon
            "calories" -> Flame
            "breathing" -> Lungs
            "spo2" -> Drop
            "weight" -> Scale
            "body_fat" -> Percent
            "lean_mass", "lean_fat", "fat_mass" -> Layers
            "waist", "whtr" -> Tape
            "bmi" -> Gauge
            "streak" -> Flame
            "week_workouts", "last_workout" -> Dumbbell
            "yesterday_workouts" -> History
            "weekly_goals" -> WeekCheck
            "today_rings" -> Rings
            else -> Grid
        }
    }
}

