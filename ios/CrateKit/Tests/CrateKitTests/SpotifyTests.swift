import Foundation
import Testing
@testable import CrateKit

@Suite("Spotify auth and client")
struct SpotifyTests {
    let auth = SpotifyAuth(clientID: "client123")

    @Test func pkceRFC7636AppendixB() {
        let pkce = PKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        #expect(pkce.challenge == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        #expect(pkce.method == "S256")
    }

    @Test func generatedVerifier() {
        let pkce = PKCE.generate()
        #expect(pkce.verifier.count == 64)
        #expect(pkce.verifier.allSatisfy { PKCE.unreserved.contains($0) })
        #expect(PKCE.generate(length: 10).verifier.count == 43)
        var a = SeededGenerator(seed: 1), b = SeededGenerator(seed: 1)
        #expect(PKCE.generate(using: &a) == PKCE.generate(using: &b))
    }

    @Test func authorizeURL() throws {
        let pkce = PKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        let url = auth.authorizeURL(pkce: pkce, state: "xyz")
        let items = Dictionary(uniqueKeysWithValues: (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") })
        #expect(url.host == "accounts.spotify.com" && url.path == "/authorize")
        #expect(items["client_id"] == "client123")
        #expect(items["response_type"] == "code")
        #expect(items["redirect_uri"] == "crate://spotify-callback")
        #expect(items["code_challenge_method"] == "S256")
        #expect(items["code_challenge"] == pkce.challenge)
        #expect(items["state"] == "xyz")
        #expect(items["scope"]?.split(separator: " ").contains("user-follow-modify") == false,
                "only the pipeline's one-time migration needed it; the app asks for the least it needs")
        #expect(items["scope"]?.split(separator: " ").contains("playlist-modify-private") == true)
    }

    @Test func callbackParsing() throws {
        #expect(try auth.code(fromCallback: URL(string: "crate://spotify-callback?code=abc&state=s")!, expectedState: "s") == "abc")
        #expect(throws: SpotifyAuthError.stateMismatch) {
            try auth.code(fromCallback: URL(string: "crate://spotify-callback?code=abc&state=evil")!, expectedState: "s")
        }
        #expect(throws: SpotifyAuthError.authorizationFailed("access_denied")) {
            try auth.code(fromCallback: URL(string: "crate://spotify-callback?error=access_denied&state=s")!, expectedState: "s")
        }
    }

    @Test func tokenRequests() {
        let exchange = auth.tokenExchangeRequest(code: "c0de", verifier: "v")
        #expect(exchange.method == "POST" && exchange.url.absoluteString == "https://accounts.spotify.com/api/token")
        #expect(exchange.header("content-type") == "application/x-www-form-urlencoded")
        let body = String(decoding: exchange.body ?? Data(), as: UTF8.self)
        #expect(body == "grant_type=authorization_code&code=c0de&redirect_uri=crate%3A%2F%2Fspotify-callback&client_id=client123&code_verifier=v")
        #expect(exchange.header("Authorization") == nil, "PKCE sends no client secret")

        let refresh = String(decoding: auth.refreshRequest(refreshToken: "r/1").body ?? Data(), as: UTF8.self)
        #expect(refresh == "grant_type=refresh_token&refresh_token=r%2F1&client_id=client123")
    }

    @Test func tokenResponseKeepsRefreshTokenWhenNotRotated() throws {
        let now = Date(timeIntervalSince1970: 1000)
        let tokens = try auth.parseTokenResponse(.json(#"{"access_token":"A","expires_in":3600,"scope":"s"}"#),
                                                 previousRefreshToken: "R", now: now)
        #expect(tokens == SpotifyTokens(accessToken: "A", refreshToken: "R", expiresAt: Date(timeIntervalSince1970: 4600), scope: "s"))
        #expect(throws: SpotifyAuthError.tokenRequestFailed(status: 400, error: "invalid_grant")) {
            try auth.parseTokenResponse(.json(400, #"{"error":"invalid_grant"}"#))
        }
    }

    private func client(_ transport: FakeTransport, expired: Bool = false) -> SpotifyClient {
        let store = InMemoryTokenStore(SpotifyTokens(accessToken: "OLD", refreshToken: "R",
                                                     expiresAt: expired ? .distantPast : .distantFuture))
        return SpotifyClient(auth: auth, transport: transport, tokenStore: store)
    }

    @Test func libraryContainsBatchesBy40WithURIsQuery() async throws {
        let uris = (0..<45).map { "spotify:track:\($0)" }
        let transport = FakeTransport { request, _ in
            let n = request.queryItems.first { $0.name == "uris" }?.value?.split(separator: ",").count ?? 0
            return .json("[" + Array(repeating: "true", count: n).joined(separator: ",") + "]")
        }
        let flags = try await client(transport).libraryContains(uris: uris)
        #expect(flags.count == 45)
        let requests = transport.recorded
        #expect(requests.count == 2)
        #expect(requests[0].url.path == "/v1/me/library/contains")
        #expect(requests[0].queryItems.first?.value?.split(separator: ",").count == 40)
        #expect(requests[0].header("Authorization") == "Bearer OLD")
    }

    @Test func saveToLibraryUsesPUTWithURIs() async throws {
        let transport = FakeTransport(responses: [.json("")])
        try await client(transport).saveToLibrary(uris: ["spotify:track:a"])
        let request = try #require(transport.recorded.first)
        #expect(request.method == "PUT" && request.url.path == "/v1/me/library")
        #expect(request.queryItems.first { $0.name == "uris" }?.value == "spotify:track:a")
    }

    @Test func removeFromLibraryUsesDELETEWithURIs() async throws {
        let transport = FakeTransport(responses: [.json("")])
        try await client(transport).removeFromLibrary(uris: ["spotify:track:a"])
        let request = try #require(transport.recorded.first)
        #expect(request.method == "DELETE" && request.url.path == "/v1/me/library")
        #expect(request.queryItems.first { $0.name == "uris" }?.value == "spotify:track:a")
    }

    @Test func expiredTokenIsRefreshedFirst() async throws {
        let transport = FakeTransport { request, _ in
            request.url.host == "accounts.spotify.com"
                ? .json(#"{"access_token":"NEW","expires_in":3600}"#)
                : .json(#"{"id":"me","display_name":"Filippo"}"#)
        }
        let user = try await client(transport, expired: true).me()
        #expect(user.id == "me")
        #expect(transport.recorded.count == 2)
        #expect(transport.recorded[1].header("Authorization") == "Bearer NEW")
    }

    @Test func ensureNowFindsOwnedPlaylistOrCreatesOne() async throws {
        let existing = FakeTransport { request, _ in
            switch request.url.path {
            case "/v1/me": return .json(#"{"id":"me"}"#)
            default: return .json(#"{"items":[{"id":"x","name":"Now","owner":{"id":"someone"}},{"id":"N1","name":"Now","owner":{"id":"me"}}],"next":null}"#)
            }
        }
        #expect(try await client(existing).ensureNowPlaylist(knownID: nil) == "N1")
        #expect(try await client(existing).ensureNowPlaylist(knownID: "K") == "K")

        let empty = FakeTransport { request, _ in
            switch (request.method, request.url.path) {
            case ("GET", "/v1/me"): return .json(#"{"id":"me"}"#)
            case ("GET", _): return .json(#"{"items":[],"next":null}"#)
            default: return .json(201, #"{"id":"NEW","name":"Now","owner":{"id":"me"}}"#)
            }
        }
        #expect(try await client(empty).ensureNowPlaylist(knownID: nil) == "NEW")
        let create = try #require(empty.recorded.last)
        #expect(create.method == "POST" && create.url.path == "/v1/me/playlists")
        let body = jsonObject(create.body)
        #expect(body["name"] as? String == "Now" && body["public"] as? Bool == false)
    }

    @Test func playlistItemsReadsItemField() async throws {
        let transport = FakeTransport(responses: [.json(#"{"items":[{"item":{"uri":"spotify:track:1"}},{"item":null}],"next":null}"#)])
        #expect(try await client(transport).playlistItemURIs(playlistID: "N") == ["spotify:track:1"])
        #expect(transport.recorded.first?.url.path == "/v1/playlists/N/items")
    }

    @Test func rateLimit() async {
        let transport = FakeTransport(responses: [.json(429, "", headers: ["Retry-After": "7"])])
        await #expect(throws: SpotifyError.rateLimited(retryAfter: 7)) { try await client(transport).me() }
    }

    @Test func playlistFetchesPublicFlag() async throws {
        let transport = FakeTransport(responses: [
            .json(#"{"id":"D1","name":"Crate Daily","public":true,"uri":"spotify:playlist:D1","owner":{"id":"me"}}"#),
            .json(#"{"id":"N1","name":"Now","public":false}"#),
            .json(#"{"id":"C1","name":"Shared","public":null}"#),
        ])
        let c = client(transport)
        let daily = try await c.playlist(id: "D1")
        #expect(daily.isPublic == true && daily.name == "Crate Daily" && daily.owner?.id == "me")
        #expect(try await c.playlist(id: "N1").isPublic == false)
        #expect(try await c.playlist(id: "C1").isPublic == nil)
        let request = try #require(transport.recorded.first)
        #expect(request.method == "GET" && request.url.path == "/v1/playlists/D1")
        #expect(request.queryItems.first { $0.name == "fields" }?.value == "id,name,public,uri,owner")
    }

    @Test func playlistListsDecodeWithoutPublic() throws {
        let page = try JSONDecoder().decode(SpotifyPage<SpotifyPlaylist>.self,
                                            from: Data(#"{"items":[{"id":"x","name":"Now"}],"next":null}"#.utf8))
        #expect(page.items.first?.isPublic == nil)
    }
}
