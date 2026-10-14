import CryptoKit
import Foundation

/// PKCE (RFC 7636) code verifier and S256 challenge.
public struct PKCE: Sendable, Equatable {
    /// Characters allowed in a verifier ("unreserved" in RFC 3986).
    static let unreserved = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    public let verifier: String
    public let challenge: String
    /// Always `S256`.
    public let method = "S256"

    /// Uses an existing verifier (e.g. the RFC test vector).
    public init(verifier: String) {
        self.verifier = verifier
        self.challenge = PKCE.challenge(for: verifier)
    }

    /// Generates a random verifier of `length` characters (43–128, default 64).
    public static func generate(length: Int = 64) -> PKCE {
        var rng = SystemRandomNumberGenerator()
        return generate(length: length, using: &rng)
    }

    /// Generates a verifier with the given generator (tests can pass a seeded one).
    public static func generate<R: RandomNumberGenerator>(length: Int = 64, using rng: inout R) -> PKCE {
        let n = min(128, max(43, length))
        let verifier = String((0..<n).map { _ in unreserved.randomElement(using: &rng)! })
        return PKCE(verifier: verifier)
    }

    /// `BASE64URL(SHA256(ASCII(verifier)))` without padding.
    public static func challenge(for verifier: String) -> String {
        Data(SHA256.hash(data: Data(verifier.utf8))).base64URLEncodedString()
    }

    /// A random URL-safe `state` value for the authorize request.
    public static func randomState() -> String {
        var rng = SystemRandomNumberGenerator()
        return String((0..<32).map { _ in unreserved.randomElement(using: &rng)! })
    }
}
