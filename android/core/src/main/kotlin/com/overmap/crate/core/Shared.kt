package com.overmap.crate.core

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URI
import java.text.Normalizer
import java.time.Duration
import java.time.Instant

/** Identifiers shared by the app and the widget. Nothing here is secret: the Spotify client ID is public (PKCE). */
object CrateIdentity {
    const val spotifyClientID = "f3d3994b17844ef395d3be6f1cff401a"
    const val spotifyRedirect = "crate://spotify-callback"

    /** A Spotify client using the app's token store. */
    fun spotifyClient(tokens: TokenStore, transport: HTTPTransport = UrlConnectionTransport()): SpotifyClient =
        SpotifyClient(SpotifyAuth(spotifyClientID, spotifyRedirect), transport, tokens)
}

/** `crate://` links between the widget and the app. */
object CrateLink {
    /** Opens the Now playlist in Spotify (the widget's "Listen on Spotify"). */
    const val now = "crate://now"
    /** Opens the app (the widget's "Open Crate" after an error). */
    const val open = "crate://open"

    /** Opens a track's detail in the app. */
    fun track(id: String): String = "crate://track/$id"

    /** Sends a preset from inside the app (fallback when the widget can't send itself). */
    fun send(preset: String): String = "crate://send" + buildQuery(listOf("preset" to preset))

    sealed class Destination {
        data object Now : Destination()
        data object Open : Destination()
        data class Track(val id: String) : Destination()
        data class Send(val preset: String) : Destination()
    }

    /** Parses a `crate://` URL. The Spotify login callback is not a destination (it returns `null`). */
    fun parse(url: String): Destination? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "crate") return null
        val parts = uri.path.orEmpty().split("/").filter { it.isNotEmpty() }
        return when (uri.host) {
            "now" -> Destination.Now
            "open" -> Destination.Open
            "track" -> parts.firstOrNull()?.let { Destination.Track(it) }
            "send" -> parseQuery(uri.rawQuery).firstOrNull { it.name == "preset" }?.value?.let { Destination.Send(it) }
            else -> null
        }
    }
}

/** Spotify track links as the share sheet passes them. */
object SpotifyLink {
    private val patterns = listOf(
        Regex("""open\.spotify\.com/(?:intl-[A-Za-z-]+/)?track/([A-Za-z0-9]{22})"""),
        Regex("""spotify:track:([A-Za-z0-9]{22})"""),
    )

    /**
     * The track ID in `https://open.spotify.com/track/<id>?si=…` (with or without an `intl-xx/` segment) or
     * `spotify:track:<id>`, anywhere in `text`. `null` for albums, playlists, artists and anything else.
     */
    fun trackID(text: String): String? =
        patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }
}

/**
 * A preset as the widget sees it: identity, display lines and the tracks it matched when the app last computed
 * it. The widget can't run the query engine on the full library, so the app resolves each preset's match set.
 */
@Serializable
data class SharedPreset(
    var id: String,
    var name: String,
    var icon: String = "bookmark",
    var color: String = "ink",
    @SerialName("in_widget") var inWidget: Boolean = false,
    /** Small widget line: "Hard Techno, Breaks · 136–142". */
    var summary: String = "",
    /** Lock Screen line: "136–142 · ϟ6–10". */
    var detail: String = "",
    /** Every matching track URI; a send samples `limit` of them. */
    var uris: List<String> = emptyList(),
    var limit: Int = 100,
) {
    /** Tracks a send would write: `min(limit, matches)`. */
    val sendCount: Int get() = minOf(limit, uris.size)

    /**
     * The short label: the number in the name ("Dark 138+" → "138+", "Sunday 90–110" → "90"), else initials
     * ("Italian summer" → "IS"), else the first three letters ("Warehouse" → "WAR").
     */
    val shortLabel: String
        get() {
            val words = name.split(" ").filter { it.isNotEmpty() }
            words.firstOrNull { w -> w.any(::isNumber) }?.let { n ->
                return if (n.length <= 4) n else n.takeWhile { isNumber(it) || it == '+' }
            }
            if (words.size > 1) return words.take(2).joinToString("") { it.first().uppercase() }
            return name.take(3).uppercase()
        }

    private fun isNumber(c: Char): Boolean = Character.getType(c).let {
        it == Character.DECIMAL_DIGIT_NUMBER.toInt() || it == Character.LETTER_NUMBER.toInt() ||
            it == Character.OTHER_NUMBER.toInt()
    }
}

/** `presets.json` in the shared store. Written by the app whenever presets or the library change. */
@Serializable
data class PresetSnapshot(
    var schema: Int = 1,
    @SerialName("generated_at") @Serializable(with = InstantSerializer::class) var generatedAt: Instant = Instant.now(),
    @SerialName("now_playlist_id") var nowPlaylistID: String? = null,
    var presets: List<SharedPreset> = emptyList(),
) {
    /** The medium widget's presets: the first four marked "In widget". */
    val widgetPresets: List<SharedPreset> get() = presets.filter { it.inWidget }.take(4)

    fun preset(id: String): SharedPreset? = presets.firstOrNull { it.id == id }

    /**
     * Spoken or typed names ("run 160", "Dark 138") to a preset: exact match first, then prefix, then contains,
     * ignoring case, accents, spaces and punctuation. (Swift: `preset(named:)`.)
     */
    fun presetNamed(query: String): SharedPreset? {
        val q = fold(query)
        if (q.isEmpty()) return null
        return presets.firstOrNull { fold(it.name) == q }
            ?: presets.firstOrNull { fold(it.name).startsWith(q) }
            ?: presets.firstOrNull { fold(it.name).contains(q) }
    }

    companion object {
        internal fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                .lowercase()
                .filter { it.isLetterOrDigit() }
    }
}

/** Why a widget send didn't happen. Names are stored in the status file. */
@Serializable
@Suppress("EnumEntryName")
enum class SendFailure {
    loginExpired, noTracks, unknownPreset, noData, network;

    /** Headline: "Spotify login expired". */
    val title: String
        get() = when (this) {
            loginExpired -> "Spotify login expired"
            noTracks -> "No tracks match"
            unknownPreset -> "Preset not found"
            noData -> "Open Crate once"
            network -> "Couldn't reach Spotify"
        }

    /** Plain words with the one fix. `preset` is the preset name when known. */
    fun explanation(preset: String?): String = when (this) {
        loginExpired -> "Spotify login expired. Open Crate to sign in again; Now wasn't changed."
        noTracks -> "No tracks match ${preset ?: "this preset"} right now."
        unknownPreset -> "That preset isn't in Crate any more. Pick another one."
        noData -> "Open Crate once so the widget can load your presets."
        network -> "Couldn't reach Spotify. Now wasn't changed; try again in a moment."
    }

    companion object {
        /** Maps any error to a failure: login problems → [loginExpired], everything else → [network]. */
        operator fun invoke(error: Throwable): SendFailure = when (error) {
            is SpotifyError.NotLoggedIn, is SpotifyError.Unauthorized -> loginExpired
            is SendFailureException -> error.failure
            else -> network
        }
    }
}

/** A [SendFailure] thrown as an exception. */
class SendFailureException(val failure: SendFailure) : Exception(failure.title)

/** The last widget send, so every widget shows the same Sending / Sent / Error state. */
@Serializable
data class SendStatus(
    var phase: Phase,
    @SerialName("preset_id") var presetID: String,
    var count: Int? = null,
    var failure: SendFailure? = null,
    @Serializable(with = InstantSerializer::class) var at: Instant = Instant.now(),
) {
    @Serializable
    @Suppress("EnumEntryName")
    enum class Phase { sending, sent, failed }

    /** The state a widget should show at `date`: sent holds 30 s, an error stays until the next success. */
    fun visible(at: Instant): SendStatus? {
        val age = Duration.between(this.at, at).toMillis() / 1000.0
        return when (phase) {
            Phase.sending -> if (age < sendingTimeout) this else null
            Phase.sent -> if (age < sentHold) this else null
            Phase.failed -> this
        }
    }

    companion object {
        /** How long "Sent" stays before the widget returns to idle, in seconds. */
        const val sentHold = 30.0
        /** A "sending" older than this (seconds) was interrupted; show idle instead. */
        const val sendingTimeout = 60.0
    }
}

/** Files read and written by the app and the widget (e.g. `File(context.filesDir, "Shared")`). */
class SharedStore(val directory: File) {
    @Serializable
    private class NowFile(val id: String)

    fun loadSnapshot(): PresetSnapshot? = read(PresetSnapshot.serializer(), "presets.json")
    fun save(snapshot: PresetSnapshot) = write(PresetSnapshot.serializer(), snapshot, "presets.json")

    fun loadStatus(): SendStatus? = read(SendStatus.serializer(), "status.json")
    fun save(status: SendStatus) = write(SendStatus.serializer(), status, "status.json")

    /** The Now playlist ID found or created by a widget send (the app copies it into presets.json). */
    fun loadNowPlaylistID(): String? = read(NowFile.serializer(), "now.json")?.id
    fun saveNowPlaylistID(id: String) = write(NowFile.serializer(), NowFile(id), "now.json")

    private fun <T> read(serializer: KSerializer<T>, name: String): T? = runCatching {
        val file = File(directory, name)
        if (!file.exists()) return null
        CrateJSON.json.decodeFromString(serializer, file.readText())
    }.getOrNull()

    private fun <T> write(serializer: KSerializer<T>, value: T, name: String) {
        // Losing a status write only costs the widget one stale frame.
        runCatching {
            directory.mkdirs()
            writeAtomically(File(directory, name), CrateJSON.encode(serializer, value))
        }
    }
}

/** A finished send. */
data class SendResult(var preset: SharedPreset, var count: Int, var playlistID: String)

/** The outcome of [PresetSender.send] (Swift: `Result<SendResult, SendFailure>`). */
sealed class SendOutcome {
    data class Success(val result: SendResult) : SendOutcome()
    data class Failure(val failure: SendFailure) : SendOutcome()

    /** The result, or throws [SendFailureException]. */
    fun get(): SendResult = when (this) {
        is Success -> result
        is Failure -> throw SendFailureException(failure)
    }
}

/**
 * Sends a preset to Now without the app UI: the widget button and shortcuts. Writes go through [NowWriter], so
 * only Now can be changed.
 */
class PresetSender(val store: SharedStore, val spotify: NowPlaylistTarget) {
    /**
     * Samples `limit` of the preset's tracks, shuffles them and replaces Now. Records Sending, then Sent or the
     * failure, in the status file. `onStatus` runs after each status write (the widget refreshes).
     */
    suspend fun send(
        presetID: String,
        rng: RandomNumberGenerator = SystemRandomNumberGenerator(),
        now: () -> Instant = { Instant.now() },
        onStatus: () -> Unit = {},
    ): SendOutcome {
        val snapshot = store.loadSnapshot() ?: return SendOutcome.Failure(SendFailure.noData)
        val preset = snapshot.preset(presetID) ?: return SendOutcome.Failure(SendFailure.unknownPreset)
        if (preset.uris.isEmpty()) {
            record(SendStatus(SendStatus.Phase.failed, presetID, failure = SendFailure.noTracks, at = now()), onStatus)
            return SendOutcome.Failure(SendFailure.noTracks)
        }
        record(SendStatus(SendStatus.Phase.sending, presetID, at = now()), onStatus)
        val uris = QueryEngine.sampleAndShuffle(preset.uris, preset.limit, rng)
        return try {
            val id = replaceNow(uris, snapshot)
            record(SendStatus(SendStatus.Phase.sent, presetID, count = uris.size, at = now()), onStatus)
            SendOutcome.Success(SendResult(preset, uris.size, id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = SendFailure(e)
            record(SendStatus(SendStatus.Phase.failed, presetID, failure = failure, at = now()), onStatus)
            SendOutcome.Failure(failure)
        }
    }

    /**
     * Replaces Now with `with` (already sampled and ordered) and returns its ID, creating Now if nobody knows it
     * yet. "More like this → Now" uses this directly.
     */
    suspend fun replaceNow(with: List<String>, snapshot: PresetSnapshot? = null): String {
        // The app's ID (presets.json) wins; the one a widget recorded only fills in until the app adopts it.
        val known = (snapshot ?: store.loadSnapshot())?.nowPlaylistID ?: store.loadNowPlaylistID()
        val id = spotify.ensureNowPlaylist(known)
        NowWriter(spotify, setOf(id)).replace(id, with)
        if (id != known) store.saveNowPlaylistID(id)
        return id
    }

    private fun record(status: SendStatus, onStatus: () -> Unit) {
        store.save(status)
        onStatus()
    }
}
