package com.boudhealth.ui.nav

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boudhealth.ui.components.DetailScaffold
import com.boudhealth.ui.components.EmptyState
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.HeaderAction
import com.boudhealth.ui.theme.LocalHB

/**
 * Placeholder used by the screen stubs until each area engineer replaces them. Not for production screens.
 */
@Composable
fun StubScreen(title: String, nav: Nav, note: String, showBack: Boolean = true, actions: List<HeaderAction> = emptyList()) {
    val hb = LocalHB.current
    DetailScaffold(title, onBack = if (showBack) ({ nav.back() }) else null, actions = actions) {
        Spacer(Modifier.height(24.dp))
        ConnectCard()
        Spacer(Modifier.height(16.dp))
        EmptyState(Glyph.Grid, hb.colors.accent, title = "Coming soon", body = note)
    }
}
