@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.boudhealth.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Goals
import com.boudhealth.data.HcReader
import com.boudhealth.data.PeriodRing
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.Sync
import com.boudhealth.data.ThemeMode
import com.boudhealth.data.V2
import com.boudhealth.data.importer.AppleImport
import com.boudhealth.ui.components.ButtonKind
import com.boudhealth.ui.components.ChevronRow
import com.boudhealth.ui.components.ColorPicker
import com.boudhealth.ui.components.DetailScaffold
import com.boudhealth.ui.components.Dot
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.BigStepper
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphCircle
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBBottomSheet
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.IconCircleButton
import com.boudhealth.ui.components.LocalScreen
import com.boudhealth.ui.components.LocalSnackbar
import com.boudhealth.ui.components.NumberSheet
import com.boudhealth.ui.components.PaletteSwatch
import com.boudhealth.ui.components.SegmentRow
import com.boudhealth.ui.components.SegmentSize
import com.boudhealth.ui.components.SegmentedRange
import com.boudhealth.ui.components.SettingsGroup
import com.boudhealth.ui.components.SettingsRow
import com.boudhealth.ui.components.SheetActions
import com.boudhealth.ui.components.Stepper
import com.boudhealth.ui.components.SwitchRow
import com.boudhealth.ui.components.TripleRing
import com.boudhealth.ui.components.hbClickable
import com.boudhealth.ui.components.touchExpand
import com.boudhealth.ui.health.effectiveHeightCm
import com.boudhealth.ui.health.hcHeightM
import com.boudhealth.ui.nav.LocalAppStatus
import com.boudhealth.ui.nav.LocalPrefs
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.nav.Route
import com.boudhealth.ui.nav.rememberPrefsEditor
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB
import com.boudhealth.ui.theme.Palettes
import com.boudhealth.ui.theme.contrast
import com.boudhealth.ui.theme.forTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Year

/*
 * V2.0 Settings (V2_PLAN §2.9, design6/v2009_settings.png, texts V2_TEXTS.md §6):
 * Profile bar (name + today's rings) · Profile & goals · Devices | Appearance · Data & sync · Version card.
 * No calorie goal, no max HR setting (fitted default, V2_API "G0 data decisions"), no hint texts. Every action once.
 * All texts are generic (no personal values): this file ships in the public repo.
 */

/** V2_TEXTS §5 Heart points (i), with the max HR sentence corrected by T7 (pending owner approval, see V2_TEXTS §6a). */
internal const val INFO_HEART_POINTS =
    "Heart points count how long your heart works hard. Each minute in Fat burn gives 1 point; each minute in Cardio or Peak gives 2. " +
        "The zones come from your heart rate reserve (maximum minus resting heart rate): Fat burn from 40 %, Cardio from 60 %, Peak from 85 %. " +
        "Maximum heart rate is 190 by default, the value at which heart points best match Google Health's Active Zone Minutes. " +
        "The WHO advises at least 150 minutes of moderate activity a week, about 150 heart points. Default target: 150 a week."

/** V2_TEXTS §5 Workout days (i). R5 #38: built from the saved rule, so it never shows other numbers than the rule. */
internal fun infoWorkoutDays(g: Goals): String {
    fun pts(n: Int) = if (n == 1) "1 heart point" else "$n heart points"
    val rule = if (g.activeDayMode == "points") "A workout day is a day with at least ${pts(g.activeDayMinPoints.coerceAtLeast(1))}. "
    else "A workout day is a day with at least one recorded workout of ${g.activeDayMinWorkoutMin.coerceAtLeast(1)} minutes or more" +
        (if (g.activeDayMinWorkoutPoints > 0) " that earned at least ${pts(g.activeDayMinWorkoutPoints)}. " else ". ") +
        "Walks count only when your watch or app saved them as a workout. "
    return rule + "You can change the rule in Settings → Profile & goals."
}

/** V2_TEXTS §5 Import Apple Health (i). */
internal const val INFO_IMPORT =
    "A one-time import of the history in an Apple Health export. On the iPhone: Health app → your profile → Export All Health Data. " +
        "Send export.zip to this phone and pick it here. Health Connect stays the live source; for each day and type only one source is shown."


/** V2_TEXTS §6a: the professional line under the heart points target. */
internal const val WHO_LINE = "WHO minimum: ${Goals.WHO_WEEKLY_HEART_POINTS} a week"

@Composable
fun SettingsScreen(nav: Nav) {
    val screen = LocalScreen.current
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }

    DetailScaffold("Settings", onBack = { nav.back() }) {
        Spacer(Modifier.height(20.dp))
        ProfileCard()
        val left: @Composable () -> Unit = {
            Gap(); ProfileGroup(onSheet = { sheet = it })
            Gap(); DevicesGroup(nav)
        }
        val right: @Composable () -> Unit = {
            Gap(); AppearanceGroup(onCustom = { sheet = "custom" })
            Gap(); DataGroup(nav, onSheet = { sheet = it })
            Gap(); VersionCard()
        }
        if (screen.wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) { left() }
                Column(Modifier.weight(1f)) { right() }
            }
        } else { left(); right() }
        Spacer(Modifier.height(24.dp))
    }

    val close = { sheet = null }
    when (sheet) {
        "name" -> NameSheet(close)
        "birth" -> BirthYearSheet(close)
        "height" -> HeightSheet(close)
        "heart" -> HeartPointsSheet(close)
        "rule" -> WorkoutDayRuleSheet(close)
        "target:weight" -> BodyTargetSheet("weight", close)
        "target:waist" -> BodyTargetSheet("waist", close)
        "target:body_fat" -> BodyTargetSheet("body_fat", close)
        "custom" -> CustomColoursSheet(close)
        "rebuild" -> RebuildSheet(close)
        "info:heart" -> InfoSheet("Heart points", INFO_HEART_POINTS, close)
        "info:days" -> InfoSheet("Workout days", infoWorkoutDays(LocalPrefs.current.goals), close)
        "info:import" -> ImportInfoSheet(close)
    }
}

@Composable
private fun Gap() = Spacer(Modifier.height(24.dp))

// =====================================================================================================================
// Profile bar: name + today's rings (V2-141)
// =====================================================================================================================

@Composable
private fun ProfileCard() {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val prefs = LocalPrefs.current
    val version by Sync.version.collectAsState()
    val rings by produceState(listOf(0f, 0f, 0f), version, prefs.goals) {
        value = runCatching {
            V2.load(ctx) { env ->
                val set = V2.rings(env, SectionPeriod.Day(env.today.toEpochDay()))
                PeriodRing.ORDER.map { id -> set.ring(id)?.progress?.coerceIn(0f, 1f) ?: 0f }
            }
        }.getOrNull() ?: listOf(0f, 0f, 0f)
    }
    Row(
        Modifier.fillMaxWidth().background(hb.colors.card, RoundedCornerShape(24.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TripleRing(rings, size = 56.dp, animate = false, shadow = false, gap = 2.dp)
        Spacer(Modifier.width(16.dp))
        Text(prefs.goals.name?.takeIf { it.isNotBlank() } ?: "Profile", style = hb.type.headlineSmall, color = hb.colors.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

// =====================================================================================================================
// Profile & goals (V2-140, V2-142)
// =====================================================================================================================

private fun num1(v: Double): String = Fmt.num(v, if (v % 1.0 == 0.0) 0 else 1)

@Composable
private fun ProfileGroup(onSheet: (String) -> Unit) {
    val hb = LocalHB.current
    val prefs = LocalPrefs.current
    val ctx = LocalContext.current
    val edit = rememberPrefsEditor()
    val g = prefs.goals
    val pal = hb.palette
    val version by Sync.version.collectAsState()
    val hc by produceState<Double?>(null, version) { value = hcHeightM(ctx) }
    val heightCm = effectiveHeightCm(hc, prefs.heightCm)
    val suggested by produceState<Int?>(null, version) {
        value = runCatching { V2.load(ctx) { env -> V2.suggestedHeartPointsTarget(env) } }.getOrNull()
    }

    SettingsGroup("Profile & goals") {
        row { ChevronRow("Name", { onSheet("name") }, value = g.name?.takeIf { it.isNotBlank() } ?: "Not set", leadingGlyph = Glyph.User) }
        row { ChevronRow("Birth year", { onSheet("birth") }, value = prefs.birthYear?.toString() ?: "Not set", leadingGlyph = Glyph.Calendar) }
        row { ChevronRow("Height", { onSheet("height") }, value = heightCm?.let { "${Math.round(it)} cm" } ?: "Not set", leadingGlyph = Glyph.Gauge) }
    }
    Spacer(Modifier.height(12.dp))
    SettingsGroup(null) {
        row {
            GoalStepperRow("Steps per day", g.steps, 100.0..100_000.0, 100.0, null, pal.ring(1).first) { v -> edit.updateGoals { it.copy(steps = v) } }
        }
        row {
            GoalStepperRow("Workout minutes per day", g.workoutMinDay, 1.0..600.0, 1.0, null, pal.ring(0).first) { v -> edit.updateGoals { it.copy(workoutMinDay = v) } }
        }
    }
    Spacer(Modifier.height(12.dp))
    SettingsGroup(null) {
        row {
            GoalStepperRow("Heart points per week", g.zoneMinWeek, 7.0..3_000.0, 10.0, { onSheet("info:heart") }, pal.ring(2).first,
                onValueClick = { onSheet("heart") }, caption = WHO_LINE) { v -> edit.updateGoals { it.copy(zoneMinWeek = v) } }
        }
        val s = suggested
        if (s != null) row {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .background(hb.colors.tile, RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.Target, size = 18.dp, color = hb.colors.muted)
                Spacer(Modifier.width(10.dp))
                Text("Suggested for you", style = hb.type.bodyS, color = hb.colors.muted)
                Spacer(Modifier.width(6.dp))
                Text(Fmt.int(s), style = hb.type.bodyS.copy(fontWeight = FontWeight.SemiBold), color = hb.colors.text, modifier = Modifier.weight(1f))
                HBButton("Use", { edit.updateGoals { it.copy(zoneMinWeek = s) } }, kind = ButtonKind.Tonal, small = true, enabled = s != g.zoneMinWeek)
            }
        }
        // R6 #2: resetting is an action, so a tonal "Reset" button (same texts as the heart points sheet's "Default … Reset" line)
        row {
            SettingsRow("Default", subtitle = Fmt.int(Goals.DEFAULT_WEEKLY_HEART_POINTS), leadingGlyph = Glyph.Undo) {
                HBButton("Reset", { edit.updateGoals { it.copy(zoneMinWeek = Goals.DEFAULT_WEEKLY_HEART_POINTS) } }, kind = ButtonKind.Tonal, small = true,
                    enabled = g.zoneMinWeek != Goals.DEFAULT_WEEKLY_HEART_POINTS)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    SettingsGroup(null) {
        row {
            GoalStepperRow("Workout days per week", g.workoutDaysWeek, 1.0..7.0, 1.0, { onSheet("info:days") }, Cat.workoutGoal.first) { v ->
                edit.updateGoals { it.copy(workoutDaysWeek = v) }
            }
        }
        row { ChevronRow("Workout day", { onSheet("rule") }, value = ruleText(g), leadingGlyph = Glyph.Calendar) }
    }
    Spacer(Modifier.height(12.dp))
    SettingsGroup(null) {
        row { ChevronRow("Target weight", { onSheet("target:weight") }, value = g.targetWeightKg?.let { "${num1(it)} kg" } ?: "Not set", leadingGlyph = Glyph.Scale) }
        row { ChevronRow("Target waist circumference", { onSheet("target:waist") }, value = g.targetWaistCm?.let { "${num1(it)} cm" } ?: "Not set", leadingGlyph = Glyph.Tape) }
        row { ChevronRow("Target body fat percentage", { onSheet("target:body_fat") }, value = g.targetFatPct?.let { "${num1(it)}${com.boudhealth.ui.section.R3Texts.PCT_SP}%" } ?: "Not set", leadingGlyph = Glyph.Percent) }
    }
}

/** Row value of the workout-day rule (V2_TEXTS §6 rule_workout / rule_points). */
private fun ruleText(g: Goals): String =
    if (g.activeDayMode == "points") "≥ ${g.activeDayMinPoints} heart points"
    else "Workout ≥ ${g.activeDayMinWorkoutMin} min · ${g.activeDayMinWorkoutPoints} pts"

/**
 * Goal row: colour dot, title, optional (i), stepper on the right; saves after the value has been still for 600 ms (long-press
 * repeats don't write 50 times). [caption] = one muted line under the title. Tapping the value opens a number sheet
 * ([onValueClick] overrides it).
 */
@Composable
private fun GoalStepperRow(
    title: String,
    stored: Int,
    range: ClosedFloatingPointRange<Double>,
    step: Double,
    onInfo: (() -> Unit)?,
    dot: Color?,
    onValueClick: (() -> Unit)? = null,
    caption: String? = null,
    onCommit: (Int) -> Unit,
) {
    val hb = LocalHB.current
    var v by remember(stored) { mutableDoubleStateOf(stored.toDouble()) }
    var typing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(v) { if (v.toInt() != stored) { delay(600); onCommit(v.toInt()) } }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) { Dot(dot); Spacer(Modifier.width(8.dp)) }
                Text(title, style = hb.type.bodyMedium, color = hb.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (onInfo != null) {
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.size(20.dp).touchExpand().hbClickable(shape = CircleShape, scaleOnPress = false, onClick = onInfo).semantics { contentDescription = "About $title" },
                        contentAlignment = Alignment.Center) { GlyphIcon(Glyph.Info, size = 16.dp, color = hb.colors.muted) }
                }
            }
            if (caption != null) Text(caption, style = hb.type.bodyXS.copy(fontSize = 12.5.sp), color = hb.colors.muted, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Stepper(v, { v = it }, step = step, range = range, onValueClick = onValueClick ?: { typing = true }, background = hb.colors.tile)
    }
    if (typing) NumberSheet(title, v, onDismiss = { typing = false }, onSave = { v = it; typing = false }, step = step, range = range)
}

/** Name entry: the one text field in Settings. */
@Composable
private fun NameSheet(onDismiss: () -> Unit) {
    val hb = LocalHB.current
    val prefs = LocalPrefs.current
    val edit = rememberPrefsEditor()
    val state = rememberTextFieldState(prefs.goals.name ?: "")
    HBBottomSheet(onDismiss = onDismiss, title = "Name") {
        Box(Modifier.fillMaxWidth().background(hb.colors.sheetTile, RoundedCornerShape(22.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
            BasicTextField(
                state, Modifier.fillMaxWidth(), lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = hb.type.titleL.copy(color = hb.colors.text), cursorBrush = SolidColor(hb.colors.accent),
            )
            if (state.text.isEmpty()) Text("Name", style = hb.type.titleL, color = hb.colors.muted)
        }
        Spacer(Modifier.height(20.dp))
        SheetActions {
            HBButton("Save", { edit.setName(state.text.toString()); onDismiss() }, fullWidth = true)
            if (!prefs.goals.name.isNullOrBlank()) HBButton("Remove name", { edit.setName(null); onDismiss() }, kind = ButtonKind.Text, fullWidth = true)
        }
    }
}

@Composable
private fun BirthYearSheet(onDismiss: () -> Unit) {
    val prefs = LocalPrefs.current
    val edit = rememberPrefsEditor()
    val now = Year.now().value
    NumberSheet("Birth year", (prefs.birthYear ?: (now - 40)).toDouble(), onDismiss = onDismiss,
        onSave = { edit.setBirthYear(it.toInt()); onDismiss() }, step = 1.0, range = (now - 100).toDouble()..(now - 10).toDouble(),
        valueText = { Math.round(it).toString() })
}

@Composable
private fun HeightSheet(onDismiss: () -> Unit) {
    val prefs = LocalPrefs.current
    val ctx = LocalContext.current
    val edit = rememberPrefsEditor()
    val version by Sync.version.collectAsState()
    val hc by produceState<Double?>(null, version) { value = hcHeightM(ctx) }
    val start = effectiveHeightCm(hc, prefs.heightCm) ?: prefs.heightCmOrDefault.toDouble()
    NumberSheet("Height", Math.round(start).toDouble(), onDismiss = onDismiss, onSave = { edit.setHeightCm(it); onDismiss() },
        step = 1.0, range = 120.0..230.0, unit = "cm")
}

/** Heart points target sheet: big stepper, "Suggested for you" with Use, default with Reset, WHO line (D17, G0). */
@Composable
private fun HeartPointsSheet(onDismiss: () -> Unit) {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val prefs = LocalPrefs.current
    val edit = rememberPrefsEditor()
    var v by rememberSaveable { mutableDoubleStateOf(prefs.goals.zoneMinWeek.toDouble()) }
    val suggested by produceState<Int?>(null) { value = runCatching { V2.load(ctx) { env -> V2.suggestedHeartPointsTarget(env) } }.getOrNull() }
    HBBottomSheet(onDismiss = onDismiss, title = "Heart points per week") {
        BigStepper(v, { v = it }, step = 10.0, range = 7.0..3_000.0, caption = WHO_LINE)
        val s = suggested
        if (s != null) {
            Spacer(Modifier.height(12.dp))
            SheetLine("Suggested for you", Fmt.int(s), "Use") { v = s.toDouble() }
        }
        Spacer(Modifier.height(12.dp))
        SheetLine("Default", Fmt.int(Goals.DEFAULT_WEEKLY_HEART_POINTS), "Reset") { v = Goals.DEFAULT_WEEKLY_HEART_POINTS.toDouble() }
        Spacer(Modifier.height(20.dp))
        SheetActions { HBButton("Save", { edit.updateGoals { it.copy(zoneMinWeek = v.toInt()) }; onDismiss() }, fullWidth = true) }
    }
}

@Composable
private fun SheetLine(label: String, value: String, action: String, onAction: () -> Unit) {
    val hb = LocalHB.current
    Row(
        Modifier.fillMaxWidth().background(hb.colors.sheetTile, RoundedCornerShape(18.dp)).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = hb.type.bodyS, color = hb.colors.muted)
        Spacer(Modifier.width(10.dp))
        Text(value, style = hb.type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = hb.colors.text, modifier = Modifier.weight(1f))
        HBButton(action, onAction, kind = ButtonKind.Text, small = true)
    }
}

/** D18 / G0: "Counts as a workout day": a recorded workout (≥ N min and ≥ M heart points), or a heart points day. */
@Composable
private fun WorkoutDayRuleSheet(onDismiss: () -> Unit) {
    val hb = LocalHB.current
    val prefs = LocalPrefs.current
    val edit = rememberPrefsEditor()
    val g = prefs.goals
    var points by rememberSaveable { mutableStateOf(g.activeDayMode == "points") }
    var minMin by rememberSaveable { mutableDoubleStateOf(g.activeDayMinWorkoutMin.toDouble()) }
    var minWorkoutPts by rememberSaveable { mutableDoubleStateOf(g.activeDayMinWorkoutPoints.toDouble()) }
    var minPts by rememberSaveable { mutableDoubleStateOf(g.activeDayMinPoints.toDouble()) }
    HBBottomSheet(onDismiss = onDismiss, title = "Counts as a workout day") {
        RuleOption("A recorded workout", selected = !points, onSelect = { points = false }) {
            RuleStepper("of at least", minMin, { minMin = it }, 5.0..240.0, 5.0, "min")
            RuleStepper("and at least", minWorkoutPts, { minWorkoutPts = it }, 0.0..200.0, 1.0, "pts")
        }
        Spacer(Modifier.height(12.dp))
        RuleOption("Heart points", selected = points, onSelect = { points = true }) {
            RuleStepper("at least", minPts, { minPts = it }, 1.0..300.0, 1.0, "pts")
        }
        val shown = g.copy(activeDayMode = if (points) "points" else "workout", activeDayMinWorkoutMin = minMin.toInt(),
            activeDayMinWorkoutPoints = minWorkoutPts.toInt(), activeDayMinPoints = minPts.toInt())
        Text(infoWorkoutDays(shown).substringBefore(" You can change"), style = hb.type.bodyXS, color = hb.colors.muted, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(20.dp))
        SheetActions {
            HBButton("Save", {
                edit.updateGoals {
                    it.copy(activeDayMode = if (points) "points" else "workout", activeDayMinWorkoutMin = minMin.toInt(),
                        activeDayMinWorkoutPoints = minWorkoutPts.toInt(), activeDayMinPoints = minPts.toInt())
                }
                onDismiss()
            }, fullWidth = true)
        }
    }
}

@Composable
private fun RuleOption(title: String, selected: Boolean, onSelect: () -> Unit, content: @Composable () -> Unit) {
    val hb = LocalHB.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().background(hb.colors.sheetTile, shape)
            .then(if (selected) Modifier.border(2.dp, hb.colors.accent, shape) else Modifier)
            .hbClickable(shape = shape, scaleOnPress = false, onClick = onSelect).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).border(2.dp, if (selected) hb.colors.accent else hb.colors.muted, CircleShape), contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.size(10.dp).background(hb.colors.accent, CircleShape))
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = hb.type.bodyMedium, color = hb.colors.text)
        }
        content()
    }
}

@Composable
private fun RuleStepper(label: String, value: Double, onChange: (Double) -> Unit, range: ClosedFloatingPointRange<Double>, step: Double, unit: String) {
    val hb = LocalHB.current
    Row(Modifier.fillMaxWidth().padding(start = 32.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = hb.type.bodyS, color = hb.colors.muted, modifier = Modifier.weight(1f))
        Stepper(value, onChange, step = step, range = range, unit = unit)
    }
}

/** One body target (D9): saving stores the start value and day through V2.withBodyTarget. */
@Composable
private fun BodyTargetSheet(metricId: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = LocalPrefs.current
    val edit = rememberPrefsEditor()
    val scope = rememberCoroutineScope()
    val g = prefs.goals
    val (title, unit, current, range, fallback) = when (metricId) {
        "weight" -> BodySpec("Target weight", "kg", g.targetWeightKg, 30.0..250.0, 75.0)
        "waist" -> BodySpec("Target waist circumference", "cm", g.targetWaistCm, 40.0..200.0, 90.0)
        else -> BodySpec("Target body fat percentage", "%", g.targetFatPct, 5.0..60.0, 20.0)
    }
    var v by rememberSaveable { mutableDoubleStateOf(current ?: fallback) }
    val latest by produceState<Double?>(null) { value = runCatching { V2.load(ctx) { env -> V2.latestValue(env, metricId) } }.getOrNull() }
    LaunchedEffect(latest) { if (current == null) latest?.let { v = Math.round(it * 2) / 2.0 } }
    fun save(target: Double?) {
        scope.launch {
            val last = withContext(Dispatchers.IO) { runCatching { V2.load(ctx) { env -> V2.latestValue(env, metricId) } }.getOrNull() }
            edit.updateGoals { V2.withBodyTarget(it, metricId, target, last, LocalDate.now()) }
            onDismiss()
        }
    }
    HBBottomSheet(onDismiss = onDismiss, title = title) {
        BigStepper(v, { v = it }, step = 0.5, range = range, decimals = 1, unit = unit)
        Spacer(Modifier.height(20.dp))
        SheetActions {
            HBButton("Save", { save(v) }, fullWidth = true)
            if (current != null) HBButton("Remove target", { save(null) }, kind = ButtonKind.Text, fullWidth = true)
        }
    }
}

private data class BodySpec(val title: String, val unit: String, val current: Double?, val range: ClosedFloatingPointRange<Double>, val fallback: Double)

// =====================================================================================================================
// Devices (V2-141: no under-text)
// =====================================================================================================================

@Composable
private fun DevicesGroup(nav: Nav) {
    val hb = LocalHB.current
    SettingsGroup("Devices") {
        row {
            Row(
                Modifier.fillMaxWidth().hbClickable(shape = HbShapes.group, scaleOnPress = false) { nav.push(Route.Tape) }
                    .heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphCircle(Glyph.Tape, Cat.waist, size = 36.dp, glyphSize = 20.dp)
                Spacer(Modifier.width(14.dp))
                Text("Renpho tape", style = hb.type.bodyMedium, color = hb.colors.text, modifier = Modifier.weight(1f))
                HBButton("Measure", { nav.push(Route.Tape) }, kind = ButtonKind.Tonal, small = true, glyph = Glyph.Bluetooth)
            }
        }
    }
}

// =====================================================================================================================
// Appearance (V2-137, V2-138, V2-139, V2-145)
// =====================================================================================================================

@Composable
private fun AppearanceGroup(onCustom: () -> Unit) {
    val hb = LocalHB.current
    val prefs = LocalPrefs.current
    val edit = rememberPrefsEditor()
    val selectedId = prefs.paletteId
    val isCustom = selectedId.startsWith(Palettes.Custom.PREFIX)
    SettingsGroup("Appearance") {
        row {
            SegmentRow("Theme", ThemeMode.entries.toList(), prefs.themeMode, { edit.setThemeMode(it) }, subtitle = null, leadingGlyph = Glyph.Moon,
                label = { when (it) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" } })
        }
        row {
            // "Presets" on its own line (current preset name right), swatches under it
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Palette, size = 22.dp, color = hb.colors.text)
                    Spacer(Modifier.width(14.dp))
                    Text("Presets", style = hb.type.bodyMedium, color = hb.colors.text, modifier = Modifier.weight(1f))
                    if (!isCustom) Text(Palettes.byId(selectedId).name, style = hb.type.bodyS, color = hb.colors.muted, maxLines = 1)
                }
                Spacer(Modifier.height(10.dp))
                // QA 22.4: 28 dp swatches without captions (9 fit), fade edge at the right when the row scrolls further
                val presetScroll = rememberScrollState()
                Box(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(presetScroll), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Palettes.curated.forEach { p -> PaletteSwatch(p, selected = p.id == selectedId, onClick = { edit.setPalette(p.id) }, size = 28.dp, caption = false) }
                    }
                    if (presetScroll.value < presetScroll.maxValue) Box(
                        Modifier.align(Alignment.CenterEnd).width(32.dp).height(40.dp)
                            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(hb.colors.card.copy(alpha = 0f), hb.colors.card))),
                    )
                }
            }
        }
        row {
            // "Custom colours" on its own line under Presets
            SettingsRow("Custom colours", leadingGlyph = Glyph.Palette, onClick = onCustom) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // QA 22.5: always a preview of the colours in use (custom: its own; preset: rings + accent)
                    val cs = Palettes.Custom.colors(selectedId)?.take(4)
                        ?: Palettes.byId(selectedId).let { listOf(it.ring(0).first, it.ring(1).first, it.ring(2).first, it.ring(it.accentRing).first) }
                    cs.forEach { c -> Box(Modifier.padding(start = 3.dp).size(12.dp).background(c, CircleShape)) }
                    Spacer(Modifier.width(6.dp))
                    GlyphIcon(Glyph.ChevR, size = 18.dp, color = hb.colors.muted)
                }
            }
        }
    }
}

/** Neutral ring track for a custom palette that has none yet. */
private val NEUTRAL_TRACK = Color(0xFF8A8A8E)

/** Quick picks: 16 hues, bright. */
private val QUICK: List<Color> = (0 until 16).map { k -> Color.hsv(k * 22.5f, 0.66f, 0.95f) }

/**
 * Custom colours: the start colour of each ring + the accent, each with a full picker (square, hue bar, hex) and quick
 * picks; ring ends are derived and light mode adjusts them through RingPalette.forTheme. A contrast check against the
 * card replaces the old light-mode sentence (V2-139).
 * Ring track (V2-137): 5th slot (Palettes.Custom.TRACK); a 4-colour custom or a preset starts from a neutral grey.
 */
@Composable
private fun CustomColoursSheet(onDismiss: () -> Unit) {
    val hb = LocalHB.current
    val prefs = LocalPrefs.current
    val edit = rememberPrefsEditor()
    val start = (Palettes.Custom.colors(prefs.paletteId)?.take(4)
        ?: Palettes.byId(prefs.paletteId).let { listOf(it.ring(0).first, it.ring(1).first, it.ring(2).first, it.ring(it.accentRing).first) }) +
        (Palettes.Custom.track(prefs.paletteId) ?: NEUTRAL_TRACK)
    var picks by remember { mutableStateOf(start) }
    var slot by rememberSaveable { mutableIntStateOf(0) }
    // palette ring order: 0 workout minutes, 1 steps, 2 heart points; 3 = accent; 4 = ring track (Custom.TRACK)
    val names = listOf("Workout minutes", "Steps", "Heart points", "Accent", "Ring track")
    val short = listOf("Workout", "Steps", "Heart pts", "Accent", "Track")
    val encoded = Palettes.Custom.encode(picks[0], picks[1], picks[2], picks[3], picks[Palettes.Custom.TRACK])
    val preview = Palettes.Custom.decode(encoded)!!.forTheme(hb.dark, hb.colors.card, hb.colors.text)
    HBBottomSheet(onDismiss = onDismiss, title = "Custom colours") {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).verticalScrollable()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TripleRing(listOf(0.85f, 0.7f, 0.95f), size = 72.dp, palette = preview, animate = false, shadow = false, gap = 2.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    names.forEachIndexed { i, n ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Box(Modifier.size(12.dp).background(picks[i], CircleShape)); Spacer(Modifier.width(8.dp))
                            Text(n, style = hb.type.bodyXS, color = if (i == slot) hb.colors.text else hb.colors.muted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            SegmentedRange((0..4).toList(), slot, { slot = it }, label = { short[it] }, size = SegmentSize.Small, track = hb.colors.sheetTile)
            Spacer(Modifier.height(14.dp))
            val c = picks[slot]
            ColorPicker(c, { nc -> picks = picks.toMutableList().also { it[slot] = nc } }, trailing = {
                val ok = contrast(c, hb.colors.card) >= 3.0
                Text(if (ok) "Contrast OK" else "Low contrast on ${if (hb.dark) "dark" else "light"}",
                    style = hb.type.labelS, color = if (ok) hb.colors.good else hb.colors.bad, maxLines = 2)
            })
            Spacer(Modifier.height(14.dp))
            Text("Quick picks", style = hb.type.label, color = hb.colors.muted)
            Spacer(Modifier.height(8.dp))
            QUICK.chunked(8).forEach { rowColors ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    rowColors.forEach { q ->
                        val sel = c.toArgb() == q.toArgb()
                        Box(
                            Modifier.size(32.dp).touchExpand().hbClickable(shape = CircleShape, scaleOnPress = false) { picks = picks.toMutableList().also { it[slot] = q } }
                                .semantics { contentDescription = "Colour for ${names[slot]}" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(32.dp).background(q, CircleShape).then(if (sel) Modifier.border(3.dp, hb.colors.text, CircleShape) else Modifier))
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            SheetActions {
                HBButton("Use these colours", { edit.setPalette(encoded); onDismiss() }, fullWidth = true)
                HBButton("Cancel", onDismiss, kind = ButtonKind.Text, fullWidth = true)
            }
        }
    }
}

@Composable
private fun Modifier.verticalScrollable(): Modifier =
    this.nestedScroll(com.boudhealth.ui.home.SheetScrollBarrier).verticalScroll(rememberScrollState())

// =====================================================================================================================
// Data & sync (V2-131 … V2-135, V2-143, V2-144, V2-146)
// =====================================================================================================================

@Composable
private fun DataGroup(nav: Nav, onSheet: (String) -> Unit) {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val status = LocalAppStatus.current
    val syncState by Sync.state.collectAsState()
    val version by Sync.version.collectAsState()
    // live: re-read after every sync and once a minute, so the shown time and day label stay right
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); tick++ } }
    val lastSync by produceState<Long?>(null, version, syncState.running, status.refreshing, tick) {
        value = withContext(Dispatchers.IO) { runCatching { Sync.lastSync(ctx) }.getOrNull() }
    }
    val granted by produceState<Set<String>?>(null, status.refreshing, status.missingPermissions) { value = grantedPermissions(ctx) }
    val bgFeature by produceState(true) { value = withContext(Dispatchers.IO) { HcReader.backgroundFeature(ctx) } }
    val importState by AppleImport.state.collectAsState()
    val failed = !status.lastSyncOk || !status.hcAvailable
    val running = syncState.running || status.refreshing

    // R5 #48 (coordinator): the "How your data is gathered" header card is removed; its group label moves to the rows
    SettingsGroup("Data & sync") {
        // R6 #2: "Last synced" + "Sync now" in one row; the action is a trailing tonal button (chevron = opens something)
        row {
            val t = lastSync?.let { Fmt.timestamp(it).let { s -> if (Fmt.date(it) == LocalDate.now()) "Today $s" else s } } ?: "Never"
            SettingsRow("Last synced", subtitle = if (running) syncState.phase.ifBlank { "Syncing…" } else t,
                subtitleColor = if (failed) hb.colors.bad else null, leadingGlyph = if (failed) Glyph.Alert else Glyph.Check) {
                HBButton("Sync now", { if (!running) status.refresh() }, kind = ButtonKind.Tonal, small = true, glyph = Glyph.Sync, busy = running)
            }
        }
        row {
            val on = granted?.contains(HcReader.BACKGROUND) == true
            SwitchRow("Background sync", on, { HcIntents.openAppPermissions(ctx) }, subtitle = null, enabled = bgFeature, leadingGlyph = Glyph.Sync)
        }
        row { ChevronRow("Data check", { nav.push(Route.DataCheck) }, leadingGlyph = Glyph.Pulse) }
        row { ChevronRow("Rebuild history", { onSheet("rebuild") }, leadingGlyph = Glyph.History) }
        row {
            val pick = rememberAppleImportLauncher()
            // status only while an import runs or failed; no hint text otherwise
            val sub = when {
                importState.running -> "Importing… ${importState.fraction?.let { "${(it * 100).toInt()}${com.boudhealth.ui.section.R3Texts.PCT_SP}%" } ?: ""}".trim()
                importState.error != null -> "Import failed"
                else -> null
            }
            SettingsRow("Import Apple Health", subtitle = sub, leadingGlyph = Glyph.Phone, onClick = pick) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconCircleButton(Glyph.Info, { onSheet("info:import") }, contentDescription = "About the import", size = 32.dp, glyphSize = 18.dp,
                        background = Color.Transparent, tint = hb.colors.muted)
                    GlyphIcon(Glyph.ChevR, size = 18.dp, color = hb.colors.muted)
                }
            }
        }
        // VO2 max read directly from the Google Health API (GoogleHealthRow.kt)
        row { GoogleHealthRow() }
    }
}

@Composable
private fun RebuildSheet(onDismiss: () -> Unit) {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val snackbar = LocalSnackbar.current
    HBBottomSheet(onDismiss = onDismiss, title = "Rebuild history") {
        Text("Reads all data from Health Connect again.", style = hb.type.bodyS, color = hb.colors.muted, maxLines = 1)
        Spacer(Modifier.height(20.dp))
        SheetActions {
            HBButton("Rebuild history", { Sync.rebuild(ctx); snackbar.toast("Rebuilding history…", Glyph.History); onDismiss() }, glyph = Glyph.History, fullWidth = true)
            HBButton("Cancel", onDismiss, kind = ButtonKind.Text, fullWidth = true)
        }
    }
}

@Composable
private fun InfoSheet(title: String, text: String, onDismiss: () -> Unit) {
    val hb = LocalHB.current
    HBBottomSheet(onDismiss = onDismiss, title = title) {
        Text(text, style = hb.type.bodyS, color = hb.colors.text)
        Spacer(Modifier.height(20.dp))
        SheetActions { HBButton("Got it", onDismiss, kind = ButtonKind.Text, fullWidth = true) }
    }
}

/** Import (i): the approved text; when an import exists, "Remove import" lives here (the Data check no longer has it). */
@Composable
private fun ImportInfoSheet(onDismiss: () -> Unit) {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val summary by produceState<AppleImport.Summary?>(null) { value = withContext(Dispatchers.IO) { runCatching { AppleImport.summary(ctx) }.getOrNull() } }
    HBBottomSheet(onDismiss = onDismiss, title = "Import Apple Health") {
        Text(INFO_IMPORT, style = hb.type.bodyS, color = hb.colors.text)
        Spacer(Modifier.height(20.dp))
        SheetActions {
            HBButton("Got it", onDismiss, kind = ButtonKind.Text, fullWidth = true)
            if (summary != null) HBButton("Remove import", {
                scope.launch { AppleImport.remove(ctx); snackbar.toast("Apple Health import removed"); onDismiss() }
            }, kind = ButtonKind.Text, fullWidth = true)
        }
    }
}

// =====================================================================================================================
// Version card (V2-130)
// =====================================================================================================================

@Composable
private fun VersionCard() {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val line = remember { appVersionLine(ctx) }
    Row(
        Modifier.fillMaxWidth().background(hb.colors.card, HbShapes.group).heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TripleRing(listOf(0.8f, 0.92f, 0.62f), size = 36.dp, animate = false, shadow = false, gap = 1.5.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Health BB", style = hb.type.bodyMedium, color = hb.colors.text)
            Text(line, style = hb.type.bodyXS.copy(fontSize = 12.5.sp), color = hb.colors.muted)
        }
    }
}
