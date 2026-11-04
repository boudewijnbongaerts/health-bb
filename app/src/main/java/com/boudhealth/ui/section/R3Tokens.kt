package com.boudhealth.ui.section

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * R3 layout tokens (IMPL_PLAN §2.1, kit/r3ui.py). UI side; the data facade with the same simple name is
 * com.boudhealth.data.R3 (import one of them with an alias when a file needs both).
 */
object R3 {
    val CONTENT_MAX = 1040.dp; val GAP = 16.dp; val GAP_COVER = 12.dp
    val HERO_H = 424.dp; val HERO_H_ACTIVITY_ALL = 344.dp; val HERO_PAD = 22.dp; val HERO_PAD_COVER = 16.dp
    val HERO_RADIUS = 26.dp; val HERO_LEFT = 340.dp /* Body, Vitals */; val HERO_LEFT_ACTIVITY = 372.dp
    val CARD_H = 288.dp; val CARD_H_COVER_S = 256.dp; val CARD_PAD = 20.dp; val CARD_RADIUS = 22.dp
    val TILE_H = 92.dp; val TILE_RADIUS = 16.dp
    val COL_S = 336.dp; val COL_M = 688.dp      // at 1,040 content; computed, not hard-coded, in CardHost

    /** 3 card columns from this content width, 2 from [COLS_2]. */
    val COLS_3 = 900.dp; val COLS_2 = 600.dp
    /** Header → period bar, bar → hero (inner / cover). */
    val HEADER_TO_BAR = 6.dp; val BAR_TO_HERO = 14.dp; val BAR_TO_HERO_COVER = 12.dp
    /** Trends strip height (inner 6 × 1 / cover 2 × 3). */
    val TRENDS_H = 148.dp; val TRENDS_H_COVER = 348.dp
    /** M-card rail width and the left part's inset (left = W − 236). */
    val RAIL_W = 208.dp; val RAIL_INSET = 236.dp
}

/** R3 shared colours not in Cat (kit theme K). Micro visuals: Color.Unspecified = the theme's text colour. */
object R3Colors {
    /** Fat mass (Body hero legend, composition bars, Trends spark): kit K["fat_mass"]. */
    val fatMass = Color(0xFFD78731)
}

/** Card span in the R3 grid: S = 1 column, M = 2 columns (inner); cover: always one column. */
enum class CardSize { S, M }
