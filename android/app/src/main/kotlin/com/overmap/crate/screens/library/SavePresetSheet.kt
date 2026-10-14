package com.overmap.crate.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.overmap.crate.core.Preset
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.ui.components.PanelLabel
import com.overmap.crate.ui.components.crateCard
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.PresetColor
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.VibeSymbols
import com.overmap.crate.ui.theme.crateIcon
import com.overmap.crate.ui.theme.hasCrateIcon
import kotlinx.coroutines.launch

/** Preset icon choices: the vibe picker plus preset-specific symbols (as iOS `SavePresetView.icons`). */
val presetIcons: List<String> = (VibeSymbols.picker + listOf(
    "bookmark", "figure.run", "sun.horizon.fill", "headphones", "music.note", "heart.fill",
    "star.fill", "dumbbell.fill", "bed.double.fill", "airplane", "beach.umbrella.fill", "tram.fill",
)).distinct()

/** Symbols the shared `crateIcon` map doesn't have yet (preset-only); everything else goes through it. */
private val extraPresetIcons: Map<String, ImageVector> by lazy {
    mapOf(
        "headphones" to Icons.Filled.Headphones,
        "dumbbell.fill" to Icons.Filled.FitnessCenter,
        "bed.double.fill" to Icons.Filled.Hotel,
        "airplane" to Icons.Filled.Flight,
        "beach.umbrella.fill" to Icons.Filled.BeachAccess,
        "tram.fill" to Icons.Filled.Tram,
    )
}

fun presetIcon(symbol: String): ImageVector =
    if (!hasCrateIcon(symbol)) extraPresetIcons[symbol] ?: crateIcon(symbol) else crateIcon(symbol)

/** A preset tile that also knows the preset-only symbols (see [extraPresetIcons]). */
@Composable
fun PresetTileX(color: PresetColor, symbol: String, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val dark = CrateTheme.colors.isDark
    Box(
        modifier.clearAndSetSemantics {}.size(size)
            .background(color.tile(dark), RoundedCornerShape(size * (Radius.chip.value / 32f))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(presetIcon(symbol), null, Modifier.size(size * 0.55f), tint = color.onTile(dark))
    }
}

/**
 * Save as preset (`presetID == null`, saves the current Build filters) or edit an existing preset's name, icon,
 * colour and widget slot. A bottom sheet. Port of iOS `SavePresetView`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SavePresetSheet(presetID: String?, onDismiss: () -> Unit, initialName: String = "") {
    val model = rememberModel()
    val c = CrateTheme.colors
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val presets = model.snapshot.presets.presets
    val existing = presetID?.let { id -> presets.firstOrNull { it.id == id } }
    val query = existing?.query ?: model.query
    val widgetFull = presets.count { it.inWidget && it.id != presetID } >= WIDGET_SLOTS

    var name by rememberSaveable { mutableStateOf(existing?.name ?: initialName) }
    var icon by rememberSaveable { mutableStateOf(existing?.icon ?: "bookmark") }
    var color by rememberSaveable {
        mutableStateOf(
            existing?.let { PresetColor.of(it.color) } ?: run {
                val used = presets.map { it.color.lowercase() }.toSet()
                PresetColor.entries.firstOrNull { it != PresetColor.INK && it.name.lowercase() !in used } ?: PresetColor.INK
            },
        )
    }
    var inWidget by rememberSaveable { mutableStateOf(existing?.inWidget ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val trimmed = name.trim()
    val focus = remember { FocusRequester() }

    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun save() {
        val n = trimmed
        val ic = icon
        val col = color.name.lowercase()
        val widget = inWidget && !(widgetFull && existing?.inWidget != true)
        if (presetID != null && existing != null) {
            model.scope.launch {
                model.editPresets { file ->
                    file.copy(presets = file.presets.map {
                        if (it.id == presetID) it.copy(name = n, icon = ic, color = col, inWidget = widget) else it
                    })
                }
            }
        } else {
            val preset = Preset(
                id = LibraryText.slug(n, presets.map { it.id }.toSet()), name = n, icon = ic, color = col,
                query = model.query, inWidget = widget,
            )
            model.scope.launch { model.editPresets { it.copy(presets = it.presets + preset) } }
        }
        close()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.bgSheet,
        dragHandle = null,
    ) {
        SheetTopBar(
            title = if (existing == null) "Save as preset" else "Edit preset",
            onCancel = close, confirmTitle = "Save", confirmEnabled = trimmed.isNotEmpty(), onConfirm = ::save,
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = Space.screenMargin).padding(bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.sectionGap),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = Space.s), contentAlignment = Alignment.Center) {
                PresetTileX(color, icon, size = 64.dp)
            }

            // Name
            Box(
                Modifier.fillMaxWidth().heightIn(min = Size.minTarget + Space.xs).crateCard().padding(horizontal = Space.m),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (name.isEmpty()) Text("Name", style = CrateType.body.copy(fontWeight = FontWeight.SemiBold), color = c.textTertiary)
                BasicTextField(
                    name, { name = it },
                    singleLine = true,
                    textStyle = CrateType.body.copy(fontWeight = FontWeight.SemiBold, color = c.textPrimary),
                    cursorBrush = SolidColor(c.textPrimary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (trimmed.isNotEmpty()) save() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Preset name" },
                )
            }
            if (existing == null && initialName.isEmpty()) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

            // Filters
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                PanelLabel("Filters")
                PresetSummaryText(PresetSummary(query, model, includeAny = true), CrateType.secondary)
                Text(
                    if (existing == null) "Saves the filters set in Build now." else "Load it in Build to change filters, then save again.",
                    style = CrateType.footnote, color = c.textTertiary,
                )
            }

            // Colour
            Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
                PanelLabel("Colour")
                Row(Modifier.fillMaxWidth()) {
                    PresetColor.entries.forEach { pc ->
                        val sel = pc == color
                        val label = pc.name.lowercase().replaceFirstChar { it.uppercase() }
                        Box(
                            Modifier.weight(1f).heightIn(min = Size.minTarget)
                                .clearAndSetSemantics {
                                    contentDescription = label; role = Role.RadioButton; selected = sel
                                    onClick { color = pc; true }
                                }
                                .clickable { color = pc },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier.size(32.dp).background(pc.tile(c.isDark), CircleShape).border(1.dp, c.strokeCard, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (sel) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = pc.onTile(c.isDark))
                            }
                        }
                    }
                }
            }

            // Icon
            Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
                PanelLabel("Icon")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    presetIcons.forEach { s ->
                        val sel = s == icon
                        Box(
                            Modifier.size(Size.minTarget)
                                .background(if (sel) c.accent else c.fillControl, RoundedCornerShape(Radius.chip))
                                .clearAndSetSemantics {
                                    contentDescription = s.replace(".fill", "").replace(".", " ")
                                    role = Role.RadioButton; selected = sel
                                    onClick { icon = s; true }
                                }
                                .clickable { icon = s },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(presetIcon(s), null, Modifier.size(22.dp), tint = if (sel) c.onAccent else c.textPrimary)
                        }
                    }
                }
            }

            // Widget
            Column(verticalArrangement = Arrangement.spacedBy(Space.labelGap)) {
                PanelLabel("Widget")
                val enabled = !(widgetFull && !inWidget)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Size.minTarget + Space.xs).crateCard()
                        .clickable(enabled = enabled, role = Role.Switch) { inWidget = !inWidget }
                        .padding(horizontal = Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Show in widget", style = CrateType.body, color = if (enabled) c.textPrimary else c.textTertiary, modifier = Modifier.weight(1f))
                    Switch(
                        inWidget, onCheckedChange = { inWidget = it }, enabled = enabled,
                        colors = SwitchDefaults.colors(checkedTrackColor = c.success, checkedThumbColor = androidx.compose.ui.graphics.Color.White),
                    )
                }
                if (widgetFull && !inWidget) {
                    Text(
                        "The widget shows $WIDGET_SLOTS presets. Remove one in Presets first.",
                        style = CrateType.footnote, color = c.textTertiary,
                    )
                }
            }

            if (existing != null) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = Size.minTarget).clickable(role = Role.Button) { confirmDelete = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Delete preset", style = CrateType.body, color = c.error)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${trimmed.ifEmpty { "this preset" }}?") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    val id = presetID ?: return@TextButton
                    model.scope.launch { model.editPresets { f -> f.copy(presets = f.presets.filter { it.id != id }) } }
                    close()
                }) { Text("Delete preset", color = c.error) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
            containerColor = c.bgSheet,
        )
    }
}
