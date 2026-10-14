package com.overmap.crate.screens.tracks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.overmap.crate.app.AppModel
import com.overmap.crate.core.EffectiveTags
import com.overmap.crate.core.Tags
import com.overmap.crate.core.TaxonomyFile
import com.overmap.crate.core.Track
import com.overmap.crate.core.TrackOverride
import com.overmap.crate.core.TrackStatus
import com.overmap.crate.core.Vibe
import com.overmap.crate.core.VibeEntry
import com.overmap.crate.ui.components.CompactTagLine
import com.overmap.crate.ui.components.CompactTagLineField
import com.overmap.crate.ui.components.CompactTagLineNoPreview
import com.overmap.crate.ui.components.ConfidenceMarkerKind
import com.overmap.crate.ui.components.ConfidenceSummary
import com.overmap.crate.ui.theme.CrateTheme
import com.overmap.crate.ui.theme.Family
import com.overmap.crate.ui.theme.VibeSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Everything the track screens show about one track, derived from the model (library + override + vibes).
 * Pure value (port of iOS `TrackInfo`); build it with [TrackInfo.of].
 */
class TrackInfo(
    val id: String,
    val track: Track,
    val override: TrackOverride?,
    val taxonomy: TaxonomyFile,
) {
    val effective: EffectiveTags = EffectiveTags(track = track, override = override)

    val title: String get() = track.title ?: "Untitled"
    val artist: String get() = track.artists.orEmpty().joinToString(", ")
    val coverURL: String? get() = track.cover
    val uri: String get() = track.spotifyURI(id)
    val isAnalysed: Boolean get() = track.auto != null && track.status != TrackStatus.noPreview

    /** The effective family (override, else consensus) as a hue. */
    val family: Family get() = Family.of(effective.family)
    /** The family the pipeline chose, before the user's override. */
    val sourcesFamily: Family? get() = Family.of(track.family).takeIf { it != Family.NONE }
    val overrideFamily: Family? get() = override?.family?.takeIf { it.isNotEmpty() }?.let { Family.of(it) }?.takeIf { it != Family.NONE }

    fun familyName(f: Family): String =
        if (f == Family.NONE) f.displayName else taxonomy.displayName(f.data).replaceFirstChar { it.uppercase() }

    fun styleName(canonical: String): String = taxonomy.label(canonical).styleDisplay

    // Styles

    /** The strongest style first, then up to 3 secondary styles (user-added, then auto ≥ 0.2), all canonical. */
    val displayStyles: List<String> get() = displayStyles(track, override, effective.primaryStyle)

    /** Styles shown before any edit (to tell which chips the user added). */
    val autoDisplayStyles: List<String>
        get() = displayStyles(track, null, EffectiveTags(auto = track.auto, override = null).primaryStyle)

    fun isStyleEdited(s: String): Boolean {
        val added = override?.stylesAdd ?: return false
        if (s !in added) return false
        val auto = autoDisplayStyles
        if (s !in auto) return true
        return added.first() == s && auto.firstOrNull() != s
    }

    // Numbers

    val bpm: Double? get() = effective.bpm
    val bpmEdited: Boolean get() = override?.bpm != null
    /** "≈" only for the detected value (a BPM the user typed is certain). */
    val bpmApprox: Boolean get() = !bpmEdited && (track.auto?.bpmConf ?: 1.0) < 0.5
    val key: String? get() = effective.key
    val keyEdited: Boolean get() = override?.key != null
    val energy: Int? get() = effective.energy
    val energyEdited: Boolean get() = override?.energy != null

    val editedFields: Set<CompactTagLineField>
        get() = buildSet {
            if (bpmEdited) add(CompactTagLineField.BPM)
            if (keyEdited) add(CompactTagLineField.KEY)
            if (energyEdited) add(CompactTagLineField.ENERGY)
            if (!override?.stylesAdd.isNullOrEmpty() || !override?.family.isNullOrEmpty()) add(CompactTagLineField.STYLE)
        }

    // Confidence

    val confidence: ConfidenceSummary?
        get() = track.consensus?.let { ConfidenceSummary.from(it, overrideFamily) }

    /** List marker: only for "no majority" and "one source" (nothing for overrides). */
    val markerKind: ConfidenceMarkerKind? get() = ConfidenceMarkerKind.of(confidence)

    // Library

    val likedDate: Instant? get() = track.likedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }

    val addedText: String
        get() {
            val d = likedDate ?: return "In library"
            val f = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()).withZone(ZoneId.systemDefault())
            return "In library · added " + f.format(d)
        }

    /** Normalised community tags in source order, without duplicates. */
    val communityTags: List<String>
        get() {
            val seen = mutableSetOf<String>()
            return track.tags.orEmpty().map { Tags.normalise(it) }.filter { it.isNotEmpty() && seen.add(it) }
        }

    /** Which outside sources gave `tag` (normalised): "LFM" Last.fm, "DSC" Discogs, "BP" Beatport, in that order. */
    fun tagSources(tag: String): List<Pair<String, String>> {
        val sources = track.tagSources.orEmpty()
        return listOf(Triple("lastfm", "LFM", "Last.fm"), Triple("discogs", "DSC", "Discogs"), Triple("beatport", "BP", "Beatport"))
            .mapNotNull { (key, code, name) ->
                if (sources[key].orEmpty().any { Tags.normalise(it) == tag }) code to name else null
            }
    }

    companion object {
        fun of(id: String, model: AppModel): TrackInfo? {
            val track = model.index.tracks[id] ?: return null
            return TrackInfo(id, track, model.index.overrides[id], model.snapshot.taxonomy)
        }

        fun displayStyles(track: Track, override: TrackOverride?, primary: String?): List<String> {
            val auto = track.auto
            val removed = override?.stylesRemove.orEmpty().toSet()
            val out = mutableListOf<String>().apply { primary?.let(::add) }
            val secondary = mutableListOf<String>()
            for (s in override?.stylesAdd.orEmpty()) if (s !in out && s !in secondary) secondary += s
            val styles = auto?.styles.orEmpty()
            for (s in EffectiveTags.rankedStyles(styles)) {
                if ((styles[s] ?: 0.0) >= EffectiveTags.styleDisplayThreshold && s !in removed && s !in out && s !in secondary) {
                    secondary += s
                }
            }
            return out + secondary.take(3)
        }
    }
}

/** The compact tag line of a track row. */
@Composable
fun TrackInfo.TagLine(vibes: List<String>) {
    if (!isAnalysed) {
        CompactTagLineNoPreview()
    } else {
        CompactTagLine(
            style = effective.primaryStyle?.let(::styleName), bpm = bpm?.let { Math.round(it).toInt() }, key = key,
            energy = energy, marker = markerKind, bpmApprox = bpmApprox, vibes = vibes, edited = editedFields,
        )
    }
}

/** The "edited" mark next to a value the user corrected. Hidden from TalkBack. */
@Composable
fun TrackEditedDot(modifier: Modifier = Modifier) {
    Box(modifier.size(5.dp).background(CrateTheme.colors.textPrimary, CircleShape))
}

// AppModel helpers for the track screens (iOS AppModel+Tracks / TrackPresentation extensions)

/** Vibes (in Build order) whose members include `trackID`. */
fun AppModel.tracksVibes(containing: String): List<VibeEntry> =
    snapshot.vibes.ordered.filter { index.vibeMembers[it.id]?.contains(containing) == true }

/** The vibe's symbol: the user's pick, else the default for its ID, else `tag`. */
fun AppModel.tracksVibeSymbol(id: String, vibe: Vibe): String = vibe.icon ?: VibeSymbols.defaults[id] ?: "tag"

/** True when the track has a sound embedding, so "More like this" can work. */
fun AppModel.canSendMoreLikeThis(trackID: String): Boolean = snapshot.vectors?.contains(trackID) == true

/**
 * "More like this → Now": the 100 library tracks that sound closest to `trackID`, shuffled, replace Now. Progress,
 * success and error go through `sendState` (same as Build's Send).
 */
suspend fun AppModel.sendMoreLikeThis(trackID: String) {
    val vectors = snapshot.vectors ?: run { sendState = AppModel.SendState.Error(NO_SOUND); return }
    val ids = runCatching { vectors.moreLikeThis(seed = trackID, count = 100, candidates = index.ids.toSet()) }.getOrDefault(emptyList())
    val uris = ids.mapNotNull { id -> index.tracks[id]?.spotifyURI(id) }
    if (uris.isEmpty()) { sendState = AppModel.SendState.Error(NO_SOUND); return }
    sendURIs(uris)
}

private const val NO_SOUND = "No sound data for this track yet."

/** The override fields Edit track manages. `null` / empty = automatic. */
data class TrackOverrideDraft(
    val family: String? = null,
    val stylesAdd: List<String> = emptyList(),
    val stylesRemove: List<String> = emptyList(),
    val bpm: Double? = null,
    val key: String? = null,
    val energy: Int? = null,
    val vocal: Boolean? = null,
) {
    fun apply(o: TrackOverride): TrackOverride = o.copy(
        family = family,
        stylesAdd = stylesAdd.ifEmpty { null },
        stylesRemove = stylesRemove.ifEmpty { null },
        bpm = bpm, key = key, energy = energy, vocal = vocal,
    )

    companion object {
        fun from(o: TrackOverride?) = TrackOverrideDraft(
            family = o?.family?.takeIf { it.isNotEmpty() },
            stylesAdd = o?.stylesAdd.orEmpty(),
            stylesRemove = o?.stylesRemove.orEmpty(),
            bpm = o?.bpm, key = o?.key, energy = o?.energy, vocal = o?.vocal,
        )
    }
}
