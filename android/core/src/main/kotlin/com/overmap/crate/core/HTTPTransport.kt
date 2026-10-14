package com.overmap.crate.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

/** A URL query item (`name=value`), percent-decoded. */
data class URLQueryItem(val name: String, val value: String?)

/** A plain HTTP request, independent of the HTTP stack so tests can inspect it. */
class HTTPRequest(
    var method: String = "GET",
    var url: URI,
    var headers: Map<String, String> = emptyMap(),
    var body: ByteArray? = null,
) {
    /** Header lookup, ignoring case. */
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** Query items of [url]. */
    val queryItems: List<URLQueryItem> get() = parseQuery(url.rawQuery)

    fun copy(method: String = this.method, url: URI = this.url, headers: Map<String, String> = this.headers,
             body: ByteArray? = this.body) = HTTPRequest(method, url, headers, body)

    override fun equals(other: Any?): Boolean = other is HTTPRequest && other.method == method && other.url == url &&
        other.headers == headers && other.body.contentEq(body)
    override fun hashCode(): Int = (method.hashCode() * 31 + url.hashCode()) * 31 + headers.hashCode()
    override fun toString(): String = "HTTPRequest($method $url)"
}

/** An HTTP response. */
class HTTPResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    /** Header lookup, ignoring case. */
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** True for 2xx. */
    val isSuccess: Boolean get() = status in 200..299

    override fun equals(other: Any?): Boolean =
        other is HTTPResponse && other.status == status && other.headers == headers && other.body.contentEquals(body)
    override fun hashCode(): Int = status * 31 + headers.hashCode()
    override fun toString(): String = "HTTPResponse($status, ${body.size} bytes)"
}

/** Sends HTTP requests. The app uses [UrlConnectionTransport]; tests use a fake. */
fun interface HTTPTransport {
    suspend fun send(request: HTTPRequest): HTTPResponse
}

/** [HTTPTransport] backed by `java.net.HttpURLConnection` (GET, POST, PUT, DELETE, with bodies). */
class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = 30_000,
    private val readTimeoutMillis: Int = 60_000,
) : HTTPTransport {
    override suspend fun send(request: HTTPRequest): HTTPResponse = withContext(Dispatchers.IO) {
        val connection = request.url.toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            // ETags are handled by GitHubStore; keep any HTTP cache from answering 304s itself.
            connection.useCaches = false
            connection.instanceFollowRedirects = true
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            } else if (request.method == "PUT" || request.method == "POST") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.close()
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else runCatching { connection.inputStream }.getOrNull()
            val bytes = stream?.use { input ->
                ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
            } ?: ByteArray(0)
            val headers = connection.headerFields.orEmpty()
                .filterKeys { it != null }
                .mapKeys { it.key!! }
                .mapValues { it.value.joinToString(", ") }
            HTTPResponse(status, headers, bytes)
        } finally {
            connection.disconnect()
        }
    }
}

/** Characters left as-is by percent-encoding ("unreserved" in RFC 3986). */
private const val unreservedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

/** Percent-encodes UTF-8 bytes of `text`, keeping unreserved characters and `extra`. */
internal fun percentEncode(text: String, extra: String = ""): String {
    val out = StringBuilder()
    for (byte in text.encodeToByteArray()) {
        val c = (byte.toInt() and 0xFF).toChar()
        if (byte >= 0 && (c in unreservedChars || c in extra)) out.append(c)
        else out.append('%').append("%02X".format(byte.toInt() and 0xFF))
    }
    return out.toString()
}

/** Percent-decodes `text` as UTF-8 (`+` is kept, as in URL components). */
internal fun percentDecode(text: String): String {
    if ('%' !in text) return text
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val hex = if (c == '%' && i + 2 < text.length) text.substring(i + 1, i + 3).takeIf { s -> s.all { it in "0123456789abcdefABCDEF" } }?.toInt(16) else null
        if (hex != null) {
            bytes.write(hex); i += 3
        } else {
            bytes.write(c.toString().encodeToByteArray()); i++
        }
    }
    return bytes.toByteArray().decodeToString()
}

/** Characters allowed unescaped in a query value besides unreserved ones (as URLComponents leaves them). */
internal const val queryValueExtra = "!$'()*,;:@/?"

/** Builds `?a=b&c=d` (empty for no items). */
internal fun buildQuery(items: List<Pair<String, String>>): String =
    if (items.isEmpty()) "" else "?" + items.joinToString("&") {
        percentEncode(it.first, queryValueExtra) + "=" + percentEncode(it.second, queryValueExtra)
    }

internal fun parseQuery(raw: String?): List<URLQueryItem> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.split("&").map { part ->
        val eq = part.indexOf('=')
        if (eq < 0) URLQueryItem(percentDecode(part), null)
        else URLQueryItem(percentDecode(part.substring(0, eq)), percentDecode(part.substring(eq + 1)))
    }
}

/** Form-encodes key/value pairs (`application/x-www-form-urlencoded`). */
internal fun formEncode(pairs: List<Pair<String, String>>): ByteArray =
    pairs.joinToString("&") { (k, v) -> percentEncode(k) + "=" + percentEncode(v) }.encodeToByteArray()

/** Base64url without padding (RFC 4648 §5), as used by PKCE. */
internal fun ByteArray.base64URLEncodedString(): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(this)
