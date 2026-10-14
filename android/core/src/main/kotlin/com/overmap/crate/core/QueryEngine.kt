package com.overmap.crate.core

import java.security.SecureRandom
import kotlin.random.Random

/**
 * A source of uniformly random 64-bit values, like Swift's `RandomNumberGenerator`. [nextInt] reproduces
 * Swift's `Int.random(in:using:)` exactly, so a [SeededGenerator] gives the same shuffles on iOS and Android.
 */
interface RandomNumberGenerator {
    fun next(): ULong
}

/** A uniform value in `0 until upperBound` (Lemire's method, as in the Swift standard library). */
fun RandomNumberGenerator.next(upperBound: ULong): ULong {
    require(upperBound != 0uL) { "upperBound cannot be zero." }
    var random = next()
    var low = random * upperBound
    if (low < upperBound) {
        val t = (0uL - upperBound) % upperBound
        while (low < t) {
            random = next()
            low = random * upperBound
        }
    }
    return multiplyHighUnsigned(random, upperBound)
}

/** Swift's `Int.random(in: range, using:)`. */
fun RandomNumberGenerator.nextInt(range: IntRange): Int {
    require(!range.isEmpty()) { "Range is empty" }
    val delta = (range.last.toLong() - range.first.toLong()).toULong() + 1uL
    return (range.first.toLong() + next(delta).toLong()).toInt()
}

private fun multiplyHighUnsigned(a: ULong, b: ULong): ULong {
    val aLo = a and 0xFFFF_FFFFuL
    val aHi = a shr 32
    val bLo = b and 0xFFFF_FFFFuL
    val bHi = b shr 32
    val loLo = aLo * bLo
    val hiLo = aHi * bLo
    val loHi = aLo * bHi
    val hiHi = aHi * bHi
    val cross = (loLo shr 32) + (hiLo and 0xFFFF_FFFFuL) + loHi
    return hiHi + (hiLo shr 32) + (cross shr 32)
}

/** A small deterministic generator (SplitMix64) for reproducible shuffles in tests and previews. */
class SeededGenerator(seed: Long) : RandomNumberGenerator {
    private var state: ULong = seed.toULong()

    companion object {
        operator fun invoke(seed: ULong): SeededGenerator = SeededGenerator(seed.toLong())
    }

    override fun next(): ULong {
        state += 0x9E37_79B9_7F4A_7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58_476D_1CE4_E5B9uL
        z = (z xor (z shr 27)) * 0x94D0_49BB_1331_11EBuL
        return z xor (z shr 31)
    }
}

/** Cryptographically secure system randomness (the default generator). */
class SystemRandomNumberGenerator : RandomNumberGenerator {
    private val random = SecureRandom()
    override fun next(): ULong = random.nextLong().toULong()
}

/** Adapts any `kotlin.random.Random` to [RandomNumberGenerator]. */
class KotlinRandomGenerator(private val random: Random) : RandomNumberGenerator {
    override fun next(): ULong = random.nextLong().toULong()
}

/** Exposes a [RandomNumberGenerator] as a `kotlin.random.Random` (e.g. for `List.shuffled(random)`). */
fun RandomNumberGenerator.asKotlinRandom(): Random = object : Random() {
    override fun nextBits(bitCount: Int): Int =
        if (bitCount == 0) 0 else (this@asKotlinRandom.next() shr (64 - bitCount)).toInt()
}

/** Evaluates [Query] values against the library. Pure functions; shared semantics with `pipeline/crate/query.py`. */
object QueryEngine {
    /**
     * True when a track with these effective tags satisfies every constrained field of `query`.
     * @param trackID The track's ID, needed for `vibes_include`.
     * @param vibeMembers Vibe ID → member track IDs (from [VibeIndex.allMembers]).
     * @param normaliser Normalises `tags_include` terms (the track side is already in `t.communityTags`).
     */
    fun matches(
        query: Query, tags: EffectiveTags, trackID: String = "",
        vibeMembers: Map<String, Set<String>> = emptyMap(),
        normaliser: TagNormaliser = TagNormaliser.bundled,
    ): Boolean {
        val t = tags
        if (query.tagsInclude.isNotEmpty()) {
            val have = t.communityTags ?: return false
            val wanted = query.tagsInclude.map(normaliser::normalise).filter { it.isNotEmpty() }
            if (!wanted.all { it in have }) return false
        }
        if (query.vibesInclude.isNotEmpty() && !query.vibesInclude.all { vibeMembers[it]?.contains(trackID) == true }) {
            return false
        }
        // One "any of" group: family OR (family AND style ticked under it) OR primary genre OR primary style.
        if (query.familiesInclude.isNotEmpty() || query.familyStyles.isNotEmpty() ||
            query.genresInclude.isNotEmpty() || query.stylesInclude.isNotEmpty()
        ) {
            val family = t.family
            val style = t.primaryStyle
            val familyHit = (family != null && family in query.familiesInclude) ||
                (family != null && style != null && query.familyStyles[family]?.contains(style) == true)
            val genreHit = t.primaryGenre?.let { it in query.genresInclude } ?: false
            val styleHit = style?.let { it in query.stylesInclude } ?: false
            if (!familyHit && !genreHit && !styleHit) return false
        }
        if (query.genresExclude.isNotEmpty()) {
            val g = t.primaryGenre
            if (g == null || g in query.genresExclude) return false
        }
        if (query.stylesExclude.isNotEmpty()) {
            val s = t.primaryStyle
            if (s == null || s in query.stylesExclude) return false
        }
        query.bpm?.let { range -> if (t.bpm?.let(range::contains) != true) return false }
        query.energy?.let { range -> if (t.energy?.let(range::contains) != true) return false }
        if (query.moodsInclude.isNotEmpty()) {
            val moods = t.moods ?: return false
            if (!moods.containsAll(query.moodsInclude)) return false
        }
        if (query.moodsExclude.isNotEmpty()) {
            val moods = t.moods ?: return false
            if (query.moodsExclude.any { it in moods }) return false
        }
        query.key?.let { filter ->
            val key = t.key?.let { Camelot(it) } ?: return false
            if (key.code !in filter.accepted) return false
        }
        return when (query.vocal) {
            Query.Vocal.any -> true
            Query.Vocal.vocal -> t.vocal == true
            Query.Vocal.instrumental -> t.vocal == false
        }
    }

    /** Convenience: merges `track` with `override` and tests it. */
    fun matches(
        query: Query, track: Track, override: TrackOverride?, trackID: String = "",
        vibeMembers: Map<String, Set<String>> = emptyMap(),
    ): Boolean = matches(query, EffectiveTags(track, override), trackID, vibeMembers)

    /** IDs of every matching track, sorted for stable output. */
    fun matchingIDs(
        query: Query, tracks: Map<String, Track>, overrides: Map<String, TrackOverride>,
        vibeMembers: Map<String, Set<String>> = emptyMap(),
    ): List<String> = tracks.keys.sorted().filter { id ->
        matches(query, tracks.getValue(id), overrides[id], id, vibeMembers)
    }

    /** Number of matching tracks (ignores [Query.limit]). */
    fun count(
        query: Query, tracks: Map<String, Track>, overrides: Map<String, TrackOverride>,
        vibeMembers: Map<String, Set<String>> = emptyMap(),
    ): Int = tracks.count { (id, track) -> matches(query, track, overrides[id], id, vibeMembers) }

    /** Runs `query`: a random sample of `min(limit, matches)` track IDs, shuffled with Fisher–Yates. */
    fun run(
        query: Query, tracks: Map<String, Track>, overrides: Map<String, TrackOverride>,
        vibeMembers: Map<String, Set<String>> = emptyMap(),
        rng: RandomNumberGenerator = SystemRandomNumberGenerator(),
    ): List<String> = sampleAndShuffle(matchingIDs(query, tracks, overrides, vibeMembers), query.limit, rng)

    /**
     * Picks `min(limit, items.size)` items uniformly at random and returns them in random order.
     * A partial Fisher–Yates draws the sample; a full Fisher–Yates then shuffles it.
     */
    fun <T> sampleAndShuffle(items: List<T>, limit: Int, rng: RandomNumberGenerator): List<T> {
        val pool = items.toMutableList()
        val k = maxOf(0, minOf(limit, pool.size))
        for (i in 0 until k) {
            val j = rng.nextInt(i until pool.size)
            pool[i] = pool[j].also { pool[j] = pool[i] }
        }
        val sample = pool.subList(0, k).toMutableList()
        fisherYates(sample, rng)
        return sample
    }

    /** In-place Fisher–Yates shuffle with an injectable generator. */
    fun <T> fisherYates(array: MutableList<T>, rng: RandomNumberGenerator) {
        if (array.size <= 1) return
        for (i in array.size - 1 downTo 1) {
            val j = rng.nextInt(0..i)
            array[i] = array[j].also { array[j] = array[i] }
        }
    }
}
