package com.overmap.crate.screens.build

import com.overmap.crate.app.AppModel
import com.overmap.crate.app.BuildState
import com.overmap.crate.core.Camelot
import com.overmap.crate.core.Query
import com.overmap.crate.core.Vibe
import com.overmap.crate.ui.theme.Family
import com.overmap.crate.ui.theme.VibeSymbols

/** One "Try one of these" suggestion on the empty Build state. */
data class BuildRelaxation(
    val id: String,
    val title: String,
    val count: Int,
    val apply: (BuildState) -> BuildState,
)

// Small read helpers for the Build screens (display names, counts): the port of iOS `AppModel+Build.swift`.

/** IDs matching the current Build state (the whole library when nothing is set). */
val AppModel.buildMatchingIDs: List<String>
    get() = if (build.isEmpty) index.ids else index.matchingIDs(query)

/** Up to [n] matching tracks with artwork for the result card, most recently liked first. */
fun AppModel.coverTrackIDs(ids: List<String>, n: Int = 4): List<String> =
    ids.filter { index.tracks[it]?.cover != null }
        .sortedByDescending { index.tracks[it]?.likedAt ?: "" }
        .take(n)

/** Tracks that have at least one community tag ("Community tags from … · 968 tracks"). */
val AppModel.communityTaggedCount: Int
    get() = index.effective.values.count { !it.communityTags.isNullOrEmpty() }

fun AppModel.familyName(id: String): String = snapshot.taxonomy.displayName(id)
fun AppModel.familyHue(id: String?): Family = Family.of(id)
fun AppModel.styleName(canonical: String): String = snapshot.taxonomy.label(canonical).styleDisplay

/** A family's styles in picker order (by track count), canonical labels. */
fun AppModel.styles(ofFamily: String): List<String> =
    index.families.firstOrNull { it.family == ofFamily }?.styles?.map { it.first } ?: emptyList()

fun vibeSymbol(id: String, vibe: Vibe): String = vibe.icon ?: VibeSymbols.defaults[id] ?: "tag"

fun vibeHasSeeds(vibe: Vibe): Boolean = !(vibe.seeds.isEmpty() && vibe.artists.isEmpty() && vibe.seedTags.isEmpty())

/** Tracks in a key (and with its compatible set), over the whole library. */
fun AppModel.keyCounts(code: String): Pair<Int, Int> {
    val key = Camelot(code) ?: return 0 to 0
    val set = key.compatible.map { it.code }.toSet()
    var exact = 0
    var compat = 0
    for (e in index.effective.values) {
        val k = e.key ?: continue
        if (k == key.code) exact++
        if (k in set) compat++
    }
    return exact to compat
}

/** Relaxations for an empty result: each loosens one filter; the top 3 that give tracks, by count. */
fun AppModel.relaxations(): List<BuildRelaxation> {
    val b = build
    val candidates = mutableListOf<Triple<String, String, (BuildState) -> BuildState>>()
    if (b.energy != BuildState.ENERGY_ANY && b.energy.first > 1) {
        val lo = maxOf(1, b.energy.first - 2)
        candidates += Triple("energy", "Energy $lo–${b.energy.last}") { s: BuildState -> s.copy(energy = lo..s.energy.last) }
    }
    if (b.bpm != BuildState.BPM_ANY) {
        val lo = maxOf(BuildState.BPM_ANY.first, b.bpm.first - 6)
        val hi = minOf(BuildState.BPM_ANY.last, b.bpm.last + 6)
        candidates += Triple("bpm", "BPM $lo–$hi") { s: BuildState -> s.copy(bpm = lo..hi) }
    }
    for (v in b.vibes) {
        val name = snapshot.vibes.vibes[v]?.name ?: v
        candidates += Triple("vibe-$v", "Drop vibe $name") { s: BuildState -> s.copy(vibes = s.vibes - v) }
    }
    for (t in b.tags) {
        candidates += Triple("tag-$t", "Drop tag $t") { s: BuildState -> s.copy(tags = s.tags - t) }
    }
    if (b.key != null) candidates += Triple("key", "Any key") { s: BuildState -> s.copy(key = null) }
    if (b.vocal != Query.Vocal.any) candidates += Triple("vocal", "Vocals any") { s: BuildState -> s.copy(vocal = Query.Vocal.any) }
    return candidates
        .map { (id, title, apply) -> BuildRelaxation(id, title, count(apply), apply) }
        .filter { it.count > 0 }
        .sortedByDescending { it.count }
        .take(3)
}
