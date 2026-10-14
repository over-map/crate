import Foundation
import Testing
@testable import CrateKit

@Suite("Vibes")
struct VibeTests {
    /// float16 bits for a few exactly representable values.
    private let bits: [Double: UInt16] = [0: 0x0000, 1: 0x3C00, 0.5: 0x3800, 0.25: 0x3400, 0.75: 0x3A00, -0.5: 0xB800]

    private let rows: [(String, [Double])] = [
        ("a", [1, 0, 0.25]), ("b", [0.75, 0.25, 0]), ("c", [0, 1, 0.5]),
        ("d", [0.5, 0.5, 0]), ("e", [0, 0.25, 1]), ("f", [-0.5, 1, 0]), ("g", [1, 0.25, 0]),
    ]

    /// Same rows as a 32-d float16 `vectors.bin` (dimensions beyond 3 are zero).
    private func vectorIndex() throws -> VectorIndex {
        var bytes = [UInt8]()
        for (_, v) in rows {
            for d in 0..<32 {
                let b = d < v.count ? bits[v[d]]! : 0
                bytes += [UInt8(b & 0xFF), UInt8(b >> 8)]
            }
        }
        return try VectorIndex(bin: Data(bytes), ids: rows.map(\.0))
    }

    private let artists = ["c": ["Sun Band"], "f": ["SUN BAND", "Other"]]

    private let vibes: [String: Vibe] = [
        "one": Vibe(name: "One", seeds: ["a"], share: 0.3),
        "artist": Vibe(name: "Artist", artists: ["sun band"], share: 0.2),
        "excl": Vibe(name: "Excl", seeds: ["a"], exclude: ["g"], share: 0.3),
        "ghost": Vibe(name: "Ghost", seeds: ["zzz"], share: 0.5),
        "neg": Vibe(name: "Neg", seeds: ["d"], exclude: ["a", "zzz"], share: 0.45),
        "tagged": Vibe(name: "Tagged", seeds: ["a"], seedTags: ["sunny"], exclude: ["c"], share: 0.3),
    ]

    private let trackTags: [String: Set<String>] = ["e": ["sunny"], "g": ["sunny"], "b": []]

    @Test func float16PathAgreesWithDoublePath() throws {
        let fromDoubles = VibeIndex(vectors: Dictionary(uniqueKeysWithValues: rows), artists: artists, trackTags: trackTags)
        let fromFloat16 = VibeIndex(index: try vectorIndex(), artists: artists, trackTags: trackTags)
        #expect(fromFloat16.vectors.count == rows.count)
        for (id, vibe) in vibes {
            #expect(fromFloat16.members(vibe) == fromDoubles.members(vibe), "\(id)")
            let r16 = fromFloat16.ranked(vibe), r64 = fromDoubles.ranked(vibe)
            #expect(r16.map(\.id) == r64.map(\.id), "\(id) ranking")
            for (x, y) in zip(r16, r64) { #expect(abs(x.score - y.score) < 1e-9) }
        }
    }

    @Test func negativesAndTagPruning() {
        let index = VibeIndex(vectors: Dictionary(uniqueKeysWithValues: rows), artists: artists, trackTags: trackTags)
        // Score = cos(x, P) − 0.2 · cos(x, N). With "a" excluded, tracks near "a" drop in the ranking.
        let plain = index.ranked(Vibe(name: "x", seeds: ["d"]))
        let pulled = index.ranked(vibes["neg"]!)
        let p = VibeIndex.unit([0.5, 0.5, 0]), n = VibeIndex.unit([1, 0, 0.25])
        let g = VibeIndex.unit([1, 0.25, 0])
        let expected = VibeIndex.dot(g, p) - VibeIndex.negativeWeight * VibeIndex.dot(g, n)
        #expect(abs((pulled.first { $0.id == "g" }?.score ?? 0) - expected) < 1e-12)
        #expect(pulled.first { $0.id == "g" }!.score < plain.first { $0.id == "g" }!.score)
        #expect(!index.members(vibes["neg"]!).contains("a"))
        // Tag seeds e and g: g sounds like a (kept), e doesn't (pruned in the first pass).
        #expect(index.tagSeeds(vibes["tagged"]!) == ["e", "g"])
        #expect(index.seedTracks(vibes["tagged"]!) == ["a", "g"])
    }

    @Test func rules() {
        let index = VibeIndex(vectors: Dictionary(uniqueKeysWithValues: rows), artists: artists)
        // k = ceil(0.3 × 7 − 1e-9) = 3: a itself, then g and b are closest to (1, 0, 0.25).
        #expect(index.members(vibes["one"]!) == ["a", "g", "b"])
        #expect(index.members(vibes["excl"]!) == ["a", "b"], "excluded track is not replaced")
        #expect(index.seedTracks(vibes["artist"]!) == ["c", "f"], "artist match is case-insensitive")
        #expect(index.members(vibes["artist"]!).isSuperset(of: ["c", "f"]))
        #expect(index.members(vibes["ghost"]!).isEmpty)
        #expect(index.members(Vibe(name: "x", share: 1)).isEmpty, "no seeds, no members")
        #expect(index.members(Vibe(name: "x", seeds: ["e"], share: 1)).count == 7)
        // 0.28 × 25 = 7.000000000000001 in floating point; the 1e-9 guard keeps k = 7, not 8.
        let many = VibeIndex(vectors: Dictionary(uniqueKeysWithValues: (0..<25).map {
            (String(format: "%02d", $0), [1.0, Double($0)])
        }))
        #expect(many.members(Vibe(name: "x", seeds: ["00"], share: 0.28)).count == 7)
    }

    @Test func fileDecodingAndQuery() throws {
        let json = #"{"schema":1,"vibes":{"hyp":{"name":"Hypnotic","seeds":["t1"],"artists":["Kangding Ray"]}}}"#
        let file = try CrateJSON.decode(VibesFile.self, from: Data(json.utf8), file: "vibes.json")
        #expect(file.vibes["hyp"] == Vibe(name: "Hypnotic", seeds: ["t1"], artists: ["Kangding Ray"], share: 0.2))
        #expect(throws: CrateDataError.self) {
            try CrateJSON.decode(VibesFile.self, from: Data(#"{"schema":2,"vibes":{}}"#.utf8), file: "vibes.json")
        }
        let back = try CrateJSON.decode(VibesFile.self, from: CrateJSON.encode(file), file: "vibes.json")
        #expect(back == file)

        let q = try JSONDecoder().decode(Query.self, from: Data(#"{"vibes_include":["hyp","x"]}"#.utf8))
        #expect(q.vibesInclude == ["hyp", "x"] && !q.isUnconstrained)
        let tags = EffectiveTags()
        #expect(QueryEngine.matches(q, tags: tags, trackID: "t1", vibeMembers: ["hyp": ["t1"], "x": ["t1"]]))
        #expect(!QueryEngine.matches(q, tags: tags, trackID: "t1", vibeMembers: ["hyp": ["t1"]]), "unknown vibe matches nothing")
    }

    @Test func vibesWriteThroughGitHubStore() async throws {
        let transport = FakeTransport(responses: [.json(404, "{}"), .json(201, #"{"content":{"sha":"v1"}}"#)])
        let store = GitHubStore(owner: "o", repo: "crate", token: { "t" }, transport: transport, cache: InMemoryGitHubCache())
        try await store.updateJSON(VibesFile.self, path: CrateKit.Path.vibes, message: "Add vibe", empty: VibesFile()) {
            $0.vibes["hyp"] = Vibe(name: "Hypnotic", seeds: ["t1"])
        }
        let put = jsonObject(transport.recorded[1].body)
        let written = try CrateJSON.decode(VibesFile.self, from: Data(base64Encoded: put["content"] as! String)!, file: "vibes.json")
        #expect(written.vibes["hyp"]?.seeds == ["t1"])
        #expect(transport.recorded[1].url.path == "/repos/o/crate/contents/data/vibes.json")
    }
}
