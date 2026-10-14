import AuthenticationServices
import CrateKit
import Foundation
import UIKit

/// Spotify login (Authorization Code + PKCE via ASWebAuthenticationSession), tokens in the Keychain, and the
/// two writes the app makes: replacing Now (through NowWriter's allow-list) and liking a track.
@MainActor
final class SpotifyService: NSObject, ASWebAuthenticationPresentationContextProviding {
    let auth = SpotifyAuth(clientID: AppConfig.spotifyClientID, redirectURI: AppConfig.spotifyRedirect)
    let tokens: any TokenStore
    let client: SpotifyClient

    init(tokens: any TokenStore) {
        self.tokens = tokens
        client = SpotifyClient(auth: auth, transport: URLSessionTransport(), tokenStore: tokens)
    }

    var isConnected: Bool { (try? tokens.load()) != nil }

    func connect() async throws {
        let pkce = PKCE.generate()
        let state = PKCE.randomState()
        let url = auth.authorizeURL(pkce: pkce, state: state)
        let callback: URL = try await withCheckedThrowingContinuation { cont in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "crate") { url, error in
                if let url { cont.resume(returning: url) } else { cont.resume(throwing: error ?? URLError(.cancelled)) }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = false
            session.start()
        }
        let code = try auth.code(fromCallback: callback, expectedState: state)
        let t = try await auth.exchange(code: code, verifier: pkce.verifier, transport: URLSessionTransport())
        try tokens.save(t)
    }

    func disconnect() { try? tokens.clear() }

    /// Replaces Now with `uris`. Returns the playlist ID (created if needed).
    func sendToNow(uris: [String], knownID: String?, progress: @escaping @Sendable (Int, Int) -> Void) async throws -> String {
        let id = try await client.ensureNowPlaylist(knownID: knownID)
        try await NowWriter(writer: client, allowedPlaylistIDs: [id]).replace(playlistID: id, with: uris, progress: progress)
        return id
    }

    func like(uri: String) async throws { try await client.saveToLibrary(uris: [uri]) }

    /// Removes a track from Liked Songs. Tonight's sync then drops it from the library (its tag edits are kept).
    func unlike(uri: String) async throws { try await client.removeFromLibrary(uris: [uri]) }

    func contains(uris: [String]) async throws -> [Bool] { try await client.libraryContains(uris: uris) }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first ?? ASPresentationAnchor()
        }
    }
}
