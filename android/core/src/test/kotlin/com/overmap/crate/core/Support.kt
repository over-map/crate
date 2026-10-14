package com.overmap.crate.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Locations of the shared files in the repository (`crate/spec`), passed by Gradle as `crate.spec`. */
object Repo {
    val spec: File = File(System.getProperty("crate.spec") ?: error("crate.spec system property not set"))
    fun spec(name: String): ByteArray = File(spec, name).readBytes()
}

/** A scripted HTTP transport that records every request. */
class FakeTransport(private val handler: (HTTPRequest, Int) -> HTTPResponse) : HTTPTransport {
    private val requests = mutableListOf<HTTPRequest>()

    /** Replies with `responses` in order (the last one repeats). */
    constructor(responses: List<HTTPResponse>) : this({ _, index -> responses[minOf(index, responses.size - 1)] })

    val recorded: List<HTTPRequest> @Synchronized get() = requests.toList()

    override suspend fun send(request: HTTPRequest): HTTPResponse = synchronized(this) {
        val index = requests.size
        requests += request
        handler(request, index)
    }
}

fun json(status: Int, text: String, headers: Map<String, String> = emptyMap()) =
    HTTPResponse(status, headers, text.encodeToByteArray())

fun json(text: String, headers: Map<String, String> = emptyMap()) = json(200, text, headers)

fun jsonObject(data: ByteArray?): JsonObject =
    data?.let { runCatching { CrateJSON.json.parseToJsonElement(it.decodeToString()).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())

val HTTPRequest.path: String get() = url.path

/** float → IEEE half bits (round to nearest even), for building test `vectors.bin` files. */
fun halfBits(value: Float): Int {
    val bits = java.lang.Float.floatToIntBits(value)
    val sign = (bits ushr 16) and 0x8000
    var exp = ((bits ushr 23) and 0xFF) - 127 + 15
    var mant = bits and 0x7FFFFF
    if (exp <= 0) return sign
    if (exp >= 0x1F) return sign or 0x7C00
    val round = mant and 0x1FFF
    mant = mant ushr 13
    if (round > 0x1000 || (round == 0x1000 && (mant and 1) == 1)) {
        mant++
        if (mant == 0x400) { mant = 0; exp++ }
    }
    return sign or (exp shl 10) or mant
}

fun float16Bytes(rows: List<List<Float>>): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (row in rows) for (v in row) { val b = halfBits(v); out.write(b and 0xFF); out.write(b shr 8) }
    return out.toByteArray()
}
