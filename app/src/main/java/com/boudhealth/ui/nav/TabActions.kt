package com.boudhealth.ui.nav

import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.HeaderAction
import com.boudhealth.ui.section.SectionHooks

/**
 * IC5 / V2-026: the top-bar actions of every tab root: All metrics (List glyph) + Settings. Home puts Arrange in front
 * of these in its own header (design6/nav/SHEET_top_bar.png).
 */
fun TabActions(nav: Nav): List<HeaderAction> = listOf(
    HeaderAction(Glyph.List, "All metrics") { nav.push(Route.AllMetrics) },
    HeaderAction(Glyph.Settings, "Settings") { nav.push(Route.Settings) },
)

/** Hooks the section framework (T2, IC4) calls into navigation. Installed once by AppShell. */
internal object NavHooks {
    @Volatile private var installed = false
    fun install() {
        if (installed) return
        installed = true
        SectionHooks.actions = { nav -> TabActions(nav) }
        SectionHooks.openMetric = { nav, id, p -> nav.openMetric(id, p) }
    }
}
