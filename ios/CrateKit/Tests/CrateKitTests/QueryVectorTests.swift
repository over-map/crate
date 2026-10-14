import Foundation
import Testing
@testable import CrateKit

/// The shared vectors in `spec/query_vectors.json` (also run by the Python pipeline).
struct QueryVectors: Decodable {
    struct Fixture: Decodable {
        let status: TrackStatus?
        let auto: AutoTags?
        let override: TrackOverride?
        let vector: [Double]?
        let artists: [String]?
        let tags: [String]?
        let family: String?
        let consensus: Consensus?
    }

    struct Case: Decodable, Sendable, CustomTestStringConvertible {
        let name: String
        let query: Query
        let matches: [String]
        let count: Int
        var testDescription: String { name }
    }

    let constants: [String: Double]
    let tracks: [String: Fixture]
    let cases: [Case]
    let vibes: [String: Vibe]

    static let shared: QueryVectors = {
        // swiftlint:disable:next force_try
        try! JSONDecoder().decode(QueryVectors.self, from: Repo.spec("query_vectors.json"))
    }()

    var library: [String: Track] {
        tracks.mapValues { Track(artists: $0.artists, status: $0.status ?? .pending, auto: $0.auto, tags: $0.tags,
                                   family: $0.family, consensus: $0.consensus) }
    }

    var overrides: [String: TrackOverride] { tracks.compactMapValues(\.override) }

    var vibeIndex: VibeIndex {
        VibeIndex(vectors: tracks.compactMapValues(\.vector), artists: tracks.compactMapValues(\.artists),
                  trackTags: library.compactMapValues(TagNormaliser.bundled.trackTags),
                  energies: VibeIndex.energies(library, overrides: overrides))
    }

    var vibeMembers: [String: Set<String>] { vibeIndex.allMembers(vibes) }
}

@Suite("Query vectors")
struct QueryVectorTests {
    let vectors = QueryVectors.shared

    @Test func constantsMatchEngine() {
        #expect(vectors.constants["MOOD_THRESHOLD"] == EffectiveTags.moodThreshold)
        #expect(vectors.constants["VOCAL_THRESHOLD"] == EffectiveTags.vocalThreshold)
        #expect(vectors.constants["DEFAULT_LIMIT"].map(Int.init) == Query.defaultLimit)
        #expect(vectors.constants["VIBE_DEFAULT_SHARE"] == Vibe.defaultShare)
        #expect(vectors.constants["VIBE_NEGATIVE_WEIGHT"] == VibeIndex.negativeWeight)
        #expect(vectors.constants["VIBE_ENERGY_MARGIN"].map(Int.init) == VibeIndex.energyMargin)
        #expect(vectors.constants["VIBE_ENERGY_OUTLIER"] == VibeIndex.energyOutlier)
        #expect(vectors.constants["VIBE_ENERGY_MIN_SEEDS"].map(Int.init) == VibeIndex.energyMinSeeds)
        #expect(vectors.cases.count >= 93)
    }

    @Test(arguments: QueryVectors.shared.cases)
    func vectorCase(_ c: QueryVectors.Case) {
        let library = vectors.library
        let overrides = vectors.overrides
        let vibes = vectors.vibeMembers
        let matched = QueryEngine.matchingIDs(query: c.query, tracks: library, overrides: overrides, vibeMembers: vibes)
        #expect(matched == c.matches.sorted(), "match set")
        #expect(QueryEngine.count(query: c.query, tracks: library, overrides: overrides, vibeMembers: vibes) == c.matches.count)

        for seed in [1, 2, 42] as [UInt64] {
            var rng = SeededGenerator(seed: seed)
            let result = QueryEngine.run(query: c.query, tracks: library, overrides: overrides, vibeMembers: vibes, rng: &rng)
            #expect(result.count == c.count, "sampled count")
            #expect(Set(result).count == result.count, "no duplicates")
            #expect(Set(result).isSubset(of: Set(c.matches)), "sample is drawn from matches")
        }
    }

    @Test func runIsDeterministicForASeedAndShuffles() {
        let library = vectors.library
        var a = SeededGenerator(seed: 7), b = SeededGenerator(seed: 7)
        let first = QueryEngine.run(query: Query(), tracks: library, overrides: [:], rng: &a)
        let second = QueryEngine.run(query: Query(), tracks: library, overrides: [:], rng: &b)
        #expect(first == second)
        #expect(first != library.keys.sorted(), "result is shuffled")
    }

    @Test func emptyJSONQueryDecodesToDefaults() throws {
        let q = try JSONDecoder().decode(Query.self, from: Data("{}".utf8))
        #expect(q == Query())
        #expect(q.limit == 100 && q.vocal == .any && q.isUnconstrained)
    }
}
