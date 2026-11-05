package com.boudhealth.ui.activity

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.boudhealth.data.ExerciseTypes
import com.boudhealth.data.Grain
import com.boudhealth.data.Insights
import com.boudhealth.data.PeriodUnit
import com.boudhealth.data.PeriodWorkouts
import com.boudhealth.data.SectionId
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SeriesBucket
import com.boudhealth.data.Sync
import com.boudhealth.data.V2
import com.boudhealth.data.Workout
import com.boudhealth.data.WorkoutGroup
import com.boudhealth.data.SessionDetail
import com.boudhealth.data.minutesByType
import com.boudhealth.data.sessionDetails
import com.boudhealth.ui.components.ButtonKind
import com.boudhealth.ui.components.EmptyState
import com.boudhealth.ui.components.FilterPill
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.HBCard
import com.boudhealth.ui.components.SkeletonBox
import com.boudhealth.ui.components.Stat
import com.boudhealth.ui.components.StatRow
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.nav.Route
import com.boudhealth.ui.section.DScope
import com.boudhealth.ui.section.DetailBars
import com.boudhealth.ui.section.SFmt
import com.boudhealth.ui.section.SectionDetailScaffold
import com.boudhealth.ui.section.SectionHooks
import com.boudhealth.ui.section.SectionScopes
import com.boudhealth.ui.section.rememberDetailNav
import com.boudhealth.ui.theme.Cat
import java.time.LocalDate

private class WorkoutsLoad(
    val ds: DScope, val p: SectionPeriod, val workouts: PeriodWorkouts, val dataStart: LocalDate,
    /** T1 V2Restore: minutes per type per bucket (3M: per day) and per-session points / average HR. */
    val byType: List<Pair<SeriesBucket, Map<WorkoutGroup, Int>>>, val grain: Grain, val details: List<SessionDetail>,
)

/**
 * Workouts sub-page (D13, V2-074; restore W1/W2/W4): period bar incl. 3M, minutes stacked by workout type with the
 * per-type read-out, Total time · Heart points · Avg HR (Calories burned on 1D), type filter, sessions by day.
 * Opened from "All n" in the Activity hero, filtered to that period.
 */
@Composable
fun WorkoutsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val version by Sync.version.collectAsState()
    val fallback = remember { SectionScopes.get(SectionId.ACTIVITY)?.effective?.takeIf { it.unit != PeriodUnit.DAY } }
    val dn = rememberDetailNav(SectionHooks.WORKOUTS_KEY, PeriodUnit.WEEK, SectionScopes.peekHandoff(SectionHooks.WORKOUTS_KEY) ?: fallback)
    remember { SectionScopes.takeHandoff(SectionHooks.WORKOUTS_KEY) }
    val scope = dn.scope
    var type by remember { mutableStateOf<WorkoutGroup?>(null) }
    var limit by remember(scope) { mutableIntStateOf(40) }
    var load by remember { mutableStateOf<WorkoutsLoad?>(null) }
    LaunchedEffect(scope, version) {
        load = runCatching {
            V2.load(ctx) { env ->
                val ds = V2.dataStart(env)
                val s = scope.std?.let { DScope.of(it.refreshed(env.today, ds)) } ?: scope
                val e = V2.engine(env)
                val p = s.period
                val (byType, grain) = if (s.is3M) {
                    generateSequence(p.start) { it.plusMonths(1) }.takeWhile { !it.isAfter(p.last) }
                        .flatMap { m -> e.minutesByType(SectionPeriod.Month(m.year, m.monthValue), env.today).perBucket }.toList() to Grain.DAY
                } else if (p is SectionPeriod.Day) {
                    // R6 item 1: 1D charts the 7 days ending on the day (as the Activity tab on 1D); the list stays the day's
                    com.boudhealth.data.DayView.days(p.start).flatMap { d -> e.minutesByType(SectionPeriod.Day(d.toEpochDay()), env.today).perBucket } to Grain.DAY
                } else e.minutesByType(p, env.today).let { mt -> mt.perBucket to com.boudhealth.data.V2Math.grainFor(p) }
                WorkoutsLoad(s, p, V2.workouts(env, p), ds, byType, grain, e.sessionDetails(p, env.today))
            }
        }.getOrNull()
    }
    val l = load
    val today = LocalDate.now()
    SectionDetailScaffold(ActivityTexts.WORKOUTS, nav, dn, l?.dataStart ?: today) {
        if (l == null) { SkeletonBox(Modifier.fillMaxWidth(), height = 320.dp, radius = 22.dp); return@SectionDetailScaffold }
        Column(Modifier.alpha(if (l.ds.period != scope.period) 0.6f else 1f)) {
            val sessions = l.workouts.sessions.filter { type == null || ExerciseTypes.group(it) == type }
            // W1: minutes stacked by workout type (T1 minutesByType), per-type read-out; R6 item 1: 1D = the 7 days ending on the day
            val day = l.p as? SectionPeriod.Day
            val chartP: SectionPeriod = day?.let { SectionPeriod.All(com.boudhealth.data.DayView.windowStart(it.start).toEpochDay(), it.epochDay) } ?: l.p
            val weekGroups = if (day == null) emptyList() else l.byType.flatMap { it.second.entries }.groupBy({ it.key }, { it.value })
                .mapValues { it.value.sum() }.filter { it.value > 0 }.entries.sortedWith(compareByDescending<Map.Entry<WorkoutGroup, Int>> { it.value }.thenBy { it.key.ordinal }).map { it.key }
            val dayChart = day != null && weekGroups.any { type == null || it == type }
            if ((day == null && sessions.isNotEmpty()) || dayChart) {
                val groups = (if (day != null) weekGroups else l.workouts.types.map { it.first }).filter { type == null || it == type }.take(6)
                val buckets = l.byType.map { (b, m) ->
                    val seg = DoubleArray(groups.size) { i -> (m[groups[i]] ?: 0).toDouble() }
                    b.copy(value = if (b.future) null else seg.sum().takeIf { it > 0 }, segments = if (seg.sum() > 0) seg else null)
                }
                HBCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                    DetailBars(chartP, buckets, l.grain, Cat.strength, "min", "Workout minutes by type", height = 200.dp,
                        segmentColors = groups.map { Cat.workoutColor(it) }, segmentNames = groups.map { it.label })
                }
                Spacer(Modifier.height(12.dp))
            }
            // W2: Total time · Heart points · Avg HR (T1 sessionDetails; the one average the v6 rules allow); 1D: Calories burned first
            val starts = sessions.map { it.start }.toSet()
            val det = l.details.filter { it.workout.start in starts }
            val totalMin = sessions.sumOf { it.minutes }
            val pts = det.sumOf { it.points ?: 0 }
            val hrW = det.filter { it.avgHr != null }
            val avgHr = if (hrW.isEmpty()) null else Math.round(hrW.sumOf { it.avgHr!!.toDouble() * it.workout.minutes.coerceAtLeast(1) } / hrW.sumOf { it.workout.minutes.coerceAtLeast(1) }).toInt()
            val kcal = sessions.mapNotNull { it.kcal }.sum()
            StatRow(listOf(
                if (l.p is SectionPeriod.Day) Stat(ActivityTexts.CALORIES_STAT, if (kcal > 0) SFmt.int(kcal) else "—", if (kcal > 0) "kcal" else null)
                else Stat(ActivityTexts.TOTAL_TIME, SFmt.hm(totalMin)),
                Stat(ActivityTexts.HEART_POINTS, SFmt.int(pts.toDouble())),
                Stat("Avg HR", avgHr?.toString() ?: "—", if (avgHr != null) "bpm" else null),   // R5 #28: kept (owner), label "Avg HR" (#41)
            ))
            Spacer(Modifier.height(12.dp))
            val types = l.workouts.types
            if (types.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill("All ${SFmt.int(l.workouts.sessionCount.toDouble())}", selected = type == null, onClick = { type = null })
                    types.forEach { (g, n) -> FilterPill("${g.label} $n", selected = type == g, onClick = { type = if (type == g) null else g }, dot = Cat.workoutColor(g)) }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (sessions.isEmpty()) {
                // R6 item 1: no empty state under a 1D chart that shows workouts in the 7 days
                if (dayChart) return@Column
                Spacer(Modifier.height(24.dp))
                EmptyState(Glyph.Dumbbell, Cat.strength, title = ActivityTexts.EMPTY_WORKOUTS, body = "")
                return@Column
            }
            sessions.take(limit).groupBy { WorkoutUi.day(it) }.forEach { (day, ws) ->
                DayGroupHeader(Insights.dayLabel(day), null, "${ws.sumOf { it.minutes }} min")
                SessionGroup(ws.sortedBy { it.start }, null, onOpen = { nav.push(Route.Session(it.start)) })
            }
            if (sessions.size > limit) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    HBButton(ActivityTexts.SHOW_MORE, { limit += 40 }, kind = ButtonKind.Text, small = true)
                }
            }
        }
    }
}

