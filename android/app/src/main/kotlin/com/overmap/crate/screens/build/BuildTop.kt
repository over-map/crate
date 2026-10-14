package com.overmap.crate.screens.build

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.overmap.crate.app.AppModel
import com.overmap.crate.app.BuildState
import com.overmap.crate.core.Preset
import com.overmap.crate.screens.rememberModel
import com.overmap.crate.ui.components.ChipChrome
import com.overmap.crate.ui.components.ChipFlow
import com.overmap.crate.ui.components.CountText
import com.overmap.crate.ui.components.CoverImage
import com.overmap.crate.ui.components.PrimaryButton
import com.overmap.crate.ui.components.PrimaryButtonState
import com.overmap.crate.ui.components.PresetPill
import com.overmap.crate.ui.components.crateCard
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.StyleShade
import com.overmap.crate.ui.theme.crateIcon
import com.overmap.crate.ui.theme.familyColor
import kotlinx.coroutines.launch
import java.text.NumberFormat

internal fun fmt(n: Int): String = NumberFormat.getIntegerInstance().format(n)

// region Preset pills

/**
 * The preset pill row. Tap loads a preset (selected while unchanged, "MODIFIED" once edited); long-press opens
 * Send now · Edit · Add to / Remove from widget · Delete… (the Android stand-in for the iOS context menu).
 */
@Composable
internal fun BuildPresetRow(
    loadedPresetID: String?,
    onLoadedChange: (String?) -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    var menuFor by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Preset?>(null) }
    val allStyles = model.allStyles

    fun load(p: Preset) {
        model.updateBuild { BuildState.from(p.query, allStyles) }
        onLoadedChange(p.id)
    }

    LazyRow(
        modifier.bleed(Space.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(Space.chipGap),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.screenMargin, vertical = Space.xs),
    ) {
        items(model.snapshot.presets.presets, key = { it.id }) { p ->
            val loaded = loadedPresetID == p.id
            val same = model.build == BuildState.from(p.query, allStyles)
            Box {
                PresetPill(
                    preset = p,
                    onClick = { load(p) },
                    isSelected = loaded && same,
                    isModified = loaded && !same,
                    onLongClick = { menuFor = p.id },
                )
                DropdownMenu(
                    expanded = menuFor == p.id,
                    onDismissRequest = { menuFor = null },
                    containerColor = colors.bgCard,
                ) {
                    DropdownMenuItem(
                        text = { Text("Send now") },
                        leadingIcon = { Icon(crateIcon("paperplane"), null) },
                        onClick = {
                            menuFor = null
                            load(p)
                            model.scope.launch { model.send() }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(crateIcon("pencil"), null) },
                        onClick = { menuFor = null; onEdit(p.id) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (p.inWidget) "Remove from widget" else "Add to widget") },
                        leadingIcon = { Icon(crateIcon(if (p.inWidget) "rectangle.badge.minus" else "rectangle.badge.plus"), null) },
                        onClick = {
                            menuFor = null
                            val id = p.id
                            model.scope.launch {
                                model.editPresets { file ->
                                    file.copy(presets = file.presets.map { if (it.id == id) it.copy(inWidget = !it.inWidget) else it })
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete…", color = colors.error) },
                        leadingIcon = { Icon(crateIcon("trash"), null, tint = colors.error) },
                        onClick = { menuFor = null; deleting = p },
                    )
                }
            }
        }
    }

    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = colors.bgSheet,
            title = { Text("Delete ${p.name}?", style = CrateType.headline, color = colors.textPrimary) },
            text = { Text("It's removed from Build, the widgets and the app shortcuts.", style = CrateType.secondary, color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    val id = p.id
                    deleting = null
                    if (loadedPresetID == id) onLoadedChange(null)
                    model.scope.launch { model.editPresets { file -> file.copy(presets = file.presets.filter { it.id != id }) } }
                }) { Text("Delete preset", color = colors.error, style = CrateType.body.copy(fontWeight = FontWeight.SemiBold)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel", color = colors.textSecondary, style = CrateType.body) }
            },
        )
    }
}

// endregion

// region Result card

/** Sticky result card: live count, "WHOLE LIBRARY" / "TRACKS MATCH", 4 covers, chevron (opens the list). */
@Composable
internal fun BuildResultCard(onOpen: (String, List<String>) -> Unit) {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val count = model.matchCount
    val isEmpty = model.build.isEmpty
    val ids = model.buildMatchingIDs
    val covers = model.coverTrackIDs(ids)
    val label = if (isEmpty) "Whole library, $count tracks" else "$count tracks match"
    val open = { onOpen(if (isEmpty) "Library" else "${fmt(count)} tracks", ids) }
    Row(
        Modifier
            .fillMaxWidth()
            .crateCard()
            .clickable(enabled = count > 0, onClick = open)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                if (count > 0) onClick(label = "Show the tracks") { open(); true } else disabled()
            }
            .padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            CountText(count, style = CrateType.numeralLarge, color = if (count == 0) colors.textTertiary else colors.textPrimary)
            TrailingPanelLabel(if (isEmpty) "Whole library" else "Tracks match", color = colors.textSecondary)
        }
        if (!CrateTheme.isAccessibilitySize) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                for (i in 0 until 4) {
                    val id = covers.getOrNull(i)
                    val t = id?.let { model.index.tracks[it] }
                    if (t != null) {
                        CoverImage(
                            url = t.cover, size = Size.coverStrip,
                            tint = familyColor(model.familyHue(model.index.effective[id]?.family), StyleShade.BASE, colors.isDark),
                        )
                    } else {
                        Box(Modifier.size(Size.coverStrip).background(colors.fillControl, RoundedCornerShape(Radius.cover)))
                    }
                }
            }
        }
        Icon(crateIcon("chevron.right"), null, Modifier.size(20.dp), tint = colors.textTertiary)
    }
}

// endregion

// region Empty state

/** "No tracks match" with up to three one-tap relaxations and their resulting counts (6f). */
@Composable
internal fun BuildEmptyState() {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val relax = model.relaxations()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("No tracks match", style = CrateType.headline, color = colors.textPrimary)
        if (relax.isNotEmpty()) {
            Text("Try one of these:", style = CrateType.secondary, color = colors.textSecondary)
            ChipFlow {
                relax.forEach { r ->
                    Box(
                        Modifier
                            .clickable { model.updateBuild(r.apply) }
                            .clearAndSetSemantics {
                                contentDescription = "${r.title}, ${r.count} tracks"
                                role = Role.Button
                                onClick { model.updateBuild(r.apply); true }
                            },
                    ) {
                        ChipChrome(colors.fillControl, colors.textPrimary) {
                            Text(r.title, style = CrateType.chipLabel.copy(fontWeight = FontWeight.Medium), maxLines = 1)
                            Text(fmt(r.count), style = CrateType.footnote.copy(fontWeight = FontWeight.Medium), color = colors.textTertiary)
                        }
                    }
                }
            }
        } else {
            Text("Remove a filter to see tracks.", style = CrateType.secondary, color = colors.textSecondary)
        }
    }
}

// endregion

// region Send bar

/** The primary button pinned 12 dp above the tab bar, fading into `bgBase`. */
@Composable
internal fun BuildSendBar() {
    val model = rememberModel()
    val colors = CrateTheme.colors
    val state = sendButtonState(model)
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val fade = Space.xl.toPx()
                drawRect(
                    Brush.verticalGradient(listOf(colors.bgBase.copy(alpha = 0f), colors.bgBase), startY = 0f, endY = fade),
                    size = GeoSize(size.width, fade),
                )
                drawRect(colors.bgBase, topLeft = Offset(0f, fade), size = GeoSize(size.width, size.height - fade))
            }
            .padding(start = Space.screenMargin, end = Space.screenMargin, top = Space.xl, bottom = Space.m),
    ) {
        PrimaryButton(
            state = state,
            onClick = {
                if (model.sendState is AppModel.SendState.Success) model.openNow() else model.scope.launch { model.send() }
            },
            secondaryTitle = "Send again",
            secondaryAction = { model.scope.launch { model.send() } },
            liftOffCovers = model.lastSentCovers,
        )
    }
}

private fun sendButtonState(model: AppModel): PrimaryButtonState = when (val s = model.sendState) {
    AppModel.SendState.Idle ->
        if (model.sendCount == 0) PrimaryButtonState.Disabled("No tracks to send") else PrimaryButtonState.Idle("Send ${model.sendCount} to Now")
    is AppModel.SendState.Sending ->
        PrimaryButtonState.Sending(if (s.total > 0) s.done.toDouble() / s.total else 0.0, "${s.done}/${s.total}")
    is AppModel.SendState.Success -> PrimaryButtonState.Success("Sent ${s.count} tracks to Now", "Listen on Spotify")
    is AppModel.SendState.Error -> PrimaryButtonState.Error(s.message, "Retry")
}

// endregion

// region BPM keyboard bar

/**
 * Shown above the number pad while a BPM numeral is typed (design-system "RangeSlider › Typed entry"): a Min/Max
 * switch, a ±1 stepper and Done. The Android stand-in for the iOS keyboard toolbar.
 */
@Composable
internal fun BpmKeyboardBar(editing: BpmBound, onSwitch: (BpmBound) -> Unit, onStep: (Int) -> Unit, onDone: () -> Unit) {
    val colors = CrateTheme.colors
    Column(Modifier.fillMaxWidth().background(colors.bgCard)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.s, vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BuildSegmented(
                options = listOf("Min", "Max"),
                selected = if (editing == BpmBound.MIN) 0 else 1,
                onSelect = { onSwitch(if (it == 0) BpmBound.MIN else BpmBound.MAX) },
                modifier = Modifier.width(140.dp),
                labels = listOf("Minimum BPM", "Maximum BPM"),
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onStep(-1) }, modifier = Modifier.clearAndSetSemantics {
                contentDescription = "Lower by 1"; role = Role.Button; onClick { onStep(-1); true }
            }) { Icon(crateIcon("minus"), null, tint = colors.textPrimary) }
            IconButton(onClick = { onStep(1) }, modifier = Modifier.clearAndSetSemantics {
                contentDescription = "Raise by 1"; role = Role.Button; onClick { onStep(1); true }
            }) { Icon(crateIcon("plus"), null, tint = colors.textPrimary) }
            TextButton(onClick = onDone, modifier = Modifier.heightIn(min = Size.minTarget)) {
                Text("Done", style = CrateType.headline, color = colors.textPrimary)
            }
        }
    }
}

// endregion
