package com.overmap.crate.core

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
private data class CamelotVectors(
    val keys: List<Key>,
    val names: List<Name>,
    val compatible: List<Compatible>,
    val invalid: List<String>,
) {
    @Serializable data class Key(val tonic: String, val scale: String, val camelot: String)
    @Serializable data class Name(val camelot: String, val name: String)
    @Serializable data class Compatible(val camelot: String, val set: List<String>)
}

class CamelotTests {
    private val vectors = CrateJSON.json.decodeFromString(CamelotVectors.serializer(), Repo.spec("camelot_vectors.json").decodeToString())

    @Test fun keysConvert() {
        for (k in vectors.keys) assertEquals("${k.tonic} ${k.scale}", k.camelot, Camelot(tonic = k.tonic, scale = k.scale)?.code)
    }

    @Test fun names() {
        for (n in vectors.names) assertEquals(n.camelot, n.name, Camelot(n.camelot)?.name)
        assertEquals("F♯ minor", Camelot("11A")?.name)
        assertEquals("D♭ major", Camelot("3B")?.name)
    }

    @Test fun compatibleSets() {
        for (c in vectors.compatible) assertEquals(c.camelot, c.set, Camelot(c.camelot)?.compatible?.map { it.code })
    }

    @Test fun invalidCodes() {
        for (code in vectors.invalid) { assertNull(code, Camelot(code)); assertFalse(Camelot.isValid(code)) }
        assertNull(Camelot("08A"))
        assertEquals("8A", Camelot(" 8a ")?.code)
    }

    @Test fun allKeysRoundTripThroughNamesAndUnicodeAccidentals() {
        assertEquals(24, Camelot.all.size)
        for (key in Camelot.all) {
            val parts = key.name.split(" ")
            assertEquals(key.name, key, Camelot(tonic = parts[0], scale = parts[1]))
        }
        assertEquals("1B", Camelot(tonic = "Cb", scale = "major")?.code)
        assertEquals("11B", Camelot(tonic = "A", scale = "maj")?.code)
        assertNull(Camelot(tonic = "H", scale = "minor"))
        assertNull(Camelot(tonic = "A", scale = "dorian"))
    }
}

class StyleTests {
    /** Expected hashes computed from the FNV-1a definition (offset 0x811C9DC5, prime 0x01000193). */
    @Test fun fnv1aKnownValues() {
        assertEquals(0x811C_9DC5u, StyleShade.fnv1a32(""))
        assertEquals(0xE40C_292Cu, StyleShade.fnv1a32("a")) // published FNV-1a test vector
        assertEquals(705_347_083u, StyleShade.fnv1a32("Electronic---Techno"))
        assertEquals(2_324_854_095u, StyleShade.fnv1a32("Electronic---Breakbeat"))
        assertEquals(1_241_154_464u, StyleShade.fnv1a32("Funk / Soul---Funk"))
    }

    @Test fun shades() {
        assertEquals(StyleShade.base, StyleShade.of("Electronic---Techno"))
        assertEquals(StyleShade.light, StyleShade.of("Electronic---Breakbeat"))
        assertEquals(StyleShade.deep, StyleShade.of("Funk / Soul---Funk"))
        assertEquals(StyleShade.deep, StyleShade.of("Electronic---Drum n Bass"))
        assertEquals(StyleShade.of("Electronic---Techno"), StyleShade.of("Electronic---Techno"))
    }

    @Test fun labelSplitAndAliases() {
        val label = StyleLabel("Funk / Soul---Funk", aliases = mapOf("Funk / Soul" to "Funk & Soul"))
        assertEquals("Funk / Soul", label.genre)
        assertEquals("Funk", label.style)
        assertEquals("Funk & Soul", label.genreDisplay)
        assertEquals("Funk · Funk & Soul", label.display)
        assertEquals("Electronic", StyleLabel.genre(of = "Electronic---Drum n Bass"))
        assertEquals("Non-Music", StyleLabel("Non-Music").style)
        assertEquals("Rock---Shoegaze", StyleLabel.canonical(genre = "Rock", style = "Shoegaze"))
    }

    @Test fun moodSymbols() {
        assertEquals("moon.fill", Mood.symbol("dark"))
        assertEquals("circle.dotted.circle", Mood.symbol("hypnotic"))
        assertEquals("tag", Mood.symbol("epic"))
        assertFalse(Mood.isChip("vocal"))
        assertTrue(Mood.isChip("dark"))
    }
}

class EffectiveTagsTests {
    private val auto = AutoTags(
        styles = mapOf("Electronic---Breakbeat" to 0.58, "Electronic---Techno" to 0.44, "Rock---Industrial" to 0.3),
        bpm = 138.0, key = "8A", energy = 7,
        moods = mapOf("dark" to 0.81, "energetic" to 0.66, "hypnotic" to 0.4), vocal = 0.12,
    )

    @Test fun autoOnly() {
        val t = EffectiveTags(auto = auto, override = null)
        assertEquals("Electronic---Breakbeat", t.primaryStyle)
        assertEquals("Electronic", t.primaryGenre)
        assertTrue(t.bpm == 138.0 && t.key == "8A" && t.energy == 7)
        assertEquals(setOf("dark", "energetic"), t.moods)
        assertEquals(false, t.vocal)
    }

    @Test fun scalarOverridesWinFieldByField() {
        val t = EffectiveTags(auto = auto, override = TrackOverride(bpm = 140.0, key = "9A", vocal = true))
        assertTrue(t.bpm == 140.0 && t.key == "9A" && t.vocal == true)
        assertEquals("fields not overridden keep the auto value", 7, t.energy)
    }

    @Test fun moodSets() {
        val t = EffectiveTags(auto = auto, override = TrackOverride(moodsAdd = listOf("hypnotic"), moodsRemove = listOf("dark")))
        assertEquals(setOf("energetic", "hypnotic"), t.moods)
        val untagged = EffectiveTags(auto = null, override = TrackOverride(moodsRemove = listOf("dark")))
        assertNull("no auto moods and nothing added = unknown", untagged.moods)
        val added = EffectiveTags(auto = null, override = TrackOverride(moodsAdd = listOf("party")))
        assertEquals(setOf("party"), added.moods)
    }

    @Test fun addedStyleIsPrimary() {
        val t = EffectiveTags(auto = auto, override = TrackOverride(stylesAdd = listOf("Electronic---Acid House", "Rock---Punk")))
        assertEquals("Electronic---Acid House", t.primaryStyle)
        assertEquals("Electronic", t.primaryGenre)
    }

    @Test fun removedStyleAndGenreAreSkipped() {
        val noBreaks = EffectiveTags(auto = auto, override = TrackOverride(stylesRemove = listOf("Electronic---Breakbeat")))
        assertEquals("Electronic---Techno", noBreaks.primaryStyle)
        val noElectronic = EffectiveTags(auto = auto, override = TrackOverride(genresRemove = listOf("Electronic")))
        assertEquals("Rock---Industrial", noElectronic.primaryStyle)
        assertEquals("Rock", noElectronic.primaryGenre)
    }

    @Test fun addedGenreKeepsOnlyAMatchingStyle() {
        val rock = EffectiveTags(auto = auto, override = TrackOverride(genresAdd = listOf("Rock")))
        assertEquals("Rock", rock.primaryGenre)
        assertNull("Breakbeat is not a Rock style", rock.primaryStyle)
        val electronic = EffectiveTags(auto = auto, override = TrackOverride(genresAdd = listOf("Electronic")))
        assertEquals("Electronic---Breakbeat", electronic.primaryStyle)
    }

    @Test fun untaggedIsUnknownEverywhere() {
        assertEquals(EffectiveTags(), EffectiveTags(auto = null, override = null))
    }

    @Test fun tiesBreakByLabel() {
        assertEquals(
            listOf("Rock---Punk", "Funk / Soul---Funk", "Jazz---Fusion"),
            EffectiveTags.rankedStyles(mapOf("Jazz---Fusion" to 0.4, "Funk / Soul---Funk" to 0.4, "Rock---Punk" to 0.5)),
        )
    }
}
