import CrateKit
import Foundation

extension AppModel {
    /// True when the track has a sound embedding, so "More like this" can work.
    func canSendMoreLikeThis(trackID: String) -> Bool {
        snapshot.vectors?.contains(trackID) == true
    }

    /// "More like this → Now": the 100 library tracks that sound closest to `trackID`, shuffled, replace Now.
    /// Mirrors `send()`: progress, success and error go through `sendState`.
    func sendMoreLikeThis(trackID: String) async {
        guard let vectors = snapshot.vectors else {
            sendState = .error("No sound data for this track yet.")
            return
        }
        var rng = SystemRandomNumberGenerator()
        let ids = (try? vectors.moreLikeThis(seed: trackID, count: 100, candidates: Set(index.ids), rng: &rng)) ?? []
        let uris = ids.compactMap { id in index.tracks[id].map { $0.spotifyURI(id: id) } }
        guard !uris.isEmpty else {
            sendState = .error("No sound data for this track yet.")
            return
        }
        lastSentCovers = ids.compactMap { index.tracks[$0]?.cover.flatMap(URL.init(string:)) }.prefix(5).map { $0 }
        sendState = .sending(done: 0, total: uris.count)
        if isDemo {
            for i in stride(from: 0, through: uris.count, by: max(1, uris.count / 4)) {
                sendState = .sending(done: i, total: uris.count)
                try? await Task.sleep(for: .milliseconds(250))
            }
            sendState = .success(uris.count)
            return
        }
        do {
            let id = try await spotify.sendToNow(uris: uris, knownID: nowPlaylistID) { done, total in
                Task { @MainActor in self.sendState = .sending(done: done, total: total) }
            }
            if id != nowPlaylistID {
                nowPlaylistID = id
                try? await source?.updatePresets { $0.nowPlaylistID = id }
            }
            sendState = .success(uris.count)
        } catch {
            sendState = .error("Couldn't reach Spotify. Nothing in Now was changed.")
        }
    }
}
