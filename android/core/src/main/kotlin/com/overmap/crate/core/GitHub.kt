package com.overmap.crate.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.io.File
import java.net.URI
import java.util.Base64

/** A cached copy of one repository file. */
class CachedFile(
    val data: ByteArray,
    /** Blob SHA, needed to write the file back. */
    val sha: String,
    val etag: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is CachedFile && other.data.contentEquals(data) && other.sha == sha && other.etag == etag
    override fun hashCode(): Int = sha.hashCode() * 31 + (etag?.hashCode() ?: 0)
    override fun toString(): String = "CachedFile(sha=$sha, etag=$etag, ${data.size} bytes)"
}

/** Where [GitHubStore] keeps file copies for ETag requests and offline use. */
interface GitHubCache {
    fun load(path: String): CachedFile?
    fun store(file: CachedFile, path: String)
}

/** A [GitHubCache] in memory (tests, previews). */
class InMemoryGitHubCache : GitHubCache {
    private val files = mutableMapOf<String, CachedFile>()
    @Synchronized override fun load(path: String): CachedFile? = files[path]
    @Synchronized override fun store(file: CachedFile, path: String) { files[path] = file }
}

/**
 * A [GitHubCache] on disk (e.g. `File(context.filesDir, "GitHubCache")`). Each file is stored as `<name>` (the
 * bytes) plus `<name>.meta.json` (sha and ETag), with `/` in the repository path replaced by `__`.
 */
class FileGitHubCache(val directory: File) : GitHubCache {
    @Serializable
    private class Meta(val sha: String, val etag: String? = null)

    private fun files(path: String): Pair<File, File> {
        val name = path.replace("/", "__")
        return File(directory, name) to File(directory, "$name.meta.json")
    }

    override fun load(path: String): CachedFile? = runCatching {
        val (dataFile, metaFile) = files(path)
        if (!dataFile.exists() || !metaFile.exists()) return null
        val meta = CrateJSON.json.decodeFromString(Meta.serializer(), metaFile.readText())
        CachedFile(dataFile.readBytes(), meta.sha, meta.etag)
    }.getOrNull()

    override fun store(file: CachedFile, path: String) {
        val (dataFile, metaFile) = files(path)
        // A failed cache write only costs a re-download next time.
        runCatching {
            directory.mkdirs()
            writeAtomically(dataFile, file.data)
            writeAtomically(metaFile, CrateJSON.json.encodeToString(Meta.serializer(), Meta(file.sha, file.etag)).encodeToByteArray())
        }
    }
}

internal fun writeAtomically(target: File, bytes: ByteArray) {
    val tmp = File(target.parentFile, ".${target.name}.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(target)) {
        target.delete()
        if (!tmp.renameTo(target)) { target.writeBytes(bytes); tmp.delete() }
    }
}

/** Errors from [GitHubStore]. */
sealed class GitHubError(message: String) : Exception(message) {
    data class NotFound(val path: String) : GitHubError("not found: $path")
    /** 401/403: the fine-grained PAT is missing, expired or lacks contents access. */
    data class Unauthorized(val status: Int) : GitHubError("unauthorized ($status)")
    /** 409/422 after every retry. */
    data class Conflict(val path: String) : GitHubError("conflict: $path")
    data class Http(val status: Int) : GitHubError("HTTP $status")
    data object MalformedResponse : GitHubError("malformed response")
}

/** A file read from the repository. */
class GitHubFile(
    val path: String,
    val data: ByteArray,
    val sha: String,
    /** True when served from the cache (304, or offline fallback). */
    val fromCache: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is GitHubFile && other.path == path &&
        other.data.contentEquals(data) && other.sha == sha && other.fromCache == fromCache
    override fun hashCode(): Int = path.hashCode() * 31 + sha.hashCode()
    override fun toString(): String = "GitHubFile($path, sha=$sha, fromCache=$fromCache)"
}

/** Reads and writes the `data/` JSON files through the GitHub Contents API (spec §7.3). */
class GitHubStore(
    val owner: String,
    val repo: String,
    val branch: String = "main",
    /** Returns the fine-grained PAT (from secure storage). Never logged. */
    private val token: () -> String,
    private val transport: HTTPTransport,
    private val cache: GitHubCache,
    private val apiBase: String = "https://api.github.com",
) {
    // Read

    /**
     * Fetches `path`, sending the cached ETag. A 304 returns the cached copy.
     * @param useCache `false` ignores the ETag (used after a write conflict).
     * @param offlineFallback On a transport error, return the cached copy instead of throwing.
     */
    suspend fun get(path: String, useCache: Boolean = true, offlineFallback: Boolean = true): GitHubFile {
        val cached = cache.load(path)
        var request = baseRequest("GET", contentsURL(path, ref = true))
        val etag = cached?.etag
        if (useCache && etag != null) request = request.copy(headers = request.headers + ("If-None-Match" to etag))
        val response = try {
            transport.send(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (offlineFallback && cached != null) return GitHubFile(path, cached.data, cached.sha, fromCache = true)
            throw e
        }
        if (response.status == 304 && cached != null) return GitHubFile(path, cached.data, cached.sha, fromCache = true)
        check(response, path)
        val body = decodeContents(response.body)
        val content = body.content
        val data = if (content != null && body.encoding == "base64" && content.isNotEmpty()) decodeBase64(content)
        else blob(body.sha) // files over 1 MB come back without content
        cache.store(CachedFile(data, body.sha, response.header("ETag")), path)
        return GitHubFile(path, data, body.sha, fromCache = false)
    }

    /** Fetches and decodes a Crate data file, applying the schema guard. */
    suspend fun <T> getJSON(serializer: KSerializer<T>, path: String): T =
        CrateJSON.decode(serializer, get(path).data, path.substringAfterLast('/'))

    suspend inline fun <reified T> getJSON(path: String): T = getJSON(serializer<T>(), path)

    // Write

    /** Writes `data` to `path`. Pass the current `sha` to update (`null` to create). Returns the new sha. */
    suspend fun put(path: String, data: ByteArray, sha: String?, message: String): String {
        val body = mutableMapOf<String, JSONValue>(
            "message" to JSONValue.String(message),
            "content" to JSONValue.String(Base64.getEncoder().encodeToString(data)),
            "branch" to JSONValue.String(branch),
        )
        if (sha != null) body["sha"] = JSONValue.String(sha)
        val request = baseRequest("PUT", contentsURL(path, ref = false)).let {
            it.copy(
                headers = it.headers + ("Content-Type" to "application/json"),
                body = CrateJSON.json.encodeToString(JsonElement.serializer(), JSONValue.Object(body).toJsonElement()).encodeToByteArray(),
            )
        }
        val response = transport.send(request)
        if (response.status == 409 || response.status == 422) throw GitHubError.Conflict(path)
        check(response, path)
        val newSHA = runCatching {
            CrateJSON.json.decodeFromString(PutBody.serializer(), response.body.decodeToString()).content.sha
        }.getOrElse { throw GitHubError.MalformedResponse }
        cache.store(CachedFile(data, newSHA, null), path)
        return newSHA
    }

    /**
     * Read-modify-write. `transform` receives the current bytes (`null` if the file doesn't exist) and returns the
     * new bytes. On 409/422 the file is fetched again and `transform` re-applied, up to three retries.
     */
    suspend fun update(path: String, message: String, transform: (ByteArray?) -> ByteArray): String {
        repeat(maxAttempts) { attempt ->
            val current = try {
                get(path, useCache = attempt == 0, offlineFallback = false)
            } catch (e: GitHubError.NotFound) {
                null
            }
            val newData = transform(current?.data)
            try {
                return put(path, newData, current?.sha, message)
            } catch (e: GitHubError.Conflict) {
                // fetch again and retry
            }
        }
        throw GitHubError.Conflict(path)
    }

    /**
     * Typed read-modify-write of a Crate JSON file, e.g. overrides.json. `empty` is used when the file is missing;
     * `mutate` returns the changed value (Swift's `inout` closure).
     */
    suspend fun <T> updateJSON(serializer: KSerializer<T>, path: String, message: String, empty: T,
                               mutate: (T) -> T): String {
        val name = path.substringAfterLast('/')
        return update(path, message) { data ->
            val value = data?.let { CrateJSON.decode(serializer, it, name) } ?: empty
            CrateJSON.encode(serializer, mutate(value))
        }
    }

    suspend inline fun <reified T> updateJSON(path: String, message: String, empty: T, noinline mutate: (T) -> T): String =
        updateJSON(serializer<T>(), path, message, empty, mutate)

    // Plumbing

    @Serializable
    private class ContentsBody(val sha: String, val content: String? = null, val encoding: String? = null)

    @Serializable
    private class PutBody(val content: Content) {
        @Serializable
        class Content(val sha: String)
    }

    private fun decodeContents(data: ByteArray): ContentsBody = runCatching {
        CrateJSON.json.decodeFromString(ContentsBody.serializer(), data.decodeToString())
    }.getOrElse { throw GitHubError.MalformedResponse }

    private fun decodeBase64(text: String): ByteArray = runCatching { Base64.getMimeDecoder().decode(text) }
        .getOrElse { throw GitHubError.MalformedResponse }

    /** `GET /repos/{owner}/{repo}/git/blobs/{sha}` (up to 100 MB). */
    private suspend fun blob(sha: String): ByteArray {
        val response = transport.send(baseRequest("GET", URI.create("$apiBase/repos/$owner/$repo/git/blobs/$sha")))
        check(response, "blob $sha")
        val content = decodeContents(response.body).content ?: throw GitHubError.MalformedResponse
        return decodeBase64(content)
    }

    private fun contentsURL(path: String, ref: Boolean): URI = URI.create(
        "$apiBase/repos/$owner/$repo/contents/" + path.split("/").joinToString("/") { percentEncode(it, "!$&'()*+,;=:@") } +
            if (ref) buildQuery(listOf("ref" to branch)) else ""
    )

    private fun baseRequest(method: String, url: URI) = HTTPRequest(method = method, url = url, headers = mapOf(
        "Authorization" to "Bearer ${token()}",
        "Accept" to "application/vnd.github+json",
        "X-GitHub-Api-Version" to "2022-11-28",
    ))

    private fun check(response: HTTPResponse, path: String) {
        when (response.status) {
            in 200..299 -> return
            401, 403 -> throw GitHubError.Unauthorized(response.status)
            404 -> throw GitHubError.NotFound(path)
            else -> throw GitHubError.Http(response.status)
        }
    }

    companion object {
        /** Attempts made by [update]: the first try plus three retries. */
        const val maxAttempts = 4
    }
}
