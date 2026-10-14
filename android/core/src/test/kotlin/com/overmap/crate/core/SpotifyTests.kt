package com.overmap.crate.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** Asserts that `block` throws exactly `expected` (by equality). */
suspend fun assertThrowsEqual(expected: Throwable, block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        assertEquals(expected, e)
        return
    }
    fail("expected $expected")
}

class SpotifyTests {
    private val auth = SpotifyAuth(clientID = "client123")

    @Test fun pkceRFC7636AppendixB() {
        val pkce = PKCE(verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.challenge)
        assertEquals("S256", pkce.method)
    }

    @Test fun generatedVerifier() {
        val pkce = PKCE.generate()
        assertEquals(64, pkce.verifier.length)
        assertTrue(pkce.verifier.all { it in PKCE.unreserved })
        assertEquals(43, PKCE.generate(length = 10).verifier.length)
        assertEquals(PKCE.generate(using = SeededGenerator(1)), PKCE.generate(using = SeededGenerator(1)))
        assertEquals(32, PKCE.randomState().length)
    }

    @Test fun authorizeURL() {
        val pkce = PKCE(verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        val url = auth.authorizeURL(pkce, state = "xyz")
        val items = parseQuery(url.rawQuery).associate { it.name to (it.value ?: "") }
        assertTrue(url.host == "accounts.spotify.com" && url.path == "/authorize")
        assertEquals("client123", items["client_id"])
        assertEquals("code", items["response_type"])
        assertEquals("crate://spotify-callback", items["redirect_uri"])
        assertEquals("S256", items["code_challenge_method"])
        assertEquals(pkce.challenge, items["code_challenge"])
        assertEquals("xyz", items["state"])
        // Only the pipeline's one-time migration needed it; the app asks for the least it needs.
        assertFalse(items["scope"]!!.split(" ").contains("user-follow-modify"))
        assertTrue(items["scope"]!!.split(" ").contains("playlist-modify-private"))
    }

    @Test fun callbackParsing() {
        assertEquals("abc", auth.code(fromCallback = "crate://spotify-callback?code=abc&state=s", expectedState = "s"))
        assertEquals(SpotifyAuthError.StateMismatch, assertThrows(SpotifyAuthError::class.java) {
            auth.code(fromCallback = "crate://spotify-callback?code=abc&state=evil", expectedState = "s")
        })
        assertEquals(SpotifyAuthError.AuthorizationFailed("access_denied"), assertThrows(SpotifyAuthError::class.java) {
            auth.code(fromCallback = "crate://spotify-callback?error=access_denied&state=s", expectedState = "s")
        })
        assertEquals(SpotifyAuthError.MissingCode, assertThrows(SpotifyAuthError::class.java) {
            auth.code(fromCallback = "crate://spotify-callback?state=s", expectedState = "s")
        })
    }

    @Test fun tokenRequests() {
        val exchange = auth.tokenExchangeRequest(code = "c0de", verifier = "v")
        assertTrue(exchange.method == "POST" && exchange.url.toString() == "https://accounts.spotify.com/api/token")
        assertEquals("application/x-www-form-urlencoded", exchange.header("content-type"))
        assertEquals("grant_type=authorization_code&code=c0de&redirect_uri=crate%3A%2F%2Fspotify-callback&client_id=client123&code_verifier=v",
            exchange.body!!.decodeToString())
        assertNull("PKCE sends no client secret", exchange.header("Authorization"))

        val refresh = auth.refreshRequest(refreshToken = "r/1").body!!.decodeToString()
        assertEquals("grant_type=refresh_token&refresh_token=r%2F1&client_id=client123", refresh)
    }

    @Test fun tokenResponseKeepsRefreshTokenWhenNotRotated() {
        val now = Instant.ofEpochSecond(1000)
        val tokens = auth.parseTokenResponse(json("""{"access_token":"A","expires_in":3600,"scope":"s"}"""),
            previousRefreshToken = "R", now = now)
        assertEquals(SpotifyTokens("A", "R", Instant.ofEpochSecond(4600), "s"), tokens)
        assertEquals(SpotifyAuthError.TokenRequestFailed(400, "invalid_grant"), assertThrows(SpotifyAuthError::class.java) {
            auth.parseTokenResponse(json(400, """{"error":"invalid_grant"}"""))
        })
        assertEquals(tokens, SpotifyTokens.fromJson(tokens.toJson()))
        assertTrue(tokens.isExpired(now = Instant.ofEpochSecond(4550)) && !tokens.isExpired(now = Instant.ofEpochSecond(4500)))
    }

    private fun client(transport: FakeTransport, expired: Boolean = false): SpotifyClient {
        val store = InMemoryTokenStore(SpotifyTokens("OLD", "R", if (expired) Instant.EPOCH else Instant.parse("4000-01-01T00:00:00Z")))
        return SpotifyClient(auth, transport, store)
    }

    @Test fun libraryContainsBatchesBy40WithURIsQuery() = runTest {
        val uris = (0 until 45).map { "spotify:track:$it" }
        val transport = FakeTransport { request, _ ->
            val n = request.queryItems.firstOrNull { it.name == "uris" }?.value?.split(",")?.size ?: 0
            json("[" + List(n) { "true" }.joinToString(",") + "]")
        }
        val flags = client(transport).libraryContains(uris)
        assertEquals(45, flags.size)
        val requests = transport.recorded
        assertEquals(2, requests.size)
        assertEquals("/v1/me/library/contains", requests[0].path)
        assertEquals(40, requests[0].queryItems.first().value?.split(",")?.size)
        assertEquals(uris.take(40).joinToString(","), requests[0].queryItems.first().value)
        assertEquals("Bearer OLD", requests[0].header("Authorization"))
    }

    @Test fun saveToLibraryUsesPUTWithURIs() = runTest {
        val transport = FakeTransport(listOf(json("")))
        client(transport).saveToLibrary(listOf("spotify:track:a"))
        val request = transport.recorded.first()
        assertTrue(request.method == "PUT" && request.path == "/v1/me/library")
        assertEquals("spotify:track:a", request.queryItems.first { it.name == "uris" }.value)
    }

    @Test fun removeFromLibraryUsesDELETEWithURIs() = runTest {
        val transport = FakeTransport(listOf(json("")))
        client(transport).removeFromLibrary(listOf("spotify:track:a"))
        val request = transport.recorded.first()
        assertTrue(request.method == "DELETE" && request.path == "/v1/me/library")
        assertEquals("spotify:track:a", request.queryItems.first { it.name == "uris" }.value)
    }

    @Test fun expiredTokenIsRefreshedFirst() = runTest {
        val transport = FakeTransport { request, _ ->
            if (request.url.host == "accounts.spotify.com") json("""{"access_token":"NEW","expires_in":3600}""")
            else json("""{"id":"me","display_name":"Filippo"}""")
        }
        val user = client(transport, expired = true).me()
        assertEquals("me", user.id)
        assertEquals(2, transport.recorded.size)
        assertEquals("Bearer NEW", transport.recorded[1].header("Authorization"))
    }

    @Test fun refreshOn401ThenUnauthorized() = runTest {
        val retried = FakeTransport { request, index ->
            when {
                request.url.host == "accounts.spotify.com" -> json("""{"access_token":"NEW","expires_in":3600}""")
                index == 0 -> json(401, "{}")
                else -> json("""{"id":"me"}""")
            }
        }
        assertEquals("me", client(retried).me().id)
        assertEquals(listOf("Bearer OLD", null, "Bearer NEW"), retried.recorded.map { it.header("Authorization") })

        val revoked = FakeTransport { request, _ ->
            if (request.url.host == "accounts.spotify.com") json(400, """{"error":"invalid_grant"}""") else json(401, "{}")
        }
        assertThrowsEqual(SpotifyError.Unauthorized) { client(revoked).me() }
        assertThrowsEqual(SpotifyError.NotLoggedIn) { SpotifyClient(auth, revoked, InMemoryTokenStore()).me() }
    }

    @Test fun ensureNowFindsOwnedPlaylistOrCreatesOne() = runTest {
        val existing = FakeTransport { request, _ ->
            when (request.path) {
                "/v1/me" -> json("""{"id":"me"}""")
                else -> json("""{"items":[{"id":"x","name":"Now","owner":{"id":"someone"}},{"id":"N1","name":"Now","owner":{"id":"me"}}],"next":null}""")
            }
        }
        assertEquals("N1", client(existing).ensureNowPlaylist(knownID = null))
        assertEquals("K", client(existing).ensureNowPlaylist(knownID = "K"))

        val empty = FakeTransport { request, _ ->
            when {
                request.method == "GET" && request.path == "/v1/me" -> json("""{"id":"me"}""")
                request.method == "GET" -> json("""{"items":[],"next":null}""")
                else -> json(201, """{"id":"NEW","name":"Now","owner":{"id":"me"}}""")
            }
        }
        assertEquals("NEW", client(empty).ensureNowPlaylist(knownID = null))
        val create = empty.recorded.last()
        assertTrue(create.method == "POST" && create.path == "/v1/me/playlists")
        val body = jsonObject(create.body)
        assertTrue(body["name"]?.jsonPrimitive?.content == "Now" && body["public"]?.jsonPrimitive?.boolean == false)
    }

    @Test fun playlistItemsReadsItemField() = runTest {
        val transport = FakeTransport(listOf(json("""{"items":[{"item":{"uri":"spotify:track:1"}},{"item":null}],"next":null}""")))
        assertEquals(listOf("spotify:track:1"), client(transport).playlistItemURIs(playlistID = "N"))
        assertEquals("/v1/playlists/N/items", transport.recorded.first().path)
    }

    @Test fun rateLimit() = runTest {
        val transport = FakeTransport(listOf(json(429, "", mapOf("Retry-After" to "7"))))
        assertThrowsEqual(SpotifyError.RateLimited(retryAfter = 7)) { client(transport).me() }
    }

    @Test fun playlistFetchesPublicFlag() = runTest {
        val transport = FakeTransport(listOf(
            json("""{"id":"D1","name":"Crate Daily","public":true,"uri":"spotify:playlist:D1","owner":{"id":"me"}}"""),
            json("""{"id":"N1","name":"Now","public":false}"""),
            json("""{"id":"C1","name":"Shared","public":null}"""),
        ))
        val c = client(transport)
        val daily = c.playlist(id = "D1")
        assertTrue(daily.isPublic == true && daily.name == "Crate Daily" && daily.owner?.id == "me")
        assertEquals(false, c.playlist(id = "N1").isPublic)
        assertNull(c.playlist(id = "C1").isPublic)
        val request = transport.recorded.first()
        assertTrue(request.method == "GET" && request.path == "/v1/playlists/D1")
        assertEquals("id,name,public,uri,owner", request.queryItems.first { it.name == "fields" }.value)
        assertEquals("spotify:playlist:D1", daily.deepLink)
    }

    @Test fun playlistListsDecodeWithoutPublic() {
        val page = CrateJSON.json.decodeFromString(SpotifyPage.serializer(SpotifyPlaylist.serializer()),
            """{"items":[{"id":"x","name":"Now"}],"next":null}""")
        assertNull(page.items.first().isPublic)
    }
}

class NowWriterTests {
    private fun makeWriter(transport: FakeTransport, allowed: Set<String>): NowWriter {
        val client = SpotifyClient(SpotifyAuth("c"), transport,
            InMemoryTokenStore(SpotifyTokens("T", null, Instant.parse("4000-01-01T00:00:00Z"))))
        return NowWriter(client, allowedPlaylistIDs = allowed)
    }

    @Test fun refusesPlaylistsOutsideAllowList() = runTest {
        val transport = FakeTransport(listOf(json(201, "{}")))
        val writer = makeWriter(transport, allowed = setOf("NOW", "WEEKLY", ""))
        assertThrowsEqual(NowWriterError.PlaylistNotAllowed("OTHER")) { writer.replace("OTHER", with = listOf("spotify:track:1")) }
        assertThrowsEqual(NowWriterError.PlaylistNotAllowed("")) { writer.replace("", with = emptyList()) }
        assertTrue("nothing is sent for a refused playlist", transport.recorded.isEmpty())
    }

    @Test fun putsFirst100ThenPostsBatchesOf100() = runTest {
        val transport = FakeTransport(listOf(json(201, """{"snapshot_id":"s"}""")))
        val uris = (0 until 250).map { "spotify:track:$it" }
        val progress = mutableListOf<Int>()
        makeWriter(transport, allowed = setOf("NOW")).replace(playlistID = "NOW", with = uris) { done, _ -> progress += done }
        val requests = transport.recorded
        assertEquals(listOf("PUT", "POST", "POST"), requests.map { it.method })
        assertTrue(requests.all { it.path == "/v1/playlists/NOW/items" })
        val batches = requests.map { r -> jsonObject(r.body)["uris"]!!.jsonArray.map { it.jsonPrimitive.content } }
        assertEquals(listOf(100, 100, 50), batches.map { it.size })
        assertEquals("order is preserved across batches", uris, batches.flatten())
        assertEquals(listOf(100, 200, 250), progress)
    }

    @Test fun emptyResultClearsWithSinglePUT() = runTest {
        val transport = FakeTransport(listOf(json(201, "{}")))
        makeWriter(transport, allowed = setOf("NOW")).replace(playlistID = "NOW", with = emptyList())
        assertEquals(listOf("PUT"), transport.recorded.map { it.method })
        assertEquals(0, jsonObject(transport.recorded[0].body)["uris"]?.jsonArray?.size)
    }
}
