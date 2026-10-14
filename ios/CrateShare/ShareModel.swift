import CrateKit
import CrateUI
import Foundation
import Observation

/// The share sheet's data (design 6s, 3m, 3n). Reads the library the app cached in the App Group; writes go to
/// Spotify only (Like, or "More like this → Now" through ``PresetSender/replaceNow(with:snapshot:)``).
@MainActor
@Observable
final class ShareModel {
    struct TrackCard: Equatable {
        var id: String
        var title: String
        var artist: String
        var coverURL: URL?
        var family: Family
        var styleName: String?
        var track: Track
        var confidence: ConfidenceSummary?
        var vibes: [(id: String, name: String, icon: String)]

        static func == (a: TrackCard, b: TrackCard) -> Bool { a.id == b.id && a.vibes.map(\.id) == b.vibes.map(\.id) }
    }

    enum Phase: Equatable {
        case loading
        case inLibrary(TrackCard)
        /// `liked`: already in Liked Songs, waiting for tonight's sync.
        case notInLibrary(id: String, liked: Bool)
        case notATrack
        /// The app hasn't cached the library yet (never opened, or App Group unavailable).
        case noData(id: String)
    }

    enum Action: Equatable { case idle, working, done(String), failed(String) }

    var phase: Phase = .loading
    var action: Action = .idle

    private var library = LibraryFile()
    private var vectors: VectorIndex?

    func load(text: String?) async {
        guard let text, let id = SpotifyLink.trackID(in: text) else { phase = .notATrack; return }
        guard let cache = FileGitHubCache.appGroup(CrateIdentity.appGroup),
              let lib = cache.load(path: "data/library.json"),
              let library = try? CrateJSON.decode(LibraryFile.self, from: lib.data, file: "library.json") else {
            phase = .noData(id: id)
            return
        }
        self.library = library
        guard let track = library.tracks[id] else {
            phase = .notInLibrary(id: id, liked: false)
            let liked = (try? await CrateIdentity.spotifyClient().libraryContains(uris: ["spotify:track:\(id)"]))?.first ?? false
            if liked { phase = .notInLibrary(id: id, liked: true) }
            return
        }

        func file<T: Decodable>(_ type: T.Type, _ name: String) -> T? {
            cache.load(path: "data/\(name)").flatMap { try? CrateJSON.decode(T.self, from: $0.data, file: name) }
        }
        let overrides = file(OverridesFile.self, "overrides.json") ?? OverridesFile()
        let taxonomy = file(TaxonomyFile.self, "taxonomy.json") ?? TaxonomyFile()
        let vibes = file(VibesFile.self, "vibes.json") ?? VibesFile()
        if let bin = cache.load(path: "data/vectors.bin"), let idx = cache.load(path: "data/vectors.idx.json") {
            vectors = try? VectorIndex(bin: bin.data, idsJSON: idx.data)
        }

        let override = overrides.tracks[id]
        let effective = EffectiveTags(track: track, override: override)
        let family = effective.family.flatMap(Family.init(rawValue:)) ?? .none
        var memberOf: [(id: String, name: String, icon: String)] = []
        if let vectors {
            let index = VibeIndex(index: vectors, library: library, overrides: overrides)
            for (vid, vibe) in vibes.ordered where index.members(vibe).contains(id) {
                memberOf.append((vid, vibe.name, vibe.icon ?? VibeSymbols.defaults[vid] ?? "circle"))
            }
        }
        phase = .inLibrary(TrackCard(
            id: id, title: track.title ?? "Unknown title", artist: track.artists?.joined(separator: ", ") ?? "",
            coverURL: track.cover.flatMap(URL.init(string:)), family: family,
            styleName: effective.primaryStyle.map { taxonomy.label(forStyle: $0).styleDisplay },
            track: track, confidence: track.consensus.flatMap { ConfidenceSummary($0, override: override?.family.flatMap(Family.init(rawValue:))) },
            vibes: memberOf))
    }

    var canSendMoreLikeThis: Bool {
        guard case let .inLibrary(card) = phase else { return false }
        return vectors?.contains(card.id) == true
    }

    /// The 100 library tracks that sound closest, shuffled, replace Now (same as the app's track detail).
    func sendMoreLikeThis() async {
        guard case let .inLibrary(card) = phase, let vectors, let store = SharedStore.appGroup() else { return }
        action = .working
        var rng = SystemRandomNumberGenerator()
        let ids = (try? vectors.moreLikeThis(seed: card.id, count: 100, candidates: Set(library.tracks.keys), rng: &rng)) ?? []
        let uris = ids.compactMap { id in library.tracks[id].map { $0.spotifyURI(id: id) } }
        guard !uris.isEmpty else { action = .failed("No sound data for this track yet."); return }
        do {
            _ = try await PresetSender(store: store, spotify: CrateIdentity.spotifyClient()).replaceNow(with: uris)
            action = .done("Sent \(uris.count) tracks to Now")
        } catch {
            action = .failed(SendFailure(error).explanation(preset: nil))
        }
    }

    func like(id: String) async {
        action = .working
        do {
            try await CrateIdentity.spotifyClient().saveToLibrary(uris: ["spotify:track:\(id)"])
            phase = .notInLibrary(id: id, liked: true)
            action = .idle
        } catch {
            action = .failed(SendFailure(error) == .loginExpired
                ? "Spotify login expired. Open Crate to sign in again."
                : "Couldn't reach Spotify. Try again in a moment.")
        }
    }
}
