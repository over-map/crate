package com.overmap.crate.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.overmap.crate.core.Camelot
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.Stroke
import com.overmap.crate.ui.theme.camelotColor
import com.overmap.crate.ui.theme.onData
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// region Badge

/** [CamelotBadge] size: tag line 18 dp, small 20, medium 28, large 48 (with key name and confidence). */
enum class CamelotBadgeStyle { TAG_LINE, SMALL, MEDIUM, LARGE }

/**
 * A Camelot key badge: the key's fill (`camelotColor`, same in both modes) and black bold text.
 *
 * @param code A Camelot code ("8A"); `null` (or invalid) draws the outlined "Any key" badge.
 * @param size Badge size.
 * @param showsKeyName Adds the musical key ("A minor") beside the badge. Always on for [CamelotBadgeStyle.LARGE].
 * @param confidence 0–1, shown under the key name at large size ("72% confidence").
 * @param withCompatible Appends the compatible keys chip ("+ 7A 9A 8B").
 */
@Composable
fun CamelotBadge(
    code: String?,
    modifier: Modifier = Modifier,
    size: CamelotBadgeStyle = CamelotBadgeStyle.MEDIUM,
    showsKeyName: Boolean = false,
    confidence: Double? = null,
    withCompatible: Boolean = false,
) {
    val colors = CrateTheme.colors
    val key = code?.let { Camelot(it) }
    val showName = showsKeyName || size == CamelotBadgeStyle.LARGE
    val fontScale = LocalDensity.current.fontScale
    val height: Dp = when (size) {
        CamelotBadgeStyle.TAG_LINE -> Metric.badgeTagLine
        CamelotBadgeStyle.SMALL -> Metric.badgeSmall
        CamelotBadgeStyle.MEDIUM -> Metric.badgeMedium
        CamelotBadgeStyle.LARGE -> Metric.badgeLarge
    } * fontScale
    val compatibleCodes = key?.compatible?.drop(1)?.map { it.code }.orEmpty()
    val label = when {
        key == null -> "Any key"
        else -> buildString {
            append("Key ${key.code}, ${key.name}")
            if (withCompatible) append(", with compatible keys " + compatibleCodes.joinToString(", "))
            if (confidence != null) append(", ${(confidence * 100).roundToInt()} percent confidence")
        }
    }

    Row(
        modifier.clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(if (size == CamelotBadgeStyle.LARGE) Space.s + Space.xxs else Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (key == null) {
            Box(
                Modifier
                    .heightIn(min = height)
                    .border(Stroke.outline, colors.textTertiary, RoundedCornerShape(Radius.badge))
                    .padding(horizontal = Space.s + Space.xxs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Any key",
                    style = if (size == CamelotBadgeStyle.TAG_LINE) TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold) else CrateType.chipLabel,
                    color = colors.textSecondary, maxLines = 1,
                )
            }
            return@Row
        }
        val font = when (size) {
            CamelotBadgeStyle.TAG_LINE -> 11.sp
            CamelotBadgeStyle.SMALL -> 12.sp
            CamelotBadgeStyle.MEDIUM -> 15.sp
            CamelotBadgeStyle.LARGE -> 28.sp
        }
        Box(
            Modifier
                .widthIn(min = height * (if (size == CamelotBadgeStyle.LARGE) 4f / 3 else 1.43f))
                .heightIn(min = height)
                .background(
                    camelotColor(key.code, colors.isDark),
                    RoundedCornerShape(if (size == CamelotBadgeStyle.LARGE) Radius.chip else Radius.badge),
                )
                .padding(horizontal = if (size == CamelotBadgeStyle.TAG_LINE) Space.xs + 1.dp else Space.s - Space.xxs),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                key.code, style = TextStyle(fontSize = font, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = colors.onData, maxLines = 1, softWrap = false,
            )
        }
        if (withCompatible) {
            Box(
                Modifier
                    .heightIn(min = height)
                    .background(colors.fillControl, RoundedCornerShape(Radius.badge))
                    .padding(horizontal = Space.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+ " + compatibleCodes.joinToString(" "),
                    style = CrateType.footnote.copy(fontWeight = FontWeight.SemiBold), color = colors.textPrimary, maxLines = 1,
                )
            }
        }
        if (showName) {
            Column {
                Text(
                    key.name,
                    style = if (size == CamelotBadgeStyle.LARGE) CrateType.headline else CrateType.footnote.copy(fontWeight = FontWeight.Medium),
                    color = if (size == CamelotBadgeStyle.LARGE) colors.textPrimary else colors.textSecondary,
                )
                if (size == CamelotBadgeStyle.LARGE && confidence != null) {
                    Text("${(confidence * 100).roundToInt()}% confidence", style = CrateType.footnote, color = colors.textSecondary)
                }
            }
        }
    }
}

// endregion

// region Wheel

/**
 * The 24-segment Camelot key picker: A (minor) on the inner ring, B (major) on the outer ring, 12 at the top.
 *
 * The selected segment gets a 3 dp `accent` ring. With [compatible] on, the selected key's compatible keys (n±1 on
 * the same ring and the same number on the other ring) stay at full colour and the rest dim to 25 %; with it off
 * only the selected key stays lit. Tapping the selected key clears it (`null` = any key). Each segment is a TalkBack
 * button ("8A, A minor").
 */
@Composable
fun CamelotWheel(
    selection: String?,
    onSelectionChange: (String?) -> Unit,
    compatible: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CrateTheme.colors
    val reduceMotion = CrateTheme.reduceMotion
    val selectedKey = selection?.let { Camelot(it) }
    val currentSelected by rememberUpdatedState(selectedKey)
    val onChange by rememberUpdatedState(onSelectionChange)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    HapticOnChange(selection)

    fun toggle(key: Camelot) = onChange(if (key == currentSelected) null else key.code)

    BoxWithConstraints(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = "Camelot wheel" },
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight)
        val scale = side.value / Metric.WHEEL_REFERENCE
        val alphas = Camelot.all.associateWith { key ->
            val lit = when {
                selectedKey == null -> true
                compatible -> selectedKey.isCompatible(key)
                else -> key == selectedKey
            }
            animateFloatAsState(if (lit) 1f else Metric.WHEEL_DIMMED, CrateMotion.chip(reduceMotion), label = "segment").value
        }
        val labelStyle = TextStyle(
            fontSize = with(density) { (13.dp * scale.coerceAtLeast(0.6f)).toSp() },
            fontWeight = FontWeight.Bold,
            color = colors.onData,
        )

        Canvas(
            Modifier
                .size(side)
                .pointerInput(side) {
                    detectTapGestures { p ->
                        val s = size.width / Metric.WHEEL_REFERENCE
                        val dx = p.x - size.width / 2f
                        val dy = p.y - size.height / 2f
                        val r = hypot(dx, dy) / s
                        val major = when (r) {
                            in Metric.WHEEL_INNER_FROM..Metric.WHEEL_INNER_TO -> false
                            in Metric.WHEEL_OUTER_FROM..Metric.WHEEL_OUTER_TO -> true
                            else -> return@detectTapGestures
                        }
                        val theta = (Math.toDegrees(atan2(dy, dx).toDouble()) + 90 + 360) % 360
                        val n = ((theta / 30).roundToInt() % 12).let { if (it == 0) 12 else it }
                        Camelot(n, major)?.let(::toggle)
                    }
                },
        ) {
            val s = size.minDimension / Metric.WHEEL_REFERENCE
            val c = center
            for (key in Camelot.all) {
                val (from, to) = if (key.isMajor) Metric.WHEEL_OUTER_FROM to Metric.WHEEL_OUTER_TO else Metric.WHEEL_INNER_FROM to Metric.WHEEL_INNER_TO
                val r0 = from * s
                val r1 = to * s
                val mid = -90f + key.number * 30f
                val inset = Math.toDegrees((Metric.wheelGap.toPx() / 2 / r0).toDouble()).toFloat()
                val a0 = mid - 15f + inset
                val sweep = 30f - 2 * inset
                val path = Path().apply {
                    arcTo(Rect(c, r1), a0, sweep, forceMoveTo = true)
                    arcTo(Rect(c, r0), a0 + sweep, -sweep, forceMoveTo = false)
                    close()
                }
                val alpha = alphas.getValue(key)
                drawPath(path, camelotColor(key.code, colors.isDark), alpha = alpha)
                if (key == selectedKey) drawPath(path, colors.accent, style = DrawStroke(Metric.wheelSelectedRing.toPx()))
                val rm = (from + to) / 2 * s
                val angle = mid / 180f * PI.toFloat()
                val layout = measurer.measure(key.code, labelStyle)
                drawText(
                    layout,
                    topLeft = Offset(
                        c.x + rm * cos(angle) - layout.size.width / 2f,
                        c.y + rm * sin(angle) - layout.size.height / 2f,
                    ),
                    alpha = alpha,
                )
            }
        }

        // TalkBack: one button per segment, centred on its label.
        val target = 36.dp * scale.coerceAtLeast(0.6f)
        for (key in Camelot.all) {
            val (from, to) = if (key.isMajor) Metric.WHEEL_OUTER_FROM to Metric.WHEEL_OUTER_TO else Metric.WHEEL_INNER_FROM to Metric.WHEEL_INNER_TO
            val rm = (from + to) / 2 * scale
            val angle = (-90f + key.number * 30f) / 180f * PI.toFloat()
            val selected = key == selectedKey
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (rm * cos(angle)).dp.roundToPx(),
                            (rm * sin(angle)).dp.roundToPx(),
                        )
                    }
                    .size(target)
                    .clearAndSetSemantics {
                        contentDescription = "${key.code}, ${key.name}"
                        role = Role.Button
                        if (selected) this.selected = true
                        onClick(label = null) { toggle(key); true }
                    },
            )
        }

        Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            if (selectedKey != null) {
                Text(selectedKey.code, style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold), color = colors.textPrimary)
                Text(selectedKey.name, style = CrateType.footnote.copy(fontWeight = FontWeight.Medium), color = colors.textSecondary)
            } else {
                Text("Any key", style = CrateType.headline, color = colors.textSecondary)
            }
        }
    }
}

// endregion
