package com.overmap.crate.app

import com.overmap.crate.core.Query

/** The Build screen's filters (design README "State (Build)"). Converts to a core [Query]. */
data class BuildState(
    /** Picked families → ticked styles. All of a family's styles ticked = the whole family. */
    val families: Map<String, Set<String>> = emptyMap(),
    val vibes: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val bpm: IntRange = BPM_ANY,
    val halfDouble: Boolean = false,
    val energy: IntRange = ENERGY_ANY,
    val key: String? = null,
    val compatible: Boolean = true,
    val vocal: Query.Vocal = Query.Vocal.any,
    val limit: Int = 100,
) {
    val isEmpty: Boolean get() = this == BuildState(limit = limit)

    /** `allStyles` gives each family's full style list, to tell "all ticked" (send the family) from "some". */
    fun query(allStyles: Map<String, Set<String>>): Query {
        val include = mutableListOf<String>()
        val some = mutableMapOf<String, List<String>>()
        for ((fam, ticked) in families) {
            if (ticked.isEmpty()) continue
            if (ticked == (allStyles[fam] ?: emptySet<String>())) include += fam else some[fam] = ticked.sorted()
        }
        return Query(
            familiesInclude = include.sorted(),
            familyStyles = some,
            vibesInclude = vibes,
            tagsInclude = tags,
            bpm = if (bpm != BPM_ANY) Query.BPMRange(bpm.first.toDouble(), bpm.last.toDouble(), halfDouble) else null,
            energy = if (energy != ENERGY_ANY) Query.EnergyRange(energy.first, energy.last) else null,
            key = key?.let { Query.KeyFilter(it, compatible) },
            vocal = vocal,
            limit = limit,
        )
    }

    companion object {
        val BPM_ANY = 60..200
        val ENERGY_ANY = 1..10

        /** Back from a preset's query (old flat fields are not shown on Build). */
        fun from(q: Query, allStyles: Map<String, Set<String>>): BuildState {
            val fams = mutableMapOf<String, Set<String>>()
            for (f in q.familiesInclude) fams[f] = allStyles[f] ?: emptySet()
            for ((f, st) in q.familyStyles) fams[f] = st.toSet()
            return BuildState(
                families = fams,
                vibes = q.vibesInclude,
                tags = q.tagsInclude,
                bpm = q.bpm?.let { it.min.toInt()..it.max.toInt() } ?: BPM_ANY,
                halfDouble = q.bpm?.halfDouble ?: false,
                energy = q.energy?.let { it.min..it.max } ?: ENERGY_ANY,
                key = q.key?.camelot,
                compatible = q.key?.compatible ?: true,
                vocal = q.vocal,
                limit = q.limit,
            )
        }
    }
}
