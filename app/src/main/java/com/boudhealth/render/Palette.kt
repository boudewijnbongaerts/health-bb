package com.boudhealth.render

import android.content.Context
import com.boudhealth.data.UiPrefs
import com.boudhealth.render.ring.RingFonts
import com.boudhealth.ui.theme.Cat
import com.boudhealth.ui.theme.argb
import com.boudhealth.ui.theme.HbColors
import com.boudhealth.ui.theme.Palettes
import com.boudhealth.ui.theme.RingPalette
import com.boudhealth.ui.theme.energyArgbForTheme
import com.boudhealth.ui.theme.forTheme
import com.boudhealth.ui.theme.zoneArgbForTheme
import com.boudhealth.ui.theme.hbColors
import com.boudhealth.ui.theme.isDark
import com.boudhealth.ui.theme.legibleArgb

/**
 * Non-Compose theme for bitmaps / widgets: the same tokens and ring palette as the app (ui/theme), as ARGB ints.
 *
 *     val t = RenderTheme.of(context, store.uiNow())     // honours themeMode + paletteId
 *     paint.color = t.card; t.ring.c0Argb(0)             // calories c0
 */
class RenderTheme(
    val dark: Boolean,
    val colors: HbColors,
    /** The selected palette as stored (seeds the accent, which has its own 4.5 : 1 rule). */
    val rawRing: RingPalette,
) {
    val bgDim = colors.bgDim.argb()
    val surface = colors.surface.argb()
    val card = colors.card.argb()
    val tile = colors.tile.argb()
    val tileHi = colors.tileHi.argb()
    val text = colors.text.argb()
    val muted = colors.muted.argb()
    val grid = colors.grid.argb()
    val outline = colors.outline.argb()
    /**
     * Ring colours for this theme: the app's own RingPalette.forTheme (ui/theme/Palettes.kt, OKLCH RingLight.adjust),
     * called directly so widget and app can never drift. LIGHT → start darkened to ≥ 3 : 1 on the card (hue fixed,
     * chroma boost, gamut-mapped), end moved by the same ΔL and kept within 1.3 : 1 of the start; a start that already
     * passes keeps its gradient exactly. DARK → unchanged. Everything that draws rings or ring labels uses this.
     */
    val ring: RingPalette = rawRing.forTheme(dark, colors.card, colors.text)
    /** Ring track alpha: 32 % on light (the darkened rings need a visible track), 20 % on dark (app HB.ringTrackAlpha). */
    val ringTrackAlpha: Float = if (dark) 0.20f else 0.32f

    /** App accent derived from the raw ring palette (v3 C2), same as the app's HbColors.accent. */
    val appAccent = rawRing.accent(dark, colors.card, colors.text)
    val accent = appAccent.accentArgb
    val onAccent = appAccent.onAccentArgb
    val good = colors.good.argb()
    val goodCont = colors.goodCont.argb()
    val bad = colors.bad.argb()
    val badCont = colors.badCont.argb()
    val inverse = colors.inverse.argb()
    val onInverse = colors.onInverse.argb()

    /** Text-safe category colour on the card (4.5:1); dark: unchanged. */
    fun legible(c: Int, min: Double = 4.5): Int = if (dark) c else legibleArgb(c, card, text, min)
    /** Ring label colour, i = 0 calories, 1 zone, 2 steps. */
    fun ringLabel(i: Int): Int = ring.labelArgb(i, dark, card, text)
    /**
     * v4.1 HRR zone colours for this theme, index below / fat burn / cardio / peak (app zoneArgbForTheme: light mode
     * lifted to ≥ 3 : 1 on the card, dark unchanged).
     */
    val zones: IntArray = zoneArgbForTheme(dark, card, text)
    /** Energy colours for this theme: [0] active, [1] resting (app energyArgbForTheme). */
    val energy: IntArray = energyArgbForTheme(dark, card, text)
    /** Deep-sleep colour for this theme (app HB.deep = Cat.deep(dark): lifted on dark, 3.9 : 1). */
    val deep: Int = Cat.deep(dark).argb()
    /** Glyph-circle tint (16 % / 22 %). */
    fun tint(c: Int): Int = c.alpha(if (dark) 0.22f else 0.16f)

    /** Week bar (v3 C1, replaces the weekly halo ring): the palette's Zone gradient on the tileHi track. */
    val weekBar: IntArray get() = intArrayOf(ring.c0Argb(1), ring.c1Argb(1))

    companion object {
        /** Theme for the effective mode: themeMode (LIGHT / DARK) or, for SYSTEM, the context's night mode. */
        fun of(context: Context, ui: UiPrefs = UiPrefs()): RenderTheme = of(context, ui, isDark(context, ui.themeMode))

        /** Theme forced to [dark] (widgets render both variants when the theme follows the system). */
        fun of(context: Context, ui: UiPrefs, dark: Boolean): RenderTheme {
            RingFonts.init(context)   // V2: bundled Inter for every widget / bitmap text
            return RenderTheme(dark, hbColors(context, dark), Palettes.byId(ui.paletteId))
        }
    }
}

/** Fixed category colours as ARGB (same as ui/theme Cat). */
object Hue {
    val heart = Cat.heart.argb()
    val vo2 = Cat.vo2.argb()
    val spo2 = Cat.spo2.argb()
    val resp = Cat.breathing.argb()
    val sleep = Cat.sleep.argb()
    val hrv = Cat.hrv.argb()
    val strength = Cat.strength.argb()
    val cardio = Cat.cardio.argb()
    val other = Cat.other.argb()
    /** Light-theme deep sleep; theme-aware code uses RenderTheme.deep. */
    val deep = Cat.deep.argb()
    val rem = Cat.rem.argb()
    val light = Cat.light.argb()
    val awake = Cat.awake.argb()
    val waist = Cat.waist.argb()
    val lean = Cat.lean.argb()
    val fat = Cat.fat.argb()
    /** Workout streak (app StreakTile uses Cat.awake). */
    val streak = Cat.awake.argb()
    val bmi = Cat.bmi.argb()
    /** SpO2 ring gradient end (c1). */
    val spo2Light = Cat.spo2Light.argb()
    /** BMI / WHtR scale-bar zones: in range, raised, high. */
    val zoneOk = Cat.cardio.argb()
    val zoneWarn = Cat.fat.argb()
    val zoneHigh = Cat.zoneCardio.argb()
    /** Dark ink on bright fills (check marks on green). */
    val checkInk = Cat.onBright.argb()

    // v4.1: zone and energy colours are theme-dependent (light-mode contrast): use RenderTheme.zones / .energy.

    /** v5 S5: one colour per workout kind (index = WorkoutGroup.ordinal), app Cat.workoutColor. */
    fun workoutGroup(g: com.boudhealth.data.WorkoutGroup): Int = Cat.workoutColor(g).argb()

    /** Workout category (ExerciseTypes.category): 0 strength, 1 cardio, else other. */
    fun workout(category: Int): Int = when (category) { 0 -> strength; 1 -> cardio; else -> other }
}

fun Int.alpha(f: Float): Int = (this and 0x00FFFFFF) or ((f.coerceIn(0f, 1f) * 255).toInt() shl 24)

