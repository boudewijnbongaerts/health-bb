package com.boudhealth.ui.health

import androidx.compose.runtime.Composable
import com.boudhealth.ui.activity.SleepDetailScreen
import com.boudhealth.ui.nav.Nav

/**
 * V2: the Sleep page lives in Activity (D2, V7-S4): T2's [SleepDetailScreen]. The v6 page that was here is removed;
 * this entry only forwards `Route.Sleep(wakeEpochDay)` until T5 routes it to SleepDetailScreen directly.
 */
@Composable
fun SleepScreen(wakeEpochDay: Long, nav: Nav) = SleepDetailScreen(wakeEpochDay, nav)
