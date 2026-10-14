package com.overmap.crate.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class PresetSenderTests {
    class FakeNow : NowPlaylistTarget {
        val known = mutableListOf<String?>()
        val writes = mutableListOf<Triple<String, String, List<String>>>()
        var error: Exception? = null

        override suspend fun ensureNowPlaylist(knownID: String?): String {
            known += knownID
            error?.let { throw it }
            return knownID ?: "NEW"
        }

        override suspend fun writePlaylistItems(method: String, playlistID: String, uris: List<String>) {
            writes += Triple(method, playlistID, uris)
        }
    }

    private fun store() = SharedStore(Files.createTempDirectory("crate-shared-").toFile())

    private fun snapshot(uris: List<String>, limit: Int = 100, now: String? = null) = PresetSnapshot(nowPlaylistID = now, presets = listOf(
        SharedPreset(id = "dark", name = "Dark 138+", inWidget = true, uris = uris, limit = limit),
        SharedPreset(id = "run", name = "Run 160", inWidget = true),
        SharedPreset(id = "it", name = "Italian summer"),
    ))

    @Test fun sendsASampleToNowAndRecordsSent() = runTest {
        val s = store()
        val spotify = FakeNow()
        s.save(snapshot((0 until 250).map { "spotify:track:$it" }, limit = 120, now = "NOW"))
        var statusCalls = 0
        val sent = PresetSender(s, spotify).send(presetID = "dark", rng = SeededGenerator(1), onStatus = { statusCalls++ }).get()
        assertTrue(sent.count == 120 && sent.playlistID == "NOW")
        assertEquals(listOf<String?>("NOW"), spotify.known)
        assertEquals(listOf("PUT", "POST"), spotify.writes.map { it.first })
        assertEquals(120, spotify.writes.flatMap { it.third }.toSet().size)
        assertTrue(s.loadStatus()?.phase == SendStatus.Phase.sent && s.loadStatus()?.count == 120)
        assertNull("a known ID isn't re-recorded", s.loadNowPlaylistID())
        assertEquals(2, statusCalls)
    }

    @Test fun recordsANewlyCreatedNowPlaylist() = runTest {
        val s = store()
        val spotify = FakeNow()
        s.save(snapshot(listOf("spotify:track:1")))
        PresetSender(s, spotify).send(presetID = "dark").get()
        assertEquals("NEW", s.loadNowPlaylistID())
        PresetSender(s, spotify).send(presetID = "dark").get()
        assertEquals("the second send reuses the playlist the first one created", listOf(null, "NEW"), spotify.known)
    }

    @Test fun failuresAreRecordedAndNothingIsWritten() = runTest {
        val s = store()
        val spotify = FakeNow()
        assertEquals(SendOutcome.Failure(SendFailure.noData), PresetSender(s, spotify).send(presetID = "dark"))
        s.save(snapshot(emptyList()))
        assertEquals(SendOutcome.Failure(SendFailure.unknownPreset), PresetSender(s, spotify).send(presetID = "nope"))
        assertEquals(SendOutcome.Failure(SendFailure.noTracks), PresetSender(s, spotify).send(presetID = "dark"))
        assertEquals(SendFailure.noTracks, s.loadStatus()?.failure)
        s.save(snapshot(listOf("spotify:track:1")))
        spotify.error = SpotifyError.Unauthorized
        assertEquals(SendOutcome.Failure(SendFailure.loginExpired), PresetSender(s, spotify).send(presetID = "dark"))
        spotify.error = SpotifyError.Http(500, null)
        assertEquals(SendOutcome.Failure(SendFailure.network), PresetSender(s, spotify).send(presetID = "dark"))
        assertTrue(spotify.writes.isEmpty())
        assertEquals(SendFailure.network, s.loadStatus()?.failure)
    }

    @Test fun statusVisibility() {
        val t = Instant.ofEpochSecond(1000)
        val sent = SendStatus(SendStatus.Phase.sent, presetID = "p", count = 8, at = t)
        assertNotNull(sent.visible(at = t.plusSeconds(29)))
        assertNull("Sent holds 30 s", sent.visible(at = t.plusSeconds(31)))
        val failed = SendStatus(SendStatus.Phase.failed, presetID = "p", failure = SendFailure.loginExpired, at = t)
        assertNotNull("an error stays", failed.visible(at = t.plusSeconds(86_400)))
        val sending = SendStatus(SendStatus.Phase.sending, presetID = "p", at = t)
        assertNull("an interrupted send goes back to idle", sending.visible(at = t.plusSeconds(120)))
        assertEquals("No tracks match Dark right now.", SendFailure.noTracks.explanation(preset = "Dark"))
    }

    @Test fun statusFileRoundTrip() {
        val s = store()
        val status = SendStatus(SendStatus.Phase.failed, presetID = "p", failure = SendFailure.loginExpired, at = Instant.ofEpochSecond(1000))
        s.save(status)
        assertEquals(status, s.loadStatus())
        val text = java.io.File(s.directory, "status.json").readText()
        assertTrue(text.contains("\"preset_id\"") && text.contains("\"loginExpired\"") && text.contains("1970-01-01T00:16:40Z"))
    }

    @Test fun presetNamesAndLabels() {
        val snap = snapshot(emptyList())
        assertEquals("run", snap.presetNamed("run 160")?.id)
        assertEquals("dark", snap.presetNamed("Dark 138")?.id)
        assertEquals("it", snap.presetNamed("italian")?.id)
        assertNull(snap.presetNamed("Run 180"))
        assertEquals(listOf("dark", "run"), snap.widgetPresets.map { it.id })
        assertEquals("138+", SharedPreset(id = "a", name = "Dark 138+").shortLabel)
        assertEquals("90", SharedPreset(id = "a", name = "Sunday 90–110").shortLabel)
        assertEquals("IS", SharedPreset(id = "a", name = "Italian summer").shortLabel)
        assertEquals("WAR", SharedPreset(id = "a", name = "Warehouse").shortLabel)
    }

    @Test fun spotifyLinks() {
        val id = "4uLU6hMCjMI75M1A2tKUQC"
        assertEquals(id, SpotifyLink.trackID("https://open.spotify.com/track/$id?si=abc123"))
        assertEquals(id, SpotifyLink.trackID("Listen: https://open.spotify.com/intl-it/track/$id"))
        assertEquals(id, SpotifyLink.trackID("spotify:track:$id"))
        assertNull(SpotifyLink.trackID("https://open.spotify.com/album/$id"))
        assertNull(SpotifyLink.trackID("https://example.com"))
    }

    @Test fun links() {
        assertEquals(CrateLink.Destination.Now, CrateLink.parse(CrateLink.now))
        assertEquals(CrateLink.Destination.Open, CrateLink.parse(CrateLink.open))
        assertEquals(CrateLink.Destination.Track("abc"), CrateLink.parse(CrateLink.track("abc")))
        assertEquals(CrateLink.Destination.Send("dark-138"), CrateLink.parse(CrateLink.send(preset = "dark-138")))
        assertEquals(CrateLink.Destination.Send("a b&c"), CrateLink.parse(CrateLink.send(preset = "a b&c")))
        assertNull(CrateLink.parse("crate://spotify-callback?code=x"))
        assertNull(CrateLink.parse("https://open.spotify.com/track/1"))
    }

    @Test fun identity() {
        assertEquals("f3d3994b17844ef395d3be6f1cff401a", CrateIdentity.spotifyClientID)
        assertEquals("crate://spotify-callback", CrateIdentity.spotifyRedirect)
    }
}
