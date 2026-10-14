import Foundation

/// Errors from Spotify authorisation.
public enum SpotifyAuthError: Error, Equatable, Sendable {
    /// The callback's `state` did not match the one sent.
    case stateMismatch
    /// The user denied access, or Spotify returned an error code.
    case authorizationFailed(String)
    /// The callback carried no `code`.
    case missingCode
    /// The token endpoint rejected the request (e.g. a revoked refresh token): "Spotify login expired".
    case tokenRequestFailed(status: Int, error: String?)
    case malformedTokenResponse
}

/// Builds the Authorization Code + PKCE requests (spec §7.2). Has no state; the caller keeps the verifier.
public struct SpotifyAuth: Sendable {
    /// Scopes the app requests.
    public static let defaultScopes = [
        "user-library-read", "user-library-modify",
        "playlist-read-private", "playlist-read-collaborative",
        "playlist-modify-private", "playlist-modify-public",
    ]

    public static let authorizeEndpoint = URL(string: "https://accounts.spotify.com/authorize")!
    public static let tokenEndpoint = URL(string: "https://accounts.spotify.com/api/token")!

    public let clientID: String
    public let redirectURI: String
    public let scopes: [String]

    public init(clientID: String, redirectURI: String = "crate://spotify-callback", scopes: [String] = SpotifyAuth.defaultScopes) {
        self.clientID = clientID; self.redirectURI = redirectURI; self.scopes = scopes
    }

    /// The URL to open in `ASWebAuthenticationSession`.
    public func authorizeURL(pkce: PKCE, state: String) -> URL {
        var components = URLComponents(url: SpotifyAuth.authorizeEndpoint, resolvingAgainstBaseURL: false)!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "code_challenge_method", value: pkce.method),
            URLQueryItem(name: "code_challenge", value: pkce.challenge),
            URLQueryItem(name: "scope", value: scopes.joined(separator: " ")),
            URLQueryItem(name: "state", value: state),
        ]
        return components.url!
    }

    /// Extracts the authorisation code from the redirect URL, checking `state`.
    public func code(fromCallback url: URL, expectedState: String) throws -> String {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
        guard value("state") == expectedState else { throw SpotifyAuthError.stateMismatch }
        if let error = value("error") { throw SpotifyAuthError.authorizationFailed(error) }
        guard let code = value("code"), !code.isEmpty else { throw SpotifyAuthError.missingCode }
        return code
    }

    /// `POST /api/token` exchanging `code` for tokens.
    public func tokenExchangeRequest(code: String, verifier: String) -> HTTPRequest {
        tokenRequest([
            ("grant_type", "authorization_code"),
            ("code", code),
            ("redirect_uri", redirectURI),
            ("client_id", clientID),
            ("code_verifier", verifier),
        ])
    }

    /// `POST /api/token` refreshing the access token.
    public func refreshRequest(refreshToken: String) -> HTTPRequest {
        tokenRequest([
            ("grant_type", "refresh_token"),
            ("refresh_token", refreshToken),
            ("client_id", clientID),
        ])
    }

    private func tokenRequest(_ form: [(String, String)]) -> HTTPRequest {
        HTTPRequest(
            method: "POST", url: SpotifyAuth.tokenEndpoint,
            headers: ["Content-Type": "application/x-www-form-urlencoded"],
            body: formEncode(form)
        )
    }

    /// Parses a token response. When Spotify does not rotate the refresh token, `previousRefreshToken` is kept.
    public func parseTokenResponse(_ response: HTTPResponse, previousRefreshToken: String? = nil,
                                   now: Date = Date()) throws -> SpotifyTokens {
        struct Body: Decodable {
            let access_token: String?
            let refresh_token: String?
            let expires_in: Double?
            let scope: String?
            let error: String?
        }
        let body = try? JSONDecoder().decode(Body.self, from: response.body)
        guard response.isSuccess else {
            throw SpotifyAuthError.tokenRequestFailed(status: response.status, error: body?.error)
        }
        guard let body, let access = body.access_token else { throw SpotifyAuthError.malformedTokenResponse }
        return SpotifyTokens(
            accessToken: access,
            refreshToken: body.refresh_token ?? previousRefreshToken,
            expiresAt: now.addingTimeInterval(body.expires_in ?? 3600),
            scope: body.scope
        )
    }

    /// Exchanges `code` for tokens over `transport`.
    public func exchange(code: String, verifier: String, transport: some HTTPTransport) async throws -> SpotifyTokens {
        let response = try await transport.send(tokenExchangeRequest(code: code, verifier: verifier))
        return try parseTokenResponse(response)
    }

    /// Refreshes `tokens` over `transport`.
    public func refresh(_ tokens: SpotifyTokens, transport: some HTTPTransport) async throws -> SpotifyTokens {
        guard let refreshToken = tokens.refreshToken else {
            throw SpotifyAuthError.tokenRequestFailed(status: 400, error: "no_refresh_token")
        }
        let response = try await transport.send(refreshRequest(refreshToken: refreshToken))
        return try parseTokenResponse(response, previousRefreshToken: refreshToken)
    }
}
