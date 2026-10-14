package com.overmap.crate.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/** The user's corrections for one track (`overrides.json → tracks[id]`). Each set field wins over `auto`. */
@Serializable
data class TrackOverride(
    var bpm: Double? = null,
    var key: String? = null,
    var energy: Int? = null,
    var vocal: Boolean? = null,
    @SerialName("moods_add") var moodsAdd: List<String>? = null,
    @SerialName("moods_remove") var moodsRemove: List<String>? = null,
    /** The first element becomes the track's primary style. */
    @SerialName("styles_add") var stylesAdd: List<String>? = null,
    @SerialName("styles_remove") var stylesRemove: List<String>? = null,
    @SerialName("genres_add") var genresAdd: List<String>? = null,
    @SerialName("genres_remove") var genresRemove: List<String>? = null,
    /** Style family chosen by the user; wins over the pipeline's consensus. */
    var family: String? = null,
    @SerialName("edited_at") var editedAt: String? = null,
) {
    /** True when the override changes nothing (and can be dropped from the file). */
    val isEmpty: Boolean
        get() = bpm == null && key == null && energy == null && vocal == null &&
            moodsAdd.isNullOrEmpty() && moodsRemove.isNullOrEmpty() &&
            stylesAdd.isNullOrEmpty() && stylesRemove.isNullOrEmpty() &&
            genresAdd.isNullOrEmpty() && genresRemove.isNullOrEmpty() && family.isNullOrEmpty()
}

/** `data/overrides.json`. Written by the app only. */
@Serializable
data class OverridesFile(
    var schema: Int = CrateJSON.supportedSchema,
    /** Overrides keyed by Spotify track ID. */
    var tracks: Map<String, TrackOverride> = emptyMap(),
)

/** A saved query with its identity on the Build screen and in widgets. */
@Serializable
data class Preset(
    var id: String,
    var name: String = "",
    /** Icon name for the preset tile. */
    var icon: String = "bookmark",
    /** Preset colour name (`ink, red, amber, lime, teal, blue, violet, pink`). */
    var color: String = "ink",
    var query: Query = Query(),
    /** Shown in the medium widget (max 4 are used). */
    @SerialName("in_widget") var inWidget: Boolean = false,
)

/**
 * `data/presets.json`. Written by the app only. Both the bare-array form and the object form
 * `{"schema": 1, "now_playlist_id": "…", "presets": […]}` decode; encoding always uses the object form.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = PresetsFileSerializer::class)
data class PresetsFile(
    var schema: Int = CrateJSON.supportedSchema,
    /** The Now playlist ID when the app created or found it. */
    @SerialName("now_playlist_id") var nowPlaylistID: String? = null,
    var presets: List<Preset> = emptyList(),
) {
    /** Presets shown in the medium widget: the first four with `in_widget`. */
    val widgetPresets: List<Preset> get() = presets.filter { it.inWidget }.take(4)
}

@OptIn(ExperimentalSerializationApi::class)
internal object PresetsFileSerializer : JsonTransformingSerializer<PresetsFile>(PresetsFile.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) JsonObject(mapOf("presets" to element)) else element
}

/** `data/state.json`: playlist IDs and sync bookkeeping. Written by Actions only; the app reads it. */
@Serializable
data class StateFile(
    var schema: Int = CrateJSON.supportedSchema,
    @SerialName("now_playlist_id") var nowPlaylistID: String? = null,
    @SerialName("weekly_playlist_id") var weeklyPlaylistID: String? = null,
    /** ISO-8601 time of the last successful sync. */
    @SerialName("last_sync") var lastSync: String? = null,
)

/** `data/taxonomy.json`: the genre → style vocabulary and optional display aliases. Written by Actions only. */
@Serializable
data class TaxonomyFile(
    var schema: Int = CrateJSON.supportedSchema,
    /** Canonical genre → its canonical style labels (`Genre---Style`). */
    var genres: Map<String, List<String>> = emptyMap(),
    /** Optional user-facing renames, keyed by canonical genre or style label. */
    var aliases: Map<String, String> = emptyMap(),
    /** Style families in display order (`techno`, `house`, …), the values of `tracks[id].family`. */
    var families: List<String> = emptyList(),
    /** UI names for families, sentence case (`breaks & bass` → `Breaks & bass`). */
    @SerialName("family_names") var familyNames: Map<String, String> = emptyMap(),
) {
    /** The UI name of a family, falling back to its ID. */
    fun displayName(family: String): String = familyNames[family] ?: family

    /** Canonical genres, sorted. */
    val genreNames: List<String> get() = genres.keys.sorted()

    /** The genre a style belongs to, from the vocabulary, else from the label prefix. */
    fun genre(ofStyle: String): String =
        genres.entries.firstOrNull { ofStyle in it.value }?.key ?: StyleLabel(ofStyle).genre

    /** The display label for a style, using [aliases]. */
    fun label(forStyle: String): StyleLabel = StyleLabel(forStyle, aliases)
}

/** Overall health level shown on the Library tab. Unknown values decode as [warning]. */
@Serializable(with = HealthLevelSerializer::class)
@Suppress("EnumEntryName")
enum class HealthLevel { healthy, warning, broken }

internal object HealthLevelSerializer :
    LenientEnumSerializer<HealthLevel>("crate.HealthLevel", HealthLevel.entries.toTypedArray(), { it.name }, HealthLevel.warning)

/** One active problem, in plain words, with the runbook step that fixes it. */
@Serializable
data class HealthProblem(
    var id: String? = null,
    var level: HealthLevel? = null,
    var message: String,
    /** Runbook anchor, e.g. `docs/runbook.md#spotify-token`. */
    var runbook: String? = null,
)

/** Expiry of one credential (GitHub PAT, Spotify refresh token, …). */
@Serializable
data class CredentialExpiry(
    var name: String,
    @SerialName("expires_at") var expiresAt: String? = null,
    @SerialName("days_left") var daysLeft: Int? = null,
)

/** `data/health.json`, written daily by `health.yml`. Every field is optional. */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = HealthFileSerializer::class)
data class HealthFile(
    var schema: Int = CrateJSON.supportedSchema,
    @SerialName("checked_at") var checkedAt: String? = null,
    var status: HealthLevel? = null,
    @SerialName("last_nightly_success") var lastNightlySuccess: String? = null,
    /** Share of tracks with tags, 0–1. */
    var coverage: Double? = null,
    var problems: List<HealthProblem> = emptyList(),
    var credentials: List<CredentialExpiry> = emptyList(),
) {
    /** The level to display: the explicit status, else derived from the problems. */
    val effectiveLevel: HealthLevel
        get() = status ?: when {
            problems.any { it.level == HealthLevel.broken } -> HealthLevel.broken
            problems.isEmpty() -> HealthLevel.healthy
            else -> HealthLevel.warning
        }
}

/** Malformed `problems` or `credentials` decode as empty lists. */
@OptIn(ExperimentalSerializationApi::class)
internal object HealthFileSerializer : JsonTransformingSerializer<HealthFile>(HealthFile.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        var o = element as? JsonObject ?: return element
        o = o.replacing("problems", o["problems"]?.takeIf { decodes(ListSerializer(HealthProblem.serializer()), it) })
        o = o.replacing("credentials", o["credentials"]?.takeIf { decodes(ListSerializer(CredentialExpiry.serializer()), it) })
        return o
    }
}

internal fun <T> decodes(serializer: kotlinx.serialization.KSerializer<T>, element: JsonElement): Boolean =
    runCatching { CrateJSON.json.decodeFromJsonElement(serializer, element) }.isSuccess
