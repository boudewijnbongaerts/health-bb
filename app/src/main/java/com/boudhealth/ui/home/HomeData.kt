package com.boudhealth.ui.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.boudhealth.data.Goals
import com.boudhealth.data.RingDay
import com.boudhealth.data.Sync
import com.boudhealth.data.V4
import com.boudhealth.render.TileSource
import com.boudhealth.ui.nav.LocalPrefs
import java.time.LocalDate

/*
 * What the Home tab, the Arrange grid and the catalog previews show, loaded in one IO hop from the backend cache (no
 * Health Connect calls). Re-loaded when Sync.version or the goals change.
 *
 * V2 (D11 GO, V2-113): the tiles are drawn by the widget's tile art (render/Tiles → render/ring/TileArt) from the SAME
 * [TileSource] the widgets use, so Home = widget pixel for pixel. The Today pill reads today's rings BY ID (RingData).
 */

/** Today's rings for the Today pill (V2-111: read by ring id through RingData, never by list index). */
internal class RingsData(
    val day: RingDay,
    /** Last foreground / background sync (epoch ms), "Updated 11:43". */
    val updated: Long?,
    val today: LocalDate,
)

internal class HomeData(
    val rings: RingsData,
    /** The widgets' tile data source, created on IO; null only in the empty placeholder (tiles show skeletons). */
    val src: TileSource?,
    val loadedAt: Long,
)

/** Loads (and caches process-wide, so screens don't flash skeletons on re-entry) the Home data. */
@Composable
internal fun rememberHomeData(): HomeData? {
    val ctx = LocalContext.current
    val version by Sync.version.collectAsState()
    val goals = LocalPrefs.current.goals
    val state = produceState(HomeLoader.last, version, goals) {
        value = runCatching { HomeLoader.load(ctx) }.getOrElse { HomeLoader.last ?: HomeLoader.empty(goals) }
    }
    return state.value
}

internal object HomeLoader {
    @Volatile var last: HomeData? = null
        private set

    fun empty(goals: Goals): HomeData {
        val today = LocalDate.now()
        return HomeData(RingsData(RingDay(today, null, goals.steps, 0, goals.workoutMinDay, null, 0, goals.zoneMinWeek), null, today), null, System.currentTimeMillis())
    }

    suspend fun load(context: Context): HomeData {
        val app = context.applicationContext
        val data = V4.load(app) { env ->
            val updated = runCatching { Sync.lastSync(app) }.getOrNull()
            val src = TileSource(env, updated)
            // the pill and the Today tile read the same ring day (one data path, D3)
            HomeData(RingsData(src.ringToday, updated, env.today), src, System.currentTimeMillis())
        }
        last = data
        return data
    }
}
