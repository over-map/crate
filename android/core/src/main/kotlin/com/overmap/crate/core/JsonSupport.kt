package com.overmap.crate.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** A string enum whose unknown values decode as [fallback] instead of failing. */
internal open class LenientEnumSerializer<E : Enum<E>>(
    name: String,
    private val values: Array<E>,
    private val raw: (E) -> String,
    private val fallback: E,
) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(raw(value))
    override fun deserialize(decoder: Decoder): E {
        val text = decoder.decodeString()
        return values.firstOrNull { raw(it) == text } ?: fallback
    }
}

/** ISO-8601 instants, written to whole seconds (`2026-10-13T10:00:00Z`) like Foundation's `.iso8601`. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("crate.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) =
        encoder.encodeString(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS)))
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

internal fun JsonObject.replacing(key: String, value: JsonElement?): JsonObject =
    JsonObject(if (value == null) this - key else this + (key to value))

internal val JsonElement.stringOrNull: String?
    get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun ByteArray?.contentEq(other: ByteArray?): Boolean =
    if (this == null || other == null) this === other else contentEquals(other)
