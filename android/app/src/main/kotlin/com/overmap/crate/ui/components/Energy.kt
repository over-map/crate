package com.overmap.crate.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.energyColor
import com.overmap.crate.ui.theme.fillControlStrong

// region Display meter

/** Size of an [EnergyMeter]. */
enum class EnergyMeterStyle {
    /** The numeral and 10 blocks (18 dp, energy ramp, unlit `fillControl`), for track detail. */
    DISPLAY,

    /** A 10-tick micro meter (2 × 9 dp ticks in the level's colour) and the value, for the tag line. */
    MICRO,
}

/** A track's energy (1–10, clamped) as a meter plus the numeral. TalkBack: "Energy 7 out of 10". */
@Composable
fun EnergyMeter(
    level: Int,
    modifier: Modifier = Modifier,
    style: EnergyMeterStyle = EnergyMeterStyle.DISPLAY,
    numeralStyle: TextStyle = CrateType.numeralHero,
) {
    val colors = CrateTheme.colors
    val value = level.coerceIn(1, 10)
    val semantics = modifier.clearAndSetSemantics { contentDescription = "Energy $value out of 10" }
    when (style) {
        EnergyMeterStyle.DISPLAY -> Row(
            semantics,
            horizontalArrangement = Arrangement.spacedBy(Space.m + Space.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CountText(value, style = numeralStyle, color = colors.textPrimary)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Metric.displayBlockGap)) {
                for (i in 1..10) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(Metric.displayBlockHeight)
                            .background(
                                if (i <= value) energyColor(i, colors.isDark) else colors.fillControl,
                                RoundedCornerShape(Metric.displayBlockRadius),
                            ),
                    )
                }
            }
        }
        EnergyMeterStyle.MICRO -> Row(
            semantics,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EnergyMicroMeter(value)
            Text(value.toString(), style = CrateType.tagLine, color = colors.textSecondary, maxLines = 1)
        }
    }
}

/** Just the 10 ticks of the micro meter (no numeral), scaled with the font size. Hidden from TalkBack. */
@Composable
fun EnergyMicroMeter(level: Int, modifier: Modifier = Modifier) {
    val colors = CrateTheme.colors
    val value = level.coerceIn(1, 10)
    val tickHeight = Metric.microTickHeight * LocalDensity.current.fontScale
    Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(Metric.microTickGap)) {
        for (i in 1..10) {
            Box(
                Modifier
                    .size(Metric.microTickWidth, tickHeight)
                    .background(
                        if (i <= value) energyColor(value, colors.isDark) else colors.fillControlStrong,
                        RoundedCornerShape(Metric.microTickWidth / 2),
                    ),
            )
        }
    }
}

// endregion

// region Range input

/** Constants of [EnergyRangeControl]. */
object EnergyRangeControl {
    /** The whole scale; equal to "Any". */
    val any: IntRange = 1..10
}

/**
 * The Build energy filter: 10 blocks, 26 dp tall (44 at accessibility sizes).
 *
 * Every block is a real clickable: a tap picks just that level (tap it again for Any). A horizontal drag across
 * the blocks sets a range; the drag only starts after horizontal touch slop, so a vertical swipe still scrolls the
 * enclosing sheet or list. `1..10` means Any: blocks are unlit and numbered. Lit blocks use the energy ramp and
 * animate in with `easeOut(0.12)` staggered 20 ms. Every change plays a selection tick. TalkBack reads "Energy, from
 * 6 to 10 out of 10" and offers Raise / Lower minimum / maximum and Any energy actions.
 */
@Composable
fun EnergyRangeControl(range: IntRange, onRangeChange: (IntRange) -> Unit, modifier: Modifier = Modifier) {
    val colors = CrateTheme.colors
    val reduceMotion = CrateTheme.reduceMotion
    val isAny = range == EnergyRangeControl.any
    val blockHeight = if (CrateTheme.isAccessibilitySize) Size.minTarget else Size.energyBlock
    val current by rememberUpdatedState(range)
    val onChange by rememberUpdatedState(onRangeChange)
    HapticOnChange(range)

    fun set(next: IntRange) { if (next != current) onChange(next) }
    fun setLower(v: Int) { val lo = v.coerceIn(1, current.last); set(lo..current.last) }
    fun setUpper(v: Int) { val hi = v.coerceIn(current.first, 10); set(current.first..hi) }

    Row(
        modifier
            .fillMaxWidth()
            .height(blockHeight)
            .clearAndSetSemantics {
                contentDescription = "Energy"
                stateDescription = if (isAny) "Any" else "from ${range.first} to ${range.last} out of 10"
                customActions = listOf(
                    CustomAccessibilityAction("Raise minimum") { setLower(current.first + 1); true },
                    CustomAccessibilityAction("Lower minimum") { setLower(current.first - 1); true },
                    CustomAccessibilityAction("Raise maximum") { setUpper(current.last + 1); true },
                    CustomAccessibilityAction("Lower maximum") { setUpper(current.last - 1); true },
                    CustomAccessibilityAction("Any energy") { set(EnergyRangeControl.any); true },
                )
            }
            .pointerInput(Unit) {
                fun index(x: Float): Int = if (size.width <= 0) 1 else ((x / size.width) * 10).toInt().plus(1).coerceIn(1, 10)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val anchor = index(down.position.x)
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (drag != null) {
                        set(minOf(anchor, index(drag.position.x))..maxOf(anchor, index(drag.position.x)))
                        horizontalDrag(drag.id) { change ->
                            val i = index(change.position.x)
                            set(minOf(anchor, i)..maxOf(anchor, i))
                            change.consume()
                        }
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(Metric.inputBlockGap),
    ) {
        for (i in 1..10) {
            val lit = !isAny && i in range
            val fill by animateColorAsState(
                if (lit) energyColor(i, colors.isDark) else colors.fillControl,
                if (reduceMotion) CrateMotion.reduced() else CrateMotion.energy(delayMillis = (i - 1) * CrateMotion.ENERGY_STAGGER_MS),
                label = "energyBlock",
            )
            val numberAlpha by animateFloatAsState(if (isAny) 1f else 0f, CrateMotion.energy(reduceMotion = reduceMotion), label = "energyNumber")
            Box(
                Modifier
                    .weight(1f)
                    .height(blockHeight)
                    .background(fill, RoundedCornerShape(Radius.block))
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        // A tap on the only lit block clears back to Any.
                        set(if (current == i..i) EnergyRangeControl.any else i..i)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    i.toString(),
                    Modifier.alpha(numberAlpha),
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

// endregion
