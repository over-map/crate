package com.overmap.crate.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.serializer

/**
 * CrateKit (Android): the shared core of the Crate app and widget.
 *
 * Data files are decoded through [CrateJSON] (schema guard); querying lives in [EffectiveTags], [Query] and
 * [QueryEngine]; services are [SpotifyAuth], [SpotifyClient], [NowWriter] and [GitHubStore].
 */
object CrateKit {
    /** Repository paths of the data files. */
    object Path {
        const val library = "data/library.json"
        const val overrides = "data/overrides.json"
        const val presets = "data/presets.json"
        const val state = "data/state.json"
        const val weekly = "data/weekly.json"
        const val health = "data/health.json"
        const val taxonomy = "data/taxonomy.json"
        const val vibes = "data/vibes.json"
        const val vectors = "data/vectors.bin"
        const val vectorsIndex = "data/vectors.idx.json"
    }
}

/** Errors raised while reading Crate data files. */
sealed class CrateDataError(message: String) : Exception(message) {
    /** The file was written by a newer version of Crate than this app understands. */
    data class UnsupportedSchema(val file: String, val found: Int, val supported: Int) : CrateDataError(
        "$file uses data schema $found, but this app only understands up to $supported. Update the app."
    )

    val errorDescription: String get() = message ?: ""
}

/** Decoding and encoding of the JSON files in `data/`, with the schema guard applied. */
object CrateJSON {
    /** The newest data schema this version of CrateKit understands. */
    const val supportedSchema = 1

    /**
     * The lenient JSON configuration every Crate type expects: unknown keys ignored, `null` or missing values
     * fall back to defaults, nulls are omitted on encode, defaults are encoded.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val pretty: Json = Json(json) {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /**
     * Decodes `data` with `serializer`, refusing files whose top-level `schema` is newer than [supportedSchema].
     * A missing `schema` is treated as the current one. Top-level arrays (legacy `presets.json`) skip the check.
     * @param file The file name, used in the error message.
     */
    fun <T> decode(serializer: KSerializer<T>, data: ByteArray, file: String): T {
        val element = json.parseToJsonElement(data.decodeToString())
        val schema = ((element as? JsonObject)?.get("schema") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (schema != null && schema > supportedSchema) {
            throw CrateDataError.UnsupportedSchema(file, schema, supportedSchema)
        }
        return json.decodeFromJsonElement(serializer, element)
    }

    inline fun <reified T> decode(data: ByteArray, file: String): T = decode(serializer<T>(), data, file)

    /** Encodes `value` deterministically (sorted keys, no escaped slashes, pretty-printed), as the pipeline does. */
    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        pretty.encodeToString(JsonElement.serializer(), sortKeys(json.encodeToJsonElement(serializer, value)))
            .encodeToByteArray()

    inline fun <reified T> encode(value: T): ByteArray = encode(serializer<T>(), value)

    private fun sortKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sortKeys(it.value) })
        is JsonArray -> JsonArray(element.map(::sortKeys))
        else -> element
    }
}
