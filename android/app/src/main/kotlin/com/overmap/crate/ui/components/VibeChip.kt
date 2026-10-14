package com.overmap.crate.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.crateIcon
import com.overmap.crate.ui.theme.fillControlStrong
import androidx.compose.material3.Text

/**
 * A vibe filter chip. Vibes have no colour; each has a user-picked SF Symbol name (mapped by [crateIcon]).
 *
 * - Off: `fillControl`, symbol, name, count (whole library, or "count if added" once filters are set).
 * - Selected: `accent` fill, "✓ Name" (the check replaces the symbol, so selection changes shape too).
 * - Count 0: dimmed to 45 %, still tappable.
 * - No seeds yet ([hasSeeds] `false`): dashed outline, "＋ seeds"; tapping opens the editor.
 *
 * Tap calls [onClick] (selection haptic on every state change); long-press calls [onEdit] (long-press haptic).
 * Counts roll. For the trailing "＋ New vibe" chip use [AddChip].
 */
@Composable
fun VibeChip(
    name: String,
    symbol: String,
    count: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    hasSeeds: Boolean = true,
    onEdit: (() -> Unit)? = null,
) {
    val colors = CrateTheme.colors
    val reduceMotion = CrateTheme.reduceMotion
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    val currentClick by rememberUpdatedState(onClick)
    val currentEdit by rememberUpdatedState(onEdit)
    val scale by animateFloatAsState(if (pressed && !reduceMotion) 0.97f else 1f, CrateMotion.chip(reduceMotion), label = "vibeScale")
    val isDimmed = hasSeeds && !isSelected && count == 0
    val alpha by animateFloatAsState(if (isDimmed) Metric.DIMMED else 1f, CrateMotion.chip(reduceMotion), label = "vibeAlpha")
    HapticOnChange(isSelected)

    val label = name + when {
        !hasSeeds -> ", no seeds yet"
        count != null -> ", $count tracks"
        else -> ""
    }

    val chipModifier = modifier
        .clearAndSetSemantics {
            contentDescription = label
            if (isSelected) selected = true
            if (!hasSeeds) stateDescription = "Opens the vibe editor"
            button(onClick)
            chipActions(onEdit = onEdit?.let { "Edit vibe" to it })
        }
        .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onLongPress = {
                    currentEdit?.let {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
                onTap = { currentClick() },
            )
        }

    val fill by animateColorAsState(
        when {
            isSelected -> colors.accent
            !hasSeeds -> Color.Transparent
            pressed -> colors.fillControlActive
            else -> colors.fillControl
        },
        CrateMotion.chip(reduceMotion), label = "vibeFill",
    )

    when {
        isSelected -> ChipChrome(fill, colors.onAccent, chipModifier) {
            Icon(Icons.Filled.Check, null, Modifier.size(14.dp))
            ChipText(name)
        }
        !hasSeeds -> ChipChrome(fill, colors.textSecondary, chipModifier, outline = colors.fillControlStrong, dashed = true) {
            Icon(crateIcon(symbol), null, Modifier.size(14.dp))
            ChipText(name)
            Text("＋ seeds", style = CrateType.footnote.copy(fontWeight = FontWeight.Medium), color = colors.textTertiary, maxLines = 1)
        }
        else -> ChipChrome(fill, colors.textSecondary, chipModifier) {
            Icon(crateIcon(symbol), null, Modifier.size(14.dp))
            ChipText(name)
            if (count != null) {
                CountText(count, style = CrateType.footnote.copy(fontWeight = FontWeight.Medium), color = colors.textTertiary)
            }
        }
    }
}
