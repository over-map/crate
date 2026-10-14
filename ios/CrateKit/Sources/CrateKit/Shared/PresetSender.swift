import Foundation

/// What a widget or Siri send needs from Spotify: find or create Now, and write its items.
public protocol NowPlaylistTarget: SpotifyPlaylistItemsWriting {
    func ensureNowPlaylist(knownID: String?) async throws -> String
}

extension SpotifyClient: NowPlaylistTarget {}

/// A finished send.
public struct SendResult: Sendable, Equatable {
    public var preset: SharedPreset
    public var count: Int
    public var playlistID: String
}

/// Sends a preset to Now without the app: the widget button, Siri, Shortcuts and the Action Button
/// (App Intent `SendPresetToNow`). Writes go through ``NowWriter``, so only Now can be changed.
public struct PresetSender: Sendable {
    public let store: SharedStore
    public let spotify: any NowPlaylistTarget

    public init(store: SharedStore, spotify: any NowPlaylistTarget) {
        self.store = store; self.spotify = spotify
    }

    /// Samples `limit` of the preset's tracks, shuffles them and replaces Now. Records Sending, then Sent or the
    /// failure, in the status file. `onStatus` runs after each status write (the widget reloads its timelines).
    public func send<R: RandomNumberGenerator & Sendable>(
        presetID: String, rng: R = SystemRandomNumberGenerator(), now: @Sendable () -> Date = { Date() },
        onStatus: @Sendable () -> Void = {}
    ) async -> Result<SendResult, SendFailure> {
        var rng = rng
        guard let snapshot = store.loadSnapshot() else { return .failure(.noData) }
        guard let preset = snapshot.preset(id: presetID) else { return .failure(.unknownPreset) }
        guard !preset.uris.isEmpty else {
            record(SendStatus(phase: .failed, presetID: presetID, failure: .noTracks, at: now()), onStatus)
            return .failure(.noTracks)
        }
        record(SendStatus(phase: .sending, presetID: presetID, at: now()), onStatus)
        let uris = QueryEngine.sampleAndShuffle(preset.uris, limit: preset.limit, rng: &rng)
        do {
            let id = try await replaceNow(with: uris, snapshot: snapshot)
            record(SendStatus(phase: .sent, presetID: presetID, count: uris.count, at: now()), onStatus)
            return .success(SendResult(preset: preset, count: uris.count, playlistID: id))
        } catch {
            let failure = SendFailure(error)
            record(SendStatus(phase: .failed, presetID: presetID, failure: failure, at: now()), onStatus)
            return .failure(failure)
        }
    }

    /// Replaces Now with `uris` (already sampled and ordered) and returns its ID, creating Now if nobody knows
    /// it yet. The share sheet's "More like this → Now" uses this directly.
    public func replaceNow(with uris: [String], snapshot: PresetSnapshot? = nil) async throws -> String {
        // The app's ID (presets.json) wins; the one a widget recorded only fills in until the app adopts it.
        let known = (snapshot ?? store.loadSnapshot())?.nowPlaylistID ?? store.loadNowPlaylistID()
        let id = try await spotify.ensureNowPlaylist(knownID: known)
        try await NowWriter(writer: spotify, allowedPlaylistIDs: [id]).replace(playlistID: id, with: uris)
        if id != known { store.saveNowPlaylistID(id) }
        return id
    }

    private func record(_ status: SendStatus, _ onStatus: @Sendable () -> Void) {
        store.save(status)
        onStatus()
    }
}
