package com.overmap.crate.core

import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** The shared vectors in `spec/query_vectors.json` (also run by the Python pipeline and Swift). */
@Serializable
data class QueryVectors(
    val constants: Map<String, Double>,
    val tracks: Map<String, Fixture>,
    val cases: List<Case>,
    val vibes: Map<String, Vibe>,
) {
    @Serializable
    data class Fixture(
        val status: TrackStatus? = null,
        val auto: AutoTags? = null,
        val override: TrackOverride? = null,
        val vector: List<Double>? = null,
        val artists: List<String>? = null,
        val tags: List<String>? = null,
        val family: String? = null,
        val consensus: Consensus? = null,
    )

    @Serializable
    data class Case(val name: String, val query: Query, val matches: List<String>, val count: Int) {
        override fun toString() = name
    }

    val library: Map<String, Track> by lazy {
        tracks.mapValues {
            val f = it.value
            Track(artists = f.artists, status = f.status ?: TrackStatus.pending, auto = f.auto, tags = f.tags,
                family = f.family, consensus = f.consensus)
        }
    }

    val overrides: Map<String, TrackOverride> by lazy {
        tracks.mapNotNull { (id, f) -> f.override?.let { id to it } }.toMap()
    }

    val vibeIndex: VibeIndex by lazy {
        VibeIndex(
            vectors = tracks.mapNotNull { (id, f) -> f.vector?.let { id to it } }.toMap(),
            artists = tracks.mapNotNull { (id, f) -> f.artists?.let { id to it } }.toMap(),
            trackTags = library.mapNotNull { (id, t) -> TagNormaliser.bundled.trackTags(t)?.let { id to it } }.toMap(),
            energies = VibeIndex.energies(library, overrides),
        )
    }

    val vibeMembers: Map<String, Set<String>> by lazy { vibeIndex.allMembers(vibes) }

    companion object {
        val shared: QueryVectors by lazy {
            CrateJSON.json.decodeFromString(serializer(), Repo.spec("query_vectors.json").decodeToString())
        }
    }
}

class QueryVectorTests {
    private val vectors = QueryVectors.shared

    @Test fun constantsMatchEngine() {
        val c = vectors.constants
        assertEquals(c["MOOD_THRESHOLD"], EffectiveTags.moodThreshold)
        assertEquals(c["VOCAL_THRESHOLD"], EffectiveTags.vocalThreshold)
        assertEquals(c["DEFAULT_LIMIT"]?.toInt(), Query.defaultLimit)
        assertEquals(c["VIBE_DEFAULT_SHARE"], Vibe.defaultShare)
        assertEquals(c["VIBE_NEGATIVE_WEIGHT"], VibeIndex.negativeWeight)
        assertEquals(c["VIBE_ENERGY_MARGIN"]?.toInt(), VibeIndex.energyMargin)
        assertEquals(c["VIBE_ENERGY_OUTLIER"], VibeIndex.energyOutlier)
        assertEquals(c["VIBE_ENERGY_MIN_SEEDS"]?.toInt(), VibeIndex.energyMinSeeds)
        assertTrue(vectors.cases.size >= 93)
    }

    @Test fun runIsDeterministicForASeedAndShuffles() {
        val library = vectors.library
        val first = QueryEngine.run(Query(), library, emptyMap(), rng = SeededGenerator(7))
        val second = QueryEngine.run(Query(), library, emptyMap(), rng = SeededGenerator(7))
        assertEquals(first, second)
        assertNotEquals("result is shuffled", library.keys.sorted(), first)
    }

    /** Expected values produced by the Swift `SeededGenerator` + `QueryEngine.sampleAndShuffle`. */
    @Test fun seededResultsMatchSwift() {
        assertEquals(listOf(1, 9, 4, 14, 8, 7, 18), QueryEngine.sampleAndShuffle((0 until 20).toList(), 7, SeededGenerator(42)))
        assertEquals(listOf(94, 1, 41, 48, 6, 52, 59, 73, 25, 50, 97, 63, 80, 11, 27),
            QueryEngine.sampleAndShuffle((0 until 100).toList(), 100, SeededGenerator(7)).take(15))
        val r = SeededGenerator(1)
        assertEquals(listOf(1, 2, 2, 1, 1), List(5) { r.nextInt(0 until 3) })
    }

    @Test fun emptyJSONQueryDecodesToDefaults() {
        val q = Query.fromJson("{}")
        assertEquals(Query(), q)
        assertTrue(q.limit == 100 && q.vocal == Query.Vocal.any && q.isUnconstrained)
    }
}

@RunWith(Parameterized::class)
class QueryVectorCaseTests(private val case: QueryVectors.Case) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = QueryVectors.shared.cases.map { arrayOf(it) }
    }

    @Test fun vectorCase() {
        val v = QueryVectors.shared
        val matched = QueryEngine.matchingIDs(case.query, v.library, v.overrides, v.vibeMembers)
        assertEquals("match set", case.matches.sorted(), matched)
        assertEquals(case.matches.size, QueryEngine.count(case.query, v.library, v.overrides, v.vibeMembers))
        for (seed in listOf(1L, 2L, 42L)) {
            val result = QueryEngine.run(case.query, v.library, v.overrides, v.vibeMembers, SeededGenerator(seed))
            assertEquals("sampled count", case.count, result.size)
            assertEquals("no duplicates", result.size, result.toSet().size)
            assertTrue("sample is drawn from matches", case.matches.containsAll(result))
        }
    }
}
