package com.overmap.crate.app

import com.overmap.crate.core.EffectiveTags
import com.overmap.crate.core.Query
import com.overmap.crate.core.QueryEngine
import com.overmap.crate.core.Tags
import com.overmap.crate.core.Track
import com.overmap.crate.core.TrackOverride
import com.overmap.crate.core.TrackStatus
import com.overmap.crate.core.VibeIndex

/** Precomputed lookups over the library so live counts stay instant (recomputed on every sync or edit). */
class LibraryIndex(snapshot: DataSnapshot) {
    data class FamilyEntry(
        val family: String,
        val count: Int,
        /** Strongest styles of the family's tracks, by count (only styles that occur). */
        val styles: List<Pair<String, Int>>,
    )

    val tracks: Map<String, Track> = snapshot.library.tracks
    val overrides: Map<String, TrackOverride> = snapshot.overrides.tracks
    val effective: Map<String, EffectiveTags>
    val ids: List<String> = tracks.keys.sorted()
    val families: List<FamilyEntry>
    val noFamilyCount: Int
    /** Normalised tag → number of tracks. */
    val tagCounts: Map<String, Int>
    val vibeMembers: Map<String, Set<String>>
    val vibeIndex: VibeIndex?
    val taggedCount: Int

    init {
        val eff = HashMap<String, EffectiveTags>(tracks.size)
        val famCount = HashMap<String, Int>()
        val famStyles = HashMap<String, HashMap<String, Int>>()
        val tags = HashMap<String, Int>()
        var none = 0
        var tagged = 0
        for ((id, t) in tracks) {
            val e = EffectiveTags(track = t, override = overrides[id])
            eff[id] = e
            if (t.status == TrackStatus.tagged) tagged++
            val f = e.family
            if (f != null) {
                famCount[f] = (famCount[f] ?: 0) + 1
                e.primaryStyle?.let { s -> famStyles.getOrPut(f) { HashMap() }.merge(s, 1, Int::plus) }
            } else {
                none++
            }
            for (tag in e.communityTags ?: emptySet()) tags.merge(tag, 1, Int::plus)
        }
        effective = eff
        val order = snapshot.taxonomy.families
        families = famCount.map { (f, n) ->
            FamilyEntry(f, n, (famStyles[f] ?: emptyMap()).entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { it.key to it.value })
        }.sortedWith(compareByDescending<FamilyEntry> { it.count }.thenBy { order.indexOf(it.family).let { i -> if (i < 0) 99 else i } })
        noFamilyCount = none
        tagCounts = tags
        taggedCount = tagged
        val v = snapshot.vectors
        if (v != null) {
            val vi = VibeIndex(index = v, library = snapshot.library, overrides = snapshot.overrides)
            vibeIndex = vi
            vibeMembers = vi.allMembers(snapshot.vibes.vibes)
        } else {
            vibeIndex = null
            vibeMembers = emptyMap()
        }
    }

    fun count(q: Query): Int = ids.count { QueryEngine.matches(q, tags = effective.getValue(it), trackID = it, vibeMembers = vibeMembers) }

    fun matchingIDs(q: Query): List<String> =
        ids.filter { QueryEngine.matches(q, tags = effective.getValue(it), trackID = it, vibeMembers = vibeMembers) }

    /** Tag suggestions for autocomplete: prefix matches first, then substring, by count. */
    fun tagSuggestions(text: String, limit: Int = 6): List<Pair<String, Int>> {
        val q = Tags.normalise(text)
        if (q.isEmpty()) return emptyList()
        return tagCounts.filterKeys { it.contains(q) }.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.key.startsWith(q) }
                .thenByDescending { it.value }.thenBy { it.key })
            .take(limit).map { it.key to it.value }
    }

    companion object {
        val empty = LibraryIndex(DataSnapshot())
    }
}
