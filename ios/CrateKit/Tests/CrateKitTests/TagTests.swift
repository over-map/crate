import Foundation
import Testing
@testable import CrateKit

struct SynonymVectors: Decodable {
    struct Case: Decodable, Sendable, CustomTestStringConvertible {
        let `in`: String
        let out: String
        var testDescription: String { "\"\(self.in)\"" }
    }
    let cases: [Case]
    static let shared = try! JSONDecoder().decode(SynonymVectors.self, from: Repo.spec("tag_synonyms.json"))
}

@Suite("Community tags")
struct TagTests {
    @Test func bundledCopyMatchesSpecByteForByte() throws {
        let url = try #require(Bundle.module.url(forResource: "tag_synonyms", withExtension: "json"))
        #expect(try Data(contentsOf: url) == Repo.spec("tag_synonyms.json"),
                "copy spec/tag_synonyms.json to ios/CrateKit/Sources/CrateKit/Resources/")
        #expect(TagNormaliser.bundled == (try TagNormaliser(json: Repo.spec("tag_synonyms.json"))))
        #expect(!TagNormaliser.bundled.synonyms.isEmpty)
    }

    @Test(arguments: SynonymVectors.shared.cases)
    func synonymCase(_ c: SynonymVectors.Case) {
        #expect(Tags.normalise(c.in) == c.out)
    }

    @Test func extraNormalisation() {
        let n = TagNormaliser.bundled
        #expect(n.normalise("Rock’n’Roll") == "rocknroll")
        #expect(n.normalise("a\t\n b") == "a b")
        #expect(n.normalise(" - ") == "")
        #expect(n.normaliseAll(["Italy", "italian", " ", "Hip-Hop"]) == ["italian", "hip hop"])
        #expect(n.normalise(n.normalise("Italia")) == "italian", "idempotent")
    }

    @Test func trackTagsUnknownVsEmpty() throws {
        let lib = try CrateJSON.decode(LibraryFile.self, from: Data(#"{"tracks":{"a":{"tags":["Italy"]},"b":{"tags":[]},"c":{}}}"#.utf8), file: "library.json")
        let n = TagNormaliser.bundled
        #expect(n.trackTags(lib.tracks["a"]!) == ["italian"])
        #expect(n.trackTags(lib.tracks["b"]!) == [])
        #expect(n.trackTags(lib.tracks["c"]!) == nil)
        let q = Query(tagsInclude: ["italian"])
        #expect(!QueryEngine.matches(q, track: lib.tracks["b"]!, override: nil))
        #expect(!QueryEngine.matches(q, track: lib.tracks["c"]!, override: nil))
        #expect(QueryEngine.matches(q, track: lib.tracks["a"]!, override: nil))
    }

    @Test func queryDecodingNormalises() throws {
        let q = try JSONDecoder().decode(Query.self, from: Data(#"{"tags_include":["Italia","HIP_HOP"," "]}"#.utf8))
        #expect(q.tagsInclude == ["italian", "hip hop"])
        #expect(!q.isUnconstrained)
        let vibe = try JSONDecoder().decode(Vibe.self, from: Data(#"{"name":"x","seed_tags":["Deep-Techno"]}"#.utf8))
        #expect(vibe.seedTags == ["Deep-Techno"])
    }

    @Test func vibeSeedTagsFromLibrary() throws {
        // a and b have vectors; b is tagged "dub techno"; c is tagged but has no vector.
        var bytes = [UInt8]()
        for first: UInt16 in [0x3C00, 0x3800] {
            for d in 0..<32 { let b: UInt16 = d == 0 ? first : 0; bytes += [UInt8(b & 0xFF), UInt8(b >> 8)] }
        }
        let index = try VectorIndex(bin: Data(bytes), ids: ["a", "b"])
        let library = LibraryFile(tracks: ["a": Track(), "b": Track(tags: ["Dub-Techno"]), "c": Track(tags: ["dub techno"])])
        let vibes = VibeIndex(index: index, library: library)
        #expect(vibes.tagSeeds(Vibe(name: "x", seedTags: ["dub techno"])) == ["b"])
        #expect(vibes.seedTracks(Vibe(name: "x", seedTags: ["dub techno"], share: 1)) == ["b"])
        // With k = 1 the first pass keeps only "a" (a and b tie at 1.0; ID breaks the tie), so b is pruned.
        #expect(vibes.seedTracks(Vibe(name: "x", seedTags: ["dub techno"], share: 0.2)).isEmpty)
    }
}
