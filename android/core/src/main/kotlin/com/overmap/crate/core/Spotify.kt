package com.overmap.crate.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

// MARK: PKCE

/** PKCE (RFC 7636) code verifier and S256 challenge. */
data class PKCE(val verifier: String) {
    val challenge: String = challenge(verifier)
    /** Always `S256`. */
    val method: String = "S256"

    companion object {
        /** Characters allowed in a verifier ("unreserved" in RFC 3986). */
        val unreserved: List<Char> = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toList()

        /** Generates a random verifier of `length` characters (43–128, default 64) with `using`. */
        fun generate(length: Int = 64, using: RandomNumberGenerator = SystemRandomNumberGenerator()): PKCE {
            val n = minOf(128, maxOf(43, length))
            return PKCE(String(CharArray(n) { unreserved[using.nextInt(unreserved.indices)] }))
        }

        /** `BASE64URL(SHA256(ASCII(verifier)))` without padding. */
        fun challenge(verifier: String): String =
            MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()).base64URLEncodedString()

        /** A random URL-safe `state` value for the authorize request. */
        fun randomState(): String {
            val rng = SystemRandomNumberGenerator()
            return String(CharArray(32) { unreserved[rng.nextInt(unreserved.indices)] })
        }
    }
}

// MARK: Tokens

/** Spotify OAuth tokens. Never log these. */
@Serializable
data class SpotifyTokens(
    var accessToken: String,
    var refreshToken: String?,
    @Serializable(with = InstantSerializer::class) var expiresAt: Instant,
    var scope: String? = null,
) {
    /** True if the access token expires within `margin` seconds of `now`. */
    fun isExpired(now: Instant = Instant.now(), margin: Double = 60.0): Boolean =
        (expiresAt.toEpochMilli() - now.toEpochMilli()) / 1000.0 <= margin

    override fun toString(): String = "SpotifyTokens(expiresAt=$expiresAt, scope=$scope)"

    companion object {
        /** JSON for a token store (keys `accessToken`, `refreshToken`, `expiresAt` ISO-8601, `scope`). */
        fun fromJson(json: String): SpotifyTokens = CrateJSON.json.decodeFromString(serializer(), json)
    }

    fun toJson(): String = CrateJSON.json.encodeToString(serializer(), this)
}

/** Stores Spotify tokens. The Android app provides an encrypted store; tests use [InMemoryTokenStore]. */
interface TokenStore {
    fun load(): SpotifyTokens?
    fun save(tokens: SpotifyTokens)
    fun clear()
}

/** A [TokenStore] that keeps tokens in memory only. */
class InMemoryTokenStore(private var tokens: SpotifyTokens? = null) : TokenStore {
    @Synchronized override fun load(): SpotifyTokens? = tokens
    @Synchronized override fun save(tokens: SpotifyTokens) { this.tokens = tokens }
    @Synchronized override fun clear() { tokens = null }
}

// MARK: Auth

/** Errors from Spotify authorisation. */
sealed class SpotifyAuthError(message: String) : Exception(message) {
    /** The callback's `state` did not match the one sent. */
    data object StateMismatch : SpotifyAuthError("state mismatch")
    /** The user denied access, or Spotify returned an error code. */
    data class AuthorizationFailed(val error: String) : SpotifyAuthError(error)
    /** The callback carried no `code`. */
    data object MissingCode : SpotifyAuthError("missing code")
    /** The token endpoint rejected the request (e.g. a revoked refresh token): "Spotify login expired". */
    data class TokenRequestFailed(val status: Int, val error: String?) : SpotifyAuthError("token request failed: $status $error")
    data object MalformedTokenResponse : SpotifyAuthError("malformed token response")
}

/** Builds the Authorization Code + PKCE requests (spec §7.2). Has no state; the caller keeps the verifier. */
class SpotifyAuth(
    val clientID: String,
    val redirectURI: String = CrateIdentity.spotifyRedirect,
    val scopes: List<String> = defaultScopes,
) {
    /** The URL to open in a Custom Tab / browser. */
    fun authorizeURL(pkce: PKCE, state: String): URI = URI.create(
        authorizeEndpoint.toString() + buildQuery(listOf(
            "client_id" to clientID,
            "response_type" to "code",
            "redirect_uri" to redirectURI,
            "code_challenge_method" to pkce.method,
            "code_challenge" to pkce.challenge,
            "scope" to scopes.joinToString(" "),
            "state" to state,
        ))
    )

    /** Extracts the authorisation code from the redirect URL, checking `state`. */
    fun code(fromCallback: String, expectedState: String): String {
        val query = fromCallback.substringAfter('?', "").substringBefore('#')
        val items = parseQuery(query)
        fun value(name: String) = items.firstOrNull { it.name == name }?.value
        if (value("state") != expectedState) throw SpotifyAuthError.StateMismatch
        value("error")?.let { throw SpotifyAuthError.AuthorizationFailed(it) }
        return value("code")?.takeIf { it.isNotEmpty() } ?: throw SpotifyAuthError.MissingCode
    }

    fun code(fromCallback: URI, expectedState: String): String = code(fromCallback.toString(), expectedState)

    /** `POST /api/token` exchanging `code` for tokens. */
    fun tokenExchangeRequest(code: String, verifier: String): HTTPRequest = tokenRequest(listOf(
        "grant_type" to "authorization_code",
        "code" to code,
        "redirect_uri" to redirectURI,
        "client_id" to clientID,
        "code_verifier" to verifier,
    ))

    /** `POST /api/token` refreshing the access token. */
    fun refreshRequest(refreshToken: String): HTTPRequest = tokenRequest(listOf(
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
        "client_id" to clientID,
    ))

    private fun tokenRequest(form: List<Pair<String, String>>) = HTTPRequest(
        method = "POST", url = tokenEndpoint,
        headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        body = formEncode(form),
    )

    @Serializable
    private class TokenBody(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Double? = null,
        val scope: String? = null,
        val error: String? = null,
    )

    /** Parses a token response. When Spotify does not rotate the refresh token, `previousRefreshToken` is kept. */
    fun parseTokenResponse(response: HTTPResponse, previousRefreshToken: String? = null,
                           now: Instant = Instant.now()): SpotifyTokens {
        val body = runCatching {
            CrateJSON.json.decodeFromString(TokenBody.serializer(), response.body.decodeToString())
        }.getOrNull()
        if (!response.isSuccess) throw SpotifyAuthError.TokenRequestFailed(response.status, body?.error)
        val access = body?.accessToken ?: throw SpotifyAuthError.MalformedTokenResponse
        return SpotifyTokens(
            accessToken = access,
            refreshToken = body.refreshToken ?: previousRefreshToken,
            expiresAt = now.plusMillis(((body.expiresIn ?: 3600.0) * 1000).toLong()),
            scope = body.scope,
        )
    }

    /** Exchanges `code` for tokens over `transport`. */
    suspend fun exchange(code: String, verifier: String, transport: HTTPTransport): SpotifyTokens =
        parseTokenResponse(transport.send(tokenExchangeRequest(code, verifier)))

    /** Refreshes `tokens` over `transport`. */
    suspend fun refresh(tokens: SpotifyTokens, transport: HTTPTransport): SpotifyTokens {
        val refreshToken = tokens.refreshToken ?: throw SpotifyAuthError.TokenRequestFailed(400, "no_refresh_token")
        return parseTokenResponse(transport.send(refreshRequest(refreshToken)), previousRefreshToken = refreshToken)
    }

    companion object {
        /** Scopes the app requests. */
        val defaultScopes = listOf(
            "user-library-read", "user-library-modify",
            "playlist-read-private", "playlist-read-collaborative",
            "playlist-modify-private", "playlist-modify-public",
        )
        val authorizeEndpoint: URI = URI.create("https://accounts.spotify.com/authorize")
        val tokenEndpoint: URI = URI.create("https://accounts.spotify.com/api/token")
    }
}

// MARK: Models

/** `GET /me`. */
@Serializable
data class SpotifyUser(val id: String, @SerialName("display_name") val displayName: String? = null)

/** A playlist summary from `GET /me/playlists`, `POST /me/playlists` or `GET /playlists/{id}`. */
@Serializable
data class SpotifyPlaylist(
    val id: String,
    val name: String,
    val owner: Owner? = null,
    val uri: String? = null,
    /** Spotify's `public` flag; `null` when not returned (Spotify sends `null` for collaborative playlists). */
    @SerialName("public") val isPublic: Boolean? = null,
) {
    @Serializable
    data class Owner(val id: String)

    /** `spotify:playlist:<id>` deep link. */
    val deepLink: String get() = uri ?: "spotify:playlist:$id"
}

/** A paging object. */
@Serializable
data class SpotifyPage<Item>(val items: List<Item> = emptyList(), val next: String? = null, val total: Int? = null)

/** One row of `GET /playlists/{id}/items` (Feb 2026: the track is under `item`, not `track`). */
@Serializable
data class SpotifyPlaylistItem(val item: Item? = null) {
    @Serializable
    data class Item(val uri: String? = null, val id: String? = null)
}

/** Errors from the Web API. */
sealed class SpotifyError(message: String) : Exception(message) {
    /** No tokens stored: the user must log in. */
    data object NotLoggedIn : SpotifyError("not logged in")
    /** 401 after a refresh: "Spotify login expired". */
    data object Unauthorized : SpotifyError("unauthorized")
    /** 429; `retryAfter` in seconds when given. */
    data class RateLimited(val retryAfter: Int?) : SpotifyError("rate limited")
    data class Http(val status: Int, override val message: String?) : SpotifyError("HTTP $status")
    data object MalformedResponse : SpotifyError("malformed response")
}

/** A minimal JSON value for request bodies. */
sealed class JSONValue {
    data class String(val value: kotlin.String) : JSONValue()
    data class Bool(val value: Boolean) : JSONValue()
    data class Number(val value: Double) : JSONValue()
    data class Array(val value: List<JSONValue>) : JSONValue()
    data class Object(val value: Map<kotlin.String, JSONValue>) : JSONValue()
    data object Null : JSONValue()

    fun toJsonElement(): JsonElement = when (this) {
        is String -> JsonPrimitive(value)
        is Bool -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Array -> JsonArray(value.map { it.toJsonElement() })
        is Object -> JsonObject(value.mapValues { it.value.toJsonElement() })
        Null -> JsonNull
    }
}

// MARK: Client

/** The single low-level write to playlist items (`PUT`/`POST /playlists/{id}/items`). */
interface SpotifyPlaylistItemsWriting {
    suspend fun writePlaylistItems(method: String, playlistID: String, uris: List<String>)
}

/** What a widget send needs from Spotify: find or create Now, and write its items. */
interface NowPlaylistTarget : SpotifyPlaylistItemsWriting {
    suspend fun ensureNowPlaylist(knownID: String?): String
}

/**
 * Spotify Web API client for the endpoints available in Development Mode (spec §2).
 * Playlist item writes go through [NowWriter] and its allow-list, not through public methods here.
 */
class SpotifyClient(
    private val auth: SpotifyAuth,
    private val transport: HTTPTransport,
    private val tokenStore: TokenStore,
    private val now: () -> Instant = { Instant.now() },
) : NowPlaylistTarget {
    private val tokenLock = Mutex()
    @Volatile private var cachedUserID: String? = null

    /** `GET /me`. */
    suspend fun me(): SpotifyUser {
        val user = decode(SpotifyUser.serializer(), send("GET", "/me"))
        cachedUserID = user.id
        return user
    }

    /** `GET /me/library/contains?uris=…`, batched by 40. Returns one flag per URI, in order. */
    suspend fun libraryContains(uris: List<String>): List<Boolean> {
        val result = mutableListOf<Boolean>()
        for (batch in uris.chunked(libraryBatchSize)) {
            val flags = decode(ListSerializer(Boolean.serializer()),
                send("GET", "/me/library/contains", query = mapOf("uris" to batch.joinToString(","))))
            if (flags.size != batch.size) throw SpotifyError.MalformedResponse
            result += flags
        }
        return result
    }

    /** `PUT /me/library?uris=…` (Like), batched by 40. */
    suspend fun saveToLibrary(uris: List<String>) {
        for (batch in uris.chunked(libraryBatchSize)) send("PUT", "/me/library", query = mapOf("uris" to batch.joinToString(",")))
    }

    /** `DELETE /me/library?uris=…` (Unlike), batched by 40. */
    suspend fun removeFromLibrary(uris: List<String>) {
        for (batch in uris.chunked(libraryBatchSize)) send("DELETE", "/me/library", query = mapOf("uris" to batch.joinToString(",")))
    }

    /** All of the user's playlists (`GET /me/playlists`, every page). */
    suspend fun myPlaylists(): List<SpotifyPlaylist> {
        val all = mutableListOf<SpotifyPlaylist>()
        var offset = 0
        while (true) {
            val page = decode(SpotifyPage.serializer(SpotifyPlaylist.serializer()),
                send("GET", "/me/playlists", query = mapOf("limit" to "50", "offset" to offset.toString())))
            all += page.items
            if (page.next == null || page.items.isEmpty()) return all
            offset += page.items.size
        }
    }

    /** One playlist's summary (`GET /playlists/{id}?fields=id,name,public,uri,owner`), e.g. to check `isPublic`. */
    suspend fun playlist(id: String): SpotifyPlaylist =
        decode(SpotifyPlaylist.serializer(), send("GET", "/playlists/$id", query = mapOf("fields" to "id,name,public,uri,owner")))

    /** `POST /me/playlists`, private by default. */
    suspend fun createPlaylist(name: String, isPublic: Boolean = false, description: String? = null): SpotifyPlaylist {
        val body = mutableMapOf<String, JSONValue>("name" to JSONValue.String(name), "public" to JSONValue.Bool(isPublic))
        if (description != null) body["description"] = JSONValue.String(description)
        return decode(SpotifyPlaylist.serializer(), send("POST", "/me/playlists", json = JSONValue.Object(body)))
    }

    /**
     * The Now playlist ID (spec §7.4 step 1): `knownID` if given, else a playlist named "Now" owned by the user,
     * else a newly created private one. The caller records the result in presets.json.
     */
    override suspend fun ensureNowPlaylist(knownID: String?): String {
        if (!knownID.isNullOrEmpty()) return knownID
        val userID = currentUserID()
        myPlaylists().firstOrNull { it.name == nowPlaylistName && it.owner?.id == userID }?.let { return it.id }
        return createPlaylist(nowPlaylistName, description = "Written by Crate. Overwritten on every send.").id
    }

    /** URIs in an owned playlist (`GET /playlists/{id}/items`, response `items[].item`). */
    suspend fun playlistItemURIs(playlistID: String): List<String> {
        val uris = mutableListOf<String>()
        var offset = 0
        while (true) {
            val page = decode(SpotifyPage.serializer(SpotifyPlaylistItem.serializer()),
                send("GET", "/playlists/$playlistID/items", query = mapOf("limit" to "50", "offset" to offset.toString())))
            uris += page.items.mapNotNull { it.item?.uri }
            if (page.next == null || page.items.isEmpty()) return uris
            offset += page.items.size
        }
    }

    /** Used by [NowWriter] only. */
    override suspend fun writePlaylistItems(method: String, playlistID: String, uris: List<String>) {
        send(method, "/playlists/$playlistID/items", json = JSONValue.Object(mapOf("uris" to JSONValue.Array(uris.map { JSONValue.String(it) }))))
    }

    // Plumbing

    private suspend fun currentUserID(): String = cachedUserID ?: me().id

    /** Sends an authorised request. Refreshes the token when it is about to expire, and once more on 401. */
    internal suspend fun send(method: String, path: String, query: Map<String, String> = emptyMap(),
                              json: JSONValue? = null): HTTPResponse {
        var request = makeRequest(method, path, query, json)
        request = request.copy(headers = request.headers + ("Authorization" to "Bearer ${accessToken(false)}"))
        var response = transport.send(request)
        if (response.status == 401) {
            request = request.copy(headers = request.headers + ("Authorization" to "Bearer ${accessToken(true)}"))
            response = transport.send(request)
            if (response.status == 401) throw SpotifyError.Unauthorized
        }
        if (response.status == 429) throw SpotifyError.RateLimited(response.header("Retry-After")?.trim()?.toIntOrNull())
        if (!response.isSuccess) throw SpotifyError.Http(response.status, response.body.decodeToString())
        return response
    }

    private fun makeRequest(method: String, path: String, query: Map<String, String>, json: JSONValue?): HTTPRequest {
        val url = URI.create(apiBase.toString() + path + buildQuery(query.keys.sorted().map { it to query.getValue(it) }))
        var request = HTTPRequest(method = method, url = url)
        if (json != null) {
            request = request.copy(
                body = CrateJSON.json.encodeToString(JsonElement.serializer(), json.toJsonElement()).encodeToByteArray(),
                headers = request.headers + ("Content-Type" to "application/json"),
            )
        }
        return request
    }

    private suspend fun accessToken(forceRefresh: Boolean): String = tokenLock.withLock {
        var tokens = tokenStore.load() ?: throw SpotifyError.NotLoggedIn
        if (forceRefresh || tokens.isExpired(now())) {
            tokens = try {
                auth.refresh(tokens, transport)
            } catch (e: SpotifyAuthError.TokenRequestFailed) {
                throw SpotifyError.Unauthorized
            }
            tokenStore.save(tokens)
        }
        tokens.accessToken
    }

    private fun <T> decode(serializer: KSerializer<T>, response: HTTPResponse): T = try {
        CrateJSON.json.decodeFromString(serializer, response.body.decodeToString())
    } catch (e: Exception) {
        throw SpotifyError.MalformedResponse
    }

    companion object {
        val apiBase: URI = URI.create("https://api.spotify.com/v1")
        /** Max URIs per `/me/library` call. */
        const val libraryBatchSize = 40
        /** Name of the playlist Crate overwrites. */
        const val nowPlaylistName = "Now"
    }
}

// MARK: NowWriter

/** Errors from [NowWriter]. */
sealed class NowWriterError(message: String) : Exception(message) {
    /** The playlist is not on the allow-list; nothing was written. */
    data class PlaylistNotAllowed(val playlistID: String) : NowWriterError("playlist not allowed: $playlistID")
}

/**
 * Replaces the contents of an allow-listed playlist (spec §7.4 step 3 and §13). Every destructive playlist call
 * goes through here: only IDs in [allowedPlaylistIDs] (Now and Crate Daily) can be modified; anything else is
 * refused before any request is sent.
 */
class NowWriter(private val writer: SpotifyPlaylistItemsWriting, allowedPlaylistIDs: Set<String>) {
    val allowedPlaylistIDs: Set<String> = allowedPlaylistIDs.filter { it.isNotEmpty() }.toSet()

    /**
     * Replaces the playlist with `with`: `PUT` the first 100 (clearing what was there), then `POST` the rest in
     * batches of 100. `progress` receives (written, total) after each request.
     */
    suspend fun replace(playlistID: String, with: List<String>, progress: ((Int, Int) -> Unit)? = null) {
        if (playlistID !in allowedPlaylistIDs) throw NowWriterError.PlaylistNotAllowed(playlistID)
        val uris = with
        val batches = uris.chunked(batchSize)
        writer.writePlaylistItems("PUT", playlistID, batches.firstOrNull() ?: emptyList())
        var written = batches.firstOrNull()?.size ?: 0
        progress?.invoke(written, uris.size)
        for (batch in batches.drop(1)) {
            writer.writePlaylistItems("POST", playlistID, batch)
            written += batch.size
            progress?.invoke(written, uris.size)
        }
    }

    companion object {
        /** Max URIs per playlist-items request. */
        const val batchSize = 100
    }
}
