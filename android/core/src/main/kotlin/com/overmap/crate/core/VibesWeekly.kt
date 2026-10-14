package com.overmap.crate.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** A user-named mood defined by example (`data/vibes.json → vibes[id]`). See `docs/query-semantics.md#vibes`. */
@Serializable
data class Vibe(
    var name: String = "",
    /** Icon name (user-picked; null = the default for the vibe ID, else "tag"). */
    var icon: String? = null,
    /** Seed track IDs. */
    var seeds: List<String> = emptyList(),
    /** Artist names; every track by one of them (case-insensitive) is also a seed. */
    var artists: List<String> = emptyList(),
    /** Community tags; every track with any of them (normalised) is also a seed. */
    @SerialName("seed_tags") var seedTags: List<String> = emptyList(),
    /** Track IDs never in the vibe (they are not replaced). */
    var exclude: List<String> = emptyList(),
    /** Fraction of the tracks with a vector kept by similarity, 0–1. */
    var share: Double = defaultShare,
) {
    companion object {
        /** Share of the tracks with a vector that a vibe keeps when `share` is missing. */
        const val defaultShare = 0.2
    }
}

/** A vibe with its ID, as listed by [VibesFile.ordered]. */
data class VibeEntry(val id: String, val vibe: Vibe)

/** `data/vibes.json`. Written by the app only (read-modify-write through [GitHubStore.updateJSON]). */
@Serializable
data class VibesFile(
    var schema: Int = CrateJSON.supportedSchema,
    /** Vibes keyed by ID (the ID is what `vibes_include` lists). */
    var vibes: Map<String, Vibe> = emptyMap(),
    /** Vibe IDs in Build order (set by the vibes manager). */
    var order: List<String> = emptyList(),
) {
    /** Vibes in Build order: those in [order] first (unknown and repeated IDs skipped), then the rest by name (ties by ID). */
    val ordered: List<VibeEntry>
        get() {
            val seen = mutableSetOf<String>()
            val out = mutableListOf<VibeEntry>()
            for (id in order) {
                if (id in seen) continue
                vibes[id]?.let { out += VibeEntry(id, it); seen += id }
            }
            out += vibes.filterKeys { it !in seen }.entries
                .sortedWith(compareBy<Map.Entry<String, Vibe>> { it.value.name }.thenBy { it.key })
                .map { VibeEntry(it.key, it.value) }
            return out
        }
}

/** One suggestion in the Daily mix, with its tags so the app can filter the mix. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = WeeklyTrackSerializer::class)
data class WeeklyTrack(
    /** Spotify track ID; `""` when missing (the dictionary form supplies it from the key). */
    var id: String = "",
    /** Pipeline candidate key (used for feedback). */
    var key: String? = null,
    var uri: String? = null,
    var isrc: String? = null,
    var title: String? = null,
    var artists: List<String>? = null,
    @SerialName("duration_ms") var durationMS: Int? = null,
    var cover: String? = null,
    /** Ranking score (similarity blended with the feedback model). */
    var score: Double? = null,
    var auto: AutoTags? = null,
    /** `taste` or `discovery`; `null` when missing or unknown. */
    var kind: Kind? = null,
    /** Style family the track was picked for. */
    var family: String? = null,
    /** One-line "why this track?". */
    var reason: String? = null,
    /** Sound-similarity vector (same 32-d space as `vectors.bin`), for "close to … which you liked". */
    var vector: List<Double>? = null,
) {
    /** Why the track was picked. */
    @Serializable
    @Suppress("EnumEntryName")
    enum class Kind {
        /** Similar to the user's own tracks in a family. */
        taste,
        /** From community charts for a family, without a seed from the library. */
        discovery,
    }
}

/** Unknown `kind` values decode as `null`. */
@OptIn(ExperimentalSerializationApi::class)
internal object WeeklyTrackSerializer : JsonTransformingSerializer<WeeklyTrack>(WeeklyTrack.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val o = element as? JsonObject ?: return element
        val kind = o["kind"]?.stringOrNull
        return if (kind != null && WeeklyTrack.Kind.entries.none { it.name == kind }) JsonObject(o - "kind") else o
    }
}

/** `data/weekly.json`: today's Crate Daily mix (the file keeps its old name). Written by Actions only. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = WeeklyFileSerializer::class)
data class WeeklyFile(
    var schema: Int = CrateJSON.supportedSchema,
    /** ISO day the mix was built for, e.g. `2026-10-13`. Missing in files written before the daily mix. */
    var date: String? = null,
    /** The same ISO day as `date` in daily files; an ISO week label (`2026-W42`) in older weekly files. */
    var week: String? = null,
    @SerialName("generated_at") var generatedAt: String? = null,
    @SerialName("playlist_id") var playlistID: String? = null,
    /** Number of candidates the mix was ranked from. */
    var candidates: Int? = null,
    var tracks: List<WeeklyTrack> = emptyList(),
) {
    /**
     * The calendar day the mix is for, in `zone`: `date`, else `week` when it is an ISO day (`2026-10-13`), else the
     * local day of `generatedAt`. `null` when none is usable (e.g. an old file with only `week: "2026-W42"`).
     */
    fun mixDay(zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
        (parseISODay(date) ?: parseISODay(week))?.let { return it }
        val stamp = generatedAt?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(stamp).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
    }

    companion object {
        /** Parses `YYYY-MM-DD` as a calendar day. Anything else returns `null`. */
        fun parseISODay(text: String?): LocalDate? {
            val parts = text?.split("-") ?: return null
            if (parts.size != 3 || parts[0].length != 4 || parts[1].length != 2 || parts[2].length != 2) return null
            val (y, m, d) = parts.map { p -> p.takeIf { s -> s.all { it in '0'..'9' } }?.toInt() ?: return null }
            return runCatching { LocalDate.of(y, m, d) }.getOrNull()
        }
    }
}

/** Accepts `week` as a string or a number, and `tracks` as an array or a dictionary keyed by ID. */
@OptIn(ExperimentalSerializationApi::class)
internal object WeeklyFileSerializer : JsonTransformingSerializer<WeeklyFile>(WeeklyFile.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        var o = element as? JsonObject ?: return element
        o = o.replacing("date", o["date"]?.takeIf { it.stringOrNull != null })
        val week = o["week"] as? JsonPrimitive
        o = o.replacing("week", when {
            week == null || week is JsonNull -> null
            week.isString -> week
            else -> week.longOrNull?.let { JsonPrimitive(it.toString()) }
        })
        o = o.replacing("candidates", (o["candidates"] as? JsonPrimitive)?.takeIf { !it.isString && it.intOrNull != null })
        val tracks = o["tracks"]
        val trackList = ListSerializer(WeeklyTrack.serializer())
        o = o.replacing("tracks", when {
            tracks is JsonArray && decodes(trackList, tracks) -> tracks
            tracks is JsonObject && decodes(MapSerializer(String.serializer(), WeeklyTrack.serializer()), tracks) ->
                JsonArray(tracks.keys.sorted().map { id ->
                    val t = tracks.getValue(id) as JsonObject
                    if ((t["id"]?.stringOrNull ?: "").isEmpty()) t.replacing("id", JsonPrimitive(id)) else t
                })
            else -> null
        })
        return o
    }
}
