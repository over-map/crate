package com.overmap.crate.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.overmap.crate.core.Consensus
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.CrateType
import com.overmap.crate.ui.theme.Family
import com.overmap.crate.ui.theme.Size
import com.overmap.crate.ui.theme.Space
import com.overmap.crate.ui.theme.Stroke

// region Model

/** The four genre sources, in the fixed display order of the [ConfidenceLine]. */
enum class ConfidenceSource(val rawValue: String) {
    AUDIO("audio"), DISCOGS("discogs"), LASTFM("lastfm"), BEATPORT("beatport");

    /** Name in the vote table ("Last.fm"). */
    val displayName: String
        get() = when (this) {
            AUDIO -> "Audio"
            DISCOGS -> "Discogs"
            LASTFM -> "Last.fm"
            BEATPORT -> "Beatport"
        }

    /** Name inside a sentence ("Only audio has an opinion"). */
    val inlineName: String get() = if (this == AUDIO) "audio" else displayName

    companion object {
        fun of(rawValue: String): ConfidenceSource? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** A family id from library.json, or `null` when it isn't one of the 17 families. */
private fun familyOrNull(raw: String?): Family? = Family.entries.firstOrNull { it != Family.NONE && it.data == raw }

/**
 * How many sources agree on a track's family. Build it from core's [Consensus] with [ConfidenceSummary.from], or
 * directly.
 *
 * @property family The family shown on the track (the user's pick when [isOverride]).
 * @property votes Votes for the winning family.
 * @property sources Sources that voted.
 * @property audioAgrees Whether the audio model voted for the winner; `null` when it had no opinion.
 * @property bySource Families each source voted for. Missing or empty = no opinion.
 * @property isOverride `true` when the user set the family by hand.
 * @property sourcesFamily What the sources said ("Set by you · sources said House"). Defaults to [family].
 */
@Immutable
data class ConfidenceSummary(
    val family: Family,
    val votes: Int,
    val sources: Int,
    val audioAgrees: Boolean? = null,
    val bySource: Map<ConfidenceSource, List<Family>> = emptyMap(),
    val isOverride: Boolean = false,
    val sourcesFamily: Family? = null,
) {
    /** Per-source dot state, in [ConfidenceSource] order. */
    enum class Vote { AGREES, DISAGREES, NO_OPINION }

    /** The family the sources are compared against. */
    internal val consensusFamily: Family get() = sourcesFamily ?: family

    /** Dot state of one source. */
    fun vote(of: ConfidenceSource): Vote {
        val families = bySource[of]
        if (families.isNullOrEmpty()) return Vote.NO_OPINION
        return if (consensusFamily in families) Vote.AGREES else Vote.DISAGREES
    }

    /** No majority for the winner. */
    val isSplit: Boolean get() = sources > 1 && votes * 2 <= sources

    /** Only one source had an opinion. */
    val isSingleSource: Boolean get() = sources == 1

    /** The plain-words line (`tokens.json → confidence.copy`). */
    val copy: String
        get() = when {
            isOverride -> ConfidenceCopy.OVERRIDE + " · sources said ${consensusFamily.displayName}"
            isSingleSource -> {
                val source = ConfidenceSource.entries.firstOrNull { vote(it) != Vote.NO_OPINION } ?: ConfidenceSource.AUDIO
                ConfidenceCopy.single(source.inlineName)
            }
            isSplit -> ConfidenceCopy.SPLIT + " · " + ConfidenceCopy.say(votes, sources, consensusFamily.displayName)
            votes == sources -> ConfidenceCopy.all(sources)
            else -> ConfidenceCopy.majority(votes, sources)
        }

    /** TalkBack: the copy plus dissenting sources ("3 of 4 sources agree: audio says Techno"). */
    val accessibilityText: String
        get() {
            val dissent = ConfidenceSource.entries.mapNotNull { source ->
                val first = bySource[source]?.firstOrNull()
                if (vote(source) == Vote.DISAGREES && first != null) "${source.inlineName} says ${first.displayName}" else null
            }
            if (dissent.isEmpty() || isOverride) return copy
            return copy + ": " + dissent.joinToString(", ")
        }

    companion object {
        /** From library.json's consensus. [override] is the user's family, if any. `null` without a family. */
        fun from(consensus: Consensus, override: Family? = null): ConfidenceSummary? {
            val family = familyOrNull(consensus.family) ?: return null
            val by = buildMap {
                for ((key, values) in consensus.bySource) {
                    val source = ConfidenceSource.of(key) ?: continue
                    put(source, values.mapNotNull(::familyOrNull))
                }
            }
            return ConfidenceSummary(
                family = override ?: family,
                votes = consensus.votes ?: 0,
                sources = consensus.sources ?: by.size,
                audioAgrees = consensus.audioAgrees,
                bySource = by,
                isOverride = override != null,
                sourcesFamily = if (override != null) family else null,
            )
        }
    }
}

/** `tokens.json → confidence.copy`. The generator doesn't export these yet, so they live here (as on iOS). */
object ConfidenceCopy {
    fun all(n: Int): String = "All $n sources agree"
    fun majority(a: Int, n: Int): String = "$a of $n sources agree"
    const val SPLIT = "Sources disagree"
    fun say(a: Int, n: Int, family: String): String = "$a of $n say $family"
    const val FOOTER = "The genre with most votes wins. Override it in Edit tags."
    fun single(source: String): String = "Only $source has an opinion"
    const val OVERRIDE = "Set by you"
}

// endregion

// region Dots

/**
 * One source dot: filled 7 dp `textPrimary` (agrees), 1.5 dp `textSecondary` ring (votes for another family), or a
 * 6 × 1.5 `textTertiary` dash (no opinion).
 */
@Composable
internal fun ConfidenceDot(vote: ConfidenceSummary.Vote) {
    val colors = CrateTheme.colors
    Box(Modifier.size(Metric.confidenceDot), contentAlignment = Alignment.Center) {
        when (vote) {
            ConfidenceSummary.Vote.AGREES -> Box(Modifier.size(Metric.confidenceDot).background(colors.textPrimary, CircleShape))
            ConfidenceSummary.Vote.DISAGREES ->
                Box(Modifier.size(Metric.confidenceDot).border(Stroke.outline, colors.textSecondary, CircleShape))
            ConfidenceSummary.Vote.NO_OPINION -> Box(
                Modifier.size(Metric.confidenceDash, Stroke.outline).background(colors.textTertiary, RoundedCornerShape(50)),
            )
        }
    }
}

// endregion

// region Line

/**
 * "How many sources agree": four dots in fixed order Audio · Discogs · Last.fm · Beatport, then the copy. Sits
 * directly under the genre / style chips. With [expandable], a tap reveals the vote table (source, family mini
 * chip, "differs" / "entered by you").
 */
@Composable
fun ConfidenceLine(
    summary: ConfidenceSummary,
    modifier: Modifier = Modifier,
    expandable: Boolean = true,
    expanded: Boolean = false,
    footer: String? = ConfidenceCopy.FOOTER,
) {
    val colors = CrateTheme.colors
    val reduceMotion = CrateTheme.reduceMotion
    var isExpanded by rememberSaveable { mutableStateOf(expanded) }
    val rotation by animateFloatAsState(if (isExpanded) 180f else 0f, CrateMotion.chip(reduceMotion), label = "chevron")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        var line = Modifier
            .fillMaxWidth()
            .heightIn(min = if (expandable) Size.minTarget else Metric.confidenceLineHeight)
        if (expandable) {
            val toggle = { isExpanded = !isExpanded }
            line = line
                .clearAndSetSemantics {
                    contentDescription = summary.accessibilityText
                    stateDescription = if (isExpanded) "Expanded" else "Collapsed"
                    role = Role.Button
                    onClick(label = if (isExpanded) "Hides the votes" else "Shows each source's vote") { toggle(); true }
                }
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = toggle)
        } else {
            line = line.clearAndSetSemantics { contentDescription = summary.accessibilityText }
        }
        Row(line, horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            if (summary.isOverride) {
                Icon(Icons.Filled.Edit, null, Modifier.size(12.dp), tint = colors.textSecondary)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Metric.confidenceDotGap), verticalAlignment = Alignment.CenterVertically) {
                    ConfidenceSource.entries.forEach { ConfidenceDot(summary.vote(it)) }
                }
            }
            Text(
                summary.copy, Modifier.weight(1f, fill = expandable),
                style = CrateType.footnote.copy(fontWeight = FontWeight.Medium), color = colors.textSecondary,
            )
            if (expandable) {
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(14.dp).rotate(rotation), tint = colors.textTertiary)
            }
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(CrateMotion.chip(reduceMotion)) + expandVertically(CrateMotion.chip(reduceMotion)),
            exit = fadeOut(CrateMotion.chip(reduceMotion)) + shrinkVertically(CrateMotion.chip(reduceMotion)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                VoteTable(summary)
                if (footer != null) Text(footer, style = CrateType.footnote, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun VoteTable(summary: ConfidenceSummary) {
    val colors = CrateTheme.colors
    Column(Modifier.fillMaxWidth().crateCard()) {
        if (summary.isOverride) {
            VoteRow(
                dot = { Icon(Icons.Filled.Edit, null, Modifier.size(10.dp), tint = colors.textSecondary) },
                name = "You", families = listOf(summary.family), note = "entered by you",
            )
            HorizontalDivider(color = colors.separator)
        }
        ConfidenceSource.entries.forEachIndexed { index, source ->
            val vote = summary.vote(source)
            VoteRow(
                dot = { ConfidenceDot(vote) }, name = source.displayName, families = summary.bySource[source].orEmpty(),
                note = if (vote == ConfidenceSummary.Vote.DISAGREES) "differs" else null,
            )
            if (index < ConfidenceSource.entries.size - 1) HorizontalDivider(color = colors.separator)
        }
    }
}

@Composable
private fun VoteRow(dot: @Composable () -> Unit, name: String, families: List<Family>, note: String?) {
    val colors = CrateTheme.colors
    val description = buildList {
        add(name)
        if (families.isEmpty()) add("No opinion") else families.forEach { add("Genre ${it.displayName}") }
        note?.let(::add)
    }.joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description }
            .heightIn(min = Size.minTarget)
            .padding(horizontal = Space.m),
        horizontalArrangement = Arrangement.spacedBy(Space.s + Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(Space.s), contentAlignment = Alignment.Center) { dot() }
        Text(name, Modifier.widthIn(min = Metric.voteSourceColumn), style = CrateType.secondary, color = colors.textPrimary)
        if (families.isEmpty()) {
            Text("No opinion", style = CrateType.footnote, color = colors.textTertiary)
        } else {
            families.forEach { FamilyMiniChip(it) }
        }
        Spacer(Modifier.weight(1f))
        if (note != null) Text(note, style = CrateType.footnote, color = colors.textSecondary)
    }
}

// endregion

// region List marker

/** Which [ConfidenceMarker] to draw. */
enum class ConfidenceMarkerKind {
    /** Sources disagree (no majority): a half-filled circle. */
    SPLIT,

    /** Only one source had an opinion: a dotted circle. */
    SINGLE;

    companion object {
        /** The marker the summary calls for (nothing for agreement, a majority or an override). */
        fun of(summary: ConfidenceSummary?): ConfidenceMarkerKind? = when {
            summary == null || summary.isOverride -> null
            summary.isSingleSource -> SINGLE
            summary.isSplit -> SPLIT
            else -> null
        }
    }
}

/**
 * The list marker before the style in a tag line: a half-filled circle (SF `circle.lefthalf.filled`) when sources
 * disagree, a dotted circle (`circle.dotted`) when only one source had an opinion, nothing for `null`. 11 dp,
 * scaled with the font size, `textSecondary`.
 */
@Composable
fun ConfidenceMarker(kind: ConfidenceMarkerKind?, modifier: Modifier = Modifier) {
    if (kind == null) return
    val color = CrateTheme.colors.textSecondary
    val size = Metric.listMarker * LocalDensity.current.fontScale
    Canvas(
        modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = if (kind == ConfidenceMarkerKind.SPLIT) ConfidenceCopy.SPLIT else "One source only" },
    ) {
        val stroke = (1.2.dp).toPx()
        val r = this.size.minDimension / 2 - stroke / 2
        when (kind) {
            ConfidenceMarkerKind.SPLIT -> {
                drawCircle(color, r, style = DrawStroke(stroke))
                drawArc(
                    color, startAngle = 90f, sweepAngle = 180f, useCenter = true,
                    topLeft = Offset(center.x - r, center.y - r), size = GeoSize(2 * r, 2 * r),
                )
            }
            ConfidenceMarkerKind.SINGLE -> {
                val circumference = (2 * Math.PI * r).toFloat()
                val dash = circumference / 16
                drawCircle(color, r, style = DrawStroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash * 0.55f, dash * 0.45f))))
            }
        }
    }
}

/** The marker for a summary (nothing for agreement, a majority or an override). */
@Composable
fun ConfidenceMarker(summary: ConfidenceSummary?, modifier: Modifier = Modifier) =
    ConfidenceMarker(ConfidenceMarkerKind.of(summary), modifier)

// endregion
