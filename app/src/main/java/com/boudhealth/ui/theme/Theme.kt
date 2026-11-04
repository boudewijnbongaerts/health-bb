package com.boudhealth.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Prefs
import com.boudhealth.data.ThemeMode

// =====================================================================================================================
// Colour tokens (DESIGN §2). Neutral + accent roles come from Material You (dynamic); hex values are the fallbacks.
// =====================================================================================================================

@Immutable
data class HbColors(
    val bgDim: Color,
    val surface: Color,
    /** Same as surface: overview cards / tiles on bgDim. */
    val card: Color,
    val sheet: Color,
    val tile: Color,
    val tileHi: Color,
    /** Wells inside sheets. */
    val sheetTile: Color,
    val text: Color,
    val muted: Color,
    val grid: Color,
    val outline: Color,
    val accent: Color,
    val onAccent: Color,
    val accentCont: Color,
    val good: Color,
    val goodCont: Color,
    val bad: Color,
    val badCont: Color,
    val inverse: Color,
    val onInverse: Color,
    val inverseAccent: Color,
    val scrim: Color,
    /** Text / glyph colour on [accentCont] (v3: palette accent container). Defaults to [text]. */
    val onAccentCont: Color = text,
) {
    companion object {
        /**
         * V2 warm "Mono Ink Light" (V2_VISUAL_QA §3): warm paper page, near-white cards (with a soft shadow, HBCard),
         * fixed status colours. Used for every light run (dynamic colour is off, V2).
         */
        val FallbackLight = HbColors(
            bgDim = Color(0xFFF2F0E9), surface = Color(0xFFFDFCF8), card = Color(0xFFFDFCF8), sheet = Color(0xFFFDFCF8),
            tile = Color(0xFFF0EEE6), tileHi = Color(0xFFE6E3DA), sheetTile = Color(0xFFF0EEE6),
            text = Color(0xFF1C1C19), muted = Color(0xFF6B6A60), grid = Color(0xFFE4E1D7), outline = Color(0xFFCFCBBF),
            accent = Color(0xFFC04D2B), onAccent = Color(0xFFFFFFFF), accentCont = Color(0xFFF9DED2),
            good = Color(0xFF2F6B4F), goodCont = Color(0xFFDCEFE4), bad = Color(0xFF9A4A2E), badCont = Color(0xFFFCE3D8),
            inverse = Color(0xFF31312A), onInverse = Color(0xFFF4F2E8), inverseAccent = Color(0xFFFF9C6E),
            scrim = Color(0xFF1C1C19).copy(alpha = 0.32f), onAccentCont = Color(0xFF5A2412),
        )
        /** V2 dark = the approved design tokens (design6 kit5.C): warm wall, card, tile. */
        val FallbackDark = HbColors(
            bgDim = Color(0xFF121310), surface = Color(0xFF1C1C19), card = Color(0xFF1C1C19), sheet = Color(0xFF26261F),
            tile = Color(0xFF272723), tileHi = Color(0xFF33332E), sheetTile = Color(0xFF33332E),
            text = Color(0xFFEDEBE2), muted = Color(0xFFA6A699), grid = Color(0xFF33332D), outline = Color(0xFF5D5E50),
            accent = Color(0xFFFF9C6E), onAccent = Color(0xFF3A1406), accentCont = Color(0xFF5B2F20),
            good = Color(0xFF93D2B5), goodCont = Color(0xFF21443A), bad = Color(0xFFFFB59A), badCont = Color(0xFF5B2B1A),
            inverse = Color(0xFFEDEBE2), onInverse = Color(0xFF31312A), inverseAccent = Color(0xFFC04D2B),
            scrim = Color.Black.copy(alpha = 0.5f), onAccentCont = Color(0xFFFFE2D3),
        )

        /** Material You mapping (DESIGN §2 table). bad/badCont stay fixed (warm). */
        fun from(s: ColorScheme, dark: Boolean): HbColors {
            val fb = if (dark) FallbackDark else FallbackLight
            return HbColors(
                bgDim = if (dark) s.surfaceContainerLowest else s.surfaceDim,
                surface = s.surface,
                card = s.surface,
                sheet = if (dark) s.surfaceContainerHigh else s.surfaceContainerLow,
                tile = s.surfaceContainer,
                tileHi = if (dark) s.surfaceContainerHighest else s.surfaceContainerHigh,
                sheetTile = if (dark) s.surfaceContainerHighest else s.surfaceContainer,
                text = s.onSurface,
                muted = s.onSurfaceVariant,
                grid = s.outlineVariant,
                outline = s.outline,
                accent = s.primary,
                onAccent = s.onPrimary,
                accentCont = s.primaryContainer,
                // V2: status colours are fixed (Material tertiary gave purple "goal met" icons)
                good = fb.good,
                goodCont = fb.goodCont,
                bad = fb.bad,
                badCont = fb.badCont,
                inverse = s.inverseSurface,
                onInverse = s.inverseOnSurface,
                inverseAccent = s.inversePrimary,
                scrim = fb.scrim,
            )
        }
    }
}

/** Resolve the token set for a context (non-Compose; also used by widgets / bitmap renderers). */
/** V2: [dynamic] defaults to OFF: the app palette's own warm surfaces, never the wallpaper's (V2_VISUAL_QA #7). */
fun hbColors(context: Context, dark: Boolean, dynamic: Boolean = false): HbColors =
    if (dynamic) {
        runCatching { HbColors.from(if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context), dark) }
            .getOrElse { if (dark) HbColors.FallbackDark else HbColors.FallbackLight }
    } else if (dark) HbColors.FallbackDark else HbColors.FallbackLight

/** Is the effective theme dark for [mode]? Non-Compose (uses the context's configuration for SYSTEM). */
fun isDark(context: Context, mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

/** Animator duration scale 0 → reduced motion (DESIGN §6). */
fun reducedMotion(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

// =====================================================================================================================
// Category colours (fixed, both themes; DESIGN §2 + C_SPEC tokens). Ring colours come from the palette.
// =====================================================================================================================

object Cat {
    // workouts
    val strength = Color(0xFF8C7CFF)
    val cardio = Color(0xFF3FD41C)
    val other = Color(0xFF0FA3FF)
    /** Weekly workout goal ring (C_SPEC): c0 → c1. */
    val workoutGoal = Color(0xFF3FD41C) to Color(0xFFB4F06A)
    // body (weight uses the text role: HB.colors.text)
    val lean = Color(0xFF4FA3E0)
    val fat = Color(0xFFF5B041)
    val waist = Color(0xFFE07A5F)
    // vitals
    val heart = Color(0xFFFF4D6A)
    val hrv = Color(0xFFFF8FB1)
    val vo2 = Color(0xFF3FD41C)
    val spo2 = Color(0xFF0FA3FF)
    val breathing = Color(0xFF2BD4C8)
    val sleep = Color(0xFF8C7CFF)
    // sleep stages
    val deep = Color(0xFF5B4BE0)
    val rem = Color(0xFF2BB8F0)
    val light = Color(0xFF9AA8FF)
    val awake = Color(0xFFFF9F5A)
    // heart-rate zones (C_SPEC)
    val zoneBelow = Color(0xFFA6A893)
    val zoneFatBurn = Color(0xFFF5B700)
    val zoneCardio = Color(0xFFFF6A3D)
    val zonePeak = Color(0xFFE0338E)
    /** v4 Q15: % max-HR zone 3 (between fat burn and cardio). */
    val zoneMid = Color(0xFFFF9A2E)
    /** v4.1 R3: the ONE zone system (Fitbit HRR zones), index = HrZones.BELOW..PEAK. */
    val azm: List<Color> get() = listOf(zoneBelow, zoneFatBurn, zoneCardio, zonePeak)
    /** v4 P-KCAL: Calories burned / active calories (calories left the rings, so they get a fixed category colour). */
    val kcalActive = Color(0xFFFF7A3D)
    /** v4 P-KCAL: resting calories (calm stone, next to the active colour). */
    val resting = Color(0xFFB9A98C)
    // source badges
    val googleBlue = Color(0xFF3D8BFF)
    /** Dark ink on bright category fills (source badges, check marks on green): ≥ 4.5 : 1 on all of them. */
    val onBright = Color(0xFF1C1C16)

    // v3.1 tokens for colours that were hard-coded in screens
    /** Deep sleep lifted for dark mode (5B4BE0 is 2.9 : 1 on the dark card; this is 3.9 : 1). */
    val deepOnDark = Color(0xFF7466EA)
    fun deep(dark: Boolean): Color = if (dark) deepOnDark else deep
    /** BMI category (metric colour). */
    val bmi = Color(0xFF9AA8FF)
    /** SpO2 ring gradient end (c1). */
    val spo2Light = Color(0xFF6FD3FF)
    /** Fat mass (darker than the body-fat % colour so both read apart in one chart). */
    val fatMass = Color(0xFFD78731)
    /** Healthy-range marker (BMI / WHtR "you are here" dot in range). */
    val healthyMark = Color(0xFF6CC75A)
    /** Scale bands (WHtR, BMI): healthy · raised · high; apply alpha at the call site. */
    val bandHealthy = Color(0xFF3FD41C)
    val bandRaised = Color(0xFFF5B041)
    val bandHigh = Color(0xFFFF5E3A)

    /** Workout category from ExerciseTypes.category(type): 0 strength, 1 cardio, else other. */
    fun workout(category: Int): Color = when (category) { 0 -> strength; 1 -> cardio; else -> other }
    /** v5 S5: one clean colour per workout kind (index = WorkoutGroup.ordinal; the last, "Workout", is neutral). */
    val workoutGroups: List<Color> = listOf(
        Color(0xFF7C5CFF), // Strength
        Color(0xFF1FA2FF), // Cycling
        Color(0xFF34C17B), // Walking
        Color(0xFFFF6B3D), // Running
        Color(0xFFFF9F1C), // Treadmill
        Color(0xFF00B8C4), // Rowing
        Color(0xFFE0479E), // Elliptical
        Color(0xFF3D7BFF), // Swimming
        Color(0xFFFF3D5A), // HIIT
        Color(0xFFB57BFF), // Yoga
        Color(0xFF6BAA3A), // Hiking
        Color(0xFFC98A3D), // Stairs
        Color(0xFFFFC233), // Sports
        Color(0xFFFF7EB6), // Dancing
        Color(0xFF9AA0A6), // Workout (unknown)
    )
    fun workoutColor(g: com.boudhealth.data.WorkoutGroup): Color = workoutGroups.getOrElse(g.ordinal) { other }
    fun workoutColor(w: com.boudhealth.data.Workout): Color = workoutColor(com.boudhealth.data.ExerciseTypes.group(w))
}

// =====================================================================================================================
// Typography (DESIGN §3). V2: the bundled Inter (res/font, tnum-frozen; same files as rings and widgets,
// render/ring/RingFonts), tabular figures everywhere so count-ups don't jitter. System font until AppTheme loads it.
// =====================================================================================================================

private const val TNUM = "tnum"

/** The app font family (AppTheme sets it once from res/font; FontFamily.Default when the resources are missing). */
object AppFont {
    @Volatile var family: FontFamily = FontFamily.Default
        private set

    fun init(context: Context) {
        if (family != FontFamily.Default) return
        com.boudhealth.render.ring.RingFonts.init(context)
        val fonts = listOf(400, 500, 600, 700).mapNotNull { w ->
            val id = com.boudhealth.render.ring.RingFonts.resId(context, w)
            if (id == 0) null else Font(id, FontWeight(w))
        }
        if (fonts.size == 4) family = FontFamily(fonts)
    }
}

private fun style(size: Float, weight: Int, line: Float? = null, tracking: Float = 0f) = TextStyle(
    fontFamily = AppFont.family,
    fontSize = size.sp,
    fontWeight = FontWeight(weight),
    lineHeight = if (line != null) line.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
    letterSpacing = tracking.sp,
    fontFeatureSettings = TNUM,
)

@Immutable
data class HbType(
    val display: TextStyle = style(56f, 700, 64f, -1.6f),
    val displayUnit: TextStyle = style(20f, 500),
    val headline: TextStyle = style(32f, 700, 38f, -0.6f),
    /** V2 screen title (design6 top_bar): 28/700, on the same row as the header actions. */
    val headlineM: TextStyle = style(28f, 700, 34f, -0.5f),
    /** Collapsed header title. */
    val headlineSmall: TextStyle = style(20f, 700, 26f, -0.2f),
    val number: TextStyle = style(26f, 700, 32f, -0.3f),
    /** Stat-row value. */
    val numberS: TextStyle = style(22f, 700, 28f, -0.3f),
    val unit: TextStyle = style(13f, 500),
    val titleL: TextStyle = style(22f, 700, 28f, -0.3f),
    val titleM: TextStyle = style(18f, 700, 24f, -0.2f),
    val titleS: TextStyle = style(16f, 600, 22f),
    val body: TextStyle = style(15f, 400, 22f),
    val bodyMedium: TextStyle = style(15f, 500, 22f),
    val bodyS: TextStyle = style(14f, 400, 20f),
    val bodyXS: TextStyle = style(13f, 400, 18f),
    val label: TextStyle = style(13f, 600, 18f),
    val labelS: TextStyle = style(12f, 600, 16f),
    val button: TextStyle = style(15f, 600, 20f),
    val buttonS: TextStyle = style(14f, 600, 18f),
    val overline: TextStyle = style(11f, 600, 14f, 0.8f),
    val caption: TextStyle = style(11f, 400, 14f),
    val micro: TextStyle = style(10f, 600, 12f),
)

// =====================================================================================================================
// Shapes + spacing (DESIGN §1, §4)
// =====================================================================================================================

object HbShapes {
    /** V2 (design6): card r 22, hero card r 26. */
    val card = RoundedCornerShape(22.dp)
    val hero = RoundedCornerShape(26.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val group = RoundedCornerShape(20.dp)
    val tile = RoundedCornerShape(18.dp)
    val tileS = RoundedCornerShape(16.dp)
    val tooltip = RoundedCornerShape(12.dp)
    val snackbar = RoundedCornerShape(16.dp)
    val state = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(percent = 50)
}

object Space {
    val s4: Dp = 4.dp; val s8: Dp = 8.dp; val s12: Dp = 12.dp; val s16: Dp = 16.dp
    val s20: Dp = 20.dp; val s24: Dp = 24.dp; val s32: Dp = 32.dp; val s40: Dp = 40.dp
    /** Screen gutter: 20 below 600 dp width, 24 at ≥ 600. */
    fun gutter(widthDp: Float): Dp = if (widthDp >= 600f) 24.dp else 20.dp
    const val WIDE_BREAKPOINT_DP = 680f
    /**
     * Content column cap. 2.0.1: 1,200 dp (was 840) so the Fold inner screen (~1,088 dp at native density) is filled
     * with the normal 24 dp gutters instead of empty side bands; only wider windows (tablets, desktop) are capped.
     */
    val contentMaxWidth: Dp = 1200.dp
}

// =====================================================================================================================
// Composition local: everything a component needs.
// =====================================================================================================================

@Immutable
class HB(
    val colors: HbColors,
    val palette: RingPalette,
    val dark: Boolean,
    val type: HbType = HbType(),
    val reducedMotion: Boolean = false,
) {
    /** Text-safe version of a category colour on the card (4.5:1). Dark: unchanged. */
    fun legible(c: Color, bg: Color = colors.card): Color = if (dark) c else com.boudhealth.ui.theme.legible(c, bg, colors.text, 4.5)
    /** Glyph-safe (≥ 20 dp glyphs, 3:1). */
    fun legibleGlyph(c: Color, bg: Color = colors.card): Color = if (dark) c else com.boudhealth.ui.theme.legible(c, bg, colors.text, 3.0)
    /** Glyph circle / tile tint: 16 % light, 22 % dark. */
    fun tint(c: Color): Color = c.copy(alpha = if (dark) 0.22f else 0.16f)
    /** Personal-normal band fill: 8 % light, 10 % dark. */
    fun band(c: Color): Color = c.copy(alpha = if (dark) 0.10f else 0.08f)
    /** Below-goal bar fill: c0 at 32 % light / 42 % dark. */
    fun belowGoal(c: Color): Color = c.copy(alpha = if (dark) 0.42f else 0.32f)
    /** Deep-sleep colour for this theme (lighter in dark mode, v3.1). */
    val deep: Color get() = Cat.deep(dark)
    /** Ring track alpha: 32 % on light (the lighter rings need a visible track), 20 % on dark. */
    val ringTrackAlpha: Float get() = if (dark) 0.20f else 0.32f
    /** Ring i label colour (0 calories, 1 zone, 2 steps). */
    fun ringLabel(i: Int): Color = palette.label(i, dark, colors.card, colors.text)
    /** Weight is drawn in the text role. */
    val weight: Color get() = colors.text
    /** v4.1: HR zone colours (below, fat burn, cardio, peak), ≥ 3 : 1 on the card in light mode. See [zoneColorsForTheme]. */
    val azm: List<Color> get() = zoneColorsForTheme(dark, colors.card, colors.text)
    /** v4.1: energy split colours (active, resting), ≥ 3 : 1 on the card in light mode. See [energyColorsForTheme]. */
    val energyActive: Color get() = energyColorsForTheme(dark, colors.card, colors.text).first
    val energyResting: Color get() = energyColorsForTheme(dark, colors.card, colors.text).second

    companion object
}

val LocalHB = staticCompositionLocalOf { HB(HbColors.FallbackLight, Palettes.default, dark = false) }

/** Shorthand: `HB.current.colors.tile`, `HB.current.palette`, `HB.current.type.display`. */
val HB.Companion.current: HB @Composable get() = LocalHB.current

private fun schemeFrom(c: HbColors, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentCont, onPrimaryContainer = c.onAccentCont,
        secondary = c.accent, onSecondary = c.onAccent, secondaryContainer = c.accentCont, onSecondaryContainer = c.onAccentCont,
        tertiary = c.good, tertiaryContainer = c.goodCont,
        background = c.surface, onBackground = c.text,
        surface = c.surface, onSurface = c.text, surfaceVariant = c.tile, onSurfaceVariant = c.muted,
        surfaceTint = Color.Transparent,
        inverseSurface = c.inverse, inverseOnSurface = c.onInverse, inversePrimary = c.inverseAccent,
        error = c.bad, errorContainer = c.badCont,
        outline = c.outline, outlineVariant = c.grid, scrim = c.scrim,
        surfaceDim = c.bgDim, surfaceBright = c.surface,
        surfaceContainerLowest = if (dark) c.bgDim else c.surface,
        surfaceContainerLow = c.sheet, surfaceContainer = c.tile,
        surfaceContainerHigh = c.tileHi, surfaceContainerHighest = c.tileHi,
    )
}

/** Material 3 type scale in the app font (Material components: buttons, dialogs, text fields). */
private fun appTypography(f: FontFamily): androidx.compose.material3.Typography {
    val t = androidx.compose.material3.Typography()
    fun TextStyle.f() = copy(fontFamily = f, fontFeatureSettings = TNUM)
    return t.copy(
        displayLarge = t.displayLarge.f(), displayMedium = t.displayMedium.f(), displaySmall = t.displaySmall.f(),
        headlineLarge = t.headlineLarge.f(), headlineMedium = t.headlineMedium.f(), headlineSmall = t.headlineSmall.f(),
        titleLarge = t.titleLarge.f(), titleMedium = t.titleMedium.f(), titleSmall = t.titleSmall.f(),
        bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
        labelLarge = t.labelLarge.f(), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
    )
}

/**
 * App theme. Resolves light/dark from [prefs].themeMode, Material You neutrals, ring palette, typography.
 * Read tokens with `val hb = LocalHB.current` (or `HB.current`).
 */
@Composable
fun AppTheme(prefs: Prefs, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (prefs.themeMode) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val palette = remember(prefs.paletteId) { Palettes.byId(prefs.paletteId) }
    val base = remember(dark, ctx) { hbColors(ctx, dark) }
    // v3 (D6 / C2): the selected palette colours the whole app. Neutrals stay Material You; the accent roles come from
    // the palette, re-derived against the actual card / text colours so contrast holds on every phone.
    val colors = remember(base, palette, dark) { base.withPaletteAccent(palette, dark) }
    val reduced = remember(ctx) { reducedMotion(ctx) }
    remember(ctx) { AppFont.init(ctx); true }
    // v3.1 (owner default): in LIGHT mode the ring colours are darkened to ≥ 3 : 1 on the card; dark mode unchanged.
    // The accent above is derived from the raw palette (it has its own 4.5 : 1 rule).
    val ringPalette = remember(palette, dark, colors) { palette.forTheme(dark, colors.card, colors.text) }
    val hb = remember(colors, ringPalette, dark, reduced) { HB(colors, ringPalette, dark, HbType(), reduced) }
    MaterialTheme(
        colorScheme = remember(colors, dark) { schemeFrom(colors, dark) },
        typography = remember(AppFont.family) { appTypography(AppFont.family) },
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
    ) {
        CompositionLocalProvider(LocalHB provides hb, content = content)
    }
}

/**
 * The token set with the accent roles taken from [palette] (C2): accent / onAccent / accentCont / onAccentCont for this
 * theme, and inverseAccent = the accent derived for the inverse surface (snackbar, popovers).
 */
fun HbColors.withPaletteAccent(palette: RingPalette, dark: Boolean): HbColors {
    val a = palette.accent(dark, card, text)
    val inv = palette.accent(!dark, inverse, onInverse)
    return copy(
        accent = a.accent, onAccent = a.onAccent, accentCont = a.accentContainer, onAccentCont = a.onAccentContainer,
        inverseAccent = inv.accent,
    )
}

/** ARGB view of the tokens for Canvas / widget code (see render/Palette.kt RenderTheme). */
fun HbColors.argb(): Map<String, Int> = mapOf(
    "bgDim" to bgDim.toArgb(), "surface" to surface.toArgb(), "card" to card.toArgb(), "sheet" to sheet.toArgb(),
    "tile" to tile.toArgb(), "tileHi" to tileHi.toArgb(), "text" to text.toArgb(), "muted" to muted.toArgb(),
    "grid" to grid.toArgb(), "outline" to outline.toArgb(), "accent" to accent.toArgb(), "good" to good.toArgb(),
    "goodCont" to goodCont.toArgb(), "bad" to bad.toArgb(), "badCont" to badCont.toArgb(), "inverse" to inverse.toArgb(),
    "onInverse" to onInverse.toArgb(),
)
