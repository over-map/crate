package com.overmap.crate.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * A tag query (spec §5.5, rules in `docs/query-semantics.md`). Every field is optional; an empty query matches
 * every track.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = QuerySerializer::class)
data class Query(
    /** Style families (`taxonomy.json → families`); part of the "any of" group with genres and styles. */
    @SerialName("families_include") var familiesInclude: List<String> = emptyList(),
    /** Family → canonical styles ticked under it: matches when the effective family is the key **and** the primary style is listed. */
    @SerialName("family_styles") var familyStyles: Map<String, List<String>> = emptyMap(),
    @SerialName("genres_include") var genresInclude: List<String> = emptyList(),
    @SerialName("genres_exclude") var genresExclude: List<String> = emptyList(),
    @SerialName("styles_include") var stylesInclude: List<String> = emptyList(),
    @SerialName("styles_exclude") var stylesExclude: List<String> = emptyList(),
    var bpm: BPMRange? = null,
    var energy: EnergyRange? = null,
    @SerialName("moods_include") var moodsInclude: List<String> = emptyList(),
    @SerialName("moods_exclude") var moodsExclude: List<String> = emptyList(),
    var key: KeyFilter? = null,
    var vocal: Vocal = Vocal.any,
    /** Vibe IDs; the track must be a member of every one (AND). An unknown ID matches nothing. */
    @SerialName("vibes_include") var vibesInclude: List<String> = emptyList(),
    /** Community tags the track must all have (AND), normalised. Tracks with unknown tags fail. */
    @SerialName("tags_include") var tagsInclude: List<String> = emptyList(),
    var limit: Int = defaultLimit,
) {
    /** Inclusive BPM range, optionally also matching half or double tempo. */
    @Serializable
    data class BPMRange(
        var min: Double = 0.0,
        var max: Double = Double.MAX_VALUE,
        @SerialName("half_double") var halfDouble: Boolean = false,
    ) {
        /** True when `bpm` (or, with [halfDouble], `bpm·2` or `bpm/2`) lies in the range. */
        fun contains(bpm: Double): Boolean {
            fun inRange(v: Double) = min <= v && v <= max
            return inRange(bpm) || (halfDouble && (inRange(bpm * 2) || inRange(bpm / 2)))
        }
    }

    /** Inclusive energy range (1–10). */
    @Serializable
    data class EnergyRange(var min: Int = 1, var max: Int = 10) {
        fun contains(energy: Int): Boolean = energy in min..max
    }

    /** A Camelot key, optionally widened to its compatible set. */
    @Serializable
    data class KeyFilter(var camelot: String, var compatible: Boolean = false) {
        /** The Camelot codes this filter accepts. */
        val accepted: Set<String>
            get() {
                val key = Camelot(camelot) ?: return emptySet()
                return if (compatible) key.compatible.map { it.code }.toSet() else setOf(key.code)
            }
    }

    /** Vocal constraint. Unknown values decode as [any]. */
    @Serializable(with = VocalSerializer::class)
    @Suppress("EnumEntryName")
    enum class Vocal { any, vocal, instrumental }

    /** True when no field constrains the result (only [limit] may differ). */
    val isUnconstrained: Boolean
        get() = familiesInclude.isEmpty() && familyStyles.isEmpty() && genresInclude.isEmpty() &&
            genresExclude.isEmpty() && stylesInclude.isEmpty() && stylesExclude.isEmpty() &&
            bpm == null && energy == null && moodsInclude.isEmpty() && moodsExclude.isEmpty() &&
            key == null && vocal == Vocal.any && vibesInclude.isEmpty() && tagsInclude.isEmpty()

    /** This query as JSON (the `query` object of a preset). */
    fun toJson(): String = CrateJSON.json.encodeToString(serializer(), this)

    companion object {
        /** Default number of tracks sent to Now. */
        const val defaultLimit = 100

        /** Decodes a query object; `tags_include` is normalised with the bundled synonyms. */
        fun fromJson(json: String): Query = CrateJSON.json.decodeFromString(serializer(), json)
    }
}

internal object VocalSerializer :
    LenientEnumSerializer<Query.Vocal>("crate.Query.Vocal", Query.Vocal.entries.toTypedArray(), { it.name }, Query.Vocal.any)

/** Normalises `tags_include` on decode (matching normalises again; idempotent). */
@OptIn(ExperimentalSerializationApi::class)
internal object QuerySerializer : JsonTransformingSerializer<Query>(Query.generatedSerializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val o = element as? JsonObject ?: return element
        val tags = o["tags_include"] as? JsonArray ?: return o
        val normalised = tags.map { t ->
            val text = (t as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return o
            Tags.normalise(text)
        }.filter { it.isNotEmpty() }
        return o.replacing("tags_include", JsonArray(normalised.map(::JsonPrimitive)))
    }
}

/** A track's tags after applying the user's override to its `auto` tags. `null` means unknown: never tagged. */
data class EffectiveTags(
    /** The strongest style, which alone decides genre/style matching. */
    var primaryStyle: String? = null,
    var primaryGenre: String? = null,
    var bpm: Double? = null,
    var key: String? = null,
    var energy: Int? = null,
    var moods: Set<String>? = null,
    var vocal: Boolean? = null,
    /** Normalised community tags; `null` when the track has never been tagged. */
    var communityTags: Set<String>? = null,
    /** Effective style family: the override's `family`, else the track's consensus `family`. */
    var family: String? = null,
) {
    companion object {
        /** Mood confidence at or above which a mood is present. */
        const val moodThreshold = 0.5
        /** Vocal probability at or above which a track is vocal. */
        const val vocalThreshold = 0.5
        /** Secondary styles the UI displays: activation ≥ this, top 3, primary excluded. */
        const val styleDisplayThreshold = 0.2

        /** Merges a library track with its override, including its normalised community tags. */
        operator fun invoke(track: Track, override: TrackOverride?, normaliser: TagNormaliser = TagNormaliser.bundled) =
            invoke(auto = track.auto, override = override).apply {
                communityTags = normaliser.trackTags(track)
                family = override?.family.nonEmpty() ?: track.family.nonEmpty()
            }

        /** Merges `auto` with `override` field by field; the override wins. */
        operator fun invoke(auto: AutoTags?, override: TrackOverride?): EffectiveTags {
            val a = auto ?: AutoTags()
            val ov = override ?: TrackOverride()
            val stylesAdd = ov.stylesAdd.orEmpty()
            val stylesRemove = ov.stylesRemove.orEmpty().toSet()
            val genresAdd = ov.genresAdd.orEmpty()
            val genresRemove = ov.genresRemove.orEmpty().toSet()

            var style: String? = stylesAdd.firstOrNull() ?: rankedStyles(a.styles.orEmpty()).firstOrNull {
                it !in stylesRemove && StyleLabel.genre(it) !in genresRemove
            }
            val genre: String?
            if (stylesAdd.isEmpty() && genresAdd.isNotEmpty()) {
                genre = genresAdd.first()
                if (style != null && StyleLabel.genre(style) != genre) style = null
            } else {
                genre = style?.let(StyleLabel::genre)
            }

            val moodsAdd = ov.moodsAdd.orEmpty()
            val moods = if (a.moods == null && moodsAdd.isEmpty()) null else
                (a.moods.orEmpty().filterValues { it >= moodThreshold }.keys + moodsAdd) - ov.moodsRemove.orEmpty().toSet()

            return EffectiveTags(
                primaryStyle = style,
                primaryGenre = genre,
                bpm = ov.bpm ?: a.bpm,
                key = ov.key ?: a.key,
                energy = ov.energy ?: a.energy,
                moods = moods,
                vocal = ov.vocal ?: a.vocal?.let { it >= vocalThreshold },
                family = ov.family.nonEmpty(), // the track's own family is added by invoke(track, override)
            )
        }

        /** Styles by activation, descending; ties broken by canonical label, ascending. */
        fun rankedStyles(styles: Map<String, Double>): List<String> =
            styles.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                .map { it.key }

        private fun String?.nonEmpty(): String? = this?.takeIf { it.isNotEmpty() }
    }
}
