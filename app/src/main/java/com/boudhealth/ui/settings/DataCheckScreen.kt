@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.boudhealth.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.produceState
import com.boudhealth.data.importer.AppleImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.data.Diagnostics
import com.boudhealth.data.DiagnosticsReader
import com.boudhealth.data.ErrKind
import com.boudhealth.data.ErrorLog
import com.boudhealth.data.HcReader
import com.boudhealth.data.Sync
import com.boudhealth.data.SyncLog
import com.boudhealth.data.TypeCoverage
import com.boudhealth.data.TypeStats
import com.boudhealth.data.V2
import com.boudhealth.data.M
import com.boudhealth.ui.components.ButtonKind
import com.boudhealth.ui.components.DetailScaffold
import com.boudhealth.ui.components.Dot
import com.boudhealth.ui.components.Fmt
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphCircle
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBBottomSheet
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.HeaderAction
import com.boudhealth.ui.components.LocalSnackbar
import com.boudhealth.ui.components.MetricSkeleton
import com.boudhealth.ui.components.SectionHeader
import com.boudhealth.ui.components.SheetActions
import com.boudhealth.ui.components.SourceChip
import com.boudhealth.ui.components.hbClickable
import com.boudhealth.ui.health.metricColor
import com.boudhealth.ui.nav.LocalAppStatus
import com.boudhealth.ui.nav.Nav
import com.boudhealth.ui.theme.HbShapes
import com.boudhealth.ui.theme.LocalHB
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Data check (V2-133, V2-136): no subtitle, no "What we noticed". Sync status · Permissions · one row per data type
 * (readable name, source apps, last value and its time, days with data in the last 30). Problems show inline on the
 * row. Rebuild history and the Apple Health import live in Settings → Data & sync only (V2-143).
 * Opens instantly from the cache, then asks Health Connect live (≈ 2 calls per type) once per open / sync.
 */
@Composable
fun DataCheckScreen(nav: Nav) {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val status = LocalAppStatus.current
    val snackbar = LocalSnackbar.current
    val syncState by Sync.state.collectAsState()
    val version by Sync.version.collectAsState()
    var raw by remember { mutableStateOf<Diagnostics?>(null) }
    var coverage by remember { mutableStateOf<Map<String, Set<LocalDate>>>(emptyMap()) }
    var extras by remember { mutableStateOf(RowExtras()) }

    // cache-only refresh whenever cached data changes (cheap)
    LaunchedEffect(version) {
        coverage = loadCoverage(ctx)
        extras = loadExtras(ctx)
        if (raw == null) raw = DiagnosticsReader.collect(ctx, live = false)
    }
    // live check on open and after each finished sync
    LaunchedEffect(syncState.lastSuccess, status.refreshing) {
        if (!status.refreshing) raw = DiagnosticsReader.collect(ctx, live = true)
    }
    val errors by ErrorLog.errors.collectAsState()

    // workouts: an app that writes one session several times counts it once (also in the copied report)
    val d = raw?.let { withWorkoutCounts(it, extras.workoutsPerApp) }
    val versionName = remember { appVersion(ctx) }
    val copy = {
        d?.let { copyToClipboard(ctx, "Health BB data check", buildReport(it, versionName)); snackbar.toast("Report copied") }
        Unit
    }

    DetailScaffold(
        "Data check", onBack = { nav.back() },
        actions = listOf(HeaderAction(Glyph.Share, "Copy report", enabled = d != null) { copy() }),
    ) {
        Spacer(Modifier.height(20.dp))
        if (d == null) { MetricSkeleton(); return@DetailScaffold }
        SyncHero(d)

        // ---- permissions
        Overline("Permissions", action = "Open Health Connect", onAction = { HcIntents.openHealthConnect(ctx) })
        Permissions(d)

        // ---- data types: one row per type
        Overline("Data types", trailing = "Days with data · last 30")
        val rows = typeRows(d, coverage, extras)
        Column(Modifier.fillMaxWidth().clip(HbShapes.group).background(hb.colors.card)) {
            rows.forEachIndexed { i, r ->
                TypeRow(r)
                if (i < rows.lastIndex) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(hb.colors.surface))
            }
        }

        // ---- recent errors (only when there are any)
        if (errors.isNotEmpty()) {
            Overline("Recent errors", action = "Clear", onAction = { ErrorLog.clear() })
            Column(Modifier.fillMaxWidth().background(hb.colors.card, HbShapes.group).padding(horizontal = 16.dp)) {
                val shown = errors.take(5)
                shown.forEachIndexed { i, e ->
                    val kind = runCatching { ErrKind.valueOf(e.kind) }.getOrDefault(ErrKind.OTHER)
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }, style = hb.type.label, color = hb.colors.bad, modifier = Modifier.weight(1f))
                            Text(Fmt.timestamp(e.time), style = hb.type.bodyXS, color = hb.colors.muted)
                        }
                        Text(kind.hint, style = hb.type.bodyS, color = hb.colors.text)
                    }
                    if (i < shown.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.surface))
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            HBButton("Copy report", { copy() }, kind = ButtonKind.Text, glyph = Glyph.Share)
        }
        // V2-155 (T8): debug builds only (renders nothing in release)
        com.boudhealth.ui.debug.DebugTourRow(nav)
    }
}

/** QA 23.5: section title as an overline (11/600 caps, letter-spaced), optional action or trailing note. */
@Composable
private fun Overline(title: String, action: String? = null, onAction: (() -> Unit)? = null, trailing: String? = null) {
    val hb = LocalHB.current
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 28.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = hb.type.overline, color = hb.colors.muted, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = hb.type.bodyXS, color = hb.colors.muted)
        if (action != null && onAction != null) Text(action, style = hb.type.buttonS, color = hb.colors.accent,
            modifier = Modifier.hbClickable(shape = RoundedCornerShape(8.dp), scaleOnPress = false, onClick = onAction).padding(horizontal = 4.dp, vertical = 6.dp))
    }
}

/** Per-row data that comes from the cache rather than from Health Connect. */
private data class RowExtras(
    val last: Map<String, LastValue> = emptyMap(),
    val workoutsPerApp: Map<String, Int> = emptyMap(),
    val waistSources: List<String> = emptyList(),
    val waistNewest: Long? = null,
)

private suspend fun loadExtras(context: android.content.Context): RowExtras = runCatching {
    V2.load(context) { env ->
        val today = env.today
        val types = TYPE_ORDER
        val last = types.mapNotNull { t -> runCatching { lastValue(env, t) }.getOrNull()?.let { t to it } }.toMap()
        val waist = env.cache.daily(M.WAIST, today.minusYears(10), today)
        RowExtras(
            last = last,
            workoutsPerApp = workoutCountsPerApp(env.cache, today.minusDays(29), today),
            waistSources = waist.asReversed().mapNotNull { it.sourceLabel }.flatMap { it.split(", ") }.distinct(),
            waistNewest = waist.lastOrNull()?.time,
        )
    }
}.getOrNull() ?: RowExtras()

private fun withWorkoutCounts(d: Diagnostics, perApp: Map<String, Int>): Diagnostics {
    if (perApp.isEmpty()) return d
    return d.copy(types = d.types.map { t ->
        if (t.type != "ExerciseSession") t
        else {
            val src = t.sources.map { s -> perApp[s.pkg]?.let { s.copy(count30d = it) } ?: s }
            t.copy(sources = src, count30d = src.mapNotNull { it.count30d }.takeIf { it.isNotEmpty() }?.sum() ?: t.count30d)
        }
    })
}

/** One Data check row: every type once (calories: total + active merged), plus waist (kept on this phone). */
private data class TypeRowModel(
    val key: String,
    val info: TypeInfo,
    val permission: Boolean,
    val sources: List<String>,
    val problem: String?,
    val last: LastValue?,
    val days: Set<LocalDate>?,
)

private fun typeRows(d: Diagnostics, coverage: Map<String, Set<LocalDate>>, x: RowExtras): List<TypeRowModel> {
    val ordered = d.types.sortedBy { TYPE_ORDER.indexOf(it.type).let { i -> if (i < 0) 99 else i } }
    val out = ArrayList<TypeRowModel>()
    ordered.groupBy { typeInfo(it.type).name }.forEach { (_, ts) ->
        val first = ts.first()
        val perm = ts.all { it.permissionGranted }
        val sources = ts.flatMap { t -> t.sources.sortedByDescending { it.count30d ?: 0 }.mapNotNull { shortSource(it.label) } }.distinct()
        val stale = ts.firstNotNullOfOrNull { staleSentence(it) }
        val days = ts.mapNotNull { coverage[it.type] }.takeIf { it.isNotEmpty() }?.reduce { a, b -> a + b }
        val problem = when {
            !perm -> "Not allowed in Health Connect"
            stale != null -> stale
            sources.isEmpty() && (days == null || days.isEmpty()) -> "No data in Health Connect"
            else -> null
        }
        out += TypeRowModel(first.type, typeInfo(first.type), perm, sources, problem, ts.firstNotNullOfOrNull { x.last[it.type] }, days)
        if (first.type == "BodyFat") out += waistRow(coverage, x)
    }
    if (out.none { it.key == "Waist" }) out += waistRow(coverage, x)
    return out
}

private fun waistRow(coverage: Map<String, Set<LocalDate>>, x: RowExtras) = TypeRowModel(
    "Waist", typeInfo("Waist"), true, x.waistSources.mapNotNull { shortSource(it) },
    if (x.waistNewest == null) "No measurements yet" else null, x.last["Waist"], coverage["Waist"],
)

/** "today 08:40" · "yesterday" · "3 days ago" · "12 Mar" (row time of the last value). */
private fun whenText(ms: Long): String {
    val d = Fmt.date(ms)
    val days = ChronoUnit.DAYS.between(d, LocalDate.now())
    return when {
        days <= 0L -> "today ${Fmt.hm(ms)}"
        days == 1L -> "yesterday"
        days < 30L -> "$days days ago"
        else -> Fmt.shortDate(d) + if (d.year != LocalDate.now().year) " ${d.year}" else ""
    }
}

// =====================================================================================================================
// Sync hero
// =====================================================================================================================

@Composable
private fun SyncHero(d: Diagnostics) {
    val hb = LocalHB.current
    val status = LocalAppStatus.current
    val syncState by Sync.state.collectAsState()
    val running = syncState.running || status.refreshing
    val failedStep = d.steps.filter { d.lastSync == null || it.at >= d.lastSync - 10 * 60_000L }.firstOrNull { !it.ok }
    val failed = !status.lastSyncOk || !d.healthConnectAvailable || failedStep != null
    Column(Modifier.fillMaxWidth().background(hb.colors.card, RoundedCornerShape(24.dp)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(if (failed) hb.colors.bad else hb.colors.good)
            Spacer(Modifier.width(8.dp))
            Text(if (failed) "Last sync failed" else "Last sync worked", style = hb.type.label, color = if (failed) hb.colors.bad else hb.colors.good)
        }
        val last = d.lastSync
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(last?.let { Fmt.hm(it) } ?: "–", style = hb.type.display.copy(fontSize = 44.sp, lineHeight = 50.sp), color = hb.colors.text)
                Spacer(Modifier.width(8.dp))
                if (last != null) Text(
                    Fmt.dayLabel(Fmt.date(last)).let { if (it == "Today" || it == "Yesterday") it.lowercase() else it },
                    style = hb.type.displayUnit, color = hb.colors.muted, modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            HBButton("Sync now", { status.refresh() }, small = true, glyph = Glyph.Sync, enabled = !running)
        }
        // only a problem or a running sync gets a line (no hint texts)
        val line = when {
            !d.healthConnectAvailable -> "Health Connect isn't available on this phone"
            failedStep != null -> plainReason(failedStep.detail, failedStep.step)
            else -> null
        }
        if (line != null) Text(line, style = hb.type.bodyS, color = hb.colors.bad, modifier = Modifier.padding(top = 6.dp))
        val bf = syncState.backfill
        if (running && bf != null && !bf.done) {
            Spacer(Modifier.height(12.dp))
            Text("Reading ${Fmt.shortDate(bf.cursor)} ${bf.cursor.year}… ${Fmt.num(bf.fraction * 100.0)} %", style = hb.type.bodyXS, color = hb.colors.text)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).background(hb.colors.tileHi, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(bf.fraction.coerceIn(0.02f, 1f)).height(6.dp).background(hb.colors.accent, RoundedCornerShape(3.dp)))
            }
        } else if (running) {
            Text(syncState.phase.ifBlank { "Syncing…" }, style = hb.type.bodyXS, color = hb.colors.text, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private fun plainReason(detail: String, step: String): String {
    val t = detail.lowercase()
    return when {
        "quota" in t || "rate" in t -> "Health Connect's read limit was hit. It continues on its own in about 20 minutes."
        "permission" in t || "security" in t -> "A permission is missing. Fix it below."
        "foreground" in t || "background" in t -> "Health Connect refused a background read."
        else -> "Health Connect didn't answer."
    }
}

// =====================================================================================================================
// Permissions
// =====================================================================================================================

@Composable
private fun Permissions(d: Diagnostics) {
    val hb = LocalHB.current
    val ctx = LocalContext.current
    val status = LocalAppStatus.current
    val core = d.permissions.filter { it.permission in HcReader.CORE_PERMISSIONS }
    val coreOn = core.count { it.granted }
    val history = d.permissions.firstOrNull { it.permission == HcReader.HISTORY }
    val bg = d.permissions.firstOrNull { it.permission == HcReader.BACKGROUND }
    Column(Modifier.fillMaxWidth().clip(HbShapes.group).background(hb.colors.card)) {
        if (coreOn == core.size) PermRow("Data types", "$coreOn of ${core.size}")
        else ProblemRow("${core.size - coreOn} data types are off", "Those charts stay empty until you allow them.") { status.requestPermissions() }
        Cut()
        when {
            !d.historyFeature -> PermRow("Access past data", null)
            history?.granted == true -> PermRow("Access past data", "On")
            else -> ProblemRow("Access past data is off", "Only the last 30 days can be read.") { HcIntents.openAppPermissions(ctx) }
        }
        Cut()
        when {
            !d.backgroundFeature -> PermRow("Background access", null)
            bg?.granted == true -> PermRow("Background access", "On")
            else -> ProblemRow("Background access is off", "Data only updates while the app is open.") { HcIntents.openAppPermissions(ctx) }
        }
    }
}

@Composable
private fun Cut() {
    val hb = LocalHB.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(hb.colors.surface))
}

@Composable
private fun PermRow(title: String, okText: String?) {
    val hb = LocalHB.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = hb.type.bodyMedium, color = hb.colors.text, modifier = Modifier.weight(1f))
        if (okText != null) {
            GlyphIcon(Glyph.Check, size = 16.dp, color = hb.colors.good)
            Spacer(Modifier.width(4.dp))
            Text(okText, style = hb.type.label, color = hb.colors.good)
        } else Text("–", style = hb.type.label, color = hb.colors.muted)
    }
}

/** Highlighted off-permission row: badCont, alert circle on surface, problem + consequence, quiet "Fix" button. */
@Composable
private fun ProblemRow(title: String, sub: String, onFix: () -> Unit) {
    val hb = LocalHB.current
    Row(
        Modifier.fillMaxWidth().background(hb.colors.badCont).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphCircle(Glyph.Alert, hb.colors.bad, size = 36.dp, glyphSize = 18.dp, background = hb.colors.surface)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = hb.type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = hb.colors.text)
            Text(sub, style = hb.type.bodyXS.copy(fontSize = 12.5.sp), color = hb.colors.text.copy(alpha = 0.75f))
        }
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.height(32.dp).hbClickable(shape = RoundedCornerShape(16.dp), onClick = onFix)
                .background(hb.colors.surface, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphIcon(Glyph.Open, size = 14.dp, color = hb.colors.bad, stroke = 2.6f)
            Spacer(Modifier.width(6.dp))
            Text("Fix", style = hb.type.buttonS, color = hb.colors.bad)
        }
    }
}

// =====================================================================================================================
// Data types: one row per type
// =====================================================================================================================

@Composable
private fun TypeRow(r: TypeRowModel) {
    val hb = LocalHB.current
    val info = r.info
    val color = if (info.colorKey.isEmpty()) hb.colors.muted else metricColor(info.colorKey)
    val today = LocalDate.now()
    val bad = r.problem != null && r.problem != "No measurements yet"
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphCircle(info.glyph, color, size = 36.dp, glyphSize = 18.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(info.name, style = hb.type.bodyMedium, color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.problem ?: r.sources.joinToString(", "), style = hb.type.bodyXS.copy(fontSize = 12.5.sp),
                    color = if (bad) hb.colors.bad else hb.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            r.last?.let { l ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(l.text, style = hb.type.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = hb.colors.text, maxLines = 1)
                    Text(whenText(l.time), style = hb.type.bodyXS, color = hb.colors.muted, maxLines = 1)
                }
            }
        }
        val days = r.days
        if (days != null && r.permission) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(start = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (k in 29 downTo 0) {
                        val has = today.minusDays(k.toLong()) in days
                        Box(Modifier.weight(1f).height(10.dp).background(if (has) color else hb.colors.tileHi, RoundedCornerShape(3.dp)))
                    }
                }
                Text("${days.size}", style = hb.type.label, color = hb.colors.text, modifier = Modifier.padding(start = 10.dp).width(22.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

// =====================================================================================================================
// Apple Health import picker (used by Settings → Data & sync)
// =====================================================================================================================

private val IMPORT_MIME = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "text/xml", "application/xml")

/**
 * Returns an action that asks for the notification permission (Android 13+, for the progress notification; the
 * import runs either way) and then opens the system file picker for export.zip / export.xml.
 */
@Composable
internal fun rememberAppleImportLauncher(): () -> Unit {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) AppleImport.start(ctx, uri)
    }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _: Boolean -> picker.launch(IMPORT_MIME) }
    return {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else picker.launch(IMPORT_MIME)
    }
}

