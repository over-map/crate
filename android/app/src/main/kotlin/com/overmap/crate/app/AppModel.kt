package com.overmap.crate.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.overmap.crate.core.CrateLink
import com.overmap.crate.core.GitHubError
import com.overmap.crate.core.OverridesFile
import com.overmap.crate.core.PresetsFile
import com.overmap.crate.core.Query
import com.overmap.crate.core.QueryEngine
import com.overmap.crate.core.SystemRandomNumberGenerator
import com.overmap.crate.core.TrackOverride
import com.overmap.crate.core.VibesFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/** The app's single source of state (the Android twin of iOS `AppModel`). Compose reads its snapshot state. */
class AppModel(private val context: Context) {
    sealed interface Phase {
        data object Onboarding : Phase
        data object Loading : Phase
        data object Ready : Phase
        data class Failed(val message: String) : Phase
    }

    sealed interface SendState {
        data object Idle : SendState
        data class Sending(val done: Int, val total: Int) : SendState
        data class Success(val count: Int) : SendState
        data class Error(val message: String) : SendState
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val secure = SecureStore(context)
    val spotify = SpotifyService(SecureTokenStore(secure))

    var phase: Phase by mutableStateOf(Phase.Onboarding)
    var snapshot: DataSnapshot by mutableStateOf(DataSnapshot())
        private set
    var index: LibraryIndex by mutableStateOf(LibraryIndex.empty)
        private set
    var build: BuildState by mutableStateOf(BuildState())
        private set
    var matchCount by mutableIntStateOf(0)
        private set
    var sendState: SendState by mutableStateOf(SendState.Idle)
    var nowPlaylistID: String? by mutableStateOf(null)
    var isDemo by mutableStateOf(false)
        private set
    var lastSync: Instant? by mutableStateOf(null)
        private set
    /** A track opened from a `crate://track/<id>` link (share screen); MainTabs shows its detail. */
    var linkedTrackID: String? by mutableStateOf(null)
    /** A `crate://` link that arrived before the library was loaded; handled after the first reload. */
    private var pendingLink: Pair<Uri, Boolean>? = null
    /** A `crate://send?preset=<id>` link from outside Crate, waiting for the user's confirmation. */
    var linkedSendPresetID: String? by mutableStateOf(null)
    /** Covers of the last tracks sent to Now, for the Send button's lift-off delight. */
    var lastSentCovers: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * Crate's own launcher shortcuts carry this key, so they send in one tap. Any other app or web page can open a
     * `crate://send` link too, and those ask first. Kept in app-private storage; other apps can't read it.
     */
    val linkKey: String by lazy {
        val prefs = context.getSharedPreferences("crate.links", Context.MODE_PRIVATE)
        prefs.getString("key", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("key", it).apply() }
    }

    var source: DataSource? = null
        private set

    init {
        if (DebugLaunch.isDemo && DemoDataSource.isAvailable(context)) {
            useDemoData()
        } else if (hasGitHubToken && spotify.isConnected) {
            source = GitHubDataSource(context, ::readToken)
            phase = Phase.Loading
        }
    }

    // MARK: Setup

    val hasGitHubToken: Boolean get() = secure.read("github.pat") != null

    fun saveGitHubToken(token: String) {
        secure.write("github.pat", token.trim())
        source = GitHubDataSource(context, ::readToken)
    }

    private fun readToken(): String = secure.read("github.pat") ?: throw GitHubError.Unauthorized(401)

    fun useDemoData() {
        isDemo = true
        source = DemoDataSource(context)
        phase = Phase.Loading
    }

    /** Validates the token by loading the library ("1,015 tracks found" in onboarding). */
    suspend fun validateGitHub(): Int {
        val s = (source ?: throw GitHubError.Unauthorized(401)).load()
        apply(s)
        return s.library.tracks.size
    }

    // MARK: Data

    suspend fun reload() {
        val src = source ?: run { phase = Phase.Onboarding; return }
        try {
            apply(src.load())
            phase = Phase.Ready
            pendingLink?.let { (uri, trusted) -> pendingLink = null; open(uri, trusted) }
        } catch (e: Exception) {
            if (snapshot.library.tracks.isEmpty()) phase = Phase.Failed(e.message ?: "Couldn't load your library")
        }
    }

    fun reloadAsync() { scope.launch { reload() } }

    private fun apply(s: DataSnapshot) {
        snapshot = s
        index = LibraryIndex(s)
        nowPlaylistID = s.presets.nowPlaylistID ?: s.state.nowPlaylistID
        lastSync = Instant.now()
        recount()
        adoptSharedNowPlaylist()
        publishShared()
    }

    val allStyles: Map<String, Set<String>>
        get() = index.families.associate { f -> f.family to f.styles.map { it.first }.toSet() }

    val query: Query get() = build.query(allStyles)

    fun updateBuild(change: (BuildState) -> BuildState) {
        build = change(build)
        recount()
    }

    private fun recount() {
        matchCount = if (build.isEmpty) index.tracks.size else index.count(query)
        if (sendState is SendState.Success) sendState = SendState.Idle
    }

    /** Count if `change` were applied (for "count if added" on chips and relaxation suggestions). */
    fun count(applying: (BuildState) -> BuildState): Int = index.count(applying(build).query(allStyles))

    // MARK: Send

    val sendCount: Int get() = minOf(matchCount, build.limit)

    suspend fun send() {
        val q = query
        val picked = QueryEngine.sampleAndShuffle(index.matchingIDs(q), q.limit, SystemRandomNumberGenerator())
        sendURIs(picked.mapNotNull { id -> index.tracks[id]?.spotifyURI(id) })
    }

    /** Shared by Build's Send and "More like this → Now": progress, success and error go through [sendState]. */
    suspend fun sendURIs(uris: List<String>) {
        if (uris.isEmpty()) return
        lastSentCovers = uris.asSequence().mapNotNull { index.tracks[it.substringAfterLast(':')]?.cover }.take(5).toList()
        // Load them now, so they're ready when the lift-off plays at the end of the send.
        val images = coil3.SingletonImageLoader.get(context)
        lastSentCovers.forEach { images.enqueue(coil3.request.ImageRequest.Builder(context).data(it).build()) }
        sendState = SendState.Sending(0, uris.size)
        if (isDemo) {
            val step = maxOf(1, uris.size / 4)
            for (i in 0..uris.size step step) {
                sendState = SendState.Sending(i, uris.size)
                kotlinx.coroutines.delay(250)
            }
            sendState = SendState.Success(uris.size)
            return
        }
        try {
            val id = spotify.sendToNow(uris, nowPlaylistID) { done, total -> sendState = SendState.Sending(done, total) }
            if (id != nowPlaylistID) {
                nowPlaylistID = id
                runCatching { source?.updatePresets { it.copy(nowPlaylistID = id) } }
                publishShared()
            }
            sendState = SendState.Success(uris.size)
        } catch (_: Exception) {
            sendState = SendState.Error("Couldn't reach Spotify. Nothing in Now was changed.")
        }
    }

    /** Opens the Now playlist in Spotify (or Spotify itself before Now exists); falls back to the web player when
     *  the Spotify app isn't installed. */
    fun openNow() {
        val id = nowPlaylistID
        val app = Uri.parse(if (id != null) "spotify:playlist:$id" else "spotify:")
        val web = Uri.parse(if (id != null) "https://open.spotify.com/playlist/$id" else "https://open.spotify.com")
        for (uri in listOf(app, web)) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: android.content.ActivityNotFoundException) {
                continue
            }
        }
    }

    // MARK: Edits (vibes, overrides, presets) — optimistic, then written to GitHub

    suspend fun editVibes(mutate: (VibesFile) -> VibesFile) {
        snapshot = snapshot.copy(vibes = mutate(snapshot.vibes))
        index = LibraryIndex(snapshot)
        recount()
        publishShared()
        runCatching { source?.updateVibes(mutate) }
    }

    suspend fun editOverride(trackID: String, mutate: (TrackOverride) -> TrackOverride) {
        val m: (OverridesFile) -> OverridesFile = { file ->
            val o = mutate(file.tracks[trackID] ?: TrackOverride()).copy(editedAt = Instant.now().toString())
            file.copy(tracks = if (o.isEmpty) file.tracks - trackID else file.tracks + (trackID to o))
        }
        snapshot = snapshot.copy(overrides = m(snapshot.overrides))
        index = LibraryIndex(snapshot)
        recount()
        publishShared()
        runCatching { source?.updateOverrides(m) }
    }

    suspend fun editPresets(mutate: (PresetsFile) -> PresetsFile) {
        snapshot = snapshot.copy(presets = mutate(snapshot.presets))
        publishShared()
        runCatching { source?.updatePresets(mutate) }
    }

    // MARK: crate:// links (widget, launcher shortcuts, share screen)

    /** `trusted`: the link came from Crate itself (a launcher shortcut carrying [linkKey]). */
    fun open(uri: Uri, trusted: Boolean = false) {
        if (phase != Phase.Ready) { pendingLink = uri to trusted; return }
        when (val d = CrateLink.parse(uri.toString())) {
            is CrateLink.Destination.Now -> openNow()
            is CrateLink.Destination.Track -> if (index.tracks.containsKey(d.id)) linkedTrackID = d.id
            is CrateLink.Destination.Send -> {
                if (snapshot.presets.presets.none { it.id == d.preset }) return
                if (trusted) sendPreset(d.preset) else linkedSendPresetID = d.preset
            }
            else -> Unit
        }
    }

    /** Confirmed from the "Send … to Now?" dialog, or straight from a launcher shortcut. */
    fun sendPreset(id: String) {
        linkedSendPresetID = null
        val preset = snapshot.presets.presets.firstOrNull { it.id == id } ?: return
        updateBuild { BuildState.from(preset.query, allStyles) }
        scope.launch { send() }
    }

    // MARK: Widget and launcher shortcuts

    /** Writes the presets' match sets for the widget and refreshes the launcher shortcuts (see Shared.kt). */
    fun publishShared() = SharedPublisher.publish(context, this)

    private fun adoptSharedNowPlaylist() {
        if (nowPlaylistID != null) return
        val id = SharedPublisher.store(context).loadNowPlaylistID() ?: return
        nowPlaylistID = id
        scope.launch { runCatching { source?.updatePresets { it.copy(nowPlaylistID = id) } } }
    }
}
