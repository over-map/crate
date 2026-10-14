package com.overmap.crate.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.Base64

class GitHubStoreTests {
    private fun b64(text: String) = Base64.getEncoder().encodeToString(text.encodeToByteArray())

    private fun contents(text: String, sha: String) =
        json("""{"sha":"$sha","encoding":"base64","content":"${b64(text)}"}""", mapOf("ETag" to "\"etag-$sha\""))

    private fun putOK(sha: String) = json(200, """{"content":{"sha":"$sha"}}""")

    private fun store(transport: HTTPTransport, cache: GitHubCache = InMemoryGitHubCache()) =
        GitHubStore(owner = "owner", repo = "crate", token = { "github_pat_TEST" }, transport = transport, cache = cache)

    @Test fun getSendsPATAndUsesETag() = runTest {
        val transport = FakeTransport(listOf(contents("v1", sha = "s1"), json(304, "")))
        val s = store(transport)
        val first = s.get("data/state.json")
        assertTrue(first.data.decodeToString() == "v1" && first.sha == "s1" && !first.fromCache)
        val second = s.get("data/state.json")
        assertTrue(second.fromCache && second.sha == "s1" && second.data.contentEquals(first.data))

        val requests = transport.recorded
        assertEquals("Bearer github_pat_TEST", requests[0].header("Authorization"))
        assertEquals("/repos/owner/crate/contents/data/state.json", requests[0].path)
        assertEquals("main", requests[0].queryItems.first { it.name == "ref" }.value)
        assertNull(requests[0].header("If-None-Match"))
        assertEquals("\"etag-s1\"", requests[1].header("If-None-Match"))
    }

    @Test fun offlineFallsBackToCache() = runTest {
        val cache = InMemoryGitHubCache()
        cache.store(CachedFile("cached".encodeToByteArray(), "s0", "e"), path = "data/library.json")
        val offline = HTTPTransport { throw IOException("not connected") }
        val s = GitHubStore(owner = "o", repo = "r", token = { "t" }, transport = offline, cache = cache)
        assertTrue(s.get("data/library.json").fromCache)
    }

    @Test fun getJSONAppliesSchemaGuard() = runTest {
        val transport = FakeTransport(listOf(contents("""{"schema":1,"now_playlist_id":"N"}""", sha = "s1")))
        assertEquals("N", store(transport).getJSON<StateFile>(CrateKit.Path.state).nowPlaylistID)
    }

    @Test fun largeFileComesFromBlobAPI() = runTest {
        val transport = FakeTransport { request, _ ->
            if (request.path.contains("/git/blobs/")) json("""{"sha":"big","encoding":"base64","content":"${b64("large")}"}""")
            else json("""{"sha":"big","encoding":"none","content":""}""")
        }
        val file = store(transport).get("data/library.json")
        assertEquals("large", file.data.decodeToString())
        assertEquals("/repos/owner/crate/git/blobs/big", transport.recorded.last().path)
    }

    @Test fun conflictRefetchesAndReappliesChange() = runTest {
        // GET (s1) → PUT 409 → GET (s2, someone else's edit) → PUT 200.
        val transport = FakeTransport(listOf(
            contents("""{"schema":1,"tracks":{"a":{"bpm":100}}}""", sha = "s1"),
            json(409, """{"message":"conflict"}"""),
            contents("""{"schema":1,"tracks":{"a":{"bpm":100},"b":{"energy":3}}}""", sha = "s2"),
            putOK("s3"),
        ))
        val newSHA = store(transport).updateJSON("data/overrides.json", "Edit tags", OverridesFile()) { file ->
            file.copy(tracks = file.tracks + ("c" to (file.tracks["c"] ?: TrackOverride()).copy(bpm = 140.0)))
        }
        assertEquals("s3", newSHA)
        val requests = transport.recorded
        assertEquals(listOf("GET", "PUT", "GET", "PUT"), requests.map { it.method })
        assertNull("refetch bypasses the ETag", requests[2].header("If-None-Match"))
        val put1 = jsonObject(requests[1].body)
        val put2 = jsonObject(requests[3].body)
        assertTrue(put1["sha"]?.jsonPrimitive?.content == "s1" && put2["sha"]?.jsonPrimitive?.content == "s2")
        assertTrue(put2["branch"]?.jsonPrimitive?.content == "main" && put2["message"]?.jsonPrimitive?.content == "Edit tags")
        val written = CrateJSON.decode<OverridesFile>(Base64.getDecoder().decode(put2["content"]!!.jsonPrimitive.content), "overrides.json")
        assertEquals("the other writer's change survives", 3, written.tracks["b"]?.energy)
        assertEquals("our change is re-applied", 140.0, written.tracks["c"]?.bpm)
    }

    @Test fun givesUpAfterThreeRetries() = runTest {
        val transport = FakeTransport { request, _ -> if (request.method == "GET") contents("{}", sha = "s") else json(422, "{}") }
        assertThrowsEqual(GitHubError.Conflict("data/presets.json")) {
            store(transport).update(path = "data/presets.json", message = "m") { it ?: ByteArray(0) }
        }
        assertEquals(GitHubStore.maxAttempts, transport.recorded.count { it.method == "PUT" })
    }

    @Test fun missingFileIsCreatedWithoutSHA() = runTest {
        val transport = FakeTransport(listOf(json(404, """{"message":"Not Found"}"""), putOK("new")))
        var old: ByteArray? = byteArrayOf(1)
        val sha = store(transport).update(path = "data/presets.json", message = "Create") { o -> old = o; "[]".encodeToByteArray() }
        assertNull(old)
        assertEquals("new", sha)
        assertFalse(jsonObject(transport.recorded[1].body).containsKey("sha"))
    }

    @Test fun unauthorized() = runTest {
        val transport = FakeTransport(listOf(json(401, "{}")))
        assertThrowsEqual(GitHubError.Unauthorized(401)) { store(transport).get("data/state.json", offlineFallback = false) }
    }

    @Test fun fileCacheRoundTrip() {
        val dir = Files.createTempDirectory("cratekit-").toFile()
        try {
            val cache = FileGitHubCache(dir)
            assertNull(cache.load(path = "data/library.json"))
            val file = CachedFile("hello".encodeToByteArray(), sha = "abc", etag = "W/\"1\"")
            cache.store(file, path = "data/library.json")
            assertEquals(file, FileGitHubCache(dir).load(path = "data/library.json"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
