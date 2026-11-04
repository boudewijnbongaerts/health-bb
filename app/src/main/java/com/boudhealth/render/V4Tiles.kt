package com.boudhealth.render

import android.graphics.Paint
import com.boudhealth.data.ExerciseTypes
import com.boudhealth.data.HrZones
import com.boudhealth.data.TileSize
import com.boudhealth.data.Workout
import com.boudhealth.data.WorkoutGroup
import com.boudhealth.data.Insights
import com.boudhealth.data.PeriodRing
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.V2
import com.boudhealth.render.ring.BmiData
import com.boudhealth.render.ring.GoalLine
import com.boudhealth.render.ring.HeartPointsData
import com.boudhealth.render.ring.RingData
import com.boudhealth.render.ring.SleepData
import com.boudhealth.render.ring.SleepSeg
import com.boudhealth.render.ring.StreakData
import com.boudhealth.render.ring.TileArt
import com.boudhealth.render.ring.TileSz
import com.boudhealth.render.ring.WeeklyGoalsData
import com.boudhealth.render.ring.WorkoutKind
import com.boudhealth.render.ring.WorkoutRow
import com.boudhealth.render.ring.WorkoutsWeekData
import com.boudhealth.render.ring.ZoneDay
import com.boudhealth.render.ring.ZonesData
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.TodayRingSet
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v4 / v4.1 tiles on the widget Canvas, mirroring the app's ui/home/TilesV4.kt + Cardio.kt tile for tile:
 * today rings (R1, all daily: steps · intensity · workout minutes), the three ring goal tiles, Total energy burned (R5),
 * Heart zones (R3: below / fat burn / cardio / peak, today + week), Weekly cardio (R2), Yesterday's workouts (Q13), This week's workouts (Q12/Q19), Workout streak (Q14),
 * Weekly goals (Q11). Small tiles carry one extra visual each (SMALL_TILES.md).
 */
internal class V4Tiles(private val k: Ink, private val s: TileSource) {
    private val t = k.t
    private val pal = t.ring

    private val m by lazy { V2Models(s) }

    fun draw(id: String, size: TileSize, w: Float, h: Float): Boolean {
        val sz = sz(size)
        when (id) {
            "today_rings" -> TileArt.today(k, sz, w, h, m.todayProgress, m.todayLabels, s.updatedAt?.let { Fmt.hm(it) }, m.week7Progress, s.rollingLetters)
            "steps" -> if (size == TileSize.S) goalS(TodayRingSet.STEPS, w, h) else goal(TodayRingSet.STEPS, size, w, h)
            "exercise_min" -> if (size == TileSize.S) goalS(TodayRingSet.WORKOUT, w, h) else goal(TodayRingSet.WORKOUT, size, w, h)
            // V2-098: one Heart points family (weekly_cardio is renamed to zone_min by T5; both ids draw the same tile)
            "zone_min", "weekly_cardio" -> m.heartPoints?.let { TileArt.heartPoints(k, sz, w, h, it) } ?: emptyTile(size, "Heart points", pal.c0Argb(2), Glyph.HeartPlus, "No data", w, h)
            "calories" -> calories(size, w, h)
            "heart_zones" -> m.zones?.let { TileArt.heartZones(k, sz, w, h, it) } ?: emptyTile(size, "Heart zones", Hue.heart, Glyph.Pulse, "No data", w, h)
            "yesterday_workouts" -> if (size == TileSize.XL && s.recent.isNotEmpty()) TileArt.recentXL(k, w, h, m.recentRows) else recent(size, w, h)
            "week_workouts" -> TileArt.workouts(k, sz, w, h, m.workouts, Hue.strength, s.weekTodayIndex)
            "streak" -> TileArt.streak(k, sz, w, h, m.streak, Hue.streak)
            "weekly_goals" -> m.weeklyGoals(k)?.let { TileArt.weeklyGoals(k, sz, w, h, it) } ?: emptyTile(size, "Weekly goals", t.text, Glyph.WeekCheck, "No data", w, h)
            else -> return false
        }
        return true
    }

    private fun sz(size: TileSize): TileSz = when (size) { TileSize.S -> TileSz.S; TileSize.M -> TileSz.M; TileSize.L -> TileSz.L; TileSize.XL -> TileSz.XL }

    /** V2-094/095: Steps (footsteps) and Workout minutes (stopwatch) S: icon, value (/ target), mini name, corner ring. */
    private fun goalS(i: Int, w: Float, h: Float) {
        val r = m.todayRing(if (i == TodayRingSet.STEPS) PeriodRing.STEPS else PeriodRing.WORKOUT)
        val col = TileArt.slot(k, i)
        val p0 = r?.progress ?: 0f
        if (i == TodayRingSet.STEPS) TileArt.small(k, w, h, Glyph.Steps, col, Fmt.int(r?.value ?: 0.0), null, "Steps") { x, y, ww, _ -> TileArt.cornerRing(k, x + ww - 26f, y, p0, col) }
        else TileArt.small(k, w, h, Glyph.Stopwatch, col, Fmt.int(r?.value ?: 0.0), null, "Workout min", target = r?.goal?.let { Fmt.int(it) }) { x, y, ww, _ -> TileArt.cornerRing(k, x + ww - 26f, y, p0, col) }
    }

    // =================================================================== shared

    private fun compactOrNum(v: Double): String = if (v >= 10_000) Fmt.compact(v) else Fmt.num(v)
    private fun cols(i: Int) = intArrayOf(pal.c0Argb(i), pal.c1Argb(i))

    /** "×1.3" lap chip in ring i's colours (label colour on its tint), h 20. Returns its width. */
    private fun lapChip(i: Int, p: Float, x: Float, top: Float, alignRight: Boolean = false): Float {
        val txt = "×${Fmt.num(p.toDouble(), 1)}"
        val tw = k.measure(txt, 10.5f, 700) + 14f
        val lx = if (alignRight) x - tw else x
        k.rrect(lx, top, lx + tw, top + 20f, 10f, pal.c0Argb(i).alpha(if (t.dark) 0.28f else 0.16f))
        k.text(txt, lx + 7f, top + 14f, 10.5f, t.ringLabel(i), 700)
        return tw
    }

    /** Empty state (as the app's EmptyTileText). */
    private fun emptyTile(size: TileSize, label: String, col: Int, glyph: Glyph, body: String, w: Float, h: Float) {
        if (size == TileSize.S) {
            val p = 12f
            k.glyphCircle(glyph, col, p, p)
            k.value("–", null, p, h - 31f, 21f, color = t.muted)
            k.text(k.fit(label, w - 2 * p, 11.5f, 500), p, h - 13f, 11.5f, t.muted, 500)
        } else {
            val p = if (size == TileSize.M) 14f else 16f
            k.labelRow(p, p - 1f, w - p, col, k.fit(label, w - 2 * p - 14f, 13f, 600), null)
            k.text(k.fit(body, w - 2 * p, 12.5f), p, h - p - 2f, 12.5f, t.muted)
        }
    }

    private fun muted(s: String, x: Float, base: Float, maxW: Float, fs: Float = 12f, align: Paint.Align = Paint.Align.LEFT) =
        k.text(k.fit(s, maxW, fs), x, base, fs, t.muted, 400, align)

    // =================================================================== Today rings (v5 R1/R2: Steps · Training (min) · Heart week)

    /**
     * Rings only, numbers integrated (clock widget "Health BB · Clock", v5 W2): a transparent [d] square, no card.
     * [labelColor] decides the wallpaper: light text = dark wallpaper (white track), dark text = light wallpaper.
     */
    fun ringsOnly(d: Float, labelColor: Int) {
        val darkWall = com.boudhealth.render.ring.RingColors.luminance(labelColor) > 0.4
        val col = k.ringColors(com.boudhealth.render.ring.RingSurface.TRANSPARENT, darkWall)
        com.boudhealth.render.ring.RingPaint.triple(k.c, 0f, 0f, d, m.todayProgress, m.todayLabels, col,
            com.boudhealth.render.ring.RingPaint.strokeFor(d), max(2f, d * 0.02f))
    }

    // =================================================================== ring goal tiles: Steps · Training (min) · Heart points

    private fun goal(i: Int, size: TileSize, w: Float, h: Float) {
        val c = cols(i); val lc = t.ringLabel(i)
        val rd = s.ringToday
        // v5: Steps / Training (min) daily vs goal; the zone_min tile is "Heart points" today, its ring = Heart week
        val heart = i == TodayRingSet.INTENSITY
        val label = when (i) { TodayRingSet.STEPS -> "Steps"; TodayRingSet.WORKOUT -> "Workout minutes"; else -> "Heart points" }
        val glyph = when (i) { TodayRingSet.STEPS -> Glyph.Steps; TodayRingSet.WORKOUT -> Glyph.Stopwatch; else -> Glyph.HeartPlus }
        val value = if (heart) (rd.intensity ?: 0).toDouble() else s.ringValues[i]
        val goal = if (heart) rd.weekIntensityGoal else s.ringGoals[i]
        val p0 = s.ringProgress[i]
        // QA 01.2: full goal number ("6,000"), the unit is dropped when it does not fit (never "6k st…")
        val goalStr = Fmt.int(goal)
        val unit = when (i) { TodayRingSet.STEPS -> "steps"; TodayRingSet.WORKOUT -> "min"; else -> "pts" }
        val ofText = "of $goalStr $unit"
        fun fitUnit(txt: String, maxW: Float, fs: Float, wt: Int = 400): String = if (k.measure(txt, fs, wt) <= maxW) txt else txt.removeSuffix(" $unit")
        val rem = goal - value
        val last7: List<Double?> = when (i) {
            TodayRingSet.STEPS -> s.ring7.map { it.steps }
            TodayRingSet.WORKOUT -> s.ring7.map { it.workoutMin.toDouble() }
            else -> s.ring7.map { it.intensity?.toDouble() }
        }
        val ctx = if (heart) "Week ${rd.weekIntensity} / ${rd.weekIntensityGoal} pts" else if (p0 >= 1f) "✓" else "${Fmt.num(rem)} $unit to go"
        val dayGoal: Double? = if (heart) null else goal.toDouble()
        fun chart(x: Float, y: Float, cw: Float, ch: Float) = k.bars(last7, x, y, cw, ch, c, dayGoal, s.rollingLetters, 6, 10f)
        when (size) {
            TileSize.S -> {
                val p = 12f
                k.mini7(last7, p, p + 2f, w - 2 * p, 18f, c[0], goal = dayGoal)
                if (heart) k.value(compactOrNum(value), "/ ${rd.intensityGoal}", p, h - 31f, 21f, 12f)
                else k.value(compactOrNum(value), null, p, h - 31f, 21f)
                k.text(k.fit(label, w - 2 * p, 11.5f, 500), p, h - 13f, 11.5f, t.muted, 500)
            }
            TileSize.M -> {
                val p = 14f; val d = min(h - 2 * p, 64f)
                val tw = w - 2 * p - d - 6f
                k.text(k.fit(label, tw, 13f, 600), p, p - 1f + 13.5f, 13f, lc, 600)
                if (i == TodayRingSet.WORKOUT && s.yesterday.isNotEmpty()) muted("Yesterday ${s.yesterday.sumOf { it.minutes }} min", p, p + 29f, tw, 11.5f)
                val vw = k.value(compactOrNum(value), null, p, h - 31.4f, 24f)
                if (p0 > 1f && !heart) lapChip(i, p0, p + vw + 6f, h - 31.4f - 15f)
                k.text(k.fit(fitUnit(ofText, tw, 12f), tw, 12f), p, h - 15f, 12f, t.muted)
                val rx = w - p - d; val ry = (h - d) / 2f
                k.singleRing(rx, ry, d, p0, c, (d * 0.17f).roundToInt().toFloat())
                val gs = (d * 0.32f).roundToInt().toFloat()
                k.glyph(glyph, rx + (d - gs) / 2f, ry + (d - gs) / 2f, gs, lc, 2.4f)
            }
            TileSize.L -> {
                val p = 16f; val d = 60f
                k.text(label, p, p - 1f + 14f, 13.5f, lc, 600)
                k.value(k.fit(compactOrNum(value), w - 2 * p - 64f, 32f, 700), null, p, 64.8f, 32f)
                k.text(k.fit(fitUnit(ofText, w - 2 * p - 64f, 12f), w - 2 * p - 64f, 12f), p, 85f, 12f, t.muted)
                k.singleRing(w - p - d, p, d, p0, c, 10f)
                k.glyph(glyph, w - p - d + 20f, p + 20f, 20f, lc, 2.4f)
                chart(p, p + 86f, w - 2 * p, h - p - 86f - p)
            }
            TileSize.XL -> {
                val p = 18f; val d = min(h - 2 * p, 150f); val tx = p + d + 22f
                k.singleRing(p, (h - d) / 2f, d, p0, c, (d * 0.15f).roundToInt().toFloat())
                val gs = (d * 0.22f).roundToInt().toFloat()
                k.glyph(glyph, p + (d - gs) / 2f, (h - gs) / 2f, gs, lc, 2.2f)
                k.labelRow(tx, p - 1f, w - p, null, label, null, 14f, lc)
                val vw = k.value(compactOrNum(value), null, tx, 72.4f, 36f)
                val gw = k.text(k.fit(fitUnit(" / $goalStr $unit", w - p - tx - vw - 50f, 13f, 500), w - p - tx - vw - 50f, 13f, 500), tx + vw, 72.4f, 13f, t.muted, 500)
                if (p0 > 1f && !heart) lapChip(i, p0, tx + vw + gw + 8f, 72.4f - 16f)
                k.text(k.fit(ctx, w - p - tx, 12.5f), tx, 99f, 12.5f, t.muted)
                chart(tx, p + 92f, w - tx - p, h - p - 92f - p)
            }
        }
    }

    // =================================================================== Calories burned (v5 D1: total only, as delivered)

    private fun calories(size: TileSize, w: Float, h: Float) {
        val total = s.calories.total?.value
        val c0 = t.energy[0]
        if (total == null) return emptyTile(size, "Calories burned", c0, Glyph.Flame, "No data", w, h)
        val k7 = s.kcal7
        when (size) {
            TileSize.S -> TileArt.small(k, w, h, Glyph.Flame, c0, Fmt.int(total), null, "kcal burned")
            TileSize.M -> {
                val p = 14f
                k.labelRow(p, p - 1f, w - p, c0, "Calories burned", null)
                k.value(Fmt.num(total), "kcal", p, h - 15.5f, 24f)
                if (k7.any { it != null }) k.mini7(k7, w - p - 74f, p + 24f, 74f, h - 2 * p - 24f, c0, barW = 7f, gap = 3f)
            }
            else -> {
                val xl = size == TileSize.XL
                val p = if (xl) 18f else 16f
                k.labelRow(p, p - 1f, w - p, c0, "Calories burned", null)
                k.value(Fmt.num(total), "kcal", p, 66f, 32f, 16f)
                if (k7.any { it != null }) {
                    val top = if (xl) p + 22f else p + 70f
                    val left = if (xl) w * 0.45f else p
                    if (h - p - top > 24f) k.bars(k7, left, top, w - p - left, h - p - top, intArrayOf(c0, c0.alpha(0.7f)), null, s.rollingLetters, 6, 10f)
                }
            }
        }
    }

    // =================================================================== workouts (v5 S5 type names and colours): Recent sessions, Training week, Streak

    private fun wCol(wk: Workout) = Hue.workoutGroup(ExerciseTypes.group(wk))

    private fun wGlyph(wk: Workout): Glyph = when (ExerciseTypes.group(wk)) {
        WorkoutGroup.CYCLING -> Glyph.Bike
        WorkoutGroup.RUNNING, WorkoutGroup.TREADMILL -> Glyph.Run
        WorkoutGroup.WALKING, WorkoutGroup.HIKING, WorkoutGroup.STAIRS -> Glyph.Walk
        WorkoutGroup.STRENGTH -> Glyph.Dumbbell
        WorkoutGroup.SWIMMING, WorkoutGroup.ROWING -> Glyph.Drop
        else -> Glyph.Pulse
    }

    /** v5 W1 "Recent workouts": the last sessions with icon, type, duration and day (tile id yesterday_workouts). */
    private fun recent(size: TileSize, w: Float, h: Float) {
        val ws = s.recent
        if (ws.isEmpty()) return emptyTile(size, "Recent workouts", Hue.cardio, Glyph.Dumbbell, "No data", w, h)
        val last = ws.first()
        if (size == TileSize.S) {
            val p = 12f
            k.glyphCircle(wGlyph(last), wCol(last), p, p)
            k.value(last.minutes.toString(), "min", p, h - 31f, 21f, 11f)
            k.text(k.fit(V2Models.workoutName(last), w - 2 * p, 11.5f, 500), p, h - 13f, 11.5f, t.muted, 500)
            return
        }
        val m = size == TileSize.M
        val p = if (m) 12f else 16f
        k.labelRow(p, p - 1f, w - p, Hue.cardio, "Recent workouts", null)
        val gd = if (m) 18f else 26f
        val top0 = p + if (m) 22f else 28f
        val rowH = if (m) (h - p - top0) / 2f else max(gd + 8f, min(40f, (h - p - top0) / 4f))
        val n = ((h - p - top0) / rowH).toInt().coerceIn(1, if (m) 2 else 6)
        ws.take(n).forEachIndexed { i, wk ->
            val ry = top0 + i * rowH
            k.glyphCircle(wGlyph(wk), wCol(wk), p, ry + (rowH - gd) / 2f, gd, if (m) 11f else 15f)
            // QA 01.3: the name keeps ≥ 70 dp; the day is dropped before the name is cut ("W…")
            val nameX = p + gd + 8f
            val full = "${wk.minutes} min · ${Fmt.dayLabel(Fmt.date(wk.start), s.today)}"
            val right = if (w - p - nameX - k.measure(full, 11.5f) - 8f >= 70f) full else "${wk.minutes} min"
            val base = k.centerBase(ry, rowH, 12.5f, 600)
            val rw = k.text(right, w - p, base, 11.5f, t.muted, 400, Paint.Align.RIGHT)
            // v6 H5: XL shows the full workout name as delivered
            val name = if (size == TileSize.XL) wk.title?.takeIf { it.isNotBlank() } ?: V2Models.workoutName(wk) else V2Models.workoutName(wk)
            k.text(k.fit(name, w - 2 * p - gd - 8f - rw - 8f, 12.5f, 600), p + gd + 8f, base, 12.5f, t.text, 600)
        }
    }
}

/**
 * V2 data for the tiles (TileSource / V2 → TileArt models). Read BY RING ID (V2-111), memoised for one render pass.
 * Blocking: same thread rules as [TileSource].
 */
internal class V2Models(private val s: TileSource) {
    private val env get() = s.env
    private val day = SectionPeriod.Day(s.today.toEpochDay())
    private val week = SectionPeriod.Week(s.monday.toEpochDay())

    val dayRings by lazy { runCatching { V2.rings(env, day) }.getOrNull() }
    val weekRings by lazy { runCatching { V2.rings(env, week) }.getOrNull() }
    fun todayRing(id: String): PeriodRing? = dayRings?.ring(id)

    val todayProgress: FloatArray by lazy { dayRings?.let { RingData.progress(it) } ?: RingData.progress(s.ringToday) }
    val todayLabels by lazy { dayRings?.let { RingData.labels(it) } ?: RingData.labels(s.ringToday) }
    /** Rolling 7 days (oldest first) for the Today XL mini rings. */
    val week7Progress: List<FloatArray> by lazy { s.ring7.map { RingData.progress(it) } }

    private val zonesWeek by lazy { runCatching { V2.zones(env, week) }.getOrNull() }
    private val zoneDays: List<ZoneDay?> by lazy {
        val z = zonesWeek ?: return@lazy emptyList<ZoneDay?>()
        (0..6).map { i ->
            val d = s.monday.plusDays(i.toLong())
            if (d.isAfter(s.today)) return@map null
            val b = z.perBucket.firstOrNull { it.day == d }
            val seg = b?.segments
            ZoneDay(seg?.getOrNull(0) ?: 0.0, seg?.getOrNull(1) ?: 0.0, seg?.getOrNull(2) ?: 0.0, b?.value ?: 0.0)
        }
    }

    val heartPoints: HeartPointsData? by lazy {
        val r = todayRing(PeriodRing.HEART) ?: return@lazy null
        val wk = weekRings?.ring(PeriodRing.HEART)
        val weekTarget = wk?.goal ?: zonesWeek?.goal?.value ?: (r.goal ?: 0.0) * 7
        // restored (audit 1.1): pace of the week and last week up to the same weekday
        val lastWeek = runCatching {
            val prev = SectionPeriod.Week(s.monday.minusDays(7).toEpochDay())
            val until = s.monday.minusDays(7).plusDays(s.weekTodayIndex.toLong())
            V2.zones(env, prev).perBucket.filter { !it.day.isAfter(until) }.sumOf { it.value ?: 0.0 }
        }.getOrNull()
        HeartPointsData(r.value, r.goal ?: 0.0, wk?.value ?: zoneDays.sumOf { it?.points ?: 0.0 }, weekTarget, zoneDays, s.weekLetters, s.weekTodayIndex,
            pace = paceOf(PeriodRing.HEART), lastWeekSoFar = lastWeek)
            .takeIf { it.dayTarget > 0 && it.weekTarget > 0 }
    }

    val zones: ZonesData? by lazy {
        val az = s.azmToday ?: return@lazy null
        ZonesData(ZoneDay(az[1].toDouble(), az[2].toDouble(), az[3].toDouble(), heartPoints?.today ?: 0.0), zoneDays, s.weekLetters, s.weekTodayIndex)
    }

    fun weeklyGoals(k: Ink): WeeklyGoalsData? {
        val set = weekRings ?: return null
        val lines = RingData.ORDER.mapIndexedNotNull { i, id ->
            val r = set.ring(id) ?: return@mapIndexedNotNull null
            val g = r.goal ?: return@mapIndexedNotNull null
            val pp = runCatching { V2.pace(env, r.metricId, week) }.getOrNull()
            GoalLine(RingData.NAMES[id] ?: id, r.value, g, RingData.label(r), TileArt.slot(k, i), expected = pp?.expectedByNow, pace = mark(pp?.status))
        }
        if (lines.isEmpty()) return null
        return WeeklyGoalsData(lines, Insights.weekLabel(week), (6 - s.weekTodayIndex).coerceAtLeast(0))
    }

    /** Week pace of a ring id (data/Pace via V2.pace), as a tile mark; null when there is no pace (new week, no data). */
    fun paceOf(id: String): com.boudhealth.render.ring.PaceMark? =
        mark(runCatching { V2.pace(env, weekRings?.ring(id)?.metricId ?: id, week) }.getOrNull()?.status)

    private fun mark(st: com.boudhealth.data.CardioPace?): com.boudhealth.render.ring.PaceMark? = when (st) {
        com.boudhealth.data.CardioPace.DONE -> com.boudhealth.render.ring.PaceMark.DONE
        com.boudhealth.data.CardioPace.AHEAD -> com.boudhealth.render.ring.PaceMark.AHEAD
        com.boudhealth.data.CardioPace.ON_TRACK -> com.boudhealth.render.ring.PaceMark.ON_TRACK
        com.boudhealth.data.CardioPace.BEHIND -> com.boudhealth.render.ring.PaceMark.BEHIND
        else -> null
    }

    val streak: StreakData by lazy {
        val (cur, best) = runCatching { V2.streakAsOf(env, s.today) }.getOrDefault(s.streakDays to s.bestStreakDays)
        val pw = runCatching { V2.workouts(env, week) }.getOrNull()
        val days = (0..6).map { i -> val d = s.monday.plusDays(i.toLong()); if (d.isAfter(s.today)) null else pw?.activeDays?.contains(d) == true }
        StreakData(cur, kotlin.math.max(best, cur), days, days.count { it == true }, pw?.activeDaysGoal ?: s.goals.workoutDaysWeek, s.weekLetters, s.weekTodayIndex)
    }

    val workouts: WorkoutsWeekData by lazy {
        val ws = s.weekWorkouts
        val byDay = ws.groupBy { Fmt.date(it.start) }
        val per = (0..6).map { i -> val d = s.monday.plusDays(i.toLong()); if (d.isAfter(s.today)) null else (byDay[d]?.size ?: 0).toDouble() }
        val kinds = ws.groupBy { ExerciseTypes.group(it) }.entries.sortedByDescending { it.value.size }
            .map { (g, l) -> WorkoutKind(if (g == WorkoutGroup.OTHER) "Workout" else g.label, glyphOf(g), Hue.workoutGroup(g), l.size) }
        WorkoutsWeekData(ws.size, ws.sumOf { it.minutes }, per, kinds)
    }

    val recentRows: List<WorkoutRow> by lazy {
        s.recent.map { w ->
            val g = ExerciseTypes.group(w)
            WorkoutRow(w.title?.takeIf { it.isNotBlank() } ?: workoutName(w), glyphOf(g), Hue.workoutGroup(g),
                "${Fmt.dayLabel(Fmt.date(w.start), s.today)} ${Fmt.hm(w.start)}", w.minutes, w.avgHr?.toInt()?.takeIf { it > 0 })
        }
    }

    companion object {
        /**
         * A workout's type name on tiles: its group ("Cycling", "Strength"); never "Other": an unrecognised type shows
         * the session's own title (the real type as recorded), else "Workout" (owner-approved texts).
         */
        fun workoutName(w: Workout): String {
            val g = ExerciseTypes.group(w)
            if (g != WorkoutGroup.OTHER) return g.label
            return w.title?.takeIf { it.isNotBlank() && !it.equals("other", ignoreCase = true) } ?: "Workout"
        }

        fun glyphOf(g: WorkoutGroup): Glyph = when (g) {
            WorkoutGroup.CYCLING -> Glyph.Bike
            WorkoutGroup.RUNNING, WorkoutGroup.TREADMILL -> Glyph.Run
            WorkoutGroup.WALKING, WorkoutGroup.HIKING, WorkoutGroup.STAIRS -> Glyph.Walk
            WorkoutGroup.STRENGTH -> Glyph.Dumbbell
            WorkoutGroup.SWIMMING, WorkoutGroup.ROWING -> Glyph.Drop
            else -> Glyph.Pulse
        }

        /** Last night's sleep (wake-up day = today, else the latest of the last 2 days). */
        fun sleep(s: TileSource): SleepData? {
            val n = (0L..1L).firstNotNullOfOrNull { com.boudhealth.data.V4.sleepNight(s.env, s.today.minusDays(it)) } ?: return null
            val segs = n.stages.filter { it.stage in setOf("deep", "rem", "light", "awake", "asleep") }.map { SleepSeg(it.stage, it.start, it.end) }
            val totals = listOf("deep" to n.deepMin, "rem" to n.remMin, "light" to n.lightMin, "awake" to n.awakeMin) +
                (if (n.asleepUnspecifiedMin > 0) listOf("asleep" to n.asleepUnspecifiedMin) else emptyList())
            val wake = n.day
            // QA 01.10: "Last night" says it all; older nights get their dates
            val meta = if (wake == s.today) "Last night" else "${Fmt.dayLabel(wake.minusDays(1), s.today)} → ${Fmt.dayLabel(wake, s.today)}"
            return SleepData(n.asleepMin, segs, totals, meta, Fmt.hm(n.start), Fmt.hm(n.end))
        }

        fun bmi(s: TileSource): BmiData? {
            val b = s.reading("bmi")?.value ?: return null
            return BmiData(b, s.heightM?.let { (it * 100).roundToInt() }, s.reading("weight")?.value)
        }
    }
}
