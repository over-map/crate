import Foundation

/// `GET /me`.
public struct SpotifyUser: Decodable, Sendable, Equatable {
    public let id: String
    public let displayName: String?

    enum CodingKeys: String, CodingKey {
        case id
        case displayName = "display_name"
    }
}

/// A playlist summary from `GET /me/playlists`, `POST /me/playlists` or `GET /playlists/{id}`.
public struct SpotifyPlaylist: Decodable, Sendable, Equatable {
    public struct Owner: Decodable, Sendable, Equatable { public let id: String }

    public let id: String
    public let name: String
    public let owner: Owner?
    public let uri: String?
    /// Spotify's `public` flag; `nil` when not returned (Spotify sends `null` for collaborative playlists).
    public let isPublic: Bool?

    public init(id: String, name: String, owner: Owner? = nil, uri: String? = nil, isPublic: Bool? = nil) {
        self.id = id; self.name = name; self.owner = owner; self.uri = uri; self.isPublic = isPublic
    }

    enum CodingKeys: String, CodingKey {
        case id, name, owner, uri
        case isPublic = "public"
    }

    /// `spotify:playlist:<id>` deep link.
    public var deepLink: URL { URL(string: uri ?? "spotify:playlist:\(id)")! }
}

/// A paging object.
public struct SpotifyPage<Item: Decodable & Sendable>: Decodable, Sendable {
    public let items: [Item]
    public let next: String?
    public let total: Int?
}

/// One row of `GET /playlists/{id}/items` (Feb 2026: the track is under `item`, not `track`).
public struct SpotifyPlaylistItem: Decodable, Sendable {
    public struct Item: Decodable, Sendable { public let uri: String?; public let id: String? }
    public let item: Item?
}

/// Errors from the Web API.
public enum SpotifyError: Error, Equatable, Sendable {
    /// No tokens stored: the user must log in.
    case notLoggedIn
    /// 401 after a refresh: "Spotify login expired".
    case unauthorized
    /// 429; `retryAfter` in seconds when given.
    case rateLimited(retryAfter: Int?)
    case http(status: Int, message: String?)
    case malformedResponse
}
