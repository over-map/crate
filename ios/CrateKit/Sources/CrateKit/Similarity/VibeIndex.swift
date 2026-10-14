import Foundation

/// Computes vibe members from sound-similarity vectors, with negative pull from excluded tracks (rules in `docs/query-semantics.md#vibes`,
/// reference `pipeline/crate/vibes.py`). All arithmetic is in `Double`.
public struct VibeIndex: Sendable {
    /// Track ID → embedding. Only these tracks can be vibe members.
    public let vectors: [String: [Double]]
    /// Track ID → artist names, for artist seeds.
    public let artists: [String: [String]]
    /// Track ID → normalised community tags, for `seed_tags`.
    public let trackTags: [String: Set<String>]
    /// Track ID → effective energy (an override wins), for the energy band (rule 5). Missing = unknown.
    public let energies: [String: Int]
    public let normaliser: TagNormaliser

    public init(vectors: [String: [Double]], artists: [String: [String]] = [:],
                trackTags: [String: Set<String>] = [:], energies: [String: Int] = [:], normaliser: TagNormaliser = .bundled) {
        self.vectors = vectors; self.artists = artists; self.trackTags = trackTags; self.energies = energies
        self.normaliser = normaliser
    }

    /// Uses the float16 rows of `index` (first row wins for a duplicated ID) and the library's artists.
    public init(index: VectorIndex, artists: [String: [String]] = [:], trackTags: [String: Set<String>] = [:],
                energies: [String: Int] = [:], normaliser: TagNormaliser = .bundled) {
        var vectors = [String: [Double]](minimumCapacity: index.count)
        for id in index.ids where vectors[id] == nil {
            if let v = index.vector(for: id) { vectors[id] = v.map(Double.init) }
        }
        self.init(vectors: vectors, artists: artists, trackTags: trackTags, energies: energies, normaliser: normaliser)
    }

    /// Uses `index` with artists, normalised tags and effective energies taken from `library` and `overrides`.
    public init(index: VectorIndex, library: LibraryFile, overrides: OverridesFile = OverridesFile(),
                normaliser: TagNormaliser = .bundled) {
        self.init(index: index, artists: library.tracks.compactMapValues(\.artists),
                  trackTags: library.tracks.compactMapValues(normaliser.trackTags),
                  energies: VibeIndex.energies(library.tracks, overrides: overrides.tracks), normaliser: normaliser)
    }

    /// Effective energy per track: the `energies` argument.
    public static func energies(_ tracks: [String: Track], overrides: [String: TrackOverride]) -> [String: Int] {
        var out: [String: Int] = [:]
        for (id, t) in tracks { out[id] = EffectiveTags(auto: t.auto, override: overrides[id]).energy }
        return out
    }

    /// Rule 5 constants: margin around the seeds' energy range, outlier distance, seeds needed.
    public static let energyMargin = 1
    public static let energyOutlier = 4.0
    public static let energyMinSeeds = 3

    /// Rule 5: the energies a member may have, or `nil` (no band) with fewer than 3 seeds of known energy.
    /// A seed more than 4 from the median of the other seeds is ignored.
    public func energyBand(seeds: some Sequence<String>) -> ClosedRange<Int>? {
        let known = seeds.compactMap { energies[$0] }
        guard known.count >= VibeIndex.energyMinSeeds else { return nil }
        let kept = known.indices.filter { i in
            var others = known; others.remove(at: i)
            return abs(Double(known[i]) - VibeIndex.median(others)) <= VibeIndex.energyOutlier
        }.map { known[$0] }
        guard let lo = kept.min(), let hi = kept.max() else { return nil }
        return (lo - VibeIndex.energyMargin)...(hi + VibeIndex.energyMargin)
    }

    static func median(_ xs: [Int]) -> Double {
        let s = xs.sorted()
        guard !s.isEmpty else { return 0 }
        return s.count % 2 == 1 ? Double(s[s.count / 2]) : Double(s[s.count / 2 - 1] + s[s.count / 2]) / 2
    }

    /// Weight of the pull away from excluded tracks in the score (rule 3).
    public static let negativeWeight = 0.2

    /// Explicit seeds: `seeds` ∪ tracks by a listed artist (case-insensitive), with a vector.
    public func explicitSeeds(_ vibe: Vibe) -> Set<String> {
        var seeds = Set(vibe.seeds)
        let names = Set(vibe.artists.map(VibeIndex.fold))
        if !names.isEmpty {
            for (id, list) in artists where list.contains(where: { names.contains(VibeIndex.fold($0)) }) {
                seeds.insert(id)
            }
        }
        return seeds.filter { vectors[$0] != nil }
    }

    /// Tag seeds: tracks with a vector and any of the vibe's normalised `seed_tags`.
    public func tagSeeds(_ vibe: Vibe) -> Set<String> {
        let wanted = normaliser.normaliseAll(vibe.seedTags)
        guard !wanted.isEmpty else { return [] }
        return Set(trackTags.compactMap { id, tags in
            !tags.isDisjoint(with: wanted) && vectors[id] != nil ? id : nil
        })
    }

    /// Final seeds, sorted: explicit seeds plus the tag seeds inside the top k of a first ranking pass with
    /// explicit ∪ tag seeds (rule 2).
    public func seedTracks(_ vibe: Vibe) -> [String] {
        let explicit = explicitSeeds(vibe)
        let fromTags = tagSeeds(vibe).subtracting(explicit)
        guard !fromTags.isEmpty else { return explicit.sorted() }
        let firstPass = Set(rank(positive: explicit.union(fromTags), negative: negatives(vibe)).prefix(k(vibe)).map(\.id))
        return explicit.union(fromTags.intersection(firstPass)).sorted()
    }

    /// Every track with a vector and its score, cos(track, P) − 0.2 × cos(track, N), best first (ties by ID).
    /// Empty when no seed has a vector.
    public func ranked(_ vibe: Vibe) -> [(id: String, score: Double)] {
        rank(positive: Set(seedTracks(vibe)), negative: negatives(vibe))
    }

    /// Members: top `k = ceil(share × tracks with a vector)`, kept only inside the seeds' energy band (rule 5),
    /// ∪ seeds − `exclude`.
    public func members(_ vibe: Vibe) -> Set<String> {
        let seeds = seedTracks(vibe)
        let order = rank(positive: Set(seeds), negative: negatives(vibe))
        guard !order.isEmpty else { return [] }
        var top = order.prefix(k(vibe)).map(\.id)
        if let band = energyBand(seeds: seeds) {
            top = top.filter { energies[$0].map(band.contains) ?? true }
        }
        var out = Set(top).union(seeds)
        out.subtract(vibe.exclude)
        return out
    }

    /// Members of every vibe, keyed by vibe ID: the `vibeMembers` argument of ``QueryEngine``.
    public func allMembers(_ vibes: [String: Vibe]) -> [String: Set<String>] {
        vibes.mapValues(members)
    }

    // MARK: Internals

    func k(_ vibe: Vibe) -> Int {
        max(0, Int((vibe.share * Double(vectors.count) - 1e-9).rounded(.up)))
    }

    private func negatives(_ vibe: Vibe) -> Set<String> {
        Set(vibe.exclude.filter { vectors[$0] != nil })
    }

    /// normalise(mean of the normalised vectors of `ids`), summed in ID order; `nil` for no IDs.
    private func centroid(_ ids: Set<String>) -> [Double]? {
        let sorted = ids.sorted()
        guard let dims = sorted.first.flatMap({ vectors[$0]?.count }) else { return nil }
        var mean = [Double](repeating: 0, count: dims)
        for id in sorted {
            let u = VibeIndex.unit(vectors[id]!)
            for d in 0..<min(dims, u.count) { mean[d] += u[d] }
        }
        return VibeIndex.unit(mean.map { $0 / Double(sorted.count) })
    }

    private func rank(positive: Set<String>, negative: Set<String>) -> [(id: String, score: Double)] {
        guard let p = centroid(positive) else { return [] }
        let n = centroid(negative)
        let scored = vectors.map { id, v -> (id: String, score: Double) in
            let u = VibeIndex.unit(v)
            var score = VibeIndex.dot(u, p)
            if let n { score -= VibeIndex.negativeWeight * VibeIndex.dot(u, n) }
            return (id, score)
        }
        return scored.sorted { $0.score != $1.score ? $0.score > $1.score : $0.id < $1.id }
    }

    static func dot(_ a: [Double], _ b: [Double]) -> Double {
        var sum = 0.0
        for d in 0..<min(a.count, b.count) { sum += a[d] * b[d] }
        return sum
    }

    static func unit(_ v: [Double]) -> [Double] {
        let norm = v.reduce(0) { $0 + $1 * $1 }.squareRoot()
        return norm > 0 ? v.map { $0 / norm } : v
    }

    static func fold(_ name: String) -> String {
        name.folding(options: [.caseInsensitive], locale: nil)
    }
}
