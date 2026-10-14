package com.overmap.crate.screens.vibes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.overmap.crate.core.Vibe
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * Vibes manager (6l): Library → Vibes, port of iOS `VibesManagerView`. Lists vibes in Build order; long-press and
 * drag to reorder (writes `order`), swipe ← to delete (with confirmation), tap to edit, "＋" for a new vibe.
 * Owner: Vibes agent.
 */
@Composable
fun VibesManagerScreen(onBack: () -> Unit) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val haptic = LocalHapticFeedback.current
    val entries = model.snapshot.vibes.ordered
    val byID = entries.associate { it.id to it.vibe }

    var editing by remember { mutableStateOf<Pair<String?, Boolean>?>(null) } // (vibeID, open)
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    // While dragging: the local order, the dragged ID and its offset from its current slot.
    var dragOrder by remember { mutableStateOf<List<String>?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(1) }

    val ids = dragOrder ?: entries.map { it.id }
    val currentIDs by rememberUpdatedState(entries.map { it.id })

    fun writeOrder(order: List<String>) { model.scope.launch { model.editVibes { it.copy(order = order) } } }
    fun nudge(id: String, by: Int) {
        val list = entries.map { it.id }.toMutableList()
        val i = list.indexOf(id)
        val j = i + by
        if (i < 0 || j !in list.indices) return
        list[i] = list[j].also { list[j] = list[i] }
        writeOrder(list)
    }
    fun delete(id: String) {
        pendingDelete = null
        model.scope.launch { model.editVibes { f -> f.copy(vibes = f.vibes - id, order = f.order - id) } }
    }

    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(colors.bgBase).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colors.textPrimary) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { editing = null to true }) { Icon(Icons.Filled.Add, "New vibe", tint = colors.textPrimary) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = dragging == null)) {
            Text(
                "Vibes", Modifier.padding(horizontal = Space.screenMargin).semantics { heading() },
                style = CrateType.screenTitle, color = colors.textPrimary,
            )
            Text(
                "A vibe is defined by example. The order here is the order on Build.",
                Modifier.padding(start = Space.screenMargin, end = Space.screenMargin, top = Space.s, bottom = Space.m),
                style = CrateType.secondary, color = colors.textSecondary,
            )
            ids.forEachIndexed { i, id ->
                val vibe = byID[id] ?: return@forEachIndexed
                key(id) {
                    val isDragged = dragging == id
                    Box(
                        Modifier
                            .zIndex(if (isDragged) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                            .onSizeChanged { rowHeight = maxOf(1, it.height) }
                            .pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragOrder = currentIDs
                                        dragging = id
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        val order = dragOrder?.toMutableList() ?: return@detectDragGesturesAfterLongPress
                                        dragOffset += amount.y
                                        var at = order.indexOf(id)
                                        while (dragOffset > rowHeight / 2f && at < order.lastIndex) {
                                            order[at] = order[at + 1].also { order[at + 1] = order[at] }
                                            at++; dragOffset -= rowHeight
                                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        }
                                        while (dragOffset < -rowHeight / 2f && at > 0) {
                                            order[at] = order[at - 1].also { order[at - 1] = order[at] }
                                            at--; dragOffset += rowHeight
                                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        }
                                        dragOrder = order
                                    },
                                    onDragEnd = {
                                        val order = dragOrder
                                        if (order != null && order != currentIDs) writeOrder(order)
                                        dragging = null; dragOffset = 0f; dragOrder = null
                                    },
                                    onDragCancel = { dragging = null; dragOffset = 0f; dragOrder = null },
                                )
                            },
                    ) {
                        GroupRow(first = i == 0, last = false, modifier = if (isDragged) Modifier.shadow(8.dp) else Modifier,
                                 dividerInset = Space.l + 36.dp + Space.m) {
                            SwipeToDeleteRow(onDelete = { pendingDelete = id }) {
                                VibeManagerRow(
                                    id, vibe, model.index.vibeMembers[id]?.size ?: 0,
                                    onOpen = { editing = id to true },
                                    onMoveUp = { nudge(id, -1) }, onMoveDown = { nudge(id, 1) }, onDelete = { pendingDelete = id },
                                )
                            }
                        }
                    }
                }
            }
            GroupRow(first = ids.isEmpty(), last = true) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Size.minTarget + Space.s).clickable { editing = null to true }
                        .semantics(mergeDescendants = true) { role = Role.Button }.padding(horizontal = Space.l),
                    horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(22.dp), tint = colors.textPrimary)
                    Text("New vibe", style = CrateType.body.copy(fontWeight = FontWeight.Medium), color = colors.textPrimary)
                }
            }
            Text(
                "Touch and hold a vibe, then drag to reorder.",
                Modifier.padding(start = Space.screenMargin + Space.l, end = Space.screenMargin, top = Space.s, bottom = Space.xxl).navigationBarsPadding(),
                style = CrateType.footnote, color = colors.textTertiary,
            )
        }
    }

    editing?.let { (vid, _) -> VibeEditorSheet(vibeID = vid, seedTrackID = null, onDismiss = { editing = null }) }

    pendingDelete?.let { id ->
        val name = byID[id]?.name ?: "vibe"
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = colors.bgCard,
            title = { Text("Delete $name?", style = CrateType.headline, color = colors.textPrimary) },
            text = { Text("Presets that use it will skip it. This can't be undone.", style = CrateType.secondary, color = colors.textSecondary) },
            confirmButton = { TextButton(onClick = { delete(id) }) { Text("Delete vibe", color = colors.error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel", color = colors.textPrimary) } },
        )
    }
}

/** Swipe ← reveals a red Delete; releasing past the threshold asks for confirmation (the row springs back). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteRow(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val colors = CrateTheme.colors
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(onDelete)
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        onDismiss = {
            if (it == SwipeToDismissBoxValue.EndToStart) current()
            scope.launch { state.reset() }
        },
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Row(
                Modifier.fillMaxSize().background(colors.error).padding(horizontal = Space.xl),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) { Icon(Icons.Filled.Delete, null, tint = androidx.compose.ui.graphics.Color.White) }
        },
    ) { content() }
}

@Composable
private fun VibeManagerRow(
    id: String, vibe: Vibe, count: Int,
    onOpen: () -> Unit, onMoveUp: () -> Unit, onMoveDown: () -> Unit, onDelete: () -> Unit,
) {
    val colors = CrateTheme.colors
    val seeded = VibeText.hasSeeds(vibe)
    val summary = VibeText.summary(vibe)
    val symbol = VibeText.symbol(id, vibe)
    val modifier = Modifier.fillMaxWidth().background(colors.bgCard).clickable(onClick = onOpen)
        .clearAndSetSemantics {
            contentDescription = "${vibe.name}, ${if (seeded) "$count tracks" else "no seeds yet"}, $summary"
            role = Role.Button
            onClick("Opens the vibe editor") { onOpen(); true }
            customActions = listOf(
                CustomAccessibilityAction("Move up") { onMoveUp(); true },
                CustomAccessibilityAction("Move down") { onMoveDown(); true },
                CustomAccessibilityAction("Delete") { onDelete(); true },
            )
        }
    if (CrateTheme.isAccessibilitySize) {
        Column(modifier.padding(horizontal = Space.l, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Icon(com.overmap.crate.ui.theme.crateIcon(symbol), null, tint = colors.textPrimary)
                Text(vibe.name, style = CrateType.headline, color = colors.textPrimary)
            }
            Text("${VibeText.count(count)} tracks · $summary", style = CrateType.footnote, color = if (seeded) colors.textSecondary else colors.textTertiary)
        }
    } else {
        Row(
            modifier.heightIn(min = Size.minTarget + Space.s).padding(start = Space.l, end = Space.m, top = Space.s, bottom = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically,
        ) {
            VibeSymbolTile(symbol)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                Text(vibe.name, style = CrateType.headline, color = colors.textPrimary, maxLines = 1)
                Text(summary, style = CrateType.footnote, color = if (seeded) colors.textSecondary else colors.textTertiary, maxLines = 1)
            }
            Text(
                VibeText.count(count), style = CrateType.headline.copy(fontFeatureSettings = "tnum"),
                color = if (seeded) colors.textPrimary else colors.textTertiary,
            )
            Icon(Icons.Filled.DragHandle, null, Modifier.size(22.dp).clip(CircleShape), tint = colors.textTertiary)
        }
    }
}
