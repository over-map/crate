#if canImport(Security)
import Foundation
import Security

/// Errors from the Keychain.
public enum KeychainError: Error, Equatable, Sendable {
    case status(OSStatus)
}

/// One generic-password Keychain item holding raw bytes.
///
/// Pass an `accessGroup` to share the item with the widget and share extension (spec §7.6); leave it `nil`
/// when keychain sharing is unavailable under the Personal Team.
public struct KeychainItem: Sendable {
    public let service: String
    public let account: String
    public let accessGroup: String?

    public init(service: String, account: String, accessGroup: String? = nil) {
        self.service = service; self.account = account; self.accessGroup = accessGroup
    }

    private var baseQuery: [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let accessGroup { query[kSecAttrAccessGroup as String] = accessGroup }
        return query
    }

    /// The stored bytes, or `nil` if there is no item.
    public func read() throws -> Data? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError.status(status) }
        return result as? Data
    }

    /// Creates or replaces the item. Readable after first unlock, so widgets can use it in the background.
    public func write(_ data: Data) throws {
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        let status = SecItemUpdate(baseQuery as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            let add = baseQuery.merging(attributes) { $1 }
            let addStatus = SecItemAdd(add as CFDictionary, nil)
            guard addStatus == errSecSuccess else { throw KeychainError.status(addStatus) }
        } else if status != errSecSuccess {
            throw KeychainError.status(status)
        }
    }

    /// Deletes the item (no error if it does not exist).
    public func delete() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeychainError.status(status) }
    }
}

/// A ``TokenStore`` backed by the Keychain.
public struct KeychainTokenStore: TokenStore {
    public let item: KeychainItem

    public init(item: KeychainItem = KeychainItem(service: "crate.spotify", account: "tokens")) { self.item = item }

    public func load() throws -> SpotifyTokens? {
        guard let data = try item.read() else { return nil }
        return try JSONDecoder().decode(SpotifyTokens.self, from: data)
    }

    public func save(_ tokens: SpotifyTokens) throws { try item.write(JSONEncoder().encode(tokens)) }

    public func clear() throws { try item.delete() }
}
#endif
