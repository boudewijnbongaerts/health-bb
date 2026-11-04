package com.boudhealth.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.boudhealth.data.MetricCategory
import com.boudhealth.data.MetricSections
import com.boudhealth.data.Metrics
import com.boudhealth.data.PeriodUnit
import com.boudhealth.data.SectionId
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SectionScope
import com.boudhealth.ui.section.SectionHooks
import com.boudhealth.ui.section.SectionScopes
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Every screen in the app. Serializable so the back stack survives rotation / fold / process death.
 *
 * V2.0 (V2-025): 4 tabs Home · Activity · Body · Vitals; All metrics is a pushed page. Retired routes stay in this class
 * **decode-only** (old saved stacks, placed widgets and intents still open): [Nav] resolves them with [Route.redirect]
 * before they reach the stack (Health → All metrics, Calories → Calories detail, HeartZones / WeeklyCardio → Heart points
 * detail, ActivityPeriod → Activity at that day / week / month / year, EditHome(w1–w3) → EditHome(home)).
 */
@Serializable
sealed class Route {
    // ---- tab roots (V2: 4 tabs)
    @Serializable data object Home : Route()
    @Serializable data object Activity : Route()
    /** V2: Body tab root (section dashboard). Was a pushed page in v6; the name is kept so old stacks decode. */
    @Serializable data object Body : Route()
    @Serializable data object Vitals : Route()

    // ---- pushed pages
    /** V2: one list of all metrics grouped Activity / Body / Vitals (top-bar List icon on every tab root). */
    @Serializable data object AllMetrics : Route()
    /** Single-metric detail page, for any metric id ("rhr", "weight", "zone_min", "calories", …). */
    @Serializable data class MetricDetail(val metricId: String) : Route()
    /** Workouts sub-page (sessions only, D13). */
    @Serializable data object Workouts : Route()
    /** One workout session; [start] = Workout.start (epoch ms) identifies it. */
    @Serializable data class Session(val start: Long) : Route()
    @Serializable data object Waist : Route()
    @Serializable data object Tape : Route()
    @Serializable data object Settings : Route()
    @Serializable data object DataCheck : Route()
    /** Home tile editor. V2-116: only "home" is edited; "w1"–"w3" decode (old links) and redirect to "home". */
    @Serializable data class EditHome(val slot: String = LayoutSlots.HOME) : Route()
    /** Sleep detail (under Activity, D2): [wakeEpochDay] = the night ending that day; -1 = the hand-off period. */
    @Serializable data class Sleep(val wakeEpochDay: Long = -1) : Route()

    // ---- decode-only (retired in V2; never pushed, see redirect)
    /** Retired v6 Health tab → All metrics. */
    @Serializable data object Health : Route()
    /** Retired → MetricDetail("calories"). */
    @Serializable data object Calories : Route()
    /** Retired → Heart points detail, zones section (MetricDetail("heart_zones")). */
    @Serializable data object HeartZones : Route()
    /** Retired → Heart points detail at 1W (MetricDetail("zone_min")). */
    @Serializable data object WeeklyCardio : Route()
    /**
     * Retired old History page → Activity at that period. [kind] "DAY" | "WEEK" | "MONTH" | "YEAR" | "ALL" around
     * [anchorEpochDay] (-1 = today). V2 also uses it as the link form of "Activity at 1D / 1W" (tile taps, deep links).
     */
    @Serializable data class ActivityPeriod(val kind: String = "WEEK", val anchorEpochDay: Long = -1) : Route()

    val isTab: Boolean get() = this is Home || this is Activity || this is Body || this is Vitals

    /** Decode-only routes (resolved by [Nav] before they reach the stack). */
    val isRetired: Boolean get() = this is Health || this is Calories || this is HeartZones || this is WeeklyCardio ||
        this is ActivityPeriod || (this is EditHome && slot != LayoutSlots.HOME)

    /** Pages reachable from every tab root that belong to no section: they open on the current tab. */
    val isTabAgnostic: Boolean get() = this is AllMetrics || this is Settings || this is DataCheck

    /** The bottom bar is hidden on full-screen modes. */
    val showsBottomBar: Boolean get() = !(this is Tape || this is EditHome)

    /** Which tab a deep-linked route belongs under (V2-027: metric pages via MetricSections, one home per metric). */
    val tab: Route get() = when (this) {
        Home, Settings, DataCheck, is EditHome, AllMetrics, Health -> Home
        Activity, Workouts, is Session, is Sleep, Calories, HeartZones, WeeklyCardio, is ActivityPeriod -> Activity
        Body, Waist, Tape -> Body
        Vitals -> Vitals
        is MetricDetail -> tabOfMetric(metricId)
    }

    companion object {
        val TABS: List<Route> = listOf(Home, Activity, Body, Vitals)
        private val json = Json { ignoreUnknownKeys = true }
        fun encode(r: Route): String = json.encodeToString(serializer(), r)
        fun decode(s: String): Route? = runCatching { json.decodeFromString(serializer(), s) }.getOrNull()?.takeIf(::isValid)

        /**
         * Deep-link / restore guard: day and time numbers must be real dates (1970 … tomorrow). Out-of-range values
         * (malformed or foreign `hb_route` links) would throw DateTimeException in LocalDate.ofEpochDay / Instant.atZone.
         */
        fun isValid(r: Route): Boolean {
            val maxDay = java.time.LocalDate.now().toEpochDay() + 1
            return when (r) {
                is ActivityPeriod -> r.anchorEpochDay < 0 || r.anchorEpochDay in 0..maxDay
                is Sleep -> r.wakeEpochDay < 0 || r.wakeEpochDay in 0..maxDay
                is Session -> r.start in 0..System.currentTimeMillis() + 86_400_000L
                else -> true
            }
        }
        internal fun encodeStack(l: List<Route>): String = json.encodeToString(ListSerializer(serializer()), l)
        internal fun decodeStack(s: String): List<Route>? = runCatching { json.decodeFromString(ListSerializer(serializer()), s) }.getOrNull()

        /** Tab of a metric / tile id: its one section (MetricSections, B34); unknown ids → Home. */
        fun tabOfMetric(id: String): Route = when {
            MetricSections.isKnown(id) || id in ACTIVITY_LINK_IDS -> sectionRoute(MetricSections.primary(id))
            else -> when (Metrics.find(id)?.category) {
                MetricCategory.ACTIVITY, MetricCategory.WORKOUTS, MetricCategory.SLEEP -> Activity
                MetricCategory.BODY -> Body
                MetricCategory.HEART -> Vitals
                null -> Home
            }
        }

        private val ACTIVITY_LINK_IDS = setOf("heart_zones", "weekly_cardio", "weekly_goals", "today_rings", "calories", "sleep", "vo2")

        fun sectionRoute(s: SectionId): Route = when (s) { SectionId.ACTIVITY -> Activity; SectionId.BODY -> Body; SectionId.VITALS -> Vitals }
        fun sectionOf(tab: Route): SectionId? = when (tab) { Activity -> SectionId.ACTIVITY; Body -> SectionId.BODY; Vitals -> SectionId.VITALS; else -> null }

        /** Activity at 1D today / 1W this week (link form; [Nav] sets the section scope). */
        val ActivityToday: Route get() = ActivityPeriod("DAY", -1)
        val ActivityWeek: Route get() = ActivityPeriod("WEEK", -1)

        /**
         * Where a tile (Home or widget) opens (V2_PLAN 2.1, V2-027). Tile ids first (they aren't all metric ids), then any
         * metric id; null for an unknown id. Widgets keep sending hb_metric=<tileId>. Period hand-offs that depend on the
         * tile size (Heart points 1D on S/M, 1W on L/XL) are set by [Nav.openTile].
         */
        /**
         * A widget tile link "<tile id>" or "<tile id>@<size>" (S, M, L, XL) → (id, large). Without a size only the
         * retired weekly tile counts as large (its widgets were L). V2-027: Heart points L/XL taps open 1W.
         */
        fun parseTileLink(link: String): Pair<String, Boolean> {
            val id = link.substringBefore('@').trim()
            val size = link.substringAfter('@', "").trim().uppercase()
            return id to (size == "L" || size == "XL" || (size.isEmpty() && id == "weekly_cardio"))
        }

        fun forTile(link: String): Route? = when (val id = link.substringBefore('@').trim()) {
            "today_rings" -> ActivityToday
            "weekly_goals", "week_workouts", "streak", "exercise_min" -> ActivityWeek
            "zone_min", "weekly_cardio" -> MetricDetail("zone_min")
            "heart_zones" -> MetricDetail("heart_zones")
            // the newest session (MetricDetail resolves it to that Session; Workouts when there is none)
            "last_workout" -> MetricDetail("last_workout")
            // yesterday's sessions: the Workouts page filtered to yesterday (one session or several), set by Nav.openTile
            "yesterday_workouts" -> Workouts
            "calories" -> MetricDetail("calories")
            "sleep" -> Sleep(java.time.LocalDate.now().toEpochDay())
            "lean_fat", "lean_mass" -> MetricDetail("lean_mass")
            "waist" -> Waist
            else -> if (Metrics.find(id) != null || MetricSections.isKnown(id)) MetricDetail(id) else null
        }

        /**
         * Simple names for widget / intent deep links (V2_PLAN 2.1): tab names, "all_metrics", "workouts", "waist",
         * "tape", "settings", "datacheck", "today", "edit_home", "edit_widget_*", "metric:<id>", "session:<startMs>",
         * or a JSON-encoded Route (old ones included; they are redirected by [Nav]).
         */
        fun fromLink(s: String?): Route? {
            if (s.isNullOrBlank()) return null
            val k = s.trim()
            return when {
                k.startsWith("{") -> decode(k)
                // widgets send hb_metric=<tileId>: tile ids resolve like a Home tile tap, then plain metric ids
                k.startsWith("metric:") -> forTile(k.removePrefix("metric:"))
                k.startsWith("session:") -> k.removePrefix("session:").toLongOrNull()?.let { Session(it) }?.takeIf(::isValid)
                else -> when (k.lowercase()) {
                    "home" -> Home
                    "activity", "rings" -> Activity
                    "today", "today_rings" -> ActivityToday
                    "body" -> Body
                    "vitals" -> Vitals
                    "health", "all", "all_metrics", "metrics" -> AllMetrics
                    "history", "trends", "week_view", "weekly_cardio", "cardio", "weekly_goals" -> ActivityWeek
                    "heart_zones", "zones" -> MetricDetail("heart_zones")
                    "calories", "energy" -> MetricDetail("calories")
                    "heart_points", "zone_min" -> MetricDetail("zone_min")
                    "sleep" -> Sleep()
                    "workouts" -> Workouts; "waist" -> Waist; "tape" -> Tape
                    "settings" -> Settings; "datacheck", "data_check" -> DataCheck
                    "edit_home" -> EditHome(LayoutSlots.HOME)
                    // V2-116: the widget slots are no longer edited in the app (widgets have their own setup)
                    "edit_widget_1", "edit_w1", "edit_widget_inner", "edit_widget_2", "edit_w2", "edit_widget_cover",
                    "edit_widget_3", "edit_w3" -> EditHome(LayoutSlots.HOME)
                    else -> if (k.startsWith("edit_widget")) EditHome(LayoutSlots.HOME) else null
                }
            }
        }
    }
}

/**
 * The app's back stack (no navigation library). The bottom of the stack is always a tab root.
 * Get it in any screen via the `nav` parameter (or LocalNav.current).
 *
 *     nav.push(Route.MetricDetail("rhr"))     // open a page
 *     nav.openMetric("weight")                // same, shorter
 *     nav.openMetric("steps", period)         // detail page at a section period (hand-off, B16)
 *     nav.openSection(SectionId.ACTIVITY, scope)
 *     nav.back()                              // pop (returns false at the Home root)
 *     nav.selectTab(Route.Body)               // switch tab (re-selecting pops to the root)
 */
@Stable
class Nav internal constructor(initial: List<Route>) {
    var stack: List<Route> by mutableStateOf(sanitize(initial))
        private set

    /** v6 bridge (read by ActivitySectionScreen once): the day the Activity tab should show (epoch day; -1 = none). */
    var activityDay: Long by mutableStateOf(-1L)

    /** Show [day] on the Activity tab (1D of that day), from anywhere. */
    fun openDay(day: java.time.LocalDate) {
        val today = java.time.LocalDate.now()
        val p = SectionPeriod.Day(day.toEpochDay())
        SectionScopes.set(SectionId.ACTIVITY, SectionScope(p, null, day == today))
        activityDay = -1L
        if (stack != listOf<Route>(Route.Activity)) { lastDirection = 0; stack = listOf(Route.Activity) }
    }

    /** +1 after a push, −1 after a pop, 0 after a tab switch / reset: drives the transition direction. */
    var lastDirection: Int by mutableStateOf(0)
        private set

    val current: Route get() = stack.last()
    val currentTab: Route get() = stack.first()
    val canGoBack: Boolean get() = stack.size > 1 || stack.first() != Route.Home

    fun push(route: Route) {
        if (!Route.isValid(route)) return
        val r = resolve(route)
        if (r.isTab) { selectTab(r); return }
        if (current == r) return
        lastDirection = 1
        stack = stack + r
    }

    // v6 H8: the Lean mass page is metric "lean_mass" (as delivered)
    fun openMetric(metricId: String) = push(Route.MetricDetail(if (metricId == "lean_fat") "lean_mass" else metricId))

    /** IC5: a metric's detail page at [period] (the period is handed off to the page, B16); null = the page's default. */
    fun openMetric(metricId: String, period: SectionPeriod?) {
        val id = if (metricId == "lean_fat") "lean_mass" else metricId
        if (period != null) SectionScopes.putHandoff(id, period)
        push(Route.MetricDetail(id))
    }

    /** IC5: the saved scope of a section (D15); null = not opened yet (the section starts at 1D today). */
    fun sectionScope(section: SectionId): SectionScope? = SectionScopes.get(section)

    /** IC5: set a section's scope without navigating. */
    fun setScope(section: SectionId, scope: SectionScope) = SectionScopes.set(section, scope)

    /** IC5: switch to a section tab at [scope] (null = keep its scope). */
    fun openSection(section: SectionId, scope: SectionScope? = null) {
        if (scope != null) SectionScopes.set(section, scope)
        selectTab(Route.sectionRoute(section))
    }

    /**
     * A Home / widget tile tap (V2_PLAN 2.1 "Home tile taps"). [large] = L / XL tile: the Heart points tile opens its
     * detail at 1W there, at 1D on S / M.
     */
    fun openTile(id: String, large: Boolean = false) {
        tileHandoff(id, large)
        push(Route.forTile(id) ?: Route.MetricDetail(id))
    }

    /** A widget tile tap (hb_metric=<tile id>): like [openTile], but as a deep link (tab root of the target + target). */
    fun openTileLink(link: String) {
        val (id, large) = Route.parseTileLink(link)
        val r = Route.forTile(id) ?: return
        tileHandoff(id, large)
        open(r)
    }

    private fun tileHandoff(id: String, large: Boolean) {
        val today = java.time.LocalDate.now()
        when (id) {
            "zone_min", "weekly_cardio" -> SectionScopes.putHandoff("zone_min",
                if (large) SectionPeriod.of(PeriodUnit.WEEK, today, today, today) else SectionPeriod.Day(today.toEpochDay()))
            "yesterday_workouts" -> SectionScopes.putHandoff(SectionHooks.WORKOUTS_KEY, SectionPeriod.Day(today.minusDays(1).toEpochDay()))
        }
    }

    /** Replace the top screen (no back entry). */
    fun replace(route: Route) {
        val r = resolve(route)
        if (r.isTab) { selectTab(r); return }
        lastDirection = 0
        stack = if (stack.size > 1) stack.dropLast(1) + r else stack + r
    }

    /** Pop one screen. At a non-Home tab root, go to Home. Returns false when nothing was popped (Home root). */
    fun back(): Boolean {
        return when {
            stack.size > 1 -> { lastDirection = -1; stack = stack.dropLast(1); true }
            stack.first() != Route.Home -> { lastDirection = -1; stack = listOf(Route.Home); true }
            else -> false
        }
    }

    /** Pop until [route] is on top (if present). */
    fun popTo(route: Route) {
        val i = stack.lastIndexOf(route)
        if (i >= 0 && i < stack.lastIndex) { lastDirection = -1; stack = stack.take(i + 1) }
    }

    /** Switch tab. A retired / non-tab route (e.g. the old Health tab) opens where it now lives instead of crashing. */
    fun selectTab(tab: Route) {
        val r = resolve(tab)
        if (!r.isTab) { open(r); return }
        lastDirection = 0
        stack = listOf(r)
    }

    /** Deep link: tab root of [route] + [route]. */
    fun open(route: Route) {
        if (!Route.isValid(route)) return
        val r = resolve(route)
        lastDirection = 0
        // V2_VISUAL_QA 20.3: pages that belong to no section (All metrics, Settings, Data check) open on the CURRENT tab
        stack = when {
            r.isTab -> listOf(r)
            r.isTabAgnostic -> listOf(currentTab, r)
            else -> listOf(r.tab, r)
        }
    }

    /** Section scopes for the Saver (D15). */
    internal fun sectionsJson(): String = runCatching { SectionScopes.encode() }.getOrDefault("{}")

    companion object {
        /** Saver v2 (V2-025): `{"v":2,"stack":[…],"sections":"…"}`; still reads the v6 plain list. */
        val saver: Saver<Nav, String> = Saver(
            save = { NavSaveV2.encode(it.stack, it.sectionsJson()) },
            restore = { s -> NavSaveV2.decode(s).let { (stack, sections) ->
                if (sections != null) runCatching { SectionScopes.decode(sections) }
                Nav(stack ?: listOf(Route.Home))
            } },
        )

        /**
         * A retired route → where it lives in V2 (V2-025). Side effect for Activity periods / Weekly cardio: the scope or
         * the detail hand-off is stored in SectionScopes, so the target opens at that period.
         */
        fun resolve(r: Route): Route = when (r) {
            Route.Health -> Route.AllMetrics
            Route.Calories -> Route.MetricDetail("calories")
            Route.HeartZones -> Route.MetricDetail("heart_zones")
            Route.WeeklyCardio -> {
                val today = java.time.LocalDate.now()
                SectionScopes.putHandoff("zone_min", SectionPeriod.of(PeriodUnit.WEEK, today, today, today))
                Route.MetricDetail("zone_min")
            }
            is Route.ActivityPeriod -> { activityScope(r)?.let { SectionScopes.set(SectionId.ACTIVITY, it) }; Route.Activity }
            is Route.EditHome -> if (r.slot != LayoutSlots.HOME) Route.EditHome(LayoutSlots.HOME) else r
            is Route.MetricDetail -> when (r.metricId) {
                "weekly_cardio" -> Route.MetricDetail("zone_min")
                "lean_fat" -> Route.MetricDetail("lean_mass")
                else -> r
            }
            else -> r
        }

        /** The Activity scope an old History link / an Activity-period link stands for. */
        internal fun activityScope(r: Route.ActivityPeriod): SectionScope? = runCatching { activityScope0(r) }.getOrNull()

        private fun activityScope0(r: Route.ActivityPeriod): SectionScope? {
            val today = java.time.LocalDate.now()
            val anchor = if (r.anchorEpochDay < 0) today else java.time.LocalDate.ofEpochDay(r.anchorEpochDay).let { if (it.isAfter(today)) today else it }
            val unit = when (r.kind.uppercase()) {
                "DAY" -> PeriodUnit.DAY; "MONTH" -> PeriodUnit.MONTH; "YEAR" -> PeriodUnit.YEAR; "ALL" -> PeriodUnit.ALL
                else -> PeriodUnit.WEEK
            }
            if (unit == PeriodUnit.ALL) return null   // the section knows its first data day; keep its scope
            val p = runCatching { SectionPeriod.of(unit, anchor, anchor, today) }.getOrNull() ?: return null
            return SectionScope(p, null, p.isCurrent(today))
        }

        /**
         * V2-025 sanitize: redirect retired routes, cut the stack at the LAST tab root found above index 0 (a stack never
         * holds two tabs), make sure index 0 is a tab root, and drop adjacent duplicates. Never returns an empty stack.
         */
        fun sanitize(input: List<Route>): List<Route> {
            val l = input.filter { Route.isValid(it) }.map { resolve(it) }
            if (l.isEmpty()) return listOf(Route.Home)
            val lastTab = l.indexOfLast { it.isTab }
            val cut = if (lastTab > 0) l.drop(lastTab) else l
            val withRoot = if (cut.first().isTab) cut else listOf(cut.first().tab) + cut
            val out = ArrayList<Route>(withRoot.size)
            withRoot.forEachIndexed { i, r -> if (i == 0 || (!r.isTab && r != out.last())) out += r }
            return out
        }
    }
}

/** Saved form of the back stack + section scopes (Saver v2). */
internal object NavSaveV2 {
    @Serializable private data class Saved(val v: Int = 2, val stack: List<Route>, val sections: String? = null, val at: Long = 0L)
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(stack: List<Route>, sections: String?, nowMs: Long = System.currentTimeMillis()): String =
        json.encodeToString(Saved.serializer(), Saved(2, stack, sections, nowMs))

    /**
     * (stack, sections JSON); the v6 format (a plain JSON list of routes) is still read. Section scopes saved more than
     * an hour ago are dropped (D15: back to 1D today after > 1 h in the background, also across process death).
     */
    fun decode(s: String, nowMs: Long = System.currentTimeMillis()): Pair<List<Route>?, String?> {
        val t = s.trimStart()
        if (t.startsWith("[")) return Route.decodeStack(t) to null
        val v = runCatching { json.decodeFromString(Saved.serializer(), t) }.getOrNull() ?: return null to null
        val fresh = v.at > 0 && nowMs - v.at in 0..SectionScopes.RESET_AFTER_MS
        return v.stack to v.sections?.takeIf { fresh }
    }
}

@Composable
fun rememberNav(initial: Route = Route.Home): Nav =
    rememberSaveable(saver = Nav.saver) { Nav(if (initial.isTab) listOf(initial) else listOf(initial.tab, initial)) }

/** Navigation for code that doesn't get `nav` passed (prefer the parameter). */
val LocalNav = androidx.compose.runtime.staticCompositionLocalOf<Nav> { error("No Nav provided") }
