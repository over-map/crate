package com.overmap.crate.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/** Errors reading the similarity vectors. */
sealed class SimilarityError(message: String) : Exception(message) {
    data class MalformedVectors(val reason: String) : SimilarityError(reason)
    data class UnknownTrack(val id: String) : SimilarityError("unknown track $id")
}

/** Converts IEEE 754 half-precision values to `Float`. */
object Float16Decoding {
    /** Decodes one half-precision bit pattern (low 16 bits of `fromBits`); handles subnormals, infinities and NaN. */
    fun float(fromBits: Int): Float {
        val bits = fromBits and 0xFFFF
        val sign = (bits and 0x8000) shl 16
        val exponent = (bits shr 10) and 0x1F
        val mantissa = bits and 0x03FF
        val result = when (exponent) {
            0 -> if (mantissa == 0) sign else {
                val value = mantissa.toFloat() * 5.9604645E-8f // mantissa · 2^-24
                return if (sign == 0) value else -value
            }
            0x1F -> sign or 0x7F80_0000 or (mantissa shl 13)
            else -> sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
        }
        return java.lang.Float.intBitsToFloat(result)
    }

    /** Decodes little-endian float16 values. Throws if the byte count is odd. */
    fun floats(fromLittleEndian: ByteArray): FloatArray {
        val data = fromLittleEndian
        if (data.size % 2 != 0) throw SimilarityError.MalformedVectors("odd byte count ${data.size}")
        return FloatArray(data.size / 2) { i ->
            float((data[2 * i].toInt() and 0xFF) or ((data[2 * i + 1].toInt() and 0xFF) shl 8))
        }
    }
}

/** A track with its similarity score. */
data class ScoredTrack(val id: String, val score: Float)

/**
 * Sound-similarity embeddings (`data/vectors.bin` + `data/vectors.idx.json`) and "More like this".
 * `vectors.bin` is a row-major matrix of little-endian float16, one row of [dimensions] values per track;
 * row *i* belongs to `vectors.idx.json[i]`.
 */
class VectorIndex(bin: ByteArray, val ids: List<String>, val dimensions: Int = defaultDimensions) {
    private val values: FloatArray
    private val norms: FloatArray
    private val rowOf: Map<String, Int>

    init {
        val expected = ids.size * dimensions * 2
        if (bin.size != expected) {
            throw SimilarityError.MalformedVectors("expected $expected bytes for ${ids.size} rows, got ${bin.size}")
        }
        values = Float16Decoding.floats(bin)
        norms = FloatArray(ids.size) { row ->
            var sum = 0f
            for (d in 0 until dimensions) { val v = values[row * dimensions + d]; sum += v * v }
            sqrt(sum)
        }
        rowOf = HashMap<String, Int>(ids.size * 2).apply { ids.forEachIndexed { i, id -> putIfAbsent(id, i) } }
    }

    /** Builds the index from `vectors.bin` and the contents of `vectors.idx.json` (a JSON array of track IDs). */
    constructor(bin: ByteArray, idsJSON: ByteArray, dimensions: Int = defaultDimensions) :
        this(bin, decodeIDs(idsJSON), dimensions)

    /** Number of tracks in the index. */
    val count: Int get() = ids.size

    /** True when `id` has an embedding. */
    fun contains(id: String): Boolean = id in rowOf

    /** The embedding of `id`, if present. */
    fun vector(id: String): List<Float>? {
        val row = rowOf[id] ?: return null
        return values.copyOfRange(row * dimensions, (row + 1) * dimensions).toList()
    }

    /** Cosine similarity between two indexed tracks. */
    fun cosine(a: String, b: String): Float? {
        val i = rowOf[a] ?: return null
        val j = rowOf[b] ?: return null
        return cosineRows(i, j)
    }

    private fun cosineRows(i: Int, j: Int): Float {
        val denominator = norms[i] * norms[j]
        if (denominator <= 0f) return 0f
        var dot = 0f
        for (d in 0 until dimensions) dot += values[i * dimensions + d] * values[j * dimensions + d]
        return dot / denominator
    }

    /**
     * The `count` tracks most similar to `seed`, best first, excluding the seed itself.
     * @param candidates Optional restriction (e.g. tracks still in the library).
     */
    fun nearest(to: String, count: Int, candidates: Set<String>? = null): List<ScoredTrack> {
        val seedRow = rowOf[to] ?: throw SimilarityError.UnknownTrack(to)
        val scored = ArrayList<ScoredTrack>(ids.size)
        ids.forEachIndexed { row, id ->
            if (id == to || rowOf[id] != row) return@forEachIndexed
            if (candidates != null && id !in candidates) return@forEachIndexed
            scored += ScoredTrack(id, cosineRows(seedRow, row))
        }
        return scored.sortedWith(scoreOrder).take(maxOf(0, count))
    }

    /** The `count` indexed tracks most similar to an outside `vector` (a Daily pick), best first. */
    fun nearest(vector: List<Double>, count: Int, candidates: Set<String>? = null): List<ScoredTrack> {
        if (vector.size != dimensions) return emptyList()
        val q = FloatArray(dimensions) { vector[it].toFloat() }
        val qn = sqrt(q.fold(0f) { acc, v -> acc + v * v })
        if (qn <= 0f) return emptyList()
        val scored = ArrayList<ScoredTrack>()
        ids.forEachIndexed { row, id ->
            if (rowOf[id] != row || norms[row] <= 0f) return@forEachIndexed
            if (candidates != null && id !in candidates) return@forEachIndexed
            var dot = 0f
            for (d in 0 until dimensions) dot += values[row * dimensions + d] * q[d]
            scored += ScoredTrack(id, dot / (norms[row] * qn))
        }
        return scored.sortedWith(scoreOrder).take(maxOf(0, count))
    }

    /** "More like this": the top `count` similar tracks (default 100), shuffled with Fisher–Yates. */
    fun moreLikeThis(
        seed: String, count: Int = 100, candidates: Set<String>? = null,
        rng: RandomNumberGenerator = SystemRandomNumberGenerator(),
    ): List<String> {
        val result = nearest(seed, count, candidates).map { it.id }.toMutableList()
        QueryEngine.fisherYates(result, rng)
        return result
    }

    companion object {
        /** Embedding size written by the pipeline (PCA 32-d). */
        const val defaultDimensions = 32

        private val scoreOrder = compareByDescending<ScoredTrack> { it.score }.thenBy { it.id }

        private fun decodeIDs(json: ByteArray): List<String> = try {
            CrateJSON.json.decodeFromString(ListSerializer(String.serializer()), json.decodeToString())
        } catch (e: Exception) {
            throw SimilarityError.MalformedVectors("vectors.idx.json is not an array of IDs")
        }
    }
}

/** A track with its vibe score. */
data class RankedTrack(val id: String, val score: Double)

/**
 * Computes vibe members from sound-similarity vectors, with negative pull from excluded tracks (rules in
 * `docs/query-semantics.md#vibes`, reference `pipeline/crate/vibes.py`). All arithmetic is in `Double`.
 */
class VibeIndex(
    /** Track ID → embedding. Only these tracks can be vibe members. */
    val vectors: Map<String, List<Double>>,
    /** Track ID → artist names, for artist seeds. */
    val artists: Map<String, List<String>> = emptyMap(),
    /** Track ID → normalised community tags, for `seed_tags`. */
    val trackTags: Map<String, Set<String>> = emptyMap(),
    /** Track ID → effective energy (an override wins), for the energy band (rule 5). Missing = unknown. */
    val energies: Map<String, Int> = emptyMap(),
    val normaliser: TagNormaliser = TagNormaliser.bundled,
) {
    /** Uses the float16 rows of `index` (first row wins for a duplicated ID). */
    constructor(
        index: VectorIndex, artists: Map<String, List<String>> = emptyMap(),
        trackTags: Map<String, Set<String>> = emptyMap(), energies: Map<String, Int> = emptyMap(),
        normaliser: TagNormaliser = TagNormaliser.bundled,
    ) : this(vectorsOf(index), artists, trackTags, energies, normaliser)

    /** Uses `index` with artists, normalised tags and effective energies taken from `library` and `overrides`. */
    constructor(
        index: VectorIndex, library: LibraryFile, overrides: OverridesFile = OverridesFile(),
        normaliser: TagNormaliser = TagNormaliser.bundled,
    ) : this(
        vectorsOf(index),
        library.tracks.mapNotNull { (id, t) -> t.artists?.let { id to it } }.toMap(),
        library.tracks.mapNotNull { (id, t) -> normaliser.trackTags(t)?.let { id to it } }.toMap(),
        energies(library.tracks, overrides.tracks),
        normaliser,
    )

    /** Rule 5: the energies a member may have, or `null` (no band) with fewer than 3 seeds of known energy. */
    fun energyBand(seeds: Iterable<String>): IntRange? {
        val known = seeds.mapNotNull { energies[it] }
        if (known.size < energyMinSeeds) return null
        val kept = known.filterIndexed { i, e ->
            val others = known.toMutableList().apply { removeAt(i) }
            abs(e.toDouble() - median(others)) <= energyOutlier
        }
        val lo = kept.minOrNull() ?: return null
        val hi = kept.max()
        return (lo - energyMargin)..(hi + energyMargin)
    }

    /** Explicit seeds: `seeds` ∪ tracks by a listed artist (case-insensitive), with a vector. */
    fun explicitSeeds(vibe: Vibe): Set<String> {
        val seeds = vibe.seeds.toMutableSet()
        val names = vibe.artists.map(::fold).toSet()
        if (names.isNotEmpty()) {
            for ((id, list) in artists) if (list.any { fold(it) in names }) seeds += id
        }
        return seeds.filter { it in vectors }.toSet()
    }

    /** Tag seeds: tracks with a vector and any of the vibe's normalised `seed_tags`. */
    fun tagSeeds(vibe: Vibe): Set<String> {
        val wanted = normaliser.normaliseAll(vibe.seedTags)
        if (wanted.isEmpty()) return emptySet()
        return trackTags.filter { (id, tags) -> tags.any { it in wanted } && id in vectors }.keys
    }

    /**
     * Final seeds, sorted: explicit seeds plus the tag seeds inside the top k of a first ranking pass with
     * explicit ∪ tag seeds (rule 2).
     */
    fun seedTracks(vibe: Vibe): List<String> {
        val explicit = explicitSeeds(vibe)
        val fromTags = tagSeeds(vibe) - explicit
        if (fromTags.isEmpty()) return explicit.sorted()
        val firstPass = rank(explicit + fromTags, negatives(vibe)).take(k(vibe)).map { it.id }.toSet()
        return (explicit + fromTags.intersect(firstPass)).sorted()
    }

    /** Every track with a vector and its score, cos(track, P) − 0.2 × cos(track, N), best first (ties by ID). */
    fun ranked(vibe: Vibe): List<RankedTrack> = rank(seedTracks(vibe).toSet(), negatives(vibe))

    /** Members: top `k = ceil(share × tracks with a vector)`, kept only inside the seeds' energy band, ∪ seeds − `exclude`. */
    fun members(vibe: Vibe): Set<String> {
        val seeds = seedTracks(vibe)
        val order = rank(seeds.toSet(), negatives(vibe))
        if (order.isEmpty()) return emptySet()
        var top = order.take(k(vibe)).map { it.id }
        energyBand(seeds)?.let { band -> top = top.filter { id -> energies[id]?.let { it in band } ?: true } }
        return (top.toSet() + seeds) - vibe.exclude.toSet()
    }

    /** Members of every vibe, keyed by vibe ID: the `vibeMembers` argument of [QueryEngine]. */
    fun allMembers(vibes: Map<String, Vibe>): Map<String, Set<String>> = vibes.mapValues { members(it.value) }

    internal fun k(vibe: Vibe): Int = maxOf(0, ceil(vibe.share * vectors.size - 1e-9).toInt())

    private fun negatives(vibe: Vibe): Set<String> = vibe.exclude.filter { it in vectors }.toSet()

    /** normalise(mean of the normalised vectors of `ids`), summed in ID order; `null` for no IDs. */
    private fun centroid(ids: Set<String>): List<Double>? {
        val sorted = ids.sorted()
        val dims = sorted.firstOrNull()?.let { vectors[it]?.size } ?: return null
        val mean = DoubleArray(dims)
        for (id in sorted) {
            val u = unit(vectors.getValue(id))
            for (d in 0 until minOf(dims, u.size)) mean[d] += u[d]
        }
        return unit(mean.map { it / sorted.size })
    }

    private fun rank(positive: Set<String>, negative: Set<String>): List<RankedTrack> {
        val p = centroid(positive) ?: return emptyList()
        val n = centroid(negative)
        return vectors.map { (id, v) ->
            val u = unit(v)
            var score = dot(u, p)
            if (n != null) score -= negativeWeight * dot(u, n)
            RankedTrack(id, score)
        }.sortedWith(compareByDescending<RankedTrack> { it.score }.thenBy { it.id })
    }

    companion object {
        /** Rule 5 constants: margin around the seeds' energy range, outlier distance, seeds needed. */
        const val energyMargin = 1
        const val energyOutlier = 4.0
        const val energyMinSeeds = 3

        /** Weight of the pull away from excluded tracks in the score (rule 3). */
        const val negativeWeight = 0.2

        /** Effective energy per track: the `energies` argument. */
        fun energies(tracks: Map<String, Track>, overrides: Map<String, TrackOverride>): Map<String, Int> =
            tracks.mapNotNull { (id, t) -> EffectiveTags(auto = t.auto, override = overrides[id]).energy?.let { id to it } }
                .toMap()

        internal fun median(xs: List<Int>): Double {
            val s = xs.sorted()
            if (s.isEmpty()) return 0.0
            return if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
        }

        fun dot(a: List<Double>, b: List<Double>): Double {
            var sum = 0.0
            for (d in 0 until minOf(a.size, b.size)) sum += a[d] * b[d]
            return sum
        }

        fun unit(v: List<Double>): List<Double> {
            val norm = sqrt(v.fold(0.0) { acc, x -> acc + x * x })
            return if (norm > 0) v.map { it / norm } else v
        }

        internal fun fold(name: String): String = name.uppercase().lowercase()

        private fun vectorsOf(index: VectorIndex): Map<String, List<Double>> {
            val out = LinkedHashMap<String, List<Double>>(index.count * 2)
            for (id in index.ids) {
                if (id in out) continue
                index.vector(id)?.let { v -> out[id] = v.map { it.toDouble() } }
            }
            return out
        }
    }
}
