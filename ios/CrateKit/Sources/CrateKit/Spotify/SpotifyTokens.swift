import Foundation

/// Spotify OAuth tokens. Never log these.
public struct SpotifyTokens: Codable, Sendable, Equatable {
    public var accessToken: String
    public var refreshToken: String?
    public var expiresAt: Date
    public var scope: String?

    public init(accessToken: String, refreshToken: String?, expiresAt: Date, scope: String? = nil) {
        self.accessToken = accessToken; self.refreshToken = refreshToken
        self.expiresAt = expiresAt; self.scope = scope
    }

    /// True if the access token expires within `margin` seconds of `now`.
    public func isExpired(now: Date = Date(), margin: TimeInterval = 60) -> Bool {
        expiresAt.timeIntervalSince(now) <= margin
    }
}

/// Stores Spotify tokens. ``KeychainTokenStore`` in the app, ``InMemoryTokenStore`` in tests.
public protocol TokenStore: Sendable {
    func load() throws -> SpotifyTokens?
    func save(_ tokens: SpotifyTokens) throws
    func clear() throws
}

/// A ``TokenStore`` that keeps tokens in memory only.
public final class InMemoryTokenStore: TokenStore, @unchecked Sendable {
    private let lock = NSLock()
    private var tokens: SpotifyTokens?

    public init(_ tokens: SpotifyTokens? = nil) { self.tokens = tokens }

    public func load() throws -> SpotifyTokens? { lock.withLock { tokens } }
    public func save(_ tokens: SpotifyTokens) throws { lock.withLock { self.tokens = tokens } }
    public func clear() throws { lock.withLock { tokens = nil } }
}
