package com.overmap.crate.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.overmap.crate.ui.theme.CrateType
import kotlin.math.roundToInt

/**
 * A BPM numeral, with "≈" before it when the beat is uncertain (`bpm_conf < 0.5`).
 *
 * The prefix takes the numeral's colour (never a colour of its own). At hero and large sizes it is regular weight
 * and followed by a thin space; in smaller styles it is tight ("≈139"). TalkBack reads "about 139 BPM".
 *
 * @param value The detected BPM (filters keep using it even when uncertain).
 * @param approx Show "≈".
 * @param style Usually `CrateType.numeralHero`, `numeralLarge`, `numeralMedium` or `tagLine`.
 * @param color Text colour; unspecified inherits `LocalContentColor`.
 * @param largeNumeral Forces the hero/large treatment of "≈"; by default detected from [style].
 */
@Composable
fun BPMText(
    value: Int,
    approx: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = CrateType.numeralHero,
    color: Color = Color.Unspecified,
    largeNumeral: Boolean? = null,
) {
    val large = largeNumeral ?: (style == CrateType.numeralHero || style == CrateType.numeralLarge)
    val text = buildAnnotatedString {
        if (approx) {
            if (large) {
                withStyle(SpanStyle(fontWeight = FontWeight.Normal)) { append("≈") }
                append(' ')
            } else {
                append("≈")
            }
        }
        append(value.toString())
    }
    Text(
        text,
        modifier.clearAndSetSemantics { contentDescription = if (approx) "about $value BPM" else "$value BPM" },
        color = color,
        style = style.copy(fontFeatureSettings = "tnum"),
        maxLines = 1,
        softWrap = false,
    )
}

/** Convenience for a core track: `approx` when `confidence < 0.5`. */
@Composable
fun BPMText(
    value: Double,
    confidence: Double?,
    modifier: Modifier = Modifier,
    style: TextStyle = CrateType.numeralHero,
    color: Color = Color.Unspecified,
) = BPMText(value.roundToInt(), (confidence ?: 1.0) < 0.5, modifier, style, color)
