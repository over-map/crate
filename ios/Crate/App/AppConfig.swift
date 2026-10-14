import CrateKit
import Foundation

/// Fixed configuration. Nothing here is secret: the Spotify client ID is a public identifier (PKCE, no secret),
/// and the GitHub token lives in the Keychain, entered at onboarding.
enum AppConfig {
    static let repoOwner = "over-map"
    static let repoName = "crate-data"  // private data repo; the code is public in over-map/crate
    static let spotifyClientID = CrateIdentity.spotifyClientID
    static let spotifyRedirect = CrateIdentity.spotifyRedirect
    /// The App Group as actually signed (re-signing tools may rename groups), else the declared one.
    static let appGroup = CrateIdentity.appGroup
    /// Keychain access group shared with the widget and share extension (prefixed by the team ID at runtime).
    static let keychainGroupSuffix = "com.overmap.crate.shared"

    /// Data files the app reads (paths in the repo).
    enum Path {
        static let library = "data/library.json"
        static let overrides = "data/overrides.json"
        static let presets = "data/presets.json"
        static let vibes = "data/vibes.json"
        static let taxonomy = "data/taxonomy.json"
        static let weekly = "data/weekly.json"
        static let state = "data/state.json"
        static let health = "data/health.json"
        static let vectorsBin = "data/vectors.bin"
        static let vectorsIdx = "data/vectors.idx.json"
    }
}
