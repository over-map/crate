import Foundation

/// Errors from ``GitHubStore``.
public enum GitHubError: Error, Equatable, Sendable {
    case notFound(path: String)
    /// 401/403: the fine-grained PAT is missing, expired or lacks contents access.
    case unauthorized(status: Int)
    /// 409/422 after every retry.
    case conflict(path: String)
    case http(status: Int)
    case malformedResponse
}

/// A file read from the repository.
public struct GitHubFile: Sendable, Equatable {
    public let path: String
    public let data: Data
    public let sha: String
    /// True when served from the cache (304, or offline fallback).
    public let fromCache: Bool
}

/// Reads and writes `data/*.json` through the GitHub Contents API (spec §7.3).
public actor GitHubStore {
    /// Attempts made by ``update(path:message:transform:)``: the first try plus three retries.
    public static let maxAttempts = 4

    public let owner: String
    public let repo: String
    public let branch: String
    private let token: @Sendable () throws -> String
    private let transport: any HTTPTransport
    private let cache: any GitHubCache
    private let apiBase: URL

    /// - Parameter token: Returns the fine-grained PAT (read from the Keychain). Never logged.
    public init(owner: String, repo: String, branch: String = "main",
                token: @escaping @Sendable () throws -> String,
                transport: any HTTPTransport, cache: any GitHubCache,
                apiBase: URL = URL(string: "https://api.github.com")!) {
        self.owner = owner; self.repo = repo; self.branch = branch
        self.token = token; self.transport = transport; self.cache = cache; self.apiBase = apiBase
    }

    // MARK: Read

    /// Fetches `path`, sending the cached ETag. A 304 returns the cached copy.
    /// - Parameters:
    ///   - useCache: `false` ignores the ETag (used after a write conflict).
    ///   - offlineFallback: On a transport error, return the cached copy instead of throwing.
    public func get(_ path: String, useCache: Bool = true, offlineFallback: Bool = true) async throws -> GitHubFile {
        let cached = cache.load(path: path)
        var request = try baseRequest("GET", contentsURL(path, ref: true))
        if useCache, let etag = cached?.etag { request.headers["If-None-Match"] = etag }
        let response: HTTPResponse
        do {
            response = try await transport.send(request)
        } catch {
            if offlineFallback, let cached { return GitHubFile(path: path, data: cached.data, sha: cached.sha, fromCache: true) }
            throw error
        }
        if response.status == 304, let cached {
            return GitHubFile(path: path, data: cached.data, sha: cached.sha, fromCache: true)
        }
        try check(response, path: path)
        let body = try decodeContents(response.body)
        let data: Data
        if let content = body.content, body.encoding == "base64", !content.isEmpty {
            data = try decodeBase64(content)
        } else {
            data = try await blob(sha: body.sha) // files over 1 MB come back without content
        }
        cache.store(CachedFile(data: data, sha: body.sha, etag: response.header("ETag")), path: path)
        return GitHubFile(path: path, data: data, sha: body.sha, fromCache: false)
    }

    /// Fetches and decodes a Crate data file, applying the schema guard.
    public func getJSON<T: Decodable>(_ type: T.Type, path: String) async throws -> T {
        let file = try await get(path)
        return try CrateJSON.decode(T.self, from: file.data, file: (path as NSString).lastPathComponent)
    }

    // MARK: Write

    /// Writes `data` to `path`. Pass the current `sha` to update (omit to create). Returns the new sha.
    @discardableResult
    public func put(_ path: String, data: Data, sha: String?, message: String) async throws -> String {
        var body: [String: JSONValue] = [
            "message": .string(message),
            "content": .string(data.base64EncodedString()),
            "branch": .string(branch),
        ]
        if let sha { body["sha"] = .string(sha) }
        var request = try baseRequest("PUT", contentsURL(path, ref: false))
        request.headers["Content-Type"] = "application/json"
        request.body = try JSONEncoder().encode(JSONValue.object(body))
        let response = try await transport.send(request)
        if response.status == 409 || response.status == 422 { throw GitHubError.conflict(path: path) }
        try check(response, path: path)
        struct PutBody: Decodable { struct Content: Decodable { let sha: String }; let content: Content }
        guard let newSHA = try? JSONDecoder().decode(PutBody.self, from: response.body).content.sha else {
            throw GitHubError.malformedResponse
        }
        cache.store(CachedFile(data: data, sha: newSHA, etag: nil), path: path)
        return newSHA
    }

    /// Read-modify-write. `transform` receives the current bytes (`nil` if the file doesn't exist) and returns
    /// the new bytes. On 409/422 the file is fetched again and `transform` re-applied, up to three retries.
    @discardableResult
    public func update(path: String, message: String,
                       transform: @Sendable (Data?) throws -> Data) async throws -> String {
        var lastError: Error = GitHubError.conflict(path: path)
        for attempt in 0..<GitHubStore.maxAttempts {
            let current: GitHubFile?
            do {
                current = try await get(path, useCache: attempt == 0, offlineFallback: false)
            } catch GitHubError.notFound {
                current = nil
            }
            let newData = try transform(current?.data)
            do {
                return try await put(path, data: newData, sha: current?.sha, message: message)
            } catch GitHubError.conflict {
                lastError = GitHubError.conflict(path: path)
                continue
            }
        }
        throw lastError
    }

    /// Typed read-modify-write of a Crate JSON file, e.g. overrides.json. `empty` is used when the file is missing.
    @discardableResult
    public func updateJSON<T: Codable & Sendable>(_ type: T.Type, path: String, message: String, empty: T,
                                                  mutate: @Sendable (inout T) throws -> Void) async throws -> String {
        let name = (path as NSString).lastPathComponent
        return try await update(path: path, message: message) { data in
            var value = try data.map { try CrateJSON.decode(T.self, from: $0, file: name) } ?? empty
            try mutate(&value)
            return try CrateJSON.encode(value)
        }
    }

    // MARK: Plumbing

    private struct ContentsBody: Decodable {
        let sha: String
        let content: String?
        let encoding: String?
    }

    private func decodeContents(_ data: Data) throws -> ContentsBody {
        guard let body = try? JSONDecoder().decode(ContentsBody.self, from: data) else { throw GitHubError.malformedResponse }
        return body
    }

    private func decodeBase64(_ text: String) throws -> Data {
        guard let data = Data(base64Encoded: text, options: .ignoreUnknownCharacters) else { throw GitHubError.malformedResponse }
        return data
    }

    /// `GET /repos/{owner}/{repo}/git/blobs/{sha}` (up to 100 MB).
    private func blob(sha: String) async throws -> Data {
        let url = apiBase.appendingPathComponent("repos/\(owner)/\(repo)/git/blobs/\(sha)")
        let response = try await transport.send(try baseRequest("GET", url))
        try check(response, path: "blob \(sha)")
        let body = try decodeContents(response.body)
        guard let content = body.content else { throw GitHubError.malformedResponse }
        return try decodeBase64(content)
    }

    private func contentsURL(_ path: String, ref: Bool) -> URL {
        var components = URLComponents(
            url: apiBase.appendingPathComponent("repos/\(owner)/\(repo)/contents/\(path)"),
            resolvingAgainstBaseURL: false)!
        if ref { components.queryItems = [URLQueryItem(name: "ref", value: branch)] }
        return components.url!
    }

    private func baseRequest(_ method: String, _ url: URL) throws -> HTTPRequest {
        HTTPRequest(method: method, url: url, headers: [
            "Authorization": "Bearer \(try token())",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        ])
    }

    private func check(_ response: HTTPResponse, path: String) throws {
        switch response.status {
        case 200..<300: return
        case 401, 403: throw GitHubError.unauthorized(status: response.status)
        case 404: throw GitHubError.notFound(path: path)
        default: throw GitHubError.http(status: response.status)
        }
    }
}
