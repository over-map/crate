import CrateKit
import Foundation

/// Precomputed lookups over the library so live counts stay instant (1,015 tracks; recomputed on every sync or
/// vibe edit). Pure value type: build once, read anywhere.
struct LibraryIndex: Sendable {
    struct FamilyEntry: Identifiable, Sendable {
        var id: String { family }
        let family: String
        let count: Int
        /// Strongest styles of the family's tracks, by count (only styles that occur).
        let styles: [(style: String, count: Int)]
    }

    let tracks: [String: Track]
    let overrides: [String: TrackOverride]
    let effective: [String: EffectiveTags]
    let ids: [String]
    let families: [FamilyEntry]
    let noFamilyCount: Int
    /// Normalised tag → number of tracks.
    let tagCounts: [String: Int]
    let vibeMembers: [String: Set<String>]
    let vibeIndex: VibeIndex?
    let taggedCount: Int

    static let empty = LibraryIndex(snapshot: DataSnapshot())

    init(snapshot: DataSnapshot) {
        tracks = snapshot.library.tracks
        overrides = snapshot.overrides.tracks
        var eff: [String: EffectiveTags] = [:]
        var famCount: [String: Int] = [:]
        var famStyles: [String: [String: Int]] = [:]
        var tags: [String: Int] = [:]
        var none = 0
        var tagged = 0
        for (id, t) in tracks {
            let e = EffectiveTags(track: t, override: overrides[id])
            eff[id] = e
            if t.status == .tagged { tagged += 1 }
            if let f = e.family {
                famCount[f, default: 0] += 1
                if let s = e.primaryStyle { famStyles[f, default: [:]][s, default: 0] += 1 }
            } else {
                none += 1
            }
            for tag in e.communityTags ?? [] { tags[tag, default: 0] += 1 }
        }
        effective = eff
        ids = tracks.keys.sorted()
        let order = snapshot.taxonomy.families
        families = famCount.map { f, n in
            FamilyEntry(family: f, count: n,
                        styles: (famStyles[f] ?? [:]).sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }
                            .map { ($0.key, $0.value) })
        }.sorted { a, b in
            a.count != b.count ? a.count > b.count : (order.firstIndex(of: a.family) ?? 99) < (order.firstIndex(of: b.family) ?? 99)
        }
        noFamilyCount = none
        tagCounts = tags
        taggedCount = tagged
        if let v = snapshot.vectors {
            let vi = VibeIndex(index: v, library: snapshot.library, overrides: snapshot.overrides)
            vibeIndex = vi
            vibeMembers = vi.allMembers(snapshot.vibes.vibes)
        } else {
            vibeIndex = nil
            vibeMembers = [:]
        }
    }

    func count(_ q: Query) -> Int {
        var n = 0
        for id in ids where QueryEngine.matches(q, tags: effective[id]!, trackID: id, vibeMembers: vibeMembers) { n += 1 }
        return n
    }

    func matchingIDs(_ q: Query) -> [String] {
        ids.filter { QueryEngine.matches(q, tags: effective[$0]!, trackID: $0, vibeMembers: vibeMembers) }
    }

    /// Tag suggestions for autocomplete: prefix matches first, then substring, by count.
    func tagSuggestions(_ text: String, limit: Int = 6) -> [(tag: String, count: Int)] {
        let q = Tags.normalise(text)
        guard !q.isEmpty else { return [] }
        let hits = tagCounts.filter { $0.key.contains(q) }
        return hits.sorted { a, b in
            let ap = a.key.hasPrefix(q), bp = b.key.hasPrefix(q)
            if ap != bp { return ap }
            return a.value != b.value ? a.value > b.value : a.key < b.key
        }.prefix(limit).map { ($0.key, $0.value) }
    }
}
