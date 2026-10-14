import Foundation
import Testing
@testable import CrateKit

@Suite("Widget and Siri sends")
struct PresetSenderTests {
    final class FakeNow: NowPlaylistTarget, @unchecked Sendable {
        let lock = NSLock()
        var known: [String?] = []
        var writes: [(method: String, id: String, uris: [String])] = []
        var error: Error?
        func ensureNowPlaylist(knownID: String?) async throws -> String {
            lock.withLock { known.append(knownID) }
            if let error { throw error }
            return knownID ?? "NEW"
        }
        func writePlaylistItems(method: String, playlistID: String, uris: [String]) async throws {
            lock.withLock { writes.append((method, playlistID, uris)) }
        }
    }

    private func store() -> SharedStore {
        SharedStore(directory: FileManager.default.temporaryDirectory.appendingPathComponent("crate-shared-\(UUID().uuidString)"))
    }

    private func snapshot(uris: [String], limit: Int = 100, now: String? = nil) -> PresetSnapshot {
        PresetSnapshot(nowPlaylistID: now, presets: [
            SharedPreset(id: "dark", name: "Dark 138+", inWidget: true, uris: uris, limit: limit),
            SharedPreset(id: "run", name: "Run 160", inWidget: true),
            SharedPreset(id: "it", name: "Italian summer"),
        ])
    }

    @Test func sendsASampleToNowAndRecordsSent() async throws {
        let s = store(), spotify = FakeNow()
        s.save(snapshot(uris: (0..<250).map { "spotify:track:\($0)" }, limit: 120, now: "NOW"))
        let result = await PresetSender(store: s, spotify: spotify).send(presetID: "dark", rng: SeededGenerator(seed: 1))
        let sent = try result.get()
        #expect(sent.count == 120 && sent.playlistID == "NOW")
        #expect(spotify.known == ["NOW"])
        #expect(spotify.writes.map(\.method) == ["PUT", "POST"])
        #expect(Set(spotify.writes.flatMap(\.uris)).count == 120)
        #expect(s.loadStatus()?.phase == .sent && s.loadStatus()?.count == 120)
        #expect(s.loadNowPlaylistID() == nil, "a known ID isn't re-recorded")
    }

    @Test func recordsANewlyCreatedNowPlaylist() async throws {
        let s = store(), spotify = FakeNow()
        s.save(snapshot(uris: ["spotify:track:1"]))
        _ = try await PresetSender(store: s, spotify: spotify).send(presetID: "dark").get()
        #expect(s.loadNowPlaylistID() == "NEW")
        _ = try await PresetSender(store: s, spotify: spotify).send(presetID: "dark").get()
        #expect(spotify.known == [nil, "NEW"], "the second send reuses the playlist the first one created")
    }

    @Test func failuresAreRecordedAndNothingIsWritten() async {
        let s = store(), spotify = FakeNow()
        #expect(await PresetSender(store: s, spotify: spotify).send(presetID: "dark") == .failure(.noData))
        s.save(snapshot(uris: []))
        #expect(await PresetSender(store: s, spotify: spotify).send(presetID: "nope") == .failure(.unknownPreset))
        #expect(await PresetSender(store: s, spotify: spotify).send(presetID: "dark") == .failure(.noTracks))
        #expect(s.loadStatus()?.failure == .noTracks)
        s.save(snapshot(uris: ["spotify:track:1"]))
        spotify.error = SpotifyError.unauthorized
        #expect(await PresetSender(store: s, spotify: spotify).send(presetID: "dark") == .failure(.loginExpired))
        spotify.error = SpotifyError.http(status: 500, message: nil)
        #expect(await PresetSender(store: s, spotify: spotify).send(presetID: "dark") == .failure(.network))
        #expect(spotify.writes.isEmpty)
    }

    @Test func statusVisibility() {
        let t = Date(timeIntervalSince1970: 1000)
        let sent = SendStatus(phase: .sent, presetID: "p", count: 8, at: t)
        #expect(sent.visible(at: t.addingTimeInterval(29)) != nil)
        #expect(sent.visible(at: t.addingTimeInterval(31)) == nil, "Sent holds 30 s")
        let failed = SendStatus(phase: .failed, presetID: "p", failure: .loginExpired, at: t)
        #expect(failed.visible(at: t.addingTimeInterval(86_400)) != nil, "an error stays")
        let sending = SendStatus(phase: .sending, presetID: "p", at: t)
        #expect(sending.visible(at: t.addingTimeInterval(120)) == nil, "an interrupted send goes back to idle")
    }

    @Test func presetNamesAndLabels() {
        let snap = snapshot(uris: [])
        #expect(snap.preset(named: "run 160")?.id == "run")
        #expect(snap.preset(named: "Dark 138")?.id == "dark")
        #expect(snap.preset(named: "italian")?.id == "it")
        #expect(snap.preset(named: "Run 180") == nil)
        #expect(snap.widgetPresets.map(\.id) == ["dark", "run"])
        #expect(SharedPreset(id: "a", name: "Dark 138+").shortLabel == "138+")
        #expect(SharedPreset(id: "a", name: "Sunday 90–110").shortLabel == "90")
        #expect(SharedPreset(id: "a", name: "Italian summer").shortLabel == "IS")
        #expect(SharedPreset(id: "a", name: "Warehouse").shortLabel == "WAR")
    }

    @Test func spotifyLinks() {
        let id = "4uLU6hMCjMI75M1A2tKUQC"
        #expect(SpotifyLink.trackID(in: "https://open.spotify.com/track/\(id)?si=abc123") == id)
        #expect(SpotifyLink.trackID(in: "Listen: https://open.spotify.com/intl-it/track/\(id)") == id)
        #expect(SpotifyLink.trackID(in: "spotify:track:\(id)") == id)
        #expect(SpotifyLink.trackID(in: "https://open.spotify.com/album/\(id)") == nil)
        #expect(SpotifyLink.trackID(in: "https://example.com") == nil)
    }

    @Test func links() {
        #expect(CrateLink.parse(CrateLink.now) == .now)
        #expect(CrateLink.parse(CrateLink.track("abc")) == .track("abc"))
        #expect(CrateLink.parse(CrateLink.send(preset: "dark-138")) == .send(preset: "dark-138"))
        #expect(CrateLink.parse(URL(string: "crate://spotify-callback?code=x")!) == nil)
        #expect(CrateLink.parse(URL(string: "https://open.spotify.com/track/1")!) == nil)
    }
}

@Suite("Daily similarity")
struct DailySimilarityTests {
    @Test func nearestToAnOutsideVector() throws {
        let ids = ["a", "b", "c"]
        var rows = [[Float]](repeating: [Float](repeating: 0, count: 32), count: 3)
        rows[0][0] = 1; rows[1][1] = 1; rows[2][0] = 0.6; rows[2][1] = 0.8
        let bin = rows.flatMap { $0 }.map { Float16($0) }.withUnsafeBufferPointer { Data(buffer: $0) }
        let index = try VectorIndex(bin: bin, ids: ids)
        var q = [Double](repeating: 0, count: 32); q[1] = 1
        #expect(index.nearest(vector: q, count: 2).map(\.id) == ["b", "c"])
        #expect(index.nearest(vector: q, count: 1, candidates: ["a", "c"]).map(\.id) == ["c"])
        #expect(index.nearest(vector: [1, 0], count: 1).isEmpty, "wrong dimensions")
    }
}
