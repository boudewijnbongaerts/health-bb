@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.boudhealth.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boudhealth.ui.nav.LayoutSlots
import com.boudhealth.ui.nav.TileSizes
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.foundation.layout.fillMaxHeight
import com.boudhealth.data.TileSize
import com.boudhealth.ui.components.FilterPill
import com.boudhealth.ui.components.Glyph
import com.boudhealth.ui.components.GlyphIcon
import com.boudhealth.ui.components.HBBottomSheet
import com.boudhealth.ui.components.HBButton
import com.boudhealth.ui.components.IconCircleButton
import com.boudhealth.ui.components.SegmentSize
import com.boudhealth.ui.components.SegmentedRange
import com.boudhealth.ui.components.hbClickable
import com.boudhealth.ui.theme.LocalHB

/**
 * "Add a tile" catalog (A_SPEC §6): fixed ~88 % sheet, filter chips (All · Activity · Workouts · Body · Vitals),
 * groups of wells with a live M preview, name, description, size chips and an ink "+" (or "✓ On home").
 * Tapping a well expands it: size picker, live preview at that size, what it shows / where it fits, "Add as L".
 */
@Composable
internal fun CatalogSheet(
    slot: String,
    present: Set<String>,
    data: HomeData?,
    onDismiss: () -> Unit,
    onAdd: (String, TileSize) -> Unit,
) {
    val hb = LocalHB.current
    var filter by rememberSaveable { mutableStateOf<TileGroup?>(null) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val onLabel = if (slot == LayoutSlots.HOME) "On home" else "On widget"

    HBBottomSheet(onDismiss = onDismiss) {
        // Q8 root cause: the list had a fixed height from LocalConfiguration.screenHeightDp (which on targetSdk 35+ counts
        // the system bars, and on the folded / landscape screens is taller than the sheet may be), so the sheet content was
        // taller than its window. A hard fling then pushed its leftover scroll into ModalBottomSheet's own nested-scroll
        // drag, which moved the oversized sheet out of place and left the window broken. Fix: size from the sheet's own
        // constraints (fillMaxHeight fraction, never taller than the sheet), and keep the list's leftover scroll / fling
        // from reaching the sheet (SheetScrollBarrier). The sheet still closes by its handle, the scrim or Close.
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                Text("Add a tile", style = hb.type.titleL, color = hb.colors.text)
                Spacer(Modifier.width(10.dp))
                Text("${TileInfos.all.size} tiles", style = hb.type.bodyS, color = hb.colors.muted, modifier = Modifier.weight(1f))
                IconCircleButton(Glyph.Close, onDismiss, contentDescription = "Close", background = hb.colors.sheetTile)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("All", filter == null, { filter = null })
                TileGroup.entries.forEach { g -> FilterPill(g.label, filter == g, { filter = g }, dot = groupColor(g)) }
            }
            Column(Modifier.fillMaxWidth().weight(1f).nestedScroll(SheetScrollBarrier).verticalScroll(rememberScrollState())) {
                TileGroup.entries.filter { filter == null || it == filter }.forEach { g ->
                    val items = TileInfos.all.filter { it.group == g }
                    Row(Modifier.padding(top = 22.dp, bottom = 12.dp, start = 4.dp), verticalAlignment = Alignment.Bottom) {
                        Text(g.label, style = hb.type.titleS, color = hb.colors.text)
                        Spacer(Modifier.width(8.dp))
                        Text("${items.size} ${if (items.size == 1) "tile" else "tiles"}", style = hb.type.bodyS, color = hb.colors.muted)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items.forEach { info ->
                            if (expanded == info.id) {
                                ExpandedWell(info, info.id in present, onLabel, data, onClose = { expanded = null }, onAdd = { s -> onAdd(info.id, s) })
                            } else {
                                CollapsedWell(info, info.id in present, onLabel, data, onExpand = { expanded = info.id },
                                    onAdd = { onAdd(info.id, TileSizes.defaultSize(info.id)) })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SizeChips(sizes: List<TileSize>) {
    val hb = LocalHB.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sizes.forEach { s ->
            Box(Modifier.size(width = if (s == TileSize.XL) 34.dp else 28.dp, height = 28.dp).background(hb.colors.sheet, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text(s.name, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun OnHomeMark(label: String) {
    val hb = LocalHB.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphIcon(Glyph.Check, size = 16.dp, color = hb.colors.good, stroke = 2.6f)
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.good, maxLines = 1)
    }
}

@Composable
private fun CollapsedWell(info: TileInfo, present: Boolean, onLabel: String, data: HomeData?, onExpand: () -> Unit, onAdd: () -> Unit) {
    val hb = LocalHB.current
    val shape = RoundedCornerShape(24.dp)
    val sizes = TileSizes.sizes(info.id)
    val previewSize = if (TileSize.M in sizes) TileSize.M else sizes.first()
    BoxWithConstraints(Modifier.fillMaxWidth().hbClickable(shape = shape, onClick = onExpand).background(hb.colors.sheetTile, shape).padding(10.dp)) {
        val pw = minOf(maxWidth * 0.52f, 186.dp)
        val cell = (pw - TileGap) / 2
        val dims = tileDims(previewSize, cell, cell, 4)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(pw), contentAlignment = Alignment.CenterStart) {
                Tile(info.id, previewSize, dims.width, dims.height, data)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(info.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = hb.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (info.desc.isNotEmpty()) Text(info.desc, style = hb.type.bodyXS, color = hb.colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SizeChips(sizes) }
                    if (present) OnHomeMark(onLabel)
                    else IconCircleButton(Glyph.Plus, onAdd, contentDescription = "Add ${info.name}", size = 36.dp, glyphSize = 20.dp,
                        background = hb.colors.text, tint = hb.colors.surface)
                }
            }
        }
    }
}

@Composable
private fun ExpandedWell(info: TileInfo, present: Boolean, onLabel: String, data: HomeData?, onClose: () -> Unit, onAdd: (TileSize) -> Unit) {
    val hb = LocalHB.current
    val shape = RoundedCornerShape(28.dp)
    val sizes = TileSizes.sizes(info.id)
    var size by rememberSaveable(info.id) { mutableStateOf(TileSizes.defaultSize(info.id)) }
    Column(Modifier.fillMaxWidth().background(hb.colors.sheetTile, shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(info.name, style = hb.type.titleM, color = hb.colors.text)
                Text("Pick a size · preview uses today's data", style = hb.type.bodyS, color = hb.colors.muted)
            }
            IconCircleButton(Glyph.Close, onClose, contentDescription = "Collapse", background = hb.colors.sheet)
        }
        Spacer(Modifier.height(14.dp))
        SegmentedRange(sizes, size, { size = it }, label = { "${it.name}  ${it.label}" }, size = SegmentSize.Regular, track = hb.colors.sheet)
        Spacer(Modifier.height(16.dp))
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // The XL preview must fit the well: cell ≤ 87 (cover-screen cell), shrink on narrow sheets.
            val cell: Dp = minOf(87.dp, (maxWidth - TileGap * 3) / 4)
            val dims = tileDims(size, cell, cell, 4)
            Tile(info.id, size, dims.width, dims.height, data)
        }
        Spacer(Modifier.height(14.dp))
        if (info.note.isNotEmpty()) Text(info.note, style = hb.type.bodyS, color = hb.colors.muted)
        Text(
            if (size == TileSize.XL) "Fits: app home (full width on 4 columns) and every widget" else "Fits: app home and every widget",
            style = hb.type.bodyS, color = hb.colors.text, modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(16.dp))
        if (present) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { OnHomeMark("$onLabel already · change its size in the grid") }
        } else {
            HBButton("Add as ${size.name}", { onAdd(size) }, glyph = Glyph.Plus, fullWidth = true)
        }
    }
}

/** Consumes whatever scroll / fling an inner list leaves over, so it never drags the modal sheet itself (Q8). */
internal object SheetScrollBarrier : NestedScrollConnection {
    override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource) = available
    override suspend fun onPostFling(consumed: androidx.compose.ui.unit.Velocity, available: androidx.compose.ui.unit.Velocity) = available
}
