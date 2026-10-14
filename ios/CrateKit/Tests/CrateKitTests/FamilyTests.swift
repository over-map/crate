import Foundation
import Testing
@testable import CrateKit

@Suite("Families")
struct FamilyTests {
    @Test func decodesFamilyAndConsensus() throws {
        let json = #"""
        {"schema":1,"tracks":{
          "a":{"family":"techno","consensus":{"family":"techno","votes":3,"sources":4,"audio_agrees":true,
               "by_source":{"audio":["techno"],"discogs":["ambient","techno"]}}},
          "b":{"family":"house","consensus":{"family":"house","votes":1,"sources":1,"audio_agrees":null,"by_source":{}}},
          "c":{}}}
        """#
        let lib = try CrateJSON.decode(LibraryFile.self, from: Data(json.utf8), file: "library.json")
        let a = try #require(lib.tracks["a"])
        #expect(a.family == "techno")
        #expect(a.consensus == Consensus(family: "techno", votes: 3, sources: 4, audioAgrees: true,
                                         bySource: ["audio": ["techno"], "discogs": ["ambient", "techno"]]))
        #expect(lib.tracks["b"]?.consensus?.audioAgrees == nil)
        #expect(lib.tracks["c"]?.family == nil && lib.tracks["c"]?.consensus == nil)

        let back = try CrateJSON.decode(LibraryFile.self, from: CrateJSON.encode(lib), file: "library.json")
        #expect(back == lib)
    }

    @Test func overrideFamilyWins() throws {
        let track = Track(family: "rock & indie")
        let ov = try JSONDecoder().decode(TrackOverride.self, from: Data(#"{"family":"indie dance"}"#.utf8))
        #expect(!ov.isEmpty)
        #expect(EffectiveTags(track: track, override: ov).family == "indie dance")
        #expect(EffectiveTags(track: track, override: nil).family == "rock & indie")
        #expect(EffectiveTags(track: track, override: TrackOverride(family: "")).family == "rock & indie",
                "empty override falls through, like Python's `or`")
        #expect(EffectiveTags(track: Track(), override: nil).family == nil)
    }

    @Test func queryAndTaxonomy() throws {
        let q = try JSONDecoder().decode(Query.self, from: Data(#"{"families_include":["techno"]}"#.utf8))
        #expect(q.familiesInclude == ["techno"] && !q.isUnconstrained)
        #expect(!QueryEngine.matches(q, track: Track(), override: nil), "unknown family fails")
        #expect(QueryEngine.matches(q, track: Track(family: "techno"), override: nil))

        let tax = try CrateJSON.decode(TaxonomyFile.self, from: Data(#"{"schema":1,"genres":{},"families":["techno","house"]}"#.utf8), file: "taxonomy.json")
        #expect(tax.families == ["techno", "house"])
    }
}
