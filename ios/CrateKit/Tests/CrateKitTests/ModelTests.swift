import Foundation
import Testing
@testable import CrateKit

@Suite("Data files")
struct ModelTests {
    @Test func libraryFromSpecExample() throws {
        let json = """
        {"schema": 1, "generated_at": "2026-10-12T03:12:00Z", "tracks": {"abc": {
          "uri": "spotify:track:abc", "isrc": "GBXXX", "title": "T", "artists": ["A"], "duration_ms": 412000,
          "cover": "https://i.scdn.co/image/x", "liked_at": "2024-05-01T10:00:00Z", "status": "tagged",
          "source": {"deezer_id": 123, "analysed_at": "x", "model_version": "2026.10.1"},
          "auto": {"genres": {"Electronic": 0.97}, "styles": {"Electronic---Breakbeat": 0.58},
            "bpm": 138.0, "bpm_conf": 0.91, "bpm_candidates": [138.0, 69.0], "key": "8A", "key_conf": 0.72,
            "energy": 7, "moods": {"dark": 0.81}, "vocal": 0.12, "future_field": true}},
          "def": {}}}
        """
        let lib = try CrateJSON.decode(LibraryFile.self, from: Data(json.utf8), file: "library.json")
        let t = try #require(lib.tracks["abc"])
        #expect(t.status == .tagged && t.durationMS == 412000 && t.source?.deezerID == 123)
        #expect(t.auto?.bpmCandidates == [138, 69] && t.auto?.keyConf == 0.72 && t.auto?.styles?["Electronic---Breakbeat"] == 0.58)
        let empty = try #require(lib.tracks["def"])
        #expect(empty.status == .pending && empty.auto == nil)
        #expect(empty.spotifyURI(id: "def") == "spotify:track:def")
    }

    @Test func statuses() throws {
        let decode = { (s: String) in try JSONDecoder().decode(TrackStatus.self, from: Data("\"\(s)\"".utf8)) }
        #expect(try decode("no_preview") == .noPreview)
        #expect(try decode("something_new") == .error)
    }

    @Test func newerSchemaIsRefused() {
        let data = Data(#"{"schema": 2, "tracks": {}}"#.utf8)
        #expect(throws: CrateDataError.unsupportedSchema(file: "library.json", found: 2, supported: 1)) {
            try CrateJSON.decode(LibraryFile.self, from: data, file: "library.json")
        }
        let message = CrateDataError.unsupportedSchema(file: "x", found: 2, supported: 1).errorDescription ?? ""
        #expect(message.contains("Update the app"))
    }

    @Test func missingSchemaIsAccepted() throws {
        let o = try CrateJSON.decode(OverridesFile.self, from: Data(#"{"tracks": {"x": {"bpm": 140, "moods_add": ["dark"]}}}"#.utf8), file: "overrides.json")
        #expect(o.tracks["x"]?.bpm == 140 && o.tracks["x"]?.moodsAdd == ["dark"])
    }

    @Test func presetsAcceptArrayAndObjectAndEncodeObject() throws {
        let array = #"[{"id": "p1", "name": "Run 160", "icon": "figure.run", "color": "red", "in_widget": true, "query": {"bpm": {"min": 156, "max": 176}}}]"#
        let fromArray = try CrateJSON.decode(PresetsFile.self, from: Data(array.utf8), file: "presets.json")
        #expect(fromArray.presets.first?.query.bpm == Query.BPMRange(min: 156, max: 176))
        #expect(fromArray.nowPlaylistID == nil && fromArray.widgetPresets.count == 1)

        let object = #"{"schema": 1, "now_playlist_id": "NOW", "presets": [{"id": "p2", "name": "x"}]}"#
        let fromObject = try CrateJSON.decode(PresetsFile.self, from: Data(object.utf8), file: "presets.json")
        #expect(fromObject.nowPlaylistID == "NOW")
        #expect(fromObject.presets.first?.color == "ink" && fromObject.presets.first?.inWidget == false)

        let encoded = jsonObject(try CrateJSON.encode(fromObject))
        #expect(encoded["now_playlist_id"] as? String == "NOW")
        #expect(encoded["schema"] as? Int == 1)
        #expect((encoded["presets"] as? [[String: Any]])?.first?["in_widget"] as? Bool == false)

        #expect(throws: CrateDataError.self) {
            try CrateJSON.decode(PresetsFile.self, from: Data(#"{"schema": 3, "presets": []}"#.utf8), file: "presets.json")
        }
    }

    @Test func queryRoundTrip() throws {
        let q = Query(stylesInclude: ["Electronic---Breakbeat"], bpm: .init(min: 136, max: 142, halfDouble: true),
                      energy: .init(min: 6, max: 10), moodsInclude: ["dark"], key: .init(camelot: "8A", compatible: true),
                      vocal: .instrumental, limit: 50)
        let back = try JSONDecoder().decode(Query.self, from: CrateJSON.encode(q))
        #expect(back == q)
        let text = String(decoding: try CrateJSON.encode(q), as: UTF8.self)
        #expect(text.contains("\"half_double\"") && text.contains("\"styles_include\""))
    }

    @Test func otherFiles() throws {
        let state = try CrateJSON.decode(StateFile.self, from: Data(#"{"schema":1,"now_playlist_id":"N","weekly_playlist_id":"W","last_sync":"t","cursors":{}}"#.utf8), file: "state.json")
        #expect(state.nowPlaylistID == "N" && state.weeklyPlaylistID == "W" && state.lastSync == "t")

        let taxonomy = try CrateJSON.decode(TaxonomyFile.self, from: Data(#"{"schema":1,"genres":{"Funk / Soul":["Funk / Soul---Funk"]},"aliases":{"Funk / Soul":"Funk & Soul"}}"#.utf8), file: "taxonomy.json")
        #expect(taxonomy.genre(ofStyle: "Funk / Soul---Funk") == "Funk / Soul")
        #expect(taxonomy.label(forStyle: "Funk / Soul---Funk").genreDisplay == "Funk & Soul")

        let weekly = try CrateJSON.decode(WeeklyFile.self, from: Data(#"{"schema":1,"week":41,"tracks":[{"id":"w1","auto":{"bpm":120}}]}"#.utf8), file: "weekly.json")
        #expect(weekly.week == "41" && weekly.tracks.first?.auto?.bpm == 120)
        let weeklyMap = try CrateJSON.decode(WeeklyFile.self, from: Data(#"{"week":"2026-W41","tracks":{"b":{},"a":{"title":"x"}}}"#.utf8), file: "weekly.json")
        #expect(weeklyMap.tracks.map(\.id) == ["a", "b"])

        let health = try CrateJSON.decode(HealthFile.self, from: Data(#"{"schema":1,"problems":[{"level":"broken","message":"Spotify login rejected","runbook":"docs/runbook.md#spotify-token"}]}"#.utf8), file: "health.json")
        #expect(health.effectiveLevel == .broken)
        #expect(try CrateJSON.decode(HealthFile.self, from: Data("{}".utf8), file: "health.json").effectiveLevel == .healthy)
    }
}
