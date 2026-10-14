package com.overmap.crate.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import kotlin.math.roundToInt

/** What the [PrimaryButton] (Send) shows. The caller owns the state machine. */
@Immutable
sealed interface PrimaryButtonState {
    /** Ready: e.g. "Send 100 to Now". */
    data class Idle(val title: String) : PrimaryButtonState

    /** Sending with a determinate fill. [progress] is 0–1; [counter] is shown after "Sending…" ("3/8"). */
    data class Sending(val progress: Double, val counter: String? = null) : PrimaryButtonState

    /** Done. [message] sits above the button with a ✓ ("Sent 8 tracks to Now"); the button shows [title]. */
    data class Success(val message: String, val title: String) : PrimaryButtonState

    /** Failed. [message] sits above on `errorFill`; the button shows [title] ("Retry"). */
    data class Error(val message: String, val title: String) : PrimaryButtonState

    /** Nothing to do ("No tracks to send"). */
    data class Disabled(val title: String) : PrimaryButtonState
}

private val PrimaryButtonState.kind: Int
    get() = when (this) {
        is PrimaryButtonState.Idle -> 0
        is PrimaryButtonState.Sending -> 1
        is PrimaryButtonState.Success -> 2
        is PrimaryButtonState.Error -> 3
        is PrimaryButtonState.Disabled -> 4
    }

/**
 * The 54 dp primary button (Send to Now) with its inline status message.
 *
 * - Idle / success / error: `accent` fill, `onAccent` label.
 * - Sending: `fillControlActive` track with an `accent` fill that grows with progress; the label is legible over
 *   both parts.
 * - Disabled: `fillControl`, `textTertiary`.
 *
 * Plays the Confirm / Reject haptic when the state changes into success or error, and morphs with
 * `spring(0.4, bounce 0.15)` (a crossfade with Reduce Motion).
 *
 * @param icon Optional leading image on the success button (the official Spotify icon asset).
 * @param secondaryTitle Trailing action on the success message line ("Send again").
 * @param onClick Called on tap in every state except sending and disabled.
 */
@Composable
fun PrimaryButton(
    state: PrimaryButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    secondaryTitle: String? = null,
    secondaryAction: (() -> Unit)? = null,
    /** Covers of what was just sent; on success they float up out of the button with a light wave (LiftOff). */
    liftOffCovers: List<String> = emptyList(),
) {
    val colors = CrateTheme.colors
    var liftOff by remember { mutableIntStateOf(0) }
    var faceSize by remember { mutableStateOf(IntSize.Zero) }
    var facePosition by remember { mutableStateOf(IntOffset.Zero) }
    val reduceMotion = CrateTheme.reduceMotion
    val haptic = LocalHapticFeedback.current
    val enabled = state !is PrimaryButtonState.Sending && state !is PrimaryButtonState.Disabled
    val kind = state.kind
    val lastKind = remember { intArrayOf(kind) }
    LaunchedEffect(kind) {
        if (lastKind[0] != kind) {
            if (kind == 2 && lastKind[0] == 1 && !reduceMotion) liftOff++
            lastKind[0] = kind
            when (kind) {
                2 -> haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                3 -> haptic.performHapticFeedback(HapticFeedbackType.Reject)
            }
        }
    }

    val title = when (state) {
        is PrimaryButtonState.Idle -> state.title
        is PrimaryButtonState.Disabled -> state.title
        is PrimaryButtonState.Success -> state.title
        is PrimaryButtonState.Error -> state.title
        is PrimaryButtonState.Sending -> listOfNotNull("Sending…", state.counter).joinToString(" ")
    }
    val a11yTitle = if (state is PrimaryButtonState.Sending) "Sending" else title
    val a11yValue = (state as? PrimaryButtonState.Sending)?.let { s ->
        s.counter?.replace("/", " of ") ?: "${(s.progress.coerceIn(0.0, 1.0) * 100).roundToInt()} percent"
    }

    Column(
        modifier.animateContentSize(CrateMotion.morph(reduceMotion)),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        // Message line
        AnimatedContent(
            targetState = state,
            contentKey = { it.kind },
            transitionSpec = { fadeIn(CrateMotion.morph(reduceMotion)) togetherWith fadeOut(CrateMotion.morph(reduceMotion)) },
            label = "message",
        ) { s ->
            when (s) {
                is PrimaryButtonState.Success -> Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Row(
                        Modifier.weight(1f).semantics(mergeDescendants = true) {},
                        horizontalArrangement = Arrangement.spacedBy(Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = colors.success)
                        Text(s.message, style = CrateType.footnote.copy(fontWeight = FontWeight.SemiBold), color = colors.success)
                    }
                    if (secondaryTitle != null && secondaryAction != null) {
                        Box(
                            Modifier.heightIn(min = Size.minTarget).clickable(onClick = secondaryAction).padding(horizontal = Space.xs),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(secondaryTitle, style = CrateType.footnote, color = colors.textSecondary)
                        }
                    }
                }
                is PrimaryButtonState.Error -> Row(
                    Modifier
                        .fillMaxWidth()
                        .semantics(mergeDescendants = true) {}
                        .background(colors.errorFill, RoundedCornerShape(Radius.control))
                        .padding(vertical = Space.s + Space.xxs, horizontal = Space.m),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Icon(Icons.Filled.Close, null, Modifier.padding(top = 2.dp).size(14.dp), tint = colors.error)
                    Text(s.message, Modifier.weight(1f), style = CrateType.footnote, color = colors.textPrimary)
                }
                else -> Spacer(Modifier.fillMaxWidth())
            }
        }

        // Button face
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val isPressed = pressed && enabled
        val scale by animateFloatAsState(if (isPressed && !reduceMotion) 0.98f else 1f, CrateMotion.chip(reduceMotion), label = "pressScale")
        val alpha by animateFloatAsState(if (isPressed) 0.85f else 1f, CrateMotion.chip(reduceMotion), label = "pressAlpha")
        val background by animateColorAsState(
            when (state) {
                is PrimaryButtonState.Sending -> colors.fillControlActive
                is PrimaryButtonState.Disabled -> colors.fillControl
                else -> colors.accent
            },
            CrateMotion.morph(reduceMotion), label = "buttonFill",
        )
        val progress by animateFloatAsState(
            ((state as? PrimaryButtonState.Sending)?.progress ?: 0.0).coerceIn(0.0, 1.0).toFloat(),
            tween(200, easing = LinearEasing), label = "progress",
        )
        val shape = RoundedCornerShape(Radius.button)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Size.buttonHeight)
                .onGloballyPositioned {
                    faceSize = it.size
                    val p = it.positionInWindow()
                    facePosition = IntOffset(p.x.toInt(), p.y.toInt())
                }
                .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                .clip(shape)
                .background(background)
                .drawBehind {
                    if (state is PrimaryButtonState.Sending) {
                        drawRect(colors.accent, size = GeoSize(size.width * progress, size.height))
                    }
                }
                .clearAndSetSemantics {
                    contentDescription = a11yTitle
                    if (a11yValue != null) {
                        stateDescription = a11yValue
                        liveRegion = LiveRegionMode.Polite
                    }
                    if (enabled) button(onClick) else disabled()
                }
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            LiftOff(trigger = liftOff, covers = liftOffCovers, buttonSize = faceSize,
                    buttonX = facePosition.x, buttonY = facePosition.y)
            if (state is PrimaryButtonState.Sending) {
                FaceText(title, colors.textPrimary)
                FaceText(
                    title, colors.onAccent,
                    Modifier.drawWithContent { clipRect(right = size.width * progress) { this@drawWithContent.drawContent() } },
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s + Space.xxs), verticalAlignment = Alignment.CenterVertically) {
                    if (state is PrimaryButtonState.Success && icon != null) {
                        Image(icon, null, Modifier.size(Space.xl - Space.xxs))
                    }
                    FaceText(title, if (state is PrimaryButtonState.Disabled) colors.textTertiary else colors.onAccent, fill = false)
                }
            }
        }
    }
}

@Composable
private fun FaceText(title: String, color: Color, modifier: Modifier = Modifier, fill: Boolean = true) {
    Text(
        title,
        (if (fill) modifier.fillMaxWidth() else modifier).padding(horizontal = Space.l, vertical = Space.s),
        style = CrateType.headline.copy(fontFeatureSettings = "tnum"),
        color = color,
        textAlign = TextAlign.Center,
    )
}
