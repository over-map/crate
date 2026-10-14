import CrateKit
import Foundation

/// The Build screen's filters (design README "State (Build)"). Converts to a CrateKit `Query`.
struct BuildState: Equatable, Sendable {
    /// Picked families → ticked styles. All of a family's styles ticked = the whole family.
    var families: [String: Set<String>] = [:]
    var vibes: [String] = []
    var tags: [String] = []
    var bpm: ClosedRange<Int> = BuildState.bpmAny
    var halfDouble = false
    var energy: ClosedRange<Int> = BuildState.energyAny
    var key: String?
    var compatible = true
    var vocal: Query.Vocal = .any
    var limit = 100

    static let bpmAny = 60...200
    static let energyAny = 1...10

    var isEmpty: Bool { self == BuildState(limit: limit) }

    /// `allStyles` gives each family's full style list, to tell "all ticked" (send the family) from "some".
    func query(allStyles: [String: Set<String>]) -> Query {
        var q = Query()
        for (fam, ticked) in families where !ticked.isEmpty {
            if ticked == (allStyles[fam] ?? []) { q.familiesInclude.append(fam) } else { q.familyStyles[fam] = ticked.sorted() }
        }
        q.familiesInclude.sort()
        q.vibesInclude = vibes
        q.tagsInclude = tags
        if bpm != BuildState.bpmAny { q.bpm = .init(min: Double(bpm.lowerBound), max: Double(bpm.upperBound), halfDouble: halfDouble) }
        if energy != BuildState.energyAny { q.energy = .init(min: energy.lowerBound, max: energy.upperBound) }
        if let key { q.key = .init(camelot: key, compatible: compatible) }
        q.vocal = vocal
        q.limit = limit
        return q
    }

    /// Back from a preset's query (presets may also use the older flat fields; those are kept as-is via `extra`).
    init(query q: Query, allStyles: [String: Set<String>]) {
        for f in q.familiesInclude { families[f] = allStyles[f] ?? [] }
        for (f, st) in q.familyStyles { families[f] = Set(st) }
        vibes = q.vibesInclude
        tags = q.tagsInclude
        if let b = q.bpm { bpm = Int(b.min)...Int(b.max); halfDouble = b.halfDouble }
        if let e = q.energy { energy = e.min...e.max }
        if let k = q.key { key = k.camelot; compatible = k.compatible }
        vocal = q.vocal
        limit = q.limit
    }

    init(limit: Int = 100) { self.limit = limit }
}
