package com.overmap.crate.app

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.overmap.crate.core.CrateIdentity
import com.overmap.crate.core.NowWriter
import com.overmap.crate.core.PKCE
import com.overmap.crate.core.SpotifyAuth
import com.overmap.crate.core.SpotifyClient
import com.overmap.crate.core.TokenStore
import com.overmap.crate.core.UrlConnectionTransport
import kotlinx.coroutines.CompletableDeferred

/**
 * Spotify login (Authorization Code + PKCE in a Custom Tab, callback `crate://spotify-callback` back into
 * MainActivity), tokens in [SecureTokenStore], and the writes the app makes: replacing Now (through NowWriter's
 * allow-list), liking and unliking a track.
 */
class SpotifyService(private val tokens: TokenStore) {
    val auth = SpotifyAuth(clientID = AppConfig.spotifyClientID, redirectURI = AppConfig.spotifyRedirect)
    val client: SpotifyClient = CrateIdentity.spotifyClient(tokens = tokens)
    private var callback: CompletableDeferred<Uri>? = null

    val isConnected: Boolean get() = runCatching { tokens.load() }.getOrNull() != null

    suspend fun connect(context: Context) {
        val pkce = PKCE.generate()
        val state = PKCE.randomState()
        val url = auth.authorizeURL(pkce = pkce, state = state)
        val wait = CompletableDeferred<Uri>().also { callback = it }
        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url.toString()))
        val uri = wait.await()
        val code = auth.code(fromCallback = uri.toString(), expectedState = state)
        tokens.save(auth.exchange(code = code, verifier = pkce.verifier, transport = UrlConnectionTransport()))
    }

    /** MainActivity hands every `crate://spotify-callback` here. Returns true if a login was waiting for it. */
    fun handleCallback(uri: Uri): Boolean {
        if (!uri.toString().startsWith(AppConfig.spotifyRedirect)) return false
        callback?.complete(uri) ?: return false
        callback = null
        return true
    }

    /** The login tab was closed without a callback. */
    fun cancelPending() {
        callback?.cancel()
        callback = null
    }

    fun disconnect() = tokens.clear()

    /** Replaces Now with `uris`. Returns the playlist ID (created if needed). */
    suspend fun sendToNow(uris: List<String>, knownID: String?, progress: (Int, Int) -> Unit): String {
        val id = client.ensureNowPlaylist(knownID = knownID)
        NowWriter(writer = client, allowedPlaylistIDs = setOf(id)).replace(playlistID = id, with = uris, progress = progress)
        return id
    }

    suspend fun like(uri: String) = client.saveToLibrary(listOf(uri))
    suspend fun unlike(uri: String) = client.removeFromLibrary(listOf(uri))
    suspend fun contains(uris: List<String>): List<Boolean> = client.libraryContains(uris)
}
