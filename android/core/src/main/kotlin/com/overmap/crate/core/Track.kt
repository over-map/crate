package com.overmap.crate.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Analysis state of a library track. Unknown future values decode as [error] rather than failing the library. */
@Serializable(with = TrackStatusSerializer::class)
@Suppress("EnumEntryName")
enum class TrackStatus(val rawValue: String) {
    tagged("tagged"), noPreview("no_preview"), pending("pending"), error("error");
}

internal object TrackStatusSerializer :
    LenientEnumSerializer<TrackStatus>("crate.TrackStatus", TrackStatus.entries.toTypedArray(), { it.rawValue }, TrackStatus.error)

/** Automatic tags written by the pipeline (`tracks[id].auto` in library.json). Every field is optional. */
@Serializable
data class AutoTags(
    /** Genre scores, derived from the styles. */
    var genres: Map<String, Double>? = null,
    /** Style activations keyed by canonical label (`Genre---Style`). */
    var styles: Map<String, Double>? = null,
    var bpm: Double? = null,
    @SerialName("bpm_conf") var bpmConf: Double? = null,
    /** Octave candidates for the BPM (e.g. `[138, 69]`). */
    @SerialName("bpm_candidates") var bpmCandidates: List<Double>? = null,
    /** Camelot code such as `8A`. */
    var key: String? = null,
    @SerialName("key_conf") var keyConf: Double? = null,
    /** Library-relative energy decile, 1–10. */
    var energy: Int? = null,
    /** Mood confidences keyed by mood name. */
    var moods: Map<String, Double>? = null,
    /** Vocal probability, 0–1. */
    var vocal: Double? = null,
)

/** Where a track's analysis came from (`tracks[id].source`). */
@Serializable
data class TrackSource(
    @SerialName("deezer_id") var deezerID: Long? = null,
    @SerialName("analysed_at") var analysedAt: String? = null,
    @SerialName("model_version") var modelVersion: String? = null,
)

/** The evidence behind a track's style family (`tracks[id].consensus`), e.g. "3 of 4 sources agree". */
@Serializable
data class Consensus(
    /** The winning family. */
    var family: String? = null,
    /** Votes for the winning family. */
    var votes: Int? = null,
    /** Number of sources that voted. */
    var sources: Int? = null,
    /** Whether the audio model's family won; `null` when there was no audio vote. */
    @SerialName("audio_agrees") var audioAgrees: Boolean? = null,
    /** Families each source voted for (`audio`, `discogs`, `lastfm`, `beatport`, …). */
    @SerialName("by_source") var bySource: Map<String, List<String>> = emptyMap(),
)

/** One Liked Song in library.json. Identity fields come from Spotify; tags live in [auto]. */
@Serializable
data class Track(
    var uri: String? = null,
    var isrc: String? = null,
    var title: String? = null,
    var artists: List<String>? = null,
    @SerialName("duration_ms") var durationMS: Int? = null,
    var cover: String? = null,
    @SerialName("liked_at") var likedAt: String? = null,
    /** Analysis status; `pending` when missing. */
    var status: TrackStatus = TrackStatus.pending,
    var source: TrackSource? = null,
    var auto: AutoTags? = null,
    /** Community tags from every outside source (raw; normalise with [TagNormaliser]). `null` = unknown, `[]` = none. */
    var tags: List<String>? = null,
    /** Which source gave which (normalised) tag: `lastfm`, `discogs`, `beatport` → tags. Display only. */
    @SerialName("tag_sources") var tagSources: Map<String, List<String>>? = null,
    /** Style family chosen by the pipeline's consensus (one of `taxonomy.json → families`). */
    var family: String? = null,
    /** Evidence for [family]. */
    var consensus: Consensus? = null,
) {
    /** The Spotify URI, falling back to `spotify:track:<id>`. */
    fun spotifyURI(id: String): String = uri ?: "spotify:track:$id"
}

/** `data/library.json`: every Liked Song with its automatic tags. Written by Actions only. */
@Serializable
data class LibraryFile(
    var schema: Int = CrateJSON.supportedSchema,
    @SerialName("generated_at") var generatedAt: String? = null,
    /** Tracks keyed by Spotify track ID. */
    var tracks: Map<String, Track> = emptyMap(),
)
