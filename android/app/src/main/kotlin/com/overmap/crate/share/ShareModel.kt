package com.overmap.crate.share

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.overmap.crate.app.AppModel
import com.overmap.crate.app.SharedPublisher
import com.overmap.crate.core.PresetSender
import com.overmap.crate.core.SendFailure
import com.overmap.crate.core.SpotifyLink
import com.overmap.crate.core.SystemRandomNumberGenerator
import com.overmap.crate.core.Track
import com.overmap.crate.ui.components.ConfidenceSummary
import com.overmap.crate.ui.theme.Family
import com.overmap.crate.ui.theme.VibeSymbols
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The share sheet's data (design 6s, 3m, 3n; Swift: `ShareModel`). Uses the app's loaded library (same process);
 * writes go to Spotify only (Like, or "More like this → Now" through [PresetSender.replaceNow]).
 */
class ShareModel(private val context: Context, private val app: AppModel) {
    data class VibeTag(val id: String, val name: String, val icon: String)

    data class TrackCard(
        val id: String,
        val title: String,
        val artist: String,
        val coverURL: String?,
        val family: Family,
        val styleName: String?,
        val track: Track,
        val confidence: ConfidenceSummary?,
        val vibes: List<VibeTag>,
    )

    sealed interface Phase {
        data object Loading : Phase
        data class InLibrary(val card: TrackCard) : Phase
        /** [liked]: already in Liked Songs, waiting for tonight's sync. */
        data class NotInLibrary(val id: String, val liked: Boolean) : Phase
        data object NotATrack : Phase
        /** The app couldn't load the library (never set up, or offline on first run). */
        data class NoData(val id: String) : Phase
    }

    sealed interface Action {
        data object Idle : Action
        data object Working : Action
        data class Done(val text: String) : Action
        data class Failed(val text: String) : Action
    }

    var phase: Phase by mutableStateOf(Phase.Loading)
        private set
    var action: Action by mutableStateOf(Action.Idle)
        private set

    suspend fun load(text: String?) {
        val id = text?.let(SpotifyLink::trackID) ?: run { phase = Phase.NotATrack; return }
        if (app.phase != AppModel.Phase.Ready || app.index.tracks.isEmpty()) app.reload()
        if (app.index.tracks.isEmpty()) { phase = Phase.NoData(id); return }

        val index = app.index
        val track = index.tracks[id] ?: run {
            phase = Phase.NotInLibrary(id, liked = false)
            val liked = runCatching { app.spotify.contains(listOf("spotify:track:$id")).firstOrNull() }.getOrNull() ?: false
            if (liked) phase = Phase.NotInLibrary(id, liked = true)
            return
        }
        val snapshot = app.snapshot
        val override = index.overrides[id]
        val effective = index.effective[id]
        val family = Family.of(effective?.family)
        val vibes = snapshot.vibes.ordered.filter { index.vibeMembers[it.id]?.contains(id) == true }.map {
            VibeTag(it.id, it.vibe.name, it.vibe.icon ?: VibeSymbols.defaults[it.id] ?: "circle")
        }
        phase = Phase.InLibrary(TrackCard(
            id = id,
            title = track.title ?: "Unknown title",
            artist = track.artists?.joinToString(", ") ?: "",
            coverURL = track.cover,
            family = family,
            styleName = effective?.primaryStyle?.let { snapshot.taxonomy.label(it).styleDisplay },
            track = track,
            confidence = track.consensus?.let { c ->
                ConfidenceSummary.from(c, override?.family?.let(Family::of)?.takeIf { it != Family.NONE })
            },
            vibes = vibes,
        ))
    }

    val canSendMoreLikeThis: Boolean
        get() = (phase as? Phase.InLibrary)?.let { app.snapshot.vectors?.contains(it.card.id) } == true

    /** The 100 library tracks that sound closest, shuffled, replace Now (same as the app's track detail). */
    suspend fun sendMoreLikeThis() {
        val card = (phase as? Phase.InLibrary)?.card ?: return
        val vectors = app.snapshot.vectors ?: return
        action = Action.Working
        val tracks = app.index.tracks
        val ids = runCatching {
            vectors.moreLikeThis(card.id, count = 100, candidates = tracks.keys, rng = SystemRandomNumberGenerator())
        }.getOrDefault(emptyList())
        val uris = ids.mapNotNull { id -> tracks[id]?.spotifyURI(id) }
        if (uris.isEmpty()) { action = Action.Failed("No sound data for this track yet."); return }
        if (app.isDemo) {
            // Demo data has no Spotify login: show the flow without writing anything (like AppModel.sendURIs).
            delay(800)
            action = Action.Done("Sent ${uris.size} tracks to Now")
            return
        }
        action = try {
            val id = PresetSender(SharedPublisher.store(context), app.spotify.client).replaceNow(uris)
            if (app.nowPlaylistID == null) app.nowPlaylistID = id
            Action.Done("Sent ${uris.size} tracks to Now")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Action.Failed(SendFailure(e).explanation(null))
        }
    }

    suspend fun like(id: String) {
        action = Action.Working
        if (app.isDemo) {
            delay(500)
            phase = Phase.NotInLibrary(id, liked = true)
            action = Action.Idle
            return
        }
        action = try {
            app.spotify.like("spotify:track:$id")
            phase = Phase.NotInLibrary(id, liked = true)
            Action.Idle
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Action.Failed(
                if (SendFailure(e) == SendFailure.loginExpired) "Spotify login expired. Open Crate to sign in again."
                else "Couldn't reach Spotify. Try again in a moment.",
            )
        }
    }
}
