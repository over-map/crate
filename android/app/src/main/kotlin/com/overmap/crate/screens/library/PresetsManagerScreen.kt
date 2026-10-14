package com.overmap.crate.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.overmap.crate.app.DebugLaunch
import com.overmap.crate.core.Preset
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.ui.components.PanelLabel
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.PresetColor
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import kotlinx.coroutines.launch

/** Presets shown in the medium widget. */
const val WIDGET_SLOTS = 4

private const val OTHER = "\u0000other"

/**
 * Presets manager (design 6v, 3j): "In widget" (max 4) and "Other". Drag the handle to reorder or to move between
 * sections, swipe left to delete (with confirmation), long-press for the menu, tap to edit. Port of iOS
 * `PresetsManagerView`.
 */
@Composable
fun PresetsManagerScreen(onBack: () -> Unit) {
    val model = rememberModel()
    val c = CrateTheme.colors
    val haptic = LocalHapticFeedback.current
    val presets = model.snapshot.presets.presets
    val byID = presets.associateBy { it.id }
    val widgetIDs = presets.filter { it.inWidget }.take(WIDGET_SLOTS).map { it.id }
    val otherIDs = presets.map { it.id }.filter { it !in widgetIDs }

    var editing by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var order by remember(presets) { mutableStateOf(widgetIDs + OTHER + otherIDs) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        if (DebugLaunch.screen == "preset-edit") editing = presets.firstOrNull { it.inWidget }?.id
    }

    fun apply(widget: List<String>, other: List<String>) {
        val all = widget + other
        val inWidget = widget.toSet()
        model.scope.launch {
            model.editPresets { file ->
                val map = LinkedHashMap<String, Preset>()
                for (p in file.presets) if (p.id !in map) map[p.id] = p
                val next = all.mapNotNull { map[it] } + file.presets.filter { it.id !in all }
                file.copy(presets = next.map { it.copy(inWidget = it.id in inWidget) })
            }
        }
    }

    /** Splits [list] at the "Other" marker; a full widget pushes its last preset to the top of Other. */
    fun commit(list: List<String>) {
        val at = list.indexOf(OTHER)
        val w = list.take(at).toMutableList()
        val o = list.drop(at + 1).toMutableList()
        while (w.size > WIDGET_SLOTS) o.add(0, w.removeAt(w.lastIndex))
        apply(w, o)
    }

    fun toggleWidget(id: String) {
        val w = widgetIDs.toMutableList()
        val o = otherIDs.toMutableList()
        if (id in w) { w.remove(id); o.add(0, id) } else if (w.size < WIDGET_SLOTS) { o.remove(id); w.add(id) }
        apply(w, o)
    }

    fun move(id: String, by: Int) {
        val list = order.toMutableList()
        val i = list.indexOf(id)
        val j = (i + by).coerceIn(0, list.lastIndex)
        if (i == j) return
        list.removeAt(i); list.add(j, id)
        commit(list)
    }

    Column(Modifier.fillMaxSize().background(c.bgBase)) {
        PushedTopBar("Presets", onBack) {
            IconButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, "Save Build filters as a preset", tint = c.textPrimary)
            }
        }
        if (presets.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(Space.xl),
                verticalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(48.dp), tint = c.textSecondary)
                Text("No presets yet", style = CrateType.sheetTitle, color = c.textPrimary)
                Text("Set filters in Build, then save them as a preset.", style = CrateType.secondary, color = c.textSecondary, textAlign = TextAlign.Center)
            }
        } else {
            val widgetCount = order.indexOf(OTHER)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = dragging == null)
                    .padding(horizontal = Space.screenMargin).padding(top = Space.s, bottom = Space.xl),
            ) {
                PanelLabel("In widget · ${widgetCount.coerceAtMost(WIDGET_SLOTS)} of $WIDGET_SLOTS", Modifier.padding(start = Space.l, bottom = Space.s))
                if (widgetCount == 0) {
                    Text(
                        "Drag a preset here to show it in the widget.", style = CrateType.footnote, color = c.textTertiary,
                        modifier = Modifier.fillMaxWidth().background(c.bgCard, RoundedCornerShape(Radius.card)).padding(Space.l),
                    )
                }
                order.forEachIndexed { index, id ->
                    key(id) {
                        if (id == OTHER) {
                            PanelLabel(
                                "Other",
                                Modifier.onSizeChanged { heights[OTHER] = it.height }
                                    .padding(start = Space.l, top = Space.xl, bottom = Space.s),
                            )
                        } else byID[id]?.let { preset ->
                        val inWidget = index < widgetCount
                        val first = index == 0 || order[index - 1] == OTHER
                        val last = index == order.lastIndex || order[index + 1] == OTHER
                        val shape = RoundedCornerShape(
                            topStart = if (first) Radius.card else 0.dp, topEnd = if (first) Radius.card else 0.dp,
                            bottomStart = if (last) Radius.card else 0.dp, bottomEnd = if (last) Radius.card else 0.dp,
                        )
                        val isDragged = dragging == id
                        Box(
                            Modifier
                                .zIndex(if (isDragged) 1f else 0f)
                                .graphicsLayer {
                                    if (isDragged) { translationY = dragOffset; shadowElevation = 8.dp.toPx() }
                                }
                                .onSizeChanged { heights[id] = it.height }
                                .clip(shape),
                        ) {
                            PresetRowSwipe(
                                preset = preset,
                                summary = PresetSummary(preset.query, model, includeAny = false),
                                inWidget = inWidget,
                                canAddToWidget = inWidget || widgetIDs.size < WIDGET_SLOTS,
                                showSeparator = !last,
                                onEdit = { editing = id },
                                onDelete = { pendingDelete = id },
                                onToggleWidget = { toggleWidget(id) },
                                onMoveUp = if (index > 0) ({ move(id, -1) }) else null,
                                onMoveDown = if (index < order.lastIndex) ({ move(id, 1) }) else null,
                                handle = Modifier.pointerInput(id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragging = id; dragOffset = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                            val list = order.toMutableList()
                                            val i = list.indexOf(id)
                                            if (dragOffset > 0 && i < list.lastIndex) {
                                                val h = heights[list[i + 1]] ?: return@detectDragGestures
                                                if (dragOffset > h / 2f) {
                                                    list.removeAt(i); list.add(i + 1, id); order = list; dragOffset -= h
                                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                                }
                                            } else if (dragOffset < 0 && i > 0) {
                                                val h = heights[list[i - 1]] ?: return@detectDragGestures
                                                if (-dragOffset > h / 2f) {
                                                    list.removeAt(i); list.add(i - 1, id); order = list; dragOffset += h
                                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                                }
                                            }
                                        },
                                        onDragEnd = { dragging = null; dragOffset = 0f; commit(order) },
                                        onDragCancel = { dragging = null; dragOffset = 0f; commit(order) },
                                    )
                                },
                                shape = shape,
                            )
                        }
                        }
                    }
                }
                Text(
                    "Drag between sections to add or remove from the widget. Tap a preset to rename it or change its icon.",
                    style = CrateType.footnote, color = c.textTertiary, modifier = Modifier.padding(start = Space.l, end = Space.l, top = Space.s),
                )
            }
        }
    }

    editing?.let { id -> SavePresetSheet(presetID = id, onDismiss = { editing = null }) }
    if (creating) SavePresetSheet(presetID = null, onDismiss = { creating = false })
    pendingDelete?.let { id ->
        val name = byID[id]?.name?.ifEmpty { null } ?: "this preset"
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete $name?") },
            text = { Text("It disappears from Build and the widget.") },
            confirmButton = {
                TextButton({
                    pendingDelete = null
                    model.scope.launch { model.editPresets { f -> f.copy(presets = f.presets.filter { it.id != id }) } }
                }) { Text("Delete preset", color = c.error) }
            },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancel") } },
            containerColor = c.bgSheet,
        )
    }
}

/** A preset row with swipe-left-to-delete (asks first) and a long-press menu. */
@Composable
private fun PresetRowSwipe(
    preset: Preset,
    summary: PresetSummary,
    inWidget: Boolean,
    canAddToWidget: Boolean,
    showSeparator: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleWidget: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    handle: Modifier,
    shape: RoundedCornerShape,
) {
    val c = CrateTheme.colors
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val swipe = rememberSwipeToDismissBoxState()
    LaunchedEffect(swipe.currentValue) {
        if (swipe.currentValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
            swipe.reset()
        }
    }
    val widgetAction = if (inWidget) "Remove from widget" else "Add to widget"

    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(c.error, shape).padding(horizontal = Space.l), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Filled.Delete, null, tint = if (c.isDark) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White)
            }
        },
    ) {
        Box {
            Column(Modifier.background(c.bgCard)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Size.minTarget + Space.xs)
                        .semantics(mergeDescendants = true) {
                            contentDescription = preset.name
                            stateDescription = summary.spoken + if (inWidget) ", in widget" else ""
                            onClick("Edit") { onEdit(); true }
                            onLongClick("More actions") { menu = true; true }
                            customActions = listOfNotNull(
                                CustomAccessibilityAction("Edit") { onEdit(); true },
                                if (inWidget || canAddToWidget) CustomAccessibilityAction(widgetAction) { onToggleWidget(); true } else null,
                                onMoveUp?.let { f -> CustomAccessibilityAction("Move up") { f(); true } },
                                onMoveDown?.let { f -> CustomAccessibilityAction("Move down") { f(); true } },
                                CustomAccessibilityAction("Delete") { onDelete(); true },
                            )
                        }
                        .combinedClickable(
                            onClick = onEdit,
                            onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                        )
                        .padding(start = Space.l, end = Space.xs, top = Space.s, bottom = Space.s),
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PresetTileX(PresetColor.of(preset.color), preset.icon)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                        Text(
                            preset.name, style = CrateType.body.copy(fontWeight = FontWeight.SemiBold), color = c.textPrimary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        PresetSummaryText(summary, CrateType.footnote, maxLines = 1, speak = false)
                    }
                    Box(handle.size(Size.minTarget), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.DragHandle, null, Modifier.size(20.dp), tint = c.textTertiary)
                    }
                }
                if (showSeparator) RowSeparator(Modifier.padding(start = Space.l + 32.dp + Space.m))
            }
            DropdownMenu(menu, onDismissRequest = { menu = false }, containerColor = c.bgSheet) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(
                    text = { Text(widgetAction) }, enabled = inWidget || canAddToWidget,
                    onClick = { menu = false; onToggleWidget() },
                )
                if (onMoveUp != null) DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; onMoveUp() })
                if (onMoveDown != null) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; onMoveDown() })
                DropdownMenuItem(text = { Text("Delete", color = c.error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
