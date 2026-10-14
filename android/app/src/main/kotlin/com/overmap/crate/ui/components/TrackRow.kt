package com.overmap.crate.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.overmap.crate.core.Camelot
import com.overmap.crate.core.Track
import com.overmap.crate.core.TrackStatus
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Radius
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import kotlin.math.roundToInt

// region Cover

/**
 * Album artwork from a URL, with the striped placeholder while it loads (or when there is none). The image
 * crossfades in over 0.25 s (instantly with Reduce Motion). Decorative: hidden from TalkBack.
 *
 * @param url Artwork URL (Spotify), or `null` for the placeholder.
 * @param size Side length: `Size.coverRow` (48), `Size.coverStrip` (38) or `Size.coverDetail` (240).
 * @param radius `Radius.cover` (4) or `Radius.coverLarge` (10).
 * @param tint Optional colour mixed into the placeholder stripes (the track's family).
 */
@Composable
fun CoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp = Size.coverRow,
    radius: Dp = Radius.cover,
    tint: Color? = null,
) {
    val reduceMotion = CrateTheme.reduceMotion
    val context = LocalContext.current
    Box(modifier.size(size).clip(RoundedCornerShape(radius)).clearAndSetSemantics {}) {
        StripedPlaceholder(tint, Modifier.fillMaxSize())
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).crossfade(if (reduceMotion) 0 else 250).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 45° stripes, 4 dp wide, in two control fills (optionally tinted). */
@Composable
internal fun StripedPlaceholder(tint: Color?, modifier: Modifier = Modifier) {
    val colors = CrateTheme.colors
    Canvas(modifier) {
        drawRect(colors.fillControl)
        val stripe = Metric.coverStripe.toPx()
        val h = size.height
        val path = Path()
        var x = -h
        while (x < size.width + h) {
            path.moveTo(x, h)
            path.lineTo(x + h, 0f)
            path.lineTo(x + h + stripe, 0f)
            path.lineTo(x + stripe, h)
            path.close()
            x += stripe * 2
        }
        drawPath(path, colors.fillControlActive)
        if (tint != null) drawRect(tint.copy(alpha = 0.18f), Offset.Zero, size)
    }
}

// endregion

// region Compact tag line

/** Values that can carry the "edited" dot in a [CompactTagLine]. */
enum class CompactTagLineField { STYLE, BPM, KEY, ENERGY }

/**
 * The one-line track summary under the artist: `[marker] Style · [≈]BPM · [key] · energy · vibes`.
 *
 * Vibes are dropped first when space runs out, then the style truncates; BPM, key and energy never truncate. At
 * accessibility font sizes the line wraps (energy and vibes move to a second line). Edited values get a dot. Used by
 * [TrackRow] and the share sheet.
 */
@Composable
fun CompactTagLine(
    style: String?,
    bpm: Int?,
    key: String?,
    energy: Int?,
    modifier: Modifier = Modifier,
    marker: ConfidenceMarkerKind? = null,
    bpmApprox: Boolean = false,
    vibes: List<String> = emptyList(),
    edited: Set<CompactTagLineField> = emptySet(),
) {
    val colors = CrateTheme.colors
    val camelot = key?.let { Camelot(it) }
    val description = buildList {
        if (marker == ConfidenceMarkerKind.SPLIT) add(ConfidenceCopy.SPLIT)
        if (marker == ConfidenceMarkerKind.SINGLE) add("One source only")
        if (style != null) add(style + if (CompactTagLineField.STYLE in edited) ", edited" else "")
        if (bpm != null) add((if (bpmApprox) "about $bpm BPM" else "$bpm BPM") + if (CompactTagLineField.BPM in edited) ", edited" else "")
        if (camelot != null) add("key ${camelot.code}, ${camelot.name}")
        if (energy != null) add("energy $energy")
        if (vibes.isNotEmpty()) add(vibes.joinToString(", "))
    }.joinToString(", ")

    val dot: @Composable () -> Unit = { Text("·", style = CrateType.tagLine, color = colors.textTertiary, maxLines = 1) }
    val editedDot: @Composable (CompactTagLineField) -> Unit = { field ->
        if (field in edited) {
            Box(Modifier.offset(x = -Space.xxs).size(Metric.editedDot).background(colors.textPrimary, CircleShape))
        }
    }
    val head: @Composable () -> Unit = {
        if (marker != null) ConfidenceMarker(marker)
        if (style != null) {
            Text(style, Modifier.layoutId(STYLE_ID), style = CrateType.tagLine, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            editedDot(CompactTagLineField.STYLE)
        }
        if (bpm != null) {
            if (style != null) dot()
            BPMText(bpm, bpmApprox, style = CrateType.tagLine, color = colors.textSecondary)
            editedDot(CompactTagLineField.BPM)
        }
        if (camelot != null) {
            if (style != null || bpm != null) dot()
            CamelotBadge(camelot.code, size = CamelotBadgeStyle.TAG_LINE)
            editedDot(CompactTagLineField.KEY)
        }
    }
    val tail: @Composable (leadingDot: Boolean) -> Unit = { leadingDot ->
        if (energy != null) {
            if (leadingDot && (style != null || bpm != null || camelot != null)) dot()
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                EnergyMicroMeter(energy.coerceIn(1, 10))
                Text(energy.toString(), style = CrateType.tagLine, color = colors.textSecondary, maxLines = 1)
            }
            editedDot(CompactTagLineField.ENERGY)
        }
        if (vibes.isNotEmpty()) {
            Row(Modifier.layoutId(VIBES_ID), horizontalArrangement = Arrangement.spacedBy(Space.chipGap), verticalAlignment = Alignment.CenterVertically) {
                if (leadingDot || energy != null) dot()
                Text(vibes.joinToString(", "), style = CrateType.tagLine, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    val semantics = modifier.clearAndSetSemantics { contentDescription = description }
    if (CrateTheme.isAccessibilitySize) {
        Column(semantics, verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            TagLineRow { head() }
            TagLineRow { tail(false) }
        }
    } else {
        TagLineRow(semantics) {
            head()
            tail(true)
        }
    }
}

/** The "No preview · tag by hand" line for tracks without analysis. */
@Composable
fun CompactTagLineNoPreview(modifier: Modifier = Modifier) {
    Text(
        "No preview · tag by hand",
        modifier.clearAndSetSemantics { contentDescription = "No preview, tag by hand" },
        style = CrateType.tagLine, color = CrateTheme.colors.textSecondary, maxLines = 1,
    )
}

/** From a core track: primary style, BPM (≈ when `bpm_conf < 0.5`), key and energy; "No preview" without analysis. */
@Composable
fun CompactTagLine(
    track: Track,
    styleName: String?,
    modifier: Modifier = Modifier,
    vibes: List<String> = emptyList(),
    marker: ConfidenceMarkerKind? = null,
) {
    val auto = track.auto
    if (auto == null || track.status == TrackStatus.noPreview) {
        CompactTagLineNoPreview(modifier)
        return
    }
    CompactTagLine(
        style = styleName, bpm = auto.bpm?.roundToInt(), key = auto.key, energy = auto.energy, modifier = modifier,
        marker = marker, bpmApprox = (auto.bpmConf ?: 1.0) < 0.5, vibes = vibes,
    )
}

private const val STYLE_ID = "style"
private const val VIBES_ID = "vibes"

/**
 * One line of the tag line, 6 dp gaps, vertically centred. Everything keeps its natural width except the style
 * (truncates) and the vibes group, which is dropped entirely when the whole line doesn't fit (SwiftUI's
 * `ViewThatFits` with and without vibes).
 */
@Composable
private fun TagLineRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = Space.chipGap.roundToPx()
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val fixed = measurables.filter { it.layoutId != STYLE_ID && it.layoutId != VIBES_ID }.associateWith { it.measure(loose) }
        val styleM = measurables.firstOrNull { it.layoutId == STYLE_ID }
        val vibesM = measurables.firstOrNull { it.layoutId == VIBES_ID }
        val fixedW = fixed.values.sumOf { it.width }
        val count = measurables.size
        val styleIdeal = styleM?.maxIntrinsicWidth(constraints.maxHeight) ?: 0
        val vibesIdeal = vibesM?.maxIntrinsicWidth(constraints.maxHeight) ?: 0
        val allGaps = gap * (count - 1).coerceAtLeast(0)
        val showVibes = vibesM != null && fixedW.toLong() + styleIdeal + vibesIdeal + allGaps <= maxW
        val gaps = gap * ((if (showVibes || vibesM == null) count else count - 1) - 1).coerceAtLeast(0)
        val styleW = (maxW.toLong() - fixedW - gaps - (if (showVibes) vibesIdeal else 0)).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val styleP = styleM?.measure(Constraints(maxWidth = minOf(styleIdeal, styleW), maxHeight = constraints.maxHeight))
        val vibesP = if (showVibes) vibesM?.measure(loose) else null
        val placeables = measurables.mapNotNull { m ->
            when (m) {
                styleM -> styleP
                vibesM -> vibesP
                else -> fixed[m]
            }
        }
        val width = (placeables.sumOf { it.width } + gap * (placeables.size - 1).coerceAtLeast(0)).coerceAtMost(maxW)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width.coerceAtLeast(constraints.minWidth), height.coerceAtLeast(constraints.minHeight)) {
            var x = 0
            placeables.forEach { p ->
                p.placeRelative(x, (height - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}

// endregion

// region Track row

/**
 * A track in a list: 48 dp cover, title, artist, compact tag line, and a trailing [accessory] (chevron, heart,
 * "?" …). At least 64 dp tall. The texts form one TalkBack element; the accessory stays separate.
 */
@Composable
fun TrackRow(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
    coverURL: String? = null,
    coverTint: Color? = null,
    tagLine: (@Composable () -> Unit)? = null,
    accessory: @Composable RowScope.() -> Unit = { TrackRowChevron() },
) {
    val colors = CrateTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Size.coverRow + Space.l)
            .padding(vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(coverURL, size = Size.coverRow, radius = Radius.cover, tint = coverTint)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            Text(title, style = CrateType.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, style = CrateType.secondary, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            tagLine?.invoke()
        }
        accessory()
    }
}

/** The trailing chevron of a navigable row (`textTertiary`). Hidden from TalkBack. */
@Composable
fun TrackRowChevron(modifier: Modifier = Modifier) {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
        modifier.size(18.dp).clearAndSetSemantics {}, tint = CrateTheme.colors.textTertiary,
    )
}

/** The Weekly heart toggle, 44 dp target. Selection tick on change; TalkBack "Like", selected when liked. */
@Composable
fun LikeButton(isLiked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = CrateTheme.colors
    val reduceMotion = CrateTheme.reduceMotion
    HapticOnChange(isLiked)
    Box(
        modifier
            .size(Size.minTarget)
            .clearAndSetSemantics {
                contentDescription = "Like"
                if (isLiked) selected = true
                button(onClick)
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(isLiked, animationSpec = CrateMotion.chip(reduceMotion), label = "like") { liked ->
            Icon(
                if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, null,
                Modifier.size(22.dp), tint = if (liked) colors.accent else colors.textSecondary,
            )
        }
    }
}

// endregion
