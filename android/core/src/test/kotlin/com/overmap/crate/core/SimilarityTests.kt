package com.overmap.crate.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Base64
import kotlin.math.abs

class SimilarityTests {
    @Test fun float16Bits() {
        assertEquals(1f, Float16Decoding.float(fromBits = 0x3C00))
        assertEquals(-2f, Float16Decoding.float(fromBits = 0xC000))
        assertEquals(0.5f, Float16Decoding.float(fromBits = 0x3800))
        assertEquals(65504f, Float16Decoding.float(fromBits = 0x7BFF))
        assertEquals(Math.scalb(1f, -24), Float16Decoding.float(fromBits = 0x0001))
        val negZero = Float16Decoding.float(fromBits = 0x8000)
        assertTrue(negZero == 0f && 1f / negZero < 0)
        assertEquals(Float.POSITIVE_INFINITY, Float16Decoding.float(fromBits = 0x7C00))
        assertTrue(Float16Decoding.float(fromBits = 0x7E00).isNaN())
        assertTrue(abs(Float16Decoding.float(fromBits = 0x3555) - 0.33325195f) < 1e-7)
    }

    @Test fun littleEndianDecoding() {
        assertEquals(listOf(1f, -2f), Float16Decoding.floats(byteArrayOf(0x00, 0x3C, 0x00, 0xC0.toByte())).toList())
        assertThrows(SimilarityError::class.java) { Float16Decoding.floats(byteArrayOf(0x00)) }
    }

    /** Builds a 32-d float16 file where each row has the given bits per dimension. */
    private fun makeIndex(rows: List<Pair<String, Map<Int, Int>>>): VectorIndex {
        val bytes = java.io.ByteArrayOutputStream()
        for ((_, dims) in rows) for (d in 0 until 32) {
            val bits = dims[d] ?: 0
            bytes.write(bits and 0xFF); bytes.write(bits shr 8)
        }
        val idx = CrateJSON.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), rows.map { it.first })
        return VectorIndex(bytes.toByteArray(), idsJSON = idx.encodeToByteArray())
    }

    @Test fun nearestExcludesSeedAndRanksByCosine() {
        // seed = (1, 0); a = (1, 0.5) ≈ 0.894; b = (0, 1) = 0; c = (1, 0) = 1; d = (-1, 0) = -1.
        val index = makeIndex(listOf(
            "seed" to mapOf(0 to 0x3C00), "a" to mapOf(0 to 0x3C00, 1 to 0x3800), "b" to mapOf(1 to 0x3C00),
            "c" to mapOf(0 to 0x3C00), "d" to mapOf(0 to 0xBC00),
        ))
        assertTrue(index.count == 5 && index.vector("a")?.get(1) == 0.5f)
        assertTrue(index.contains("a") && !index.contains("zzz"))
        assertEquals(1f, index.cosine("seed", "c"))
        val top = index.nearest(to = "seed", count = 3)
        assertEquals(listOf("c", "a", "b"), top.map { it.id })
        assertTrue(abs(top[1].score - 0.8944272f) < 1e-5)
        assertEquals(listOf("b", "d"), index.nearest(to = "seed", count = 10, candidates = setOf("d", "b")).map { it.id })

        val more = index.moreLikeThis(seed = "seed", count = 3, rng = SeededGenerator(3))
        assertEquals(setOf("c", "a", "b"), more.toSet())
        assertEquals(SimilarityError.UnknownTrack("zzz"), assertThrows(SimilarityError.UnknownTrack::class.java) { index.nearest(to = "zzz", count = 1) })
    }

    @Test fun sizeMismatchIsRejected() {
        assertThrows(SimilarityError::class.java) { VectorIndex(ByteArray(63), ids = listOf("a")) }
    }

    /** Daily similarity: nearest to an outside vector. */
    @Test fun nearestToAnOutsideVector() {
        val rows = List(3) { MutableList(32) { 0f } }
        rows[0][0] = 1f; rows[1][1] = 1f; rows[2][0] = 0.6f; rows[2][1] = 0.8f
        val index = VectorIndex(float16Bytes(rows), ids = listOf("a", "b", "c"))
        val q = MutableList(32) { 0.0 }; q[1] = 1.0
        assertEquals(listOf("b", "c"), index.nearest(vector = q, count = 2).map { it.id })
        assertEquals(listOf("c"), index.nearest(vector = q, count = 1, candidates = setOf("a", "c")).map { it.id })
        assertTrue("wrong dimensions", index.nearest(vector = listOf(1.0, 0.0), count = 1).isEmpty())
    }
}

class VibeTests {
    /** float16 bits for a few exactly representable values. */
    private val bits = mapOf(0.0 to 0x0000, 1.0 to 0x3C00, 0.5 to 0x3800, 0.25 to 0x3400, 0.75 to 0x3A00, -0.5 to 0xB800)

    private val rows: List<Pair<String, List<Double>>> = listOf(
        "a" to listOf(1.0, 0.0, 0.25), "b" to listOf(0.75, 0.25, 0.0), "c" to listOf(0.0, 1.0, 0.5),
        "d" to listOf(0.5, 0.5, 0.0), "e" to listOf(0.0, 0.25, 1.0), "f" to listOf(-0.5, 1.0, 0.0), "g" to listOf(1.0, 0.25, 0.0),
    )

    /** Same rows as a 32-d float16 `vectors.bin` (dimensions beyond 3 are zero). */
    private fun vectorIndex(): VectorIndex {
        val bytes = java.io.ByteArrayOutputStream()
        for ((_, v) in rows) for (d in 0 until 32) {
            val b = if (d < v.size) bits.getValue(v[d]) else 0
            bytes.write(b and 0xFF); bytes.write(b shr 8)
        }
        return VectorIndex(bytes.toByteArray(), ids = rows.map { it.first })
    }

    private val artists = mapOf("c" to listOf("Sun Band"), "f" to listOf("SUN BAND", "Other"))

    private val vibes = mapOf(
        "one" to Vibe("One", seeds = listOf("a"), share = 0.3),
        "artist" to Vibe("Artist", artists = listOf("sun band"), share = 0.2),
        "excl" to Vibe("Excl", seeds = listOf("a"), exclude = listOf("g"), share = 0.3),
        "ghost" to Vibe("Ghost", seeds = listOf("zzz"), share = 0.5),
        "neg" to Vibe("Neg", seeds = listOf("d"), exclude = listOf("a", "zzz"), share = 0.45),
        "tagged" to Vibe("Tagged", seeds = listOf("a"), seedTags = listOf("sunny"), exclude = listOf("c"), share = 0.3),
    )

    private val trackTags = mapOf("e" to setOf("sunny"), "g" to setOf("sunny"), "b" to emptySet())

    @Test fun float16PathAgreesWithDoublePath() {
        val fromDoubles = VibeIndex(rows.toMap(), artists = artists, trackTags = trackTags)
        val fromFloat16 = VibeIndex(vectorIndex(), artists = artists, trackTags = trackTags)
        assertEquals(rows.size, fromFloat16.vectors.size)
        for ((id, vibe) in vibes) {
            assertEquals(id, fromDoubles.members(vibe), fromFloat16.members(vibe))
            val r16 = fromFloat16.ranked(vibe)
            val r64 = fromDoubles.ranked(vibe)
            assertEquals("$id ranking", r64.map { it.id }, r16.map { it.id })
            for ((x, y) in r16.zip(r64)) assertTrue(abs(x.score - y.score) < 1e-9)
        }
    }

    @Test fun negativesAndTagPruning() {
        val index = VibeIndex(rows.toMap(), artists = artists, trackTags = trackTags)
        // Score = cos(x, P) − 0.2 · cos(x, N). With "a" excluded, tracks near "a" drop in the ranking.
        val plain = index.ranked(Vibe("x", seeds = listOf("d")))
        val pulled = index.ranked(vibes.getValue("neg"))
        val p = VibeIndex.unit(listOf(0.5, 0.5, 0.0))
        val n = VibeIndex.unit(listOf(1.0, 0.0, 0.25))
        val g = VibeIndex.unit(listOf(1.0, 0.25, 0.0))
        val expected = VibeIndex.dot(g, p) - VibeIndex.negativeWeight * VibeIndex.dot(g, n)
        assertTrue(abs(pulled.first { it.id == "g" }.score - expected) < 1e-12)
        assertTrue(pulled.first { it.id == "g" }.score < plain.first { it.id == "g" }.score)
        assertFalse("a" in index.members(vibes.getValue("neg")))
        // Tag seeds e and g: g sounds like a (kept), e doesn't (pruned in the first pass).
        assertEquals(setOf("e", "g"), index.tagSeeds(vibes.getValue("tagged")))
        assertEquals(listOf("a", "g"), index.seedTracks(vibes.getValue("tagged")))
    }

    @Test fun rules() {
        val index = VibeIndex(rows.toMap(), artists = artists)
        // k = ceil(0.3 × 7 − 1e-9) = 3: a itself, then g and b are closest to (1, 0, 0.25).
        assertEquals(setOf("a", "g", "b"), index.members(vibes.getValue("one")))
        assertEquals("excluded track is not replaced", setOf("a", "b"), index.members(vibes.getValue("excl")))
        assertEquals("artist match is case-insensitive", listOf("c", "f"), index.seedTracks(vibes.getValue("artist")))
        assertTrue(index.members(vibes.getValue("artist")).containsAll(setOf("c", "f")))
        assertTrue(index.members(vibes.getValue("ghost")).isEmpty())
        assertTrue("no seeds, no members", index.members(Vibe("x", share = 1.0)).isEmpty())
        assertEquals(7, index.members(Vibe("x", seeds = listOf("e"), share = 1.0)).size)
        // 0.28 × 25 = 7.000000000000001 in floating point; the 1e-9 guard keeps k = 7, not 8.
        val many = VibeIndex((0 until 25).associate { "%02d".format(it) to listOf(1.0, it.toDouble()) })
        assertEquals(7, many.members(Vibe("x", seeds = listOf("00"), share = 0.28)).size)
    }

    @Test fun energyBand() {
        val index = VibeIndex(rows.toMap(), energies = mapOf("a" to 8, "b" to 9, "c" to 8, "d" to 1, "e" to 3, "f" to 10))
        assertEquals(7..10, index.energyBand(listOf("a", "b", "c")))
        assertEquals("d is an outlier", 7..10, index.energyBand(listOf("a", "b", "c", "d")))
        assertEquals("fewer than 3 known", null, index.energyBand(listOf("a", "b", "g")))
        val members = index.members(Vibe("x", seeds = listOf("a", "b", "c"), share = 1.0))
        assertEquals("e (3) and d (1) fall outside 7–10; g (unknown) stays", setOf("a", "b", "c", "f", "g"), members)
        val lib = mapOf("a" to Track(auto = AutoTags(energy = 5)), "b" to Track(), "c" to Track(auto = AutoTags(energy = 2)))
        assertEquals(mapOf("a" to 5, "c" to 9), VibeIndex.energies(lib, mapOf("c" to TrackOverride(energy = 9))))
    }

    @Test fun fileDecodingAndQuery() {
        val json = """{"schema":1,"vibes":{"hyp":{"name":"Hypnotic","seeds":["t1"],"artists":["Kangding Ray"]}}}"""
        val file = CrateJSON.decode<VibesFile>(json.encodeToByteArray(), "vibes.json")
        assertEquals(Vibe("Hypnotic", seeds = listOf("t1"), artists = listOf("Kangding Ray"), share = 0.2), file.vibes["hyp"])
        assertThrows(CrateDataError::class.java) {
            CrateJSON.decode<VibesFile>("""{"schema":2,"vibes":{}}""".encodeToByteArray(), "vibes.json")
        }
        assertEquals(file, CrateJSON.decode<VibesFile>(CrateJSON.encode(file), "vibes.json"))

        val q = Query.fromJson("""{"vibes_include":["hyp","x"]}""")
        assertTrue(q.vibesInclude == listOf("hyp", "x") && !q.isUnconstrained)
        val tags = EffectiveTags()
        assertTrue(QueryEngine.matches(q, tags, trackID = "t1", vibeMembers = mapOf("hyp" to setOf("t1"), "x" to setOf("t1"))))
        assertFalse("unknown vibe matches nothing", QueryEngine.matches(q, tags, trackID = "t1", vibeMembers = mapOf("hyp" to setOf("t1"))))
    }

    @Test fun vibesWriteThroughGitHubStore() = runTest {
        val transport = FakeTransport(listOf(json(404, "{}"), json(201, """{"content":{"sha":"v1"}}""")))
        val store = GitHubStore(owner = "o", repo = "crate", token = { "t" }, transport = transport, cache = InMemoryGitHubCache())
        store.updateJSON(CrateKit.Path.vibes, "Add vibe", VibesFile()) {
            it.copy(vibes = it.vibes + ("hyp" to Vibe("Hypnotic", seeds = listOf("t1"))))
        }
        val put = jsonObject(transport.recorded[1].body)
        val written = CrateJSON.decode<VibesFile>(Base64.getDecoder().decode(put["content"]!!.jsonPrimitive.content), "vibes.json")
        assertEquals(listOf("t1"), written.vibes["hyp"]?.seeds)
        assertEquals("/repos/o/crate/contents/data/vibes.json", transport.recorded[1].path)
    }
}

@Serializable
private data class SynonymVectors(val cases: List<Case>) {
    @Serializable data class Case(val `in`: String, val out: String) {
        override fun toString() = "\"$`in`\""
    }
}

@RunWith(Parameterized::class)
class SynonymCaseTests(private val case: Any) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> =
            CrateJSON.json.decodeFromString(SynonymVectors.serializer(), Repo.spec("tag_synonyms.json").decodeToString())
                .cases.map { arrayOf(it) }
    }

    @Test fun synonymCase() {
        val c = case as SynonymVectors.Case
        assertEquals(c.out, Tags.normalise(c.`in`))
    }
}

class TagTests {
    @Test fun bundledCopyMatchesSpecByteForByte() {
        val bundled = TagNormaliser::class.java.getResourceAsStream("tag_synonyms.json")!!.use { it.readBytes() }
        assertTrue("copy spec/tag_synonyms.json to android/core/src/main/resources/com/overmap/crate/core/",
            bundled.contentEquals(Repo.spec("tag_synonyms.json")))
        assertEquals(TagNormaliser(json = Repo.spec("tag_synonyms.json")), TagNormaliser.bundled)
        assertTrue(TagNormaliser.bundled.synonyms.isNotEmpty())
    }

    @Test fun extraNormalisation() {
        val n = TagNormaliser.bundled
        assertEquals("rocknroll", n.normalise("Rock’n’Roll"))
        assertEquals("a b", n.normalise("a\t\n b"))
        assertEquals("", n.normalise(" - "))
        assertEquals(setOf("italian", "hip hop"), n.normaliseAll(listOf("Italy", "italian", " ", "Hip-Hop")))
        assertEquals("idempotent", "italian", n.normalise(n.normalise("Italia")))
        assertEquals("cafe", n.normalise("Café"))
    }

    @Test fun trackTagsUnknownVsEmpty() {
        val lib = CrateJSON.decode<LibraryFile>("""{"tracks":{"a":{"tags":["Italy"]},"b":{"tags":[]},"c":{}}}""".encodeToByteArray(), "library.json")
        val n = TagNormaliser.bundled
        assertEquals(setOf("italian"), n.trackTags(lib.tracks.getValue("a")))
        assertEquals(emptySet<String>(), n.trackTags(lib.tracks.getValue("b")))
        assertEquals(null, n.trackTags(lib.tracks.getValue("c")))
        val q = Query(tagsInclude = listOf("italian"))
        assertFalse(QueryEngine.matches(q, lib.tracks.getValue("b"), null))
        assertFalse(QueryEngine.matches(q, lib.tracks.getValue("c"), null))
        assertTrue(QueryEngine.matches(q, lib.tracks.getValue("a"), null))
    }

    @Test fun queryDecodingNormalises() {
        val q = Query.fromJson("""{"tags_include":["Italia","HIP_HOP"," "]}""")
        assertEquals(listOf("italian", "hip hop"), q.tagsInclude)
        assertFalse(q.isUnconstrained)
        val vibe = CrateJSON.json.decodeFromString(Vibe.serializer(), """{"name":"x","seed_tags":["Deep-Techno"]}""")
        assertEquals(listOf("Deep-Techno"), vibe.seedTags)
    }

    @Test fun vibeSeedTagsFromLibrary() {
        // a and b have vectors; b is tagged "dub techno"; c is tagged but has no vector.
        val rows = listOf(MutableList(32) { 0f }.apply { this[0] = 1f }, MutableList(32) { 0f }.apply { this[0] = 0.5f })
        val index = VectorIndex(float16Bytes(rows), ids = listOf("a", "b"))
        val library = LibraryFile(tracks = mapOf("a" to Track(), "b" to Track(tags = listOf("Dub-Techno")), "c" to Track(tags = listOf("dub techno"))))
        val vibes = VibeIndex(index, library)
        assertEquals(setOf("b"), vibes.tagSeeds(Vibe("x", seedTags = listOf("dub techno"))))
        assertEquals(listOf("b"), vibes.seedTracks(Vibe("x", seedTags = listOf("dub techno"), share = 1.0)))
        // With k = 1 the first pass keeps only "a" (a and b tie at 1.0; ID breaks the tie), so b is pruned.
        assertTrue(vibes.seedTracks(Vibe("x", seedTags = listOf("dub techno"), share = 0.2)).isEmpty())
    }
}
