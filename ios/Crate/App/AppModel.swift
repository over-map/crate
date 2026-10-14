import CrateKit
import Foundation
import Observation
import SwiftUI

/// The app's single source of state. Views read it from the environment.
@MainActor
@Observable
final class AppModel {
    enum Phase: Equatable { case onboarding, loading, ready, failed(String) }
    enum SendState: Equatable { case idle, sending(done: Int, total: Int), success(Int), error(String) }

    var phase: Phase = .onboarding
    var snapshot = DataSnapshot()
    var index = LibraryIndex.empty
    var build = BuildState() { didSet { recount() } }
    var matchCount = 0
    var sendState: SendState = .idle
    var nowPlaylistID: String?
    var isDemo = false
    var lastSync: Date?
    /// A track opened from a `crate://track/<id>` link (share extension); MainTabs shows its detail.
    var linkedTrackID: String?
    /// A `crate://send?preset=<id>` link waiting for the user's confirmation.
    var linkedSendPresetID: String?
    /// Covers of the last tracks sent to Now, for the Send button's lift-off delight.
    var lastSentCovers: [URL] = []
    /// A `crate://` link that arrived before the library was loaded; handled after the first reload.
    var pendingLink: URL?

    private(set) var source: (any DataSource)?
    let spotify: SpotifyService
    private let githubToken = KeychainItem(service: "crate.github", account: "pat")

    init() {
        spotify = SpotifyService(tokens: KeychainTokenStore())
        RefreshNotifications.shared.install()
        if DebugLaunch.isDemo, DemoDataSource.isAvailable {
            isDemo = true
            source = DemoDataSource()
            phase = .loading
            return
        }
        if hasGitHubToken && spotify.isConnected {
            phase = .loading
            source = GitHubDataSource(token: { [githubToken] in try Self.readToken(githubToken) })
        }
    }

    // MARK: Setup

    var hasGitHubToken: Bool { (try? githubToken.read()).flatMap { $0 } != nil }

    func saveGitHubToken(_ token: String) throws {
        try githubToken.write(Data(token.trimmingCharacters(in: .whitespacesAndNewlines).utf8))
        source = GitHubDataSource(token: { [githubToken] in try Self.readToken(githubToken) })
    }

    nonisolated private static func readToken(_ item: KeychainItem) throws -> String {
        guard let d = try item.read(), let s = String(data: d, encoding: .utf8) else { throw GitHubError.unauthorized(status: 401) }
        return s
    }

    func useDemoData() {
        isDemo = true
        source = DemoDataSource()
        phase = .loading
    }

    /// Validates the token by loading the library ("3,104 tracks found" in onboarding).
    func validateGitHub() async throws -> Int {
        guard let source else { throw GitHubError.unauthorized(status: 401) }
        let s = try await source.load()
        apply(s)
        return s.library.tracks.count
    }

    // MARK: Data

    func reload() async {
        guard let source else { phase = .onboarding; return }
        do {
            apply(try await source.load())
            phase = .ready
            if let link = pendingLink { pendingLink = nil; open(link) }
        } catch {
            if snapshot.library.tracks.isEmpty { phase = .failed(error.localizedDescription) }
        }
    }

    private func apply(_ s: DataSnapshot) {
        snapshot = s
        index = LibraryIndex(snapshot: s)
        nowPlaylistID = s.presets.nowPlaylistID ?? s.state.nowPlaylistID
        lastSync = Date()
        recount()
        adoptSharedNowPlaylist()
        publishShared()
    }

    var allStyles: [String: Set<String>] {
        Dictionary(uniqueKeysWithValues: index.families.map { ($0.family, Set($0.styles.map(\.style))) })
    }

    var query: Query { build.query(allStyles: allStyles) }

    private func recount() {
        matchCount = build.isEmpty ? index.tracks.count : index.count(query)
        if case .success = sendState { sendState = .idle }
    }

    /// Count if `change` were applied (for "count if added" on chips and relaxation suggestions).
    func count(applying change: (inout BuildState) -> Void) -> Int {
        var b = build
        change(&b)
        return index.count(b.query(allStyles: allStyles))
    }

    // MARK: Send

    var sendCount: Int { min(matchCount, build.limit) }

    func send() async {
        let q = query
        let ids = index.matchingIDs(q)
        var rng = SystemRandomNumberGenerator()
        let picked = QueryEngine.sampleAndShuffle(ids, limit: q.limit, rng: &rng)
        let uris = picked.compactMap { id in index.tracks[id].map { $0.spotifyURI(id: id) } }
        guard !uris.isEmpty else { return }
        lastSentCovers = picked.compactMap { index.tracks[$0]?.cover.flatMap(URL.init(string:)) }.prefix(5).map { $0 }
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

    var nowURL: URL? { nowPlaylistID.map { URL(string: "spotify:playlist:\($0)")! } }

    // MARK: Edits (vibes, overrides, presets) — optimistic, then reload the index

    func editVibes(_ mutate: @escaping @Sendable (inout VibesFile) -> Void) async {
        mutate(&snapshot.vibes)
        index = LibraryIndex(snapshot: snapshot)
        recount()
        publishShared()
        try? await source?.updateVibes(mutate)
    }

    func editOverride(trackID: String, _ mutate: @escaping @Sendable (inout TrackOverride) -> Void) async {
        let m: @Sendable (inout OverridesFile) -> Void = { file in
            var o = file.tracks[trackID] ?? TrackOverride()
            mutate(&o)
            o.editedAt = ISO8601DateFormatter().string(from: Date())
            file.tracks[trackID] = o.isEmpty ? nil : o
        }
        m(&snapshot.overrides)
        index = LibraryIndex(snapshot: snapshot)
        recount()
        publishShared()
        try? await source?.updateOverrides(m)
    }

    func editPresets(_ mutate: @escaping @Sendable (inout PresetsFile) -> Void) async {
        mutate(&snapshot.presets)
        publishShared()
        try? await source?.updatePresets(mutate)
    }
}
