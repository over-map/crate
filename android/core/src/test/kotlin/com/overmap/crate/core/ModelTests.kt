package com.overmap.crate.core

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private fun bytes(text: String) = text.encodeToByteArray()

class ModelTests {
    @Test fun libraryFromSpecExample() {
        val json = """
        {"schema": 1, "generated_at": "2026-10-12T03:12:00Z", "tracks": {"abc": {
          "uri": "spotify:track:abc", "isrc": "GBXXX", "title": "T", "artists": ["A"], "duration_ms": 412000,
          "cover": "https://i.scdn.co/image/x", "liked_at": "2024-05-01T10:00:00Z", "status": "tagged",
          "source": {"deezer_id": 123, "analysed_at": "x", "model_version": "2026.10.1"},
          "auto": {"genres": {"Electronic": 0.97}, "styles": {"Electronic---Breakbeat": 0.58},
            "bpm": 138.0, "bpm_conf": 0.91, "bpm_candidates": [138.0, 69.0], "key": "8A", "key_conf": 0.72,
            "energy": 7, "moods": {"dark": 0.81}, "vocal": 0.12, "future_field": true}},
          "def": {}}}
        """
        val lib = CrateJSON.decode<LibraryFile>(bytes(json), "library.json")
        val t = lib.tracks.getValue("abc")
        assertTrue(t.status == TrackStatus.tagged && t.durationMS == 412000 && t.source?.deezerID == 123L)
        assertTrue(t.auto?.bpmCandidates == listOf(138.0, 69.0) && t.auto?.keyConf == 0.72 &&
            t.auto?.styles?.get("Electronic---Breakbeat") == 0.58)
        val empty = lib.tracks.getValue("def")
        assertTrue(empty.status == TrackStatus.pending && empty.auto == null)
        assertEquals("spotify:track:def", empty.spotifyURI("def"))
    }

    @Test fun statuses() {
        fun decode(s: String) = CrateJSON.json.decodeFromString(TrackStatus.serializer(), "\"$s\"")
        assertEquals(TrackStatus.noPreview, decode("no_preview"))
        assertEquals(TrackStatus.error, decode("something_new"))
    }

    @Test fun newerSchemaIsRefused() {
        val e = assertThrows(CrateDataError.UnsupportedSchema::class.java) {
            CrateJSON.decode<LibraryFile>(bytes("""{"schema": 2, "tracks": {}}"""), "library.json")
        }
        assertEquals(CrateDataError.UnsupportedSchema("library.json", 2, 1), e)
        assertTrue(CrateDataError.UnsupportedSchema("x", 2, 1).errorDescription.contains("Update the app"))
    }

    @Test fun missingSchemaIsAccepted() {
        val o = CrateJSON.decode<OverridesFile>(bytes("""{"tracks": {"x": {"bpm": 140, "moods_add": ["dark"]}}}"""), "overrides.json")
        assertTrue(o.tracks["x"]?.bpm == 140.0 && o.tracks["x"]?.moodsAdd == listOf("dark"))
    }

    @Test fun presetsAcceptArrayAndObjectAndEncodeObject() {
        val array = """[{"id": "p1", "name": "Run 160", "icon": "figure.run", "color": "red", "in_widget": true, "query": {"bpm": {"min": 156, "max": 176}}}]"""
        val fromArray = CrateJSON.decode<PresetsFile>(bytes(array), "presets.json")
        assertEquals(Query.BPMRange(min = 156.0, max = 176.0), fromArray.presets.first().query.bpm)
        assertTrue(fromArray.nowPlaylistID == null && fromArray.widgetPresets.size == 1)

        val obj = """{"schema": 1, "now_playlist_id": "NOW", "presets": [{"id": "p2", "name": "x"}]}"""
        val fromObject = CrateJSON.decode<PresetsFile>(bytes(obj), "presets.json")
        assertEquals("NOW", fromObject.nowPlaylistID)
        assertTrue(fromObject.presets.first().color == "ink" && !fromObject.presets.first().inWidget)

        val encoded = jsonObject(CrateJSON.encode(fromObject))
        assertEquals("NOW", encoded["now_playlist_id"]?.jsonPrimitive?.content)
        assertEquals(1, encoded["schema"]?.jsonPrimitive?.int)
        assertEquals(false, encoded["presets"]?.jsonArray?.first()?.jsonObject?.get("in_widget")?.jsonPrimitive?.boolean)

        assertThrows(CrateDataError::class.java) {
            CrateJSON.decode<PresetsFile>(bytes("""{"schema": 3, "presets": []}"""), "presets.json")
        }
    }

    @Test fun queryRoundTrip() {
        val q = Query(stylesInclude = listOf("Electronic---Breakbeat"), bpm = Query.BPMRange(136.0, 142.0, halfDouble = true),
            energy = Query.EnergyRange(6, 10), moodsInclude = listOf("dark"), key = Query.KeyFilter("8A", compatible = true),
            vocal = Query.Vocal.instrumental, limit = 50)
        val back = CrateJSON.json.decodeFromString(Query.serializer(), CrateJSON.encode(q).decodeToString())
        assertEquals(q, back)
        assertEquals(q, Query.fromJson(q.toJson()))
        val text = CrateJSON.encode(q).decodeToString()
        assertTrue(text.contains("\"half_double\"") && text.contains("\"styles_include\""))
    }

    @Test fun otherFiles() {
        val state = CrateJSON.decode<StateFile>(bytes("""{"schema":1,"now_playlist_id":"N","weekly_playlist_id":"W","last_sync":"t","cursors":{}}"""), "state.json")
        assertTrue(state.nowPlaylistID == "N" && state.weeklyPlaylistID == "W" && state.lastSync == "t")

        val taxonomy = CrateJSON.decode<TaxonomyFile>(bytes("""{"schema":1,"genres":{"Funk / Soul":["Funk / Soul---Funk"]},"aliases":{"Funk / Soul":"Funk & Soul"}}"""), "taxonomy.json")
        assertEquals("Funk / Soul", taxonomy.genre(ofStyle = "Funk / Soul---Funk"))
        assertEquals("Funk & Soul", taxonomy.label(forStyle = "Funk / Soul---Funk").genreDisplay)

        val weekly = CrateJSON.decode<WeeklyFile>(bytes("""{"schema":1,"week":41,"tracks":[{"id":"w1","auto":{"bpm":120}}]}"""), "weekly.json")
        assertTrue(weekly.week == "41" && weekly.tracks.first().auto?.bpm == 120.0)
        val weeklyMap = CrateJSON.decode<WeeklyFile>(bytes("""{"week":"2026-W41","tracks":{"b":{},"a":{"title":"x"}}}"""), "weekly.json")
        assertEquals(listOf("a", "b"), weeklyMap.tracks.map { it.id })

        val health = CrateJSON.decode<HealthFile>(bytes("""{"schema":1,"problems":[{"level":"broken","message":"Spotify login rejected","runbook":"docs/runbook.md#spotify-token"}]}"""), "health.json")
        assertEquals(HealthLevel.broken, health.effectiveLevel)
        assertEquals(HealthLevel.healthy, CrateJSON.decode<HealthFile>(bytes("{}"), "health.json").effectiveLevel)
        val odd = CrateJSON.decode<HealthFile>(bytes("""{"status":"meh","problems":[{"level":"x"}],"credentials":5}"""), "health.json")
        assertTrue(odd.status == HealthLevel.warning && odd.problems.isEmpty() && odd.credentials.isEmpty())
    }
}

class FamilyTests {
    @Test fun decodesFamilyAndConsensus() {
        val json = """
        {"schema":1,"tracks":{
          "a":{"family":"techno","consensus":{"family":"techno","votes":3,"sources":4,"audio_agrees":true,
               "by_source":{"audio":["techno"],"discogs":["ambient","techno"]}}},
          "b":{"family":"house","consensus":{"family":"house","votes":1,"sources":1,"audio_agrees":null,"by_source":{}}},
          "c":{}}}
        """
        val lib = CrateJSON.decode<LibraryFile>(bytes(json), "library.json")
        val a = lib.tracks.getValue("a")
        assertEquals("techno", a.family)
        assertEquals(Consensus("techno", 3, 4, true, mapOf("audio" to listOf("techno"), "discogs" to listOf("ambient", "techno"))), a.consensus)
        assertNull(lib.tracks["b"]?.consensus?.audioAgrees)
        assertTrue(lib.tracks["c"]?.family == null && lib.tracks["c"]?.consensus == null)

        val back = CrateJSON.decode<LibraryFile>(CrateJSON.encode(lib), "library.json")
        assertEquals(lib, back)
    }

    @Test fun overrideFamilyWins() {
        val track = Track(family = "rock & indie")
        val ov = CrateJSON.json.decodeFromString(TrackOverride.serializer(), """{"family":"indie dance"}""")
        assertFalse(ov.isEmpty)
        assertEquals("indie dance", EffectiveTags(track, ov).family)
        assertEquals("rock & indie", EffectiveTags(track, null).family)
        assertEquals("empty override falls through, like Python's `or`", "rock & indie", EffectiveTags(track, TrackOverride(family = "")).family)
        assertNull(EffectiveTags(Track(), null).family)
        assertTrue(TrackOverride().isEmpty && TrackOverride(moodsAdd = emptyList()).isEmpty)
    }

    @Test fun queryAndTaxonomy() {
        val q = Query.fromJson("""{"families_include":["techno"]}""")
        assertTrue(q.familiesInclude == listOf("techno") && !q.isUnconstrained)
        assertFalse("unknown family fails", QueryEngine.matches(q, Track(), null))
        assertTrue(QueryEngine.matches(q, Track(family = "techno"), null))

        val tax = CrateJSON.decode<TaxonomyFile>(bytes("""{"schema":1,"genres":{},"families":["techno","house"]}"""), "taxonomy.json")
        assertEquals(listOf("techno", "house"), tax.families)
    }
}

class DesignV2Tests {
    @Test fun familyStyles() {
        val q = Query.fromJson("""{"family_styles":{"house":["Electronic---Techno"]}}""")
        assertTrue(q.familyStyles == mapOf("house" to listOf("Electronic---Techno")) && !q.isUnconstrained)
        val houseTechno = EffectiveTags(primaryStyle = "Electronic---Techno", primaryGenre = "Electronic", family = "house")
        val technoTechno = EffectiveTags(primaryStyle = "Electronic---Techno", primaryGenre = "Electronic", family = "techno")
        assertTrue(QueryEngine.matches(q, houseTechno))
        assertFalse("a style counts only under its family", QueryEngine.matches(q, technoTechno))
        assertFalse("unknown family", QueryEngine.matches(q, EffectiveTags(primaryStyle = "Electronic---Techno")))
        val empty = Query(familyStyles = mapOf("house" to emptyList()))
        assertFalse(QueryEngine.matches(empty, houseTechno))
        assertEquals(q, Query.fromJson(CrateJSON.encode(q).decodeToString()))
    }

    @Test fun vibeOrder() {
        val json = """
        {"schema":1,"order":["b","ghost","a","b"],"vibes":{
          "a":{"name":"Acid"},"b":{"name":"Breaks"},"z":{"name":"Calm"},"y":{"name":"Calm"},"x":{"name":"Afro"}}}
        """
        val file = CrateJSON.decode<VibesFile>(bytes(json), "vibes.json")
        assertEquals(listOf("b", "ghost", "a", "b"), file.order)
        assertEquals(listOf("b", "a", "x", "y", "z"), file.ordered.map { it.id })
        assertEquals(listOf("q"), VibesFile(vibes = mapOf("q" to Vibe("Q"))).ordered.map { it.id })
        val encoded = jsonObject(CrateJSON.encode(file))
        assertEquals(listOf("b", "ghost", "a", "b"), encoded["order"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test fun familyNames() {
        val tax = CrateJSON.decode<TaxonomyFile>(bytes("""{"schema":1,"families":["breaks & bass","techno"],"family_names":{"breaks & bass":"Breaks & bass"}}"""), "taxonomy.json")
        assertEquals("Breaks & bass", tax.displayName(family = "breaks & bass"))
        assertEquals("techno", tax.displayName(family = "techno"))
    }

    @Test fun weeklyKindFamilyReason() {
        val json = """
        {"schema":1,"week":"2026-W42","candidates":1800,"slots":{"taste:techno":4},"tracks":[
          {"key":"k1","id":"s1","uri":"spotify:track:s1","kind":"taste","family":"techno","reason":"Like 412 of your techno tracks","score":0.8},
          {"id":"s2","kind":"wild"}]}
        """
        val weekly = CrateJSON.decode<WeeklyFile>(bytes(json), "weekly.json")
        assertEquals(1800, weekly.candidates)
        val first = weekly.tracks.first()
        assertTrue(first.kind == WeeklyTrack.Kind.taste && first.family == "techno" && first.key == "k1")
        assertEquals("Like 412 of your techno tracks", first.reason)
        assertNull("unknown kind decodes as null", weekly.tracks[1].kind)
        val map = CrateJSON.decode<WeeklyFile>(bytes("""{"tracks":{"s3":{"kind":"discovery"}}}"""), "weekly.json")
        assertTrue(map.tracks.first().id == "s3" && map.tracks.first().kind == WeeklyTrack.Kind.discovery)
        val withVector = CrateJSON.decode<WeeklyFile>(bytes("""{"tracks":[{"id":"v","vector":[0.5,1]}]}"""), "weekly.json")
        assertEquals(listOf(0.5, 1.0), withVector.tracks.first().vector)
    }

    @Test fun dailyDate() {
        val rome = ZoneId.of("Europe/Rome")
        val daily = CrateJSON.decode<WeeklyFile>(bytes("""{"schema":1,"date":"2026-10-13","week":"2026-10-13","tracks":[]}"""), "weekly.json")
        assertTrue(daily.date == "2026-10-13" && daily.week == "2026-10-13")
        assertEquals(java.time.LocalDate.of(2026, 10, 13), daily.mixDay(rome))

        // Only `week` as a day.
        assertEquals(12, WeeklyFile(week = "2026-10-12").mixDay(rome)?.dayOfMonth)

        // Old weekly file: no date, ISO week label; falls back to generated_at's local day.
        val old = CrateJSON.decode<WeeklyFile>(bytes("""{"schema":1,"week":"2026-W42","generated_at":"2026-10-12T23:30:00Z","tracks":[]}"""), "weekly.json")
        assertNull(old.date)
        assertEquals("23:30Z is the 13th in Rome", 13, old.mixDay(rome)?.dayOfMonth)
        assertNull(WeeklyFile(week = "2026-W42").mixDay(rome))

        assertNull(WeeklyFile.parseISODay("2026-02-30"))
        assertNull(WeeklyFile.parseISODay("2026-W42"))
        assertNull(WeeklyFile.parseISODay("26-10-13"))
        // A non-string date is tolerated.
        val odd = CrateJSON.decode<WeeklyFile>(bytes("""{"date":20261013,"tracks":[]}"""), "weekly.json")
        assertNull(odd.date)
    }
}
