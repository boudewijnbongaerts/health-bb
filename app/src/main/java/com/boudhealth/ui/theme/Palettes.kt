package com.boudhealth.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.boudhealth.data.UiPrefs

/**
 * App accent derived from a ring palette (v3, D6): lets the selected palette colour the whole app, not only the rings.
 * Use for: selected segment of the range control, primary buttons, switch-on, links / "See all", chart highlight
 * (scrub dot, selected bar), hero-number accents, focus. Contrast is guaranteed on the card colour it was derived for:
 * [accent] ≥ 4.5 : 1 on the card, [onAccent] ≥ 4.5 : 1 on [accent], [onAccentContainer] ≥ 4.5 : 1 on [accentContainer].
 */
data class AppAccent(
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
) {
    val accentArgb: Int get() = accent.toArgb()
    val onAccentArgb: Int get() = onAccent.toArgb()
    val accentContainerArgb: Int get() = accentContainer.toArgb()
    val onAccentContainerArgb: Int get() = onAccentContainer.toArgb()
}

/** ARGB of a Compose colour, for render/ code (keeps androidx.compose imports out of render/, IC7). */
fun Color.argb(): Int = toArgb()

/**
 * V2 ring slots (cause 11): colour follows POSITION, outer → inner. The constructor fields keep their historic names
 * (calories / zone / steps) only so the 24 built-in palettes and stored custom palettes stay source-compatible; read
 * them through [RingPalette.workoutMin], [RingPalette.stepsSlot], [RingPalette.heartPoints] or [RingSlot.of].
 */
object RingSlot {
    const val WORKOUT_MIN = 0
    const val STEPS = 1
    const val HEART_POINTS = 2
    /** Slot of a ring / metric id ("exercise_min", "steps", "zone_min"); -1 if it is not a ring. */
    fun of(id: String): Int = when (id) { "exercise_min" -> WORKOUT_MIN; "steps" -> STEPS; "zone_min", "heart_points" -> HEART_POINTS; else -> -1 }
}

/**
 * Ring palette: gradient start/end (c0 → c1) per ring slot (V2: 0 Workout minutes, 1 Steps, 2 Heart points; see [RingSlot]).
 * Applies everywhere rings or ring-coloured data appear (app + widgets). No Compose runtime needed: safe in renderers.
 * [accentRing] picks the ring whose hue seeds the app accent ([accent]).
 */
data class RingPalette(
    val id: String,
    val name: String,
    /** Short name for small captions (picker grid). */
    val short: String,
    val note: String,
    val calories: Pair<Color, Color>,
    val zone: Pair<Color, Color>,
    val steps: Pair<Color, Color>,
    /** Weak on the light card: show "made for dark mode" in the picker. */
    val darkOnly: Boolean = false,
    val group: String = "",
    /** 0 calories, 1 zone, 2 steps: the ring that seeds the app accent. */
    val accentRing: Int = 0,
    /** v4 custom palettes (Q21): an explicit accent colour; null = derive from [accentRing]. */
    val accentSeed: Color? = null,
    /** V2-137: the user's ring TRACK colour (custom palettes only); null = the neutral track (tile mixed 11 % toward text). */
    val trackSeed: Color? = null,
) {
    /** V2 slot names (cause 11). Slot 0 = Workout minutes (outer). */
    val workoutMin: Pair<Color, Color> get() = calories
    /** Slot 1 = Steps (middle). */
    val stepsSlot: Pair<Color, Color> get() = zone
    /** Slot 2 = Heart points (inner). */
    val heartPoints: Pair<Color, Color> get() = steps
    /** Colours of a ring / metric id ([RingSlot.of]); null if it is not a ring. */
    fun forId(id: String): Pair<Color, Color>? = RingSlot.of(id).takeIf { it >= 0 }?.let { rings[it] }

    /** v6 names, kept for source compatibility. */
    val workout: Pair<Color, Color> get() = calories
    val stepsRing: Pair<Color, Color> get() = zone
    val intensity: Pair<Color, Color> get() = steps

    /** Ring order outer → inner: slot 0 (Workout minutes), 1 (Steps), 2 (Heart points). */
    val rings: List<Pair<Color, Color>> get() = listOf(calories, zone, steps)
    fun ring(i: Int): Pair<Color, Color> = rings[i]

    /** ARGB ints for Canvas / widgets: argb[ring][0 = c0, 1 = c1]. */
    val argb: Array<IntArray> by lazy { Array(3) { i -> intArrayOf(rings[i].first.toArgb(), rings[i].second.toArgb()) } }
    fun c0Argb(i: Int): Int = argb[i][0]
    fun c1Argb(i: Int): Int = argb[i][1]

    /**
     * Label / text colour for ring [i] on [card] (DESIGN §2): light = legible(c0, card, text, 4.5); dark = c1.
     */
    fun label(i: Int, dark: Boolean, card: Color, text: Color): Color =
        if (dark) rings[i].second else legible(rings[i].first, card, text, 4.5)

    fun labelArgb(i: Int, dark: Boolean, card: Int, text: Int): Int =
        label(i, dark, Color(card), Color(text)).toArgb()

    /** App accent on the fallback cards (DESIGN §2 hex values). Cached. */
    val accentLight: AppAccent by lazy { deriveAccent(this, false, Palettes.LIGHT_CARD, Palettes.LIGHT_TEXT) }
    val accentDark: AppAccent by lazy { deriveAccent(this, true, Palettes.DARK_CARD, Palettes.DARK_TEXT) }

    /**
     * App accent for the actual theme. Pass the resolved (Material You) card + text colours when they differ from the
     * fallbacks; contrast is then re-checked against them. Not cached when card/text are given: call it once per theme.
     */
    fun accent(dark: Boolean, card: Color? = null, text: Color? = null): AppAccent =
        if (card == null || text == null) (if (dark) accentDark else accentLight) else deriveAccent(this, dark, card, text)
}

/** Minimum contrast of a ring START colour (c0) against the card in light mode (graphics, WCAG 1.4.11). */
const val RING_MIN_CONTRAST_LIGHT = 3.0
/** The ring END colour (c1) stays within this contrast of the adjusted start, so the gradient survives. */
const val RING_MAX_GRADIENT_CONTRAST = 1.3
/** Chroma boost per unit of relative lightness removed (keeps darkened colours vivid instead of muddy). */
const val RING_CHROMA_BOOST = 0.25

/**
 * v3.1 round 2 — THE light-mode ring colour rule (owner default). Dark mode: palette unchanged.
 * Light mode, per ring, in OKLCH (hue never changes):
 *  1. c0: lower L in steps of 0.005 until contrast(c0, card) ≥ 3.0; chroma C = C0 · (1 + 0.25 · (L0 − L) / L0);
 *     each candidate is gamut-mapped by lowering chroma in 0.002 steps until it is inside sRGB.
 *  2. c1: shift L by the same ΔL as c0 (same chroma boost formula relative to c1's own L), gamut-mapped the same way.
 *  3. while contrast(c0', c1') > 1.3: move c1' lightness 0.005 toward c0' (chroma formula re-applied).
 * Only c0 is held to 3 : 1. AppTheme applies it, so `LocalHB.current.palette` is already adjusted.
 * Widgets: [ringArgbForTheme]. The picker shows raw palettes; the accent uses the raw palette ([deriveAccent]).
 */
fun RingPalette.forTheme(dark: Boolean, card: Color, @Suppress("UNUSED_PARAMETER") text: Color): RingPalette {
    // v4.1 review: dark branch – a (custom) start colour under 3 : 1 on the dark card is lifted in OKLCH L until it passes;
    // palettes that already pass (all curated ones) are returned unchanged
    if (dark) {
        fun d(c: Pair<Color, Color>) = RingLight.adjustDark(c.first, c.second, card)
        return copy(calories = d(calories), zone = d(zone), steps = d(steps))
    }
    fun f(c: Pair<Color, Color>) = RingLight.adjust(c.first, c.second, card)
    return copy(calories = f(calories), zone = f(zone), steps = f(steps))
}

/** ARGB form of [forTheme] for Canvas / widget renderers: result[ring][0 = c0, 1 = c1]. */
fun ringArgbForTheme(p: RingPalette, dark: Boolean, cardArgb: Int, textArgb: Int): Array<IntArray> =
    p.forTheme(dark, Color(cardArgb), Color(textArgb)).argb

/** OKLCH implementation of [forTheme] (mirrors scratchpad/ui3chk/ring.py, which checks all 22 palettes). */
internal object RingLight {
    private fun lin(x: Double) = if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
    private fun gam(x: Double) = if (x <= 0.0031308) 12.92 * x else 1.055 * Math.pow(x, 1 / 2.4) - 0.055

    /** (L, C, h) */
    private fun toOklch(c: Color): DoubleArray {
        val r = lin(c.red.toDouble()); val g = lin(c.green.toDouble()); val b = lin(c.blue.toDouble())
        val l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        val L = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s
        val a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s
        val bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        return doubleArrayOf(L, Math.hypot(a, bb), Math.atan2(bb, a))
    }

    private fun linFromOklch(L: Double, C: Double, h: Double): DoubleArray {
        val a = C * Math.cos(h); val b = C * Math.sin(h)
        val l = Math.pow(L + 0.3963377774 * a + 0.2158037573 * b, 3.0)
        val m = Math.pow(L - 0.1055613458 * a - 0.0638541728 * b, 3.0)
        val s = Math.pow(L - 0.0894841775 * a - 1.2914855480 * b, 3.0)
        return doubleArrayOf(
            4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.5115718624 * m + 1.7076537461 * s,
        )
    }

    /** OKLCH → sRGB, lowering chroma (0.002 steps) until in gamut. */
    private fun make(L: Double, C0: Double, h: Double, alpha: Float): Color {
        var C = C0
        var v = linFromOklch(L, C, h)
        while (C > 0 && v.any { it < -1e-4 || it > 1 + 1e-4 }) { C -= 0.002; v = linFromOklch(L, C.coerceAtLeast(0.0), h) }
        fun ch(x: Double) = gam(x.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0).toFloat()
        return Color(ch(v[0]), ch(v[1]), ch(v[2]), alpha)
    }

    /** Gradient end for a picked colour: + 0.14 OKLCH lightness, chroma kept (gamut-mapped), same hue. */
    fun lighter(c: Color): Color {
        val (l, k, h) = toOklch(c).let { Triple(it[0], it[1], it[2]) }
        return make((l + 0.14).coerceAtMost(0.96), k, h, c.alpha)
    }

    /** Dark theme: raise L (0.005 steps, chroma kept, gamut-mapped) until contrast(c0, card) ≥ 3; c1 moves by the same ΔL. */
    fun adjustDark(c0: Color, c1: Color, card: Color): Pair<Color, Color> {
        if (contrast(c0, card) >= RING_MIN_CONTRAST_LIGHT) return c0 to c1
        val (l0, k0, h0) = toOklch(c0).let { Triple(it[0], it[1], it[2]) }
        val (l1, k1, h1) = toOklch(c1).let { Triple(it[0], it[1], it[2]) }
        var L = l0; var a = c0
        while (contrast(a, card) < RING_MIN_CONTRAST_LIGHT && L < 0.97) { L += 0.005; a = make(L, k0, h0, c0.alpha) }
        return a to make((l1 + (L - l0)).coerceAtMost(0.98), k1, h1, c1.alpha)
    }

    fun adjust(c0: Color, c1: Color, card: Color): Pair<Color, Color> {
        val (l0, k0, h0) = toOklch(c0).let { Triple(it[0], it[1], it[2]) }
        val (l1, k1, h1) = toOklch(c1).let { Triple(it[0], it[1], it[2]) }
        var a = c0; var L = l0
        while (contrast(a, card) < RING_MIN_CONTRAST_LIGHT && L > 0.05) {
            L -= 0.005
            a = make(L, k0 * (1 + RING_CHROMA_BOOST * (l0 - L) / l0), h0, c0.alpha)
        }
        if (a == c0) return c0 to c1   // already ≥ 3 : 1: the palette's own gradient stays exactly as designed
        val d = l0 - L
        var lb = l1 - d
        fun end(x: Double) = make(x, k1 * (1 + RING_CHROMA_BOOST * (l1 - x) / l1.coerceAtLeast(1e-3)), h1, c1.alpha)
        var b = end(lb)
        val la = toOklch(a)[0]
        var guard = 0
        while (contrast(a, b) > RING_MAX_GRADIENT_CONTRAST && guard++ < 400) {
            lb += if (lb > la) -0.005 else 0.005
            b = end(lb)
        }
        return a to b
    }
}

/**
 * Accent derivation (mirrors design3/palettes.js). Seed = ring [RingPalette.accentRing].
 * Light: accent = legible(c0) on card; onAccent = white if ≥ 4.5 else text; container = c0 mixed 80 % into card;
 *        onContainer = legible(c0) on the container.
 * Dark:  accent = legible(c1) on card; onAccent = card if ≥ 4.5 else black; container = c0 mixed 62 % into card;
 *        onContainer = legible(c1) on the container.
 */
fun deriveAccent(p: RingPalette, dark: Boolean, card: Color, text: Color): AppAccent {
    val (c0, c1) = p.accentSeed?.let { it to RingLight.lighter(it) } ?: p.ring(p.accentRing.coerceIn(0, 2))
    return if (!dark) {
        val accent = legible(c0, card, text, 4.5)
        val on = if (contrast(Color.White, accent) >= 4.5) Color.White else text
        val cont = lerpColor(c0, card, 0.80f)
        AppAccent(accent, on, cont, legible(c0, cont, text, 4.5))
    } else {
        val accent = legible(c1, card, text, 4.5)
        val on = if (contrast(card, accent) >= 4.5) card else Color.Black
        val cont = lerpColor(c0, card, 0.62f)
        AppAccent(accent, on, cont, legible(c1, cont, text, 4.5))
    }
}

private fun c(hex: Long) = Color(0xFF000000 or hex)
private fun p(a: Long, b: Long) = c(a) to c(b)

object Palettes {
    const val DEFAULT_ID = UiPrefs.DEFAULT_PALETTE_ID

    /** Fallback card / text colours (DESIGN §2) the cached accents are derived for. */
    val LIGHT_CARD = Color(0xFFFBF9F1)
    val LIGHT_TEXT = Color(0xFF1C1C16)
    val DARK_CARD = Color(0xFF1C1C16)
    val DARK_TEXT = Color(0xFFE5E3D6)

    /**
     * 22 palettes (v3, D6). Order = picker order, grouped by [RingPalette.group]:
     * Earth · Vivid · Cool · Nature · Soft · Bold · Dark (made for dark mode) · Calm. Existing ids are kept.
     */
    val all: List<RingPalette> = listOf(
        // ---- Earth
        RingPalette("copper_pine_marigold", "Copper · Pine · Marigold", "Copper", "Metallic copper, deep pine, golden marigold.",
            p(0xBC6128, 0xF2A35E), p(0x25845E, 0x5FCB92), p(0xFFA21A, 0xFFD45C), group = "Earth", accentRing = 0),
        RingPalette("terracotta_moss_amber", "Terracotta · Moss · Amber", "Terracotta", "Warm clay red, fresh moss, honey amber.",
            p(0xC4572E, 0xF09A68), p(0x5E9A22, 0xB2DA4C), p(0xF28A00, 0xFFC547), group = "Earth", accentRing = 0),
        RingPalette("clay_olive_saffron", "Clay · Olive · Saffron", "Clay", "Closest to the wallpaper; olive in the middle.",
            p(0xB8653A, 0xEBA77E), p(0x8A9A12, 0xCFDB4E), p(0xF5A300, 0xFFD54D), group = "Earth", accentRing = 1),
        RingPalette("cocoa_sage_tangerine", "Cocoa · Sage · Tangerine", "Cocoa", "Soft and calm; caramel lift on cocoa.",
            p(0xA8643C, 0xE0A876), p(0x4FA36A, 0xA6DB92), p(0xFF7A1F, 0xFFB660), group = "Earth", accentRing = 2),
        RingPalette("walnut_fern_apricot", "Walnut · Fern · Apricot", "Walnut", "Deepest brown, bright fern, pastel apricot.",
            p(0xA35E34, 0xDE9A62), p(0x2F9A55, 0x86DB6E), p(0xFF8F4D, 0xFFC18F), group = "Earth", accentRing = 1),
        // ---- Vivid
        RingPalette("sunset_bloom", "Sunset Bloom", "Sunset", "Warm to cool.",
            p(0xFF5E3A, 0xFFB03A), p(0xE0338E, 0xFF7AC6), p(0x6C4DFF, 0xAE9BFF), group = "Vivid", accentRing = 2),
        RingPalette("aurora", "Aurora", "Aurora", "Gold, orchid, polar teal.",
            p(0xFF8A00, 0xFFD23F), p(0x9B4DFF, 0xE07BFF), p(0x00A88F, 0x6FEBC8), group = "Vivid", accentRing = 1),
        RingPalette("coral_lagoon", "Coral Lagoon", "Lagoon", "Soft coral, deep teal, cobalt.",
            p(0xFF5C6C, 0xFFA98F), p(0x00A3A3, 0x4FE3CF), p(0x2F52FF, 0x86A3FF), group = "Vivid", accentRing = 1),
        RingPalette("prism", "Prism", "Prism", "Each ring crosses hues.",
            p(0xFF6A3D, 0xFF3D9A), p(0xA040FF, 0x4D7CFF), p(0x00B39A, 0xB5E61D), group = "Vivid", accentRing = 1),
        RingPalette("citrus", "Citrus", "Citrus", "Orange, lime, lemon. Brightest on dark.",
            p(0xFF6F00, 0xFFB347), p(0x5FA800, 0xC2EE4A), p(0xE0B400, 0xFFEE6A), group = "Vivid", accentRing = 0),
        // ---- Cool
        RingPalette("glacier", "Glacier", "Glacier", "Icy blue, glacier teal, silver lilac.",
            p(0x2F7FD8, 0x8CC8FF), p(0x0E9C96, 0x74E3D6), p(0x7E76CC, 0xCFC8FF), group = "Cool", accentRing = 0),
        RingPalette("ocean", "Ocean", "Ocean", "Deep-sea blue, reef teal, warm sand.",
            p(0x2F62D6, 0x86A6FF), p(0x00968A, 0x5BE0C8), p(0xD9A33C, 0xF7D98B), group = "Cool", accentRing = 0),
        // ---- Nature
        RingPalette("forest", "Forest", "Forest", "Bark brown, pine needle, sunlit lichen.",
            p(0x9C6640, 0xD6A57A), p(0x26855A, 0x62C58E), p(0xA7B81E, 0xE2EC6A), group = "Nature", accentRing = 1),
        RingPalette("desert", "Desert", "Desert", "Burnt sienna, dune sand, dusty sage.",
            p(0xB4532A, 0xE89366), p(0x6E8B74, 0xAFC9B4), p(0xC8A165, 0xEBD3A3), group = "Nature", accentRing = 0),
        // ---- Soft
        RingPalette("sorbet", "Sorbet", "Sorbet", "Pastel peach, mint and lavender.",
            p(0xF08A6C, 0xFFC7B0), p(0x3DBF9A, 0xA8F0D6), p(0x9A86E8, 0xD6CCFF), group = "Soft", accentRing = 2),
        RingPalette("candy", "Candy", "Candy", "Bubblegum, aqua pop, lemon drop.",
            p(0xFF4FA3, 0xFF9ED0), p(0x00BFD8, 0x7FEAF5), p(0xE8B000, 0xFFE66A), group = "Soft", accentRing = 0),
        // ---- Bold
        RingPalette("berry", "Berry", "Berry", "Raspberry, plum, rose blush.",
            p(0xD81B60, 0xFF5C8D), p(0x8A3CB8, 0xC48BEE), p(0xE5735F, 0xFFC0AE), group = "Bold", accentRing = 0),
        RingPalette("volcano", "Volcano", "Volcano", "Lava, basalt plum, sulphur.",
            p(0xE2361B, 0xFF8A3D), p(0x9A4A8A, 0xD68AC4), p(0xE0AE00, 0xFFE36A), group = "Bold", accentRing = 0),
        // ---- Dark
        RingPalette("neon_night", "Neon Night", "Neon", "Loudest. Made for dark mode.",
            p(0xFF2BD6, 0xFF9AF0), p(0xFFB000, 0xFFE45C), p(0x2E6BFF, 0x73B4FF), darkOnly = true, group = "Dark", accentRing = 0),
        RingPalette("synthwave", "Synthwave", "Synth", "Laser red, electric cyan, ultraviolet. Made for dark mode.",
            p(0xFF2E63, 0xFF7A9A), p(0x00C2EE, 0x8AF0FF), p(0x8B5CFF, 0xC7B2FF), darkOnly = true, group = "Dark", accentRing = 2),
        // ---- Calm
        RingPalette("mono_ink", "Mono Ink", "Mono", "Graphite neutrals with one warm ember.",
            p(0xD9542B, 0xFF9C6E), p(0x6E7A75, 0xB5C2BB), p(0xA08458, 0xE3CFA2), group = "Calm", accentRing = 0),
        RingPalette("graphite", "Graphite", "Graphite", "Slate and stone with one electric-blue ring.",
            p(0x2F6BFF, 0x7FA6FF), p(0x737A85, 0xB4BBC5), p(0x8C8C84, 0xC9C8BE), group = "Calm", accentRing = 0),
    )

    private val byId = all.associateBy { it.id }

    val default: RingPalette get() = byId[DEFAULT_ID] ?: all.first()

    /** Picker groups in order. */
    val groups: List<String> get() = all.map { it.group }.distinct()

    /** Unknown / legacy ids fall back to the default (Copper · Pine · Marigold). "custom:…" ids are decoded ([custom]). */
    fun byId(id: String?): RingPalette = id?.let { byId[it] ?: Custom.decode(it) } ?: default

    /**
     * v4 Q21: the 8 curated presets shown in Settings (diverse hues, all checked for light-mode contrast via forTheme).
     * Every other palette id stays valid (a saved one keeps working and is shown as "Current").
     */
    val curated: List<RingPalette> get() = listOf(
        "copper_pine_marigold", "mono_ink", "sunset_bloom", "aurora", "coral_lagoon", "glacier", "forest", "berry", "graphite",
    ).mapNotNull { byId[it] }

    /**
     * Custom palette (Q21): the user picks the start colour of the 3 rings and the accent; each ring's end colour is
     * the start lifted in OKLCH lightness (same hue). Stored in UiPrefs.paletteId as
     * "custom:RRGGBB,RRGGBB,RRGGBB,RRGGBB[,RRGGBB]" (ring 0 Workout minutes, ring 1 Steps, ring 2 Heart points, accent,
     * V2-137 optional 5th = ring track), so widgets (RenderTheme → Palettes.byId) get it with no new preference key.
     * Stored 4-colour customs keep decoding unchanged (no track → the neutral track).
     */
    object Custom {
        const val PREFIX = "custom:"
        /** Index of the optional track colour in [colors]. */
        const val TRACK = 4
        fun encode(r0: Color, r1: Color, r2: Color, accent: Color, track: Color? = null): String =
            PREFIX + listOfNotNull(r0, r1, r2, accent, track).joinToString(",") { String.format("%06X", it.toArgb() and 0xFFFFFF) }
        /** The stored colours: 4 (ring 0–2, accent) or 5 (+ track). */
        fun colors(id: String): List<Color>? {
            if (!id.startsWith(PREFIX)) return null
            val parts = id.removePrefix(PREFIX).split(',')
            if (parts.size != 4 && parts.size != 5) return null
            return parts.map { h -> h.toLongOrNull(16)?.let { c(it) } ?: return null }
        }
        /** The stored track colour, or null (4-colour custom or not a custom id). */
        fun track(id: String): Color? = colors(id)?.getOrNull(TRACK)
        fun decode(id: String): RingPalette? {
            val cs = colors(id) ?: return null
            fun g(x: Color) = x to RingLight.lighter(x)
            return RingPalette(id, "Custom", "Custom", "Your own ring and accent colours.", g(cs[0]), g(cs[1]), g(cs[2]),
                group = "Custom", accentSeed = cs[3], trackSeed = cs.getOrNull(TRACK))
        }
    }
}

// ------------------------------------------------------------------ contrast helpers (DESIGN §2, lib.js legible())

private fun channel(v: Float): Double { val x = v.toDouble(); return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }

/** WCAG relative luminance (alpha ignored). */
fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

fun contrast(a: Color, b: Color): Double {
    val la = luminance(a); val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

/**
 * Walk [c] toward [text] in 2 % steps until it reaches [min] contrast on [bg] (DESIGN §2).
 * Use 4.5 for text, 3.0 for glyphs ≥ 20 dp. In dark theme category colours pass unchanged anyway.
 */
fun legible(c: Color, bg: Color, text: Color, min: Double = 4.5): Color {
    var t = 0f
    while (t <= 1.0001f) {
        val x = lerpColor(c, text, t)
        if (contrast(x, bg) >= min) return x
        t += 0.02f
    }
    return text
}

fun legibleArgb(c: Int, bg: Int, text: Int, min: Double = 4.5): Int = legible(Color(c), Color(bg), Color(text), min).toArgb()


// ------------------------------------------------------------------ v4.1 review: zone + energy colours per theme
// Light mode: the legible-glyph rule (walk toward the text colour until ≥ 3 : 1 on the card), e.g. fat burn 1.56 → 3.0,
// cardio 2.47 → 3.0. Dark mode: unchanged. Widgets / renderers use the ARGB forms with their own card + text colours.

/** Zone colours [below, fat burn, cardio, peak] (HrZones.BELOW..PEAK) for this theme. */
fun zoneColorsForTheme(dark: Boolean, card: Color, text: Color): List<Color> =
    Cat.azm.map { if (dark) it else legible(it, card, text, 3.0) }

/** Energy split colours (active, resting) for this theme. */
fun energyColorsForTheme(dark: Boolean, card: Color, text: Color): Pair<Color, Color> =
    if (dark) Cat.kcalActive to Cat.resting else legible(Cat.kcalActive, card, text, 3.0) to legible(Cat.resting, card, text, 3.0)

/** ARGB of [zoneColorsForTheme]: index = HrZones.BELOW..PEAK. */
fun zoneArgbForTheme(dark: Boolean, cardArgb: Int, textArgb: Int): IntArray =
    zoneColorsForTheme(dark, Color(cardArgb), Color(textArgb)).map { it.toArgb() }.toIntArray()

/** ARGB of [energyColorsForTheme]: [0] active, [1] resting. */
fun energyArgbForTheme(dark: Boolean, cardArgb: Int, textArgb: Int): IntArray =
    energyColorsForTheme(dark, Color(cardArgb), Color(textArgb)).let { intArrayOf(it.first.toArgb(), it.second.toArgb()) }
