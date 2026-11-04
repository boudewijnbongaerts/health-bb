package com.boudhealth.render.ring

import android.content.Context
import android.graphics.Typeface

/**
 * The bundled app font (V2-028, cause 3): Inter (SIL OFL 1.1) in res/font, as tnum-frozen copies, so every digit has
 * the same width everywhere (rings, tiles, widgets, Compose text). No synthetic bold: each weight is its own file.
 *
 *     RingFonts.init(context)             // once per process (RenderTheme.of and AppTheme call it)
 *     paint.typeface = RingFonts.face(700)
 *
 * Without init (JVM harness, tests) [face] falls back to Typeface.create(DEFAULT, weight).
 */
object RingFonts {
    /** res/font file names by weight. */
    private val FILES = linkedMapOf(400 to "inter_regular", 500 to "inter_medium", 600 to "inter_semibold", 700 to "inter_bold")
    /** Feature string for every Paint that draws numbers (the files are already tnum-frozen; kept for system fallbacks). */
    const val FEATURES = "tnum"

    @Volatile private var loaded: Map<Int, Typeface> = emptyMap()
    private val fallback = HashMap<Int, Typeface>()

    /** Loads the bundled faces once (cheap after the first call). Safe on any thread. */
    fun init(context: Context) {
        if (loaded.isNotEmpty()) return
        synchronized(this) {
            if (loaded.isNotEmpty()) return
            val res = context.applicationContext?.resources ?: context.resources
            val pkg = context.packageName
            val m = HashMap<Int, Typeface>()
            for ((w, name) in FILES) {
                val id = res.getIdentifier(name, "font", pkg)
                if (id != 0) runCatching { res.getFont(id) }.getOrNull()?.let { m[w] = it }
            }
            loaded = m
        }
    }

    /** Resource id of the face closest to [weight] (0 if the resources are missing), for Compose Font(resId). */
    fun resId(context: Context, weight: Int): Int =
        context.resources.getIdentifier(FILES[nearest(weight)] ?: "inter_regular", "font", context.packageName)

    /** The typeface for [context] and [weight] (initialises on first use). */
    fun typeface(context: Context, weight: Int = 600): Typeface { init(context); return face(weight) }

    /** The bundled face closest to [weight]; system fallback before [init]. */
    fun face(weight: Int): Typeface {
        val l = loaded
        if (l.isNotEmpty()) l[nearest(weight)]?.let { return it }
        return synchronized(fallback) { fallback.getOrPut(weight) { Typeface.create(Typeface.DEFAULT, weight, false) } }
    }

    val isBundled: Boolean get() = loaded.isNotEmpty()

    private fun nearest(weight: Int): Int = when {
        weight >= 650 -> 700
        weight >= 550 -> 600
        weight >= 450 -> 500
        else -> 400
    }
}
