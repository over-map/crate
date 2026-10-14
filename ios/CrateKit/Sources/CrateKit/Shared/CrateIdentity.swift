import Foundation

/// Identifiers shared by the app, the widget and the share extension. Nothing here is secret: the Spotify client ID
/// is a public identifier (PKCE, no client secret).
public enum CrateIdentity {
    public static let spotifyClientID = "f3d3994b17844ef395d3be6f1cff401a"
    public static let spotifyRedirect = "crate://spotify-callback"
    public static let appGroupPrefix = "group.com.overmap.crate"

    /// The App Group as actually signed (re-signing tools may rename groups), else the declared one.
    /// Each extension carries its own signing profile, so this works in the widget and share extension too.
    public static var appGroup: String {
        SigningExpiry.appGroup(preferredPrefix: appGroupPrefix, fallback: appGroupPrefix)
    }

    /// A Spotify client using the shared Keychain tokens (readable by the app and its extensions).
    public static func spotifyClient(tokens: any TokenStore = KeychainTokenStore(),
                                     transport: any HTTPTransport = URLSessionTransport()) -> SpotifyClient {
        SpotifyClient(auth: SpotifyAuth(clientID: spotifyClientID, redirectURI: spotifyRedirect),
                      transport: transport, tokenStore: tokens)
    }
}

/// `crate://` links between the widget, the share extension and the app.
public enum CrateLink {
    /// Opens the Now playlist in Spotify (the widget's "Listen on Spotify").
    public static let now = URL(string: "crate://now")!
    /// Opens the app (the widget's "Open Crate" after an error).
    public static let open = URL(string: "crate://open")!

    /// Opens a track's detail in the app.
    public static func track(_ id: String) -> URL { URL(string: "crate://track/\(id)")! }

    /// Sends a preset from inside the app (fallback when the widget can't send itself).
    public static func send(preset id: String) -> URL {
        var c = URLComponents(string: "crate://send")!
        c.queryItems = [URLQueryItem(name: "preset", value: id)]
        return c.url!
    }

    public enum Destination: Equatable, Sendable {
        case now, open, track(String), send(preset: String)
    }

    /// Parses a `crate://` URL. The Spotify login callback is not a destination (it returns `nil`).
    public static func parse(_ url: URL) -> Destination? {
        guard url.scheme == "crate" else { return nil }
        let parts = url.path.split(separator: "/").map(String.init)
        switch url.host {
        case "now": return .now
        case "open": return .open
        case "track": return parts.first.map(Destination.track)
        case "send":
            let id = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "preset" }?.value
            return id.map { .send(preset: $0) }
        default: return nil
        }
    }
}

/// Spotify track links as the share sheet passes them.
public enum SpotifyLink {
    /// The track ID in `https://open.spotify.com/track/<id>?si=…` (with or without an `intl-xx/` segment) or
    /// `spotify:track:<id>`, anywhere in `text`. `nil` for albums, playlists, artists and anything else.
    public static func trackID(in text: String) -> String? {
        let patterns = [#"open\.spotify\.com/(?:intl-[A-Za-z-]+/)?track/([A-Za-z0-9]{22})"#, #"spotify:track:([A-Za-z0-9]{22})"#]
        for p in patterns {
            guard let re = try? NSRegularExpression(pattern: p),
                  let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
                  let r = Range(m.range(at: 1), in: text) else { continue }
            return String(text[r])
        }
        return nil
    }
}
