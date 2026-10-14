import Foundation

/// Evaluates ``Query`` values against the library. Pure functions; shared semantics with `pipeline/crate/query.py`.
public enum QueryEngine {
    /// True when a track with these effective tags satisfies every constrained field of `query`.
    /// - Parameters:
    ///   - trackID: The track's ID, needed for `vibes_include`.
    ///   - vibeMembers: Vibe ID → member track IDs (from ``VibeIndex/allMembers(_:)``).
    ///   - normaliser: Normalises `tags_include` terms (the track side is already in `t.communityTags`).
    public static func matches(_ query: Query, tags t: EffectiveTags, trackID: String = "",
                               vibeMembers: [String: Set<String>] = [:],
                               normaliser: TagNormaliser = .bundled) -> Bool {
        if !query.tagsInclude.isEmpty {
            guard let have = t.communityTags else { return false }
            let wanted = query.tagsInclude.map(normaliser.normalise).filter { !$0.isEmpty }
            guard wanted.allSatisfy(have.contains) else { return false }
        }
        if !query.vibesInclude.isEmpty {
            guard query.vibesInclude.allSatisfy({ vibeMembers[$0]?.contains(trackID) == true }) else { return false }
        }
        // One "any of" group: family OR (family AND style ticked under it) OR primary genre OR primary style.
        if !query.familiesInclude.isEmpty || !query.familyStyles.isEmpty
            || !query.genresInclude.isEmpty || !query.stylesInclude.isEmpty {
            var familyHit = t.family.map(query.familiesInclude.contains) ?? false
            if !familyHit, let family = t.family, let style = t.primaryStyle,
               let ticked = query.familyStyles[family], ticked.contains(style) {
                familyHit = true
            }
            let genreHit = t.primaryGenre.map(query.genresInclude.contains) ?? false
            let styleHit = t.primaryStyle.map(query.stylesInclude.contains) ?? false
            if !familyHit && !genreHit && !styleHit { return false }
        }
        if !query.genresExclude.isEmpty {
            guard let g = t.primaryGenre, !query.genresExclude.contains(g) else { return false }
        }
        if !query.stylesExclude.isEmpty {
            guard let s = t.primaryStyle, !query.stylesExclude.contains(s) else { return false }
        }
        if let range = query.bpm {
            guard let bpm = t.bpm, range.contains(bpm) else { return false }
        }
        if let range = query.energy {
            guard let energy = t.energy, range.contains(energy) else { return false }
        }
        if !query.moodsInclude.isEmpty {
            guard let moods = t.moods, query.moodsInclude.allSatisfy(moods.contains) else { return false }
        }
        if !query.moodsExclude.isEmpty {
            guard let moods = t.moods, moods.isDisjoint(with: query.moodsExclude) else { return false }
        }
        if let filter = query.key {
            guard let key = t.key.flatMap(Camelot.init), filter.accepted.contains(key.code) else { return false }
        }
        switch query.vocal {
        case .any: break
        case .vocal: guard t.vocal == true else { return false }
        case .instrumental: guard t.vocal == false else { return false }
        }
        return true
    }

    /// Convenience: merges `track` with `override` and tests it.
    public static func matches(_ query: Query, track: Track, override: TrackOverride?, trackID: String = "",
                               vibeMembers: [String: Set<String>] = [:]) -> Bool {
        matches(query, tags: EffectiveTags(track: track, override: override), trackID: trackID, vibeMembers: vibeMembers)
    }

    /// IDs of every matching track, sorted for stable output.
    public static func matchingIDs(query: Query, tracks: [String: Track], overrides: [String: TrackOverride],
                                   vibeMembers: [String: Set<String>] = [:]) -> [String] {
        tracks.keys.sorted().filter { id in
            matches(query, track: tracks[id]!, override: overrides[id], trackID: id, vibeMembers: vibeMembers)
        }
    }

    /// Number of matching tracks (ignores ``Query/limit``).
    public static func count(query: Query, tracks: [String: Track], overrides: [String: TrackOverride],
                             vibeMembers: [String: Set<String>] = [:]) -> Int {
        tracks.reduce(0) { n, entry in
            n + (matches(query, track: entry.value, override: overrides[entry.key],
                         trackID: entry.key, vibeMembers: vibeMembers) ? 1 : 0)
        }
    }

    /// Runs `query`: a random sample of `min(limit, matches)` track IDs, shuffled with Fisher–Yates.
    public static func run<R: RandomNumberGenerator>(
        query: Query, tracks: [String: Track], overrides: [String: TrackOverride],
        vibeMembers: [String: Set<String>] = [:], rng: inout R
    ) -> [String] {
        let all = matchingIDs(query: query, tracks: tracks, overrides: overrides, vibeMembers: vibeMembers)
        return sampleAndShuffle(all, limit: query.limit, rng: &rng)
    }

    /// Picks `min(limit, items.count)` items uniformly at random and returns them in random order.
    ///
    /// A partial Fisher–Yates draws the sample; a full Fisher–Yates then shuffles it.
    public static func sampleAndShuffle<T, R: RandomNumberGenerator>(_ items: [T], limit: Int, rng: inout R) -> [T] {
        var pool = items
        let k = max(0, min(limit, pool.count))
        for i in 0..<k {
            let j = Int.random(in: i..<pool.count, using: &rng)
            pool.swapAt(i, j)
        }
        var sample = Array(pool.prefix(k))
        fisherYates(&sample, rng: &rng)
        return sample
    }

    /// In-place Fisher–Yates shuffle with an injectable generator.
    public static func fisherYates<T, R: RandomNumberGenerator>(_ array: inout [T], rng: inout R) {
        guard array.count > 1 else { return }
        for i in stride(from: array.count - 1, to: 0, by: -1) {
            let j = Int.random(in: 0...i, using: &rng)
            array.swapAt(i, j)
        }
    }
}
