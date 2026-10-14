import Foundation

/// Spotify Web API client for the endpoints available in Development Mode (spec §2).
///
/// Playlist item writes are deliberately not exposed here; they go through ``NowWriter`` and its allow-list.
public actor SpotifyClient {
    public static let apiBase = URL(string: "https://api.spotify.com/v1")!
    /// Max URIs per `/me/library` call.
    public static let libraryBatchSize = 40
    /// Name of the playlist Crate overwrites.
    public static let nowPlaylistName = "Now"

    private let auth: SpotifyAuth
    private let transport: any HTTPTransport
    private let tokenStore: any TokenStore
    private let now: @Sendable () -> Date
    private var cachedUserID: String?

    public init(auth: SpotifyAuth, transport: any HTTPTransport, tokenStore: any TokenStore,
                now: @escaping @Sendable () -> Date = { Date() }) {
        self.auth = auth; self.transport = transport; self.tokenStore = tokenStore; self.now = now
    }

    // MARK: Endpoints

    /// `GET /me`.
    public func me() async throws -> SpotifyUser {
        let user: SpotifyUser = try await decode(send("GET", "/me"))
        cachedUserID = user.id
        return user
    }

    /// `GET /me/library/contains?uris=…`, batched by 40. Returns one flag per URI, in order.
    public func libraryContains(uris: [String]) async throws -> [Bool] {
        var result: [Bool] = []
        for batch in uris.chunked(SpotifyClient.libraryBatchSize) {
            let flags: [Bool] = try await decode(send("GET", "/me/library/contains", query: ["uris": batch.joined(separator: ",")]))
            guard flags.count == batch.count else { throw SpotifyError.malformedResponse }
            result += flags
        }
        return result
    }

    /// `PUT /me/library?uris=…` (Like), batched by 40.
    public func saveToLibrary(uris: [String]) async throws {
        for batch in uris.chunked(SpotifyClient.libraryBatchSize) {
            _ = try await send("PUT", "/me/library", query: ["uris": batch.joined(separator: ",")])
        }
    }

    /// `DELETE /me/library?uris=…` (Unlike), batched by 40.
    public func removeFromLibrary(uris: [String]) async throws {
        for batch in uris.chunked(SpotifyClient.libraryBatchSize) {
            _ = try await send("DELETE", "/me/library", query: ["uris": batch.joined(separator: ",")])
        }
    }

    /// All of the user's playlists (`GET /me/playlists`, every page).
    public func myPlaylists() async throws -> [SpotifyPlaylist] {
        var all: [SpotifyPlaylist] = []
        var offset = 0
        while true {
            let page: SpotifyPage<SpotifyPlaylist> = try await decode(
                send("GET", "/me/playlists", query: ["limit": "50", "offset": String(offset)]))
            all += page.items
            if page.next == nil || page.items.isEmpty { return all }
            offset += page.items.count
        }
    }

    /// One playlist's summary (`GET /playlists/{id}?fields=id,name,public,uri,owner`), e.g. to check `isPublic`.
    public func playlist(id: String) async throws -> SpotifyPlaylist {
        try await decode(send("GET", "/playlists/\(id)", query: ["fields": "id,name,public,uri,owner"]))
    }

    /// `POST /me/playlists`, private by default.
    public func createPlaylist(name: String, isPublic: Bool = false, description: String? = nil) async throws -> SpotifyPlaylist {
        var body: [String: JSONValue] = ["name": .string(name), "public": .bool(isPublic)]
        if let description { body["description"] = .string(description) }
        return try await decode(send("POST", "/me/playlists", json: .object(body)))
    }

    /// The Now playlist ID (spec §7.4 step 1): `knownID` if given, else a playlist named "Now" owned by the
    /// user, else a newly created private one. The caller records the result in presets.json.
    public func ensureNowPlaylist(knownID: String?) async throws -> String {
        if let knownID, !knownID.isEmpty { return knownID }
        let userID = try await currentUserID()
        if let existing = try await myPlaylists().first(where: {
            $0.name == SpotifyClient.nowPlaylistName && $0.owner?.id == userID
        }) {
            return existing.id
        }
        return try await createPlaylist(name: SpotifyClient.nowPlaylistName,
                                        description: "Written by Crate. Overwritten on every send.").id
    }

    /// URIs in an owned playlist (`GET /playlists/{id}/items`, response `items[].item`).
    public func playlistItemURIs(playlistID: String) async throws -> [String] {
        var uris: [String] = []
        var offset = 0
        while true {
            let page: SpotifyPage<SpotifyPlaylistItem> = try await decode(
                send("GET", "/playlists/\(playlistID)/items", query: ["limit": "50", "offset": String(offset)]))
            uris += page.items.compactMap { $0.item?.uri }
            if page.next == nil || page.items.isEmpty { return uris }
            offset += page.items.count
        }
    }

    // MARK: Plumbing

    private func currentUserID() async throws -> String {
        if let cachedUserID { return cachedUserID }
        return try await me().id
    }

    /// Sends an authorised request. Refreshes the token when it is about to expire, and once more on 401.
    func send(_ method: String, _ path: String, query: [String: String] = [:], json: JSONValue? = nil) async throws -> HTTPResponse {
        var request = try makeRequest(method, path, query: query, json: json)
        request.headers["Authorization"] = "Bearer \(try await accessToken(forceRefresh: false))"
        var response = try await transport.send(request)
        if response.status == 401 {
            request.headers["Authorization"] = "Bearer \(try await accessToken(forceRefresh: true))"
            response = try await transport.send(request)
            if response.status == 401 { throw SpotifyError.unauthorized }
        }
        if response.status == 429 {
            throw SpotifyError.rateLimited(retryAfter: response.header("Retry-After").flatMap { Int($0) })
        }
        guard response.isSuccess else {
            throw SpotifyError.http(status: response.status, message: String(data: response.body, encoding: .utf8))
        }
        return response
    }

    private func makeRequest(_ method: String, _ path: String, query: [String: String], json: JSONValue?) throws -> HTTPRequest {
        var components = URLComponents(url: SpotifyClient.apiBase.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty {
            components.queryItems = query.keys.sorted().map { URLQueryItem(name: $0, value: query[$0]) }
        }
        var request = HTTPRequest(method: method, url: components.url!)
        if let json {
            request.body = try JSONEncoder().encode(json)
            request.headers["Content-Type"] = "application/json"
        }
        return request
    }

    private func accessToken(forceRefresh: Bool) async throws -> String {
        guard var tokens = try tokenStore.load() else { throw SpotifyError.notLoggedIn }
        if forceRefresh || tokens.isExpired(now: now()) {
            do {
                tokens = try await auth.refresh(tokens, transport: transport)
            } catch SpotifyAuthError.tokenRequestFailed {
                throw SpotifyError.unauthorized
            }
            try tokenStore.save(tokens)
        }
        return tokens.accessToken
    }

    private func decode<T: Decodable>(_ response: HTTPResponse) throws -> T {
        do { return try JSONDecoder().decode(T.self, from: response.body) } catch { throw SpotifyError.malformedResponse }
    }
}

extension SpotifyClient: SpotifyPlaylistItemsWriting {
    /// Used by ``NowWriter`` only.
    public func writePlaylistItems(method: String, playlistID: String, uris: [String]) async throws {
        _ = try await send(method, "/playlists/\(playlistID)/items", json: .object(["uris": .array(uris.map(JSONValue.string))]))
    }
}

/// A minimal JSON value for request bodies.
public enum JSONValue: Encodable, Sendable, Equatable {
    case string(String), bool(Bool), number(Double), array([JSONValue]), object([String: JSONValue]), null

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case let .string(v): try c.encode(v)
        case let .bool(v): try c.encode(v)
        case let .number(v): try c.encode(v)
        case let .array(v): try c.encode(v)
        case let .object(v): try c.encode(v)
        case .null: try c.encodeNil()
        }
    }
}

extension Array {
    /// Splits into consecutive chunks of at most `size` elements.
    func chunked(_ size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map { Array(self[$0 ..< Swift.min($0 + size, count)]) }
    }
}
