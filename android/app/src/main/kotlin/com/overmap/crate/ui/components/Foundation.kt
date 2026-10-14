package com.overmap.crate.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.Stroke
import java.text.NumberFormat

// region Panel label

/**
 * The small uppercase monospaced label above a control group ("GENRE & STYLE", "VIBE · ALL OF").
 * An optional [trailing] label sits on the right in `textTertiary` ("IF ADDED · ALL"); at accessibility font
 * sizes the two stack. TalkBack treats the label as a heading.
 */
@Composable
fun PanelLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    val colors = CrateTheme.colors
    val label: @Composable () -> Unit = {
        Text(
            text.uppercase(), style = CrateType.panelLabel, color = colors.textSecondary,
            modifier = Modifier.semantics { heading() },
        )
    }
    val trail: @Composable () -> Unit = {
        if (trailing != null) Text(trailing.uppercase(), style = CrateType.panelLabel, color = colors.textTertiary)
    }
    when {
        trailing == null -> Box(modifier) { label() }
        CrateTheme.isAccessibilitySize ->
            Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xxs)) { label(); trail() }
        else -> Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { label() }
            Spacer(Modifier.width(Space.s))
            trail()
        }
    }
}

// endregion

// region Card

/** Level-1 surface: `bgCard`, a 1 dp inset `strokeCard` hairline, `Radius.card` corners. Add padding after it. */
fun Modifier.crateCard(): Modifier = composed {
    val colors = CrateTheme.colors
    val shape = RoundedCornerShape(Radius.card)
    this
        .background(colors.bgCard, shape)
        .border(Stroke.hairline, colors.strokeCard, shape)
}

// endregion

// region Motion

/**
 * Animation tokens (`tokens.json → motion`). SwiftUI's duration-based springs are mapped to Compose springs with
 * the same response (stiffness = (2π / duration)²) and damping 1 − bounce. Pass `reduceMotion` (see
 * [CrateTheme.reduceMotion]) to get the 0.2 s crossfade instead.
 */
object CrateMotion {
    /** Count ticker: `.snappy(duration: 0.35)`. */
    fun <T> count(reduceMotion: Boolean = false): FiniteAnimationSpec<T> = pick(spring(0.85f, stiffness(0.35f)), reduceMotion)

    /** Chip state: `.snappy(duration: 0.18)`. */
    fun <T> chip(reduceMotion: Boolean = false): FiniteAnimationSpec<T> = pick(spring(0.85f, stiffness(0.18f)), reduceMotion)

    /** Send success morph: `.spring(duration: 0.4, bounce: 0.15)`. */
    fun <T> morph(reduceMotion: Boolean = false): FiniteAnimationSpec<T> = pick(spring(0.85f, stiffness(0.4f)), reduceMotion)

    /** Energy blocks light up with `.easeOut(duration: 0.12)`, optionally delayed (stagger). */
    fun <T> energy(delayMillis: Int = 0, reduceMotion: Boolean = false): FiniteAnimationSpec<T> =
        pick(tween(120, delayMillis, EaseOut), reduceMotion)

    /** Delay between two energy blocks, in milliseconds (20 ms). */
    const val ENERGY_STAGGER_MS = 20

    /** Cover crossfade, 0.25 s. */
    fun <T> cover(reduceMotion: Boolean = false): FiniteAnimationSpec<T> = pick(tween(250, easing = EaseInOut), reduceMotion)

    /** Reduce Motion replacement: a 0.2 s crossfade. */
    fun <T> reduced(): FiniteAnimationSpec<T> = tween(200, easing = EaseInOut)

    /** [spec], or the Reduce Motion crossfade. */
    fun <T> animation(spec: FiniteAnimationSpec<T>, reduceMotion: Boolean): FiniteAnimationSpec<T> = pick(spec, reduceMotion)

    private fun <T> pick(spec: FiniteAnimationSpec<T>, reduceMotion: Boolean): FiniteAnimationSpec<T> =
        if (reduceMotion) reduced() else spec

    private fun stiffness(duration: Float): Float {
        val omega = (2 * Math.PI / duration).toFloat()
        return (omega * omega).coerceAtLeast(Spring.StiffnessVeryLow)
    }
}

/**
 * A count that rolls (slides up when it grows, down when it shrinks) with `CrateMotion.count`, the Compose stand-in
 * for SwiftUI's `.contentTransition(.numericText())`. Crossfades with Reduce Motion. Grouped per locale.
 */
@Composable
fun CountText(value: Int, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current, color: Color = Color.Unspecified) {
    val reduceMotion = CrateTheme.reduceMotion
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            if (reduceMotion) {
                ContentTransform(fadeIn(CrateMotion.reduced()), fadeOut(CrateMotion.reduced()))
            } else {
                val up = targetState > initialState
                (slideInVertically(CrateMotion.count()) { h -> if (up) h else -h } + fadeIn(CrateMotion.count()))
                    .togetherWith(slideOutVertically(CrateMotion.count()) { h -> if (up) -h else h } + fadeOut(CrateMotion.count()))
            }
        },
        label = "count",
    ) { v -> Text(formatCount(v), style = style, color = color, maxLines = 1) }
}

internal fun formatCount(value: Int): String = NumberFormat.getIntegerInstance().format(value)

// endregion

// region Haptics

/** Plays [type] whenever [trigger] changes (not on first composition), like SwiftUI's `.sensoryFeedback(_:trigger:)`. */
@Composable
internal fun HapticOnChange(trigger: Any?, type: HapticFeedbackType = HapticFeedbackType.SegmentTick) {
    val haptic = LocalHapticFeedback.current
    val last = remember { arrayOf(trigger) }
    LaunchedEffect(trigger) {
        if (last[0] != trigger) {
            last[0] = trigger
            haptic.performHapticFeedback(type)
        }
    }
}

// endregion

// region Chip chrome

/** Height of a chip: 32 dp, or 44 dp at accessibility font sizes. */
internal val chipHeight: Dp
    @Composable get() = if (CrateTheme.isAccessibilitySize) Size.chipHeightAX else Size.chipHeight

/** Lines a chip label may use: 1, or 3 at accessibility font sizes. */
internal val chipMaxLines: Int
    @Composable get() = if (CrateTheme.isAccessibilitySize) 3 else 1

/**
 * The shared look of every 32 dp chip: height (44 at accessibility sizes), padding, `Radius.chip`, the chip label
 * font and [foreground] as content colour, an optional 1.5 dp [outline] (1 dp dashed when [dashed]).
 * The 44 dp touch target comes from [CrateTheme]'s minimum touch target size. Use it for app-specific chips.
 */
@Composable
fun ChipChrome(
    fill: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
    outline: Color? = null,
    dashed: Boolean = false,
    horizontalPadding: Dp = Metric.chipPaddingH,
    spacing: Dp = Metric.chipInnerGap,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.chip)
    var m = modifier.heightIn(min = chipHeight).background(fill, shape)
    if (outline != null) {
        m = m.drawBehind {
            val width = (if (dashed) Stroke.hairline else Stroke.outline).toPx()
            val r = Radius.chip.toPx()
            val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(Space.xs.toPx(), (Space.xs - Stroke.hairline).toPx())) else null
            drawRoundRect(
                color = outline,
                topLeft = Offset(width / 2, width / 2),
                size = GeoSize(size.width - width, size.height - width),
                cornerRadius = CornerRadius(r - width / 2),
                style = DrawStroke(width = width, pathEffect = effect),
            )
        }
    }
    CompositionLocalProvider(LocalContentColor provides foreground, LocalTextStyle provides CrateType.chipLabel) {
        Row(
            m.padding(horizontal = horizontalPadding, vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** A chip label: inherits colour and style, one line (three at accessibility sizes), ellipsised. */
@Composable
internal fun ChipText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current, color: Color = Color.Unspecified) {
    Text(text, modifier, color = color, style = style, maxLines = chipMaxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * Press feedback for chips (the iOS `ChipButtonStyle`): scales to 0.97 and draws [pressedOverlay] over the chip
 * while pressed. [onClick] `null` makes it static. Supports an optional long press.
 */
@Composable
internal fun ChipPressable(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    pressedOverlay: Color? = null,
    onLongClick: (() -> Unit)? = null,
    shapeRadius: Dp = Radius.chip,
    content: @Composable (pressed: Boolean) -> Unit,
) {
    if (onClick == null && onLongClick == null) {
        Box(modifier) { content(false) }
        return
    }
    val reduceMotion = CrateTheme.reduceMotion
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduceMotion) 0.97f else 1f, CrateMotion.chip(reduceMotion), label = "press")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
            )
            .drawWithContent {
                drawContent()
                if (pressed && pressedOverlay != null) {
                    drawRoundRect(pressedOverlay, cornerRadius = CornerRadius(shapeRadius.toPx()))
                }
            },
    ) { content(pressed) }
}

// endregion

// region Flow layout

/** Wrapping row for chips: lays children left to right and wraps, `Space.chipGap` apart by default. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(
    modifier: Modifier = Modifier,
    spacing: Dp = Space.chipGap,
    lineSpacing: Dp = Space.chipGap,
    alignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(lineSpacing),
        itemVerticalAlignment = alignment,
        content = content,
    )
}

/** The mono "OR" between genre chips. Hidden from TalkBack. */
@Composable
fun ChipSeparator(text: String = "OR", modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier.clearAndSetSemantics {}, style = CrateType.panelLabel,
        color = CrateTheme.colors.textSecondary,
    )
}

// endregion
