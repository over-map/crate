package com.overmap.crate.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Stroke
import com.overmap.crate.ui.theme.thumb
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Thumb { LOWER, UPPER }

/**
 * A dual-thumb range slider with 1-step detents (BPM, 60–200 by default).
 *
 * - Track: `fillControlActive`, 4 dp; the selected part is filled with [tint] (`bpm`). No fill when [range]
 *   equals [bounds] (unconstrained).
 * - Thumbs: 28 dp white discs, 34 dp while dragging.
 * - Narrow ranges: when the thumbs would overlap, each becomes a half-capsule *outside* the range so both stay
 *   operable (e.g. 136–142).
 * - [ghosts] (half/double time) are drawn on the track at 45 % opacity.
 * - Gestures: a press within a thumb's 44 dp target grows it; the value only moves once the finger has passed
 *   horizontal touch slop, so a vertical swipe starting on a thumb still scrolls the enclosing sheet.
 * - Haptics: a selection tick on every step. TalkBack: one adjustable element per thumb ("Minimum BPM", "136"),
 *   inside a container that reads "BPM from 136 to 142".
 */
@Composable
fun RangeSlider(
    range: IntRange,
    onRangeChange: (IntRange) -> Unit,
    modifier: Modifier = Modifier,
    bounds: IntRange = 60..200,
    ghosts: List<IntRange> = emptyList(),
    tint: Color = CrateTheme.colors.bpm,
    accessibilityName: String = "BPM",
) {
    val colors = CrateTheme.colors
    val reduceMotion = CrateTheme.reduceMotion
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf<Thumb?>(null) }
    val current by rememberUpdatedState(range)
    val onChange by rememberUpdatedState(onRangeChange)
    val currentBounds by rememberUpdatedState(bounds)
    HapticOnChange(range)

    fun set(which: Thumb, newValue: Int) {
        val r = current
        val b = currentBounds
        when (which) {
            Thumb.LOWER -> {
                val v = newValue.coerceIn(b.first, r.last)
                if (v != r.first) onChange(v..r.last)
            }
            Thumb.UPPER -> {
                val v = newValue.coerceIn(r.first, b.last)
                if (v != r.last) onChange(r.first..v)
            }
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(Size.minTarget)
            .semantics { contentDescription = "$accessibilityName from ${range.first} to ${range.last}" },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val radiusPx = with(density) { (Size.thumb / 2).toPx() }
        val span = (bounds.last - bounds.first).coerceAtLeast(1)
        fun x(value: Int): Float = radiusPx + (value - bounds.first).toFloat() / span * (widthPx - 2 * radiusPx).coerceAtLeast(0f)
        fun valueAt(px: Float): Int {
            val usable = (widthPx - 2 * radiusPx).coerceAtLeast(1f)
            val t = ((px - radiusPx) / usable).coerceIn(0f, 1f)
            return bounds.first + (t * span).roundToInt()
        }
        val narrow = with(density) { (x(range.last) - x(range.first)).toDp() } < Size.minTarget

        Canvas(Modifier.fillMaxSize()) {
            val h = Metric.trackHeight.toPx()
            val top = size.height / 2 - h / 2
            drawRoundRect(colors.fillControlActive, Offset(0f, top), GeoSize(size.width, h), CornerRadius(h / 2))
            fun segment(r: IntRange, alpha: Float) {
                val lo = x(r.first.coerceAtLeast(bounds.first))
                val hi = x(r.last.coerceAtMost(bounds.last))
                drawRect(tint.copy(alpha = tint.alpha * alpha), Offset(lo, top), GeoSize((hi - lo).coerceAtLeast(Stroke.outline.toPx()), h))
            }
            ghosts.forEach { segment(it, Metric.DIMMED) }
            if (range != bounds) segment(range, 1f)
        }

        for (which in Thumb.entries) {
            val value = if (which == Thumb.LOWER) range.first else range.last
            val thumbSize by animateDpAsState(
                if (dragging == which) Metric.thumbDragging else Size.thumb, CrateMotion.chip(reduceMotion), label = "thumb",
            )
            val sizePx = with(density) { thumbSize.toPx() }
            val center = x(value)
            val leading = if (narrow) (if (which == Thumb.LOWER) center - sizePx else center) else center - sizePx / 2
            val half = thumbSize / 2
            val inner: Dp = Metric.halfThumbInnerRadius
            val shape = when {
                !narrow -> RoundedCornerShape(50)
                which == Thumb.LOWER -> RoundedCornerShape(topStart = half, bottomStart = half, topEnd = inner, bottomEnd = inner)
                else -> RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = half, bottomEnd = half)
            }
            val name = if (which == Thumb.LOWER) "Minimum $accessibilityName" else "Maximum $accessibilityName"
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(leading.roundToInt(), 0) }
                    .size(thumbSize)
                    .shadow(if (dragging == which) 5.dp else 3.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                    .background(colors.thumb, shape)
                    .semantics {
                        contentDescription = name
                        stateDescription = value.toString()
                        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), bounds.first.toFloat()..bounds.last.toFloat(), steps = (span - 1).coerceAtLeast(0))
                        setProgress { target -> set(which, target.roundToInt()); true }
                    },
            )
        }

        // One gesture handler for both thumbs, so the grab offset and the narrow half-capsules are handled alike.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(narrow, widthPx, bounds) {
                    val target = Size.minTarget.toPx() / 2
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val lo = x(current.first)
                        val hi = x(current.last)
                        // Narrow: each half-capsule sits outside the range, so split at the midpoint.
                        val which = when {
                            narrow -> if (down.position.x < (lo + hi) / 2) Thumb.LOWER else Thumb.UPPER
                            abs(down.position.x - lo) <= abs(down.position.x - hi) -> Thumb.LOWER
                            else -> Thumb.UPPER
                        }
                        val thumbCenter = if (which == Thumb.LOWER) lo else hi
                        val visualCenter = if (narrow) thumbCenter + (if (which == Thumb.LOWER) -radiusPx else radiusPx) else thumbCenter
                        if (abs(down.position.x - visualCenter) > target) return@awaitEachGesture
                        dragging = which
                        val grab = down.position.x - thumbCenter
                        try {
                            val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                            if (slop != null) {
                                set(which, valueAt(slop.position.x - grab))
                                horizontalDrag(slop.id) { change ->
                                    set(which, valueAt(change.position.x - grab))
                                    change.consume()
                                }
                            }
                        } finally {
                            dragging = null
                        }
                    }
                },
        )
    }
}
