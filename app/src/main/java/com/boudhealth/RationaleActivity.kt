package com.boudhealth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boudhealth.data.Prefs
import com.boudhealth.data.prefsNow
import com.boudhealth.ui.components.ButtonKind
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.ScreenHeader
import com.boudhealth.ui.theme.AppTheme
import com.boudhealth.ui.theme.LocalHB
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Privacy page, also opened from Health Connect's permission screens. */
class RationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = runBlocking { withTimeoutOrNull(300) { (application as App).store.prefsNow() } } ?: Prefs()
        setContent {
            AppTheme(prefs) {
                val hb = LocalHB.current
                Column(Modifier.fillMaxSize().background(hb.colors.surface).padding(horizontal = 20.dp)) {
                    ScreenHeader("Privacy", onBack = { finish() })
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Health BB reads your health data from Health Connect only to draw your dashboard and home-screen widget. " +
                            "It never writes, changes or deletes health data.",
                        style = hb.type.body, color = hb.colors.text,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Everything stays on this phone. No internet permission, no analytics, no third parties.",
                        style = hb.type.body, color = hb.colors.muted,
                    )
                    Spacer(Modifier.height(24.dp))
                    HBButton("Back", { finish() }, kind = ButtonKind.Tonal, small = true)
                }
            }
        }
    }
}
