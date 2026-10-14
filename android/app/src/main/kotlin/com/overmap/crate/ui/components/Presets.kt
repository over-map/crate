package com.overmap.crate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.overmap.crate.core.Preset
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.PresetColor
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.Stroke
import com.overmap.crate.ui.theme.crateIcon

// region Preset tile

/**
 * A preset's identity tile: its [PresetColor] fill with the preset symbol in black (`onTile`, white on the ink
 * tile in light mode). 32 dp in the Presets manager. Decorative: hidden from TalkBack.
 */
@Composable
fun PresetTile(color: PresetColor, symbol: String, modifier: Modifier = Modifier, size: Dp = Metric.presetTile) {
    val dark = CrateTheme.colors.isDark
    Box(
        modifier
            .clearAndSetSemantics {}
            .size(size)
            .background(color.tile(dark), RoundedCornerShape(size * (Radius.chip / Metric.presetTile))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(crateIcon(symbol), null, Modifier.size(size * 0.55f), tint = color.onTile(dark))
    }
}

/** From a core preset (unknown colours fall back to ink). */
@Composable
fun PresetTile(preset: Preset, modifier: Modifier = Modifier, size: Dp = Metric.presetTile) =
    PresetTile(PresetColor.of(preset.color), preset.icon, modifier, size)

// endregion

// region Preset pill

/**
 * A preset in the Build pill row: small colour tile + name.
 *
 * - Idle: `fillControl`, `textPrimary`.
 * - Selected (loaded): `accent` fill, `onAccent` text (the ink tile inverts so it stays visible).
 * - Modified (loaded, then changed): idle fill with a 1.5 dp `textTertiary` outline and "MODIFIED".
 *
 * Tap loads the preset. [onLongClick] (with a long-press haptic) opens the caller's menu (Send now · Edit · Add to
 * widget · Delete), the Android stand-in for the iOS context menu.
 */
@Composable
fun PresetPill(
    name: String,
    color: PresetColor,
    symbol: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isModified: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = CrateTheme.colors
    val haptic = LocalHapticFeedback.current
    HapticOnChange(isSelected)
    ChipPressable(
        onClick = onClick,
        pressedOverlay = if (isSelected) colors.onAccent.copy(alpha = 0.1f) else colors.fillControlActive.copy(alpha = 0.6f),
        onLongClick = onLongClick?.let { action -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); action() } },
        modifier = modifier.clearAndSetSemantics {
            contentDescription = name
            if (isModified) stateDescription = "Modified"
            if (isSelected) selected = true
            button(onClick)
            onLongClick?.let { chipActions(onEdit = "Preset actions" to it) }
        },
    ) {
        ChipChrome(
            fill = if (isSelected) colors.accent else colors.fillControl,
            foreground = if (isSelected) colors.onAccent else colors.textPrimary,
            outline = if (isModified && !isSelected) colors.textTertiary else null,
            horizontalPadding = Space.m - Space.xxs,
            spacing = Space.s - Space.xxs,
        ) {
            if (isSelected && color == PresetColor.INK) {
                Box(
                    Modifier.size(Metric.presetPillTile).background(colors.onAccent, RoundedCornerShape(Radius.badge)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(crateIcon(symbol), null, Modifier.size(Metric.presetPillTile * 0.55f), tint = colors.accent)
                }
            } else {
                PresetTile(color, symbol, size = Metric.presetPillTile)
            }
            ChipText(name, style = if (isSelected) CrateType.chipLabel else CrateType.secondary.copy(fontWeight = FontWeight.Medium))
            if (isModified && !isSelected) {
                Text("MODIFIED", style = CrateType.panelLabel, color = colors.textSecondary, maxLines = 1)
            }
        }
    }
}

/** From a core preset (unknown colours fall back to ink). */
@Composable
fun PresetPill(
    preset: Preset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isModified: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) = PresetPill(preset.name, PresetColor.of(preset.color), preset.icon, onClick, modifier, isSelected, isModified, onLongClick)

// endregion

// region Tick ring

/** [TickRing] state. */
enum class TickState { EMPTY, CHECKED, MIXED }

/** The 24 dp (font-scaled) tri-state tick used in the Genre & style picker: empty ring / ✓ (all) / – (some). */
@Composable
fun TickRing(state: TickState, modifier: Modifier = Modifier) {
    val colors = CrateTheme.colors
    val size = Metric.tickRing * LocalDensity.current.fontScale
    val label = when (state) {
        TickState.CHECKED -> "All selected"
        TickState.MIXED -> "Some selected"
        TickState.EMPTY -> "Not selected"
    }
    Box(
        modifier
            .clearAndSetSemantics { contentDescription = label }
            .size(size)
            .then(
                if (state == TickState.EMPTY) Modifier.border(Stroke.outline, colors.textTertiary, CircleShape)
                else Modifier.background(colors.accent, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (state != TickState.EMPTY) {
            Icon(
                if (state == TickState.CHECKED) Icons.Filled.Check else Icons.Filled.Remove, null,
                Modifier.size(size * 0.6f), tint = colors.onAccent,
            )
        }
    }
}

/** Two-state convenience. */
@Composable
fun TickRing(isOn: Boolean, modifier: Modifier = Modifier) = TickRing(if (isOn) TickState.CHECKED else TickState.EMPTY, modifier)

// endregion
