import CrateKit
import CrateUI
import Foundation

/// One "Try one of these" suggestion on the empty Build state.
struct BuildRelaxation: Identifiable {
    let id: String
    let title: String
    let count: Int
    let apply: (inout BuildState) -> Void
}

/// Small read helpers for the Build screens (display names, counts). Owner: Build agent.
extension AppModel {
    /// IDs matching the current Build state (the whole library when nothing is set).
    var buildMatchingIDs: [String] { build.isEmpty ? index.ids : index.matchingIDs(query) }

    /// Up to `n` matching tracks with artwork for the result card, most recently liked first.
    func coverTrackIDs(_ ids: [String], n: Int = 4) -> [String] {
        let withCover = ids.filter { index.tracks[$0]?.cover != nil }
        return Array(withCover.sorted { (index.tracks[$0]?.likedAt ?? "") > (index.tracks[$1]?.likedAt ?? "") }.prefix(n))
    }

    /// Tracks that have at least one community tag ("Community tags from … · 968 tracks").
    var communityTaggedCount: Int {
        index.effective.values.reduce(0) { $0 + (($1.communityTags?.isEmpty ?? true) ? 0 : 1) }
    }

    func familyName(_ id: String) -> String { snapshot.taxonomy.displayName(family: id) }
    func familyHue(_ id: String) -> Family { Family(rawValue: id) ?? .none }
    func styleName(_ canonical: String) -> String { snapshot.taxonomy.label(forStyle: canonical).styleDisplay }

    /// A family's styles in picker order (by track count), canonical labels.
    func styles(ofFamily id: String) -> [String] {
        index.families.first { $0.family == id }?.styles.map(\.style) ?? []
    }

    func vibeSymbol(id: String, vibe: Vibe) -> String { vibe.icon ?? VibeSymbols.defaults[id] ?? "tag" }

    func vibeHasSeeds(_ vibe: Vibe) -> Bool { !(vibe.seeds.isEmpty && vibe.artists.isEmpty && vibe.seedTags.isEmpty) }

    /// Tracks in a key (and with its compatible set), over the whole library.
    func keyCounts(_ code: String) -> (exact: Int, compatible: Int) {
        guard let key = Camelot(code) else { return (0, 0) }
        let set = Set(key.compatible.map(\.code))
        var exact = 0, compat = 0
        for e in index.effective.values {
            guard let k = e.key else { continue }
            if k == key.code { exact += 1 }
            if set.contains(k) { compat += 1 }
        }
        return (exact, compat)
    }

    /// Relaxations for an empty result: each loosens one filter; the top 3 that give tracks, by count.
    func relaxations() -> [BuildRelaxation] {
        let b = build
        var candidates: [(id: String, title: String, apply: (inout BuildState) -> Void)] = []
        if b.energy != BuildState.energyAny, b.energy.lowerBound > 1 {
            let lo = max(1, b.energy.lowerBound - 2)
            candidates.append(("energy", "Energy \(lo)–\(b.energy.upperBound)", { $0.energy = lo...$0.energy.upperBound }))
        }
        if b.bpm != BuildState.bpmAny {
            let lo = max(BuildState.bpmAny.lowerBound, b.bpm.lowerBound - 6)
            let hi = min(BuildState.bpmAny.upperBound, b.bpm.upperBound + 6)
            candidates.append(("bpm", "BPM \(lo)–\(hi)", { $0.bpm = lo...hi }))
        }
        for v in b.vibes {
            let name = snapshot.vibes.vibes[v]?.name ?? v
            candidates.append(("vibe-\(v)", "Drop vibe \(name)", { $0.vibes.removeAll { $0 == v } }))
        }
        for t in b.tags {
            candidates.append(("tag-\(t)", "Drop tag \(t)", { $0.tags.removeAll { $0 == t } }))
        }
        if b.key != nil { candidates.append(("key", "Any key", { $0.key = nil })) }
        if b.vocal != .any { candidates.append(("vocal", "Vocals any", { $0.vocal = .any })) }
        return candidates
            .map { BuildRelaxation(id: $0.id, title: $0.title, count: count(applying: $0.apply), apply: $0.apply) }
            .filter { $0.count > 0 }
            .sorted { $0.count > $1.count }
            .prefix(3)
            .map { $0 }
    }
}
