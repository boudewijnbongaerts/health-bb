package com.boudhealth.ui.section

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boudhealth.data.PeriodUnit
import com.boudhealth.data.SectionPeriod
import com.boudhealth.data.SectionScope
import com.boudhealth.ui.components.DetailScaffold
import com.boudhealth.ui.components.LocalScreen
import com.boudhealth.ui.nav.Nav
import java.time.LocalDate

/**
 * Scope of a detail page: the period handed off by the card that was tapped (B16, same period), else the current
 * [defaultUnit] period. A hand-off day inside a week / month becomes a selected day of that period.
 */
@Composable
fun rememberDetailScope(key: String, defaultUnit: PeriodUnit = PeriodUnit.WEEK, initial: SectionPeriod? = null): MutableState<SectionScope> = remember(key) {
    val today = LocalDate.now()
    val p = initial ?: SectionScopes.takeHandoff(key)
    mutableStateOf(
        when (p) {
            null -> SectionScope(SectionPeriod.current(defaultUnit, today, today.minusYears(10)), null, true)
            else -> SectionScope(p, null, p.isCurrent(today))
        },
    )
}

/**
 * Detail page frame shared by section detail pages: back header, sticky [PeriodBar] (same units and rules as the
 * section), Back clears a selected day first.
 */
@Composable
fun SectionDetailScaffold(
    title: String,
    nav: Nav,
    scope: SectionScope,
    dataStart: LocalDate,
    onScope: (SectionScope) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val today = LocalDate.now()
    val wide = LocalScreen.current.wide
    BackHandler(enabled = scope.selectedDay != null) { onScope(scope.clearSelection(today)) }
    DetailScaffold(
        title = title,
        onBack = { nav.back() },
        sticky = { PeriodBar(scope, dataStart, today, onScope, wide) },
    ) {
        Spacer(Modifier.height(16.dp))
        content()
    }
}
