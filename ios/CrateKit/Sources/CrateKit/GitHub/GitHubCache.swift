import Foundation

/// A cached copy of one repository file.
public struct CachedFile: Codable, Sendable, Equatable {
    public var data: Data
    /// Blob SHA, needed to write the file back.
    public var sha: String
    public var etag: String?

    public init(data: Data, sha: String, etag: String?) {
        self.data = data; self.sha = sha; self.etag = etag
    }
}

/// Where ``GitHubStore`` keeps file copies for ETag requests and offline use.
public protocol GitHubCache: Sendable {
    func load(path: String) -> CachedFile?
    func store(_ file: CachedFile, path: String)
}

/// A ``GitHubCache`` in memory (tests, previews).
public final class InMemoryGitHubCache: GitHubCache, @unchecked Sendable {
    private let lock = NSLock()
    private var files: [String: CachedFile] = [:]

    public init() {}

    public func load(path: String) -> CachedFile? { lock.withLock { files[path] } }
    public func store(_ file: CachedFile, path: String) { lock.withLock { files[path] = file } }
}

/// A ``GitHubCache`` on disk. Pass the App Group container (or a subfolder of it) so the widget and share
/// extension see the same data; see ``FileGitHubCache/appGroup(_:)``.
///
/// Each file is stored as `<name>` (the bytes) plus `<name>.meta.json` (sha and ETag), with `/` in the
/// repository path replaced by `__`.
public struct FileGitHubCache: GitHubCache {
    public let directory: URL

    public init(directory: URL) { self.directory = directory }

    /// A cache in `GitHubCache/` inside the App Group container, or `nil` if the group is unavailable.
    public static func appGroup(_ identifier: String) -> FileGitHubCache? {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier) else {
            return nil
        }
        return FileGitHubCache(directory: container.appendingPathComponent("GitHubCache", isDirectory: true))
    }

    private struct Meta: Codable { var sha: String; var etag: String? }

    private func urls(_ path: String) -> (data: URL, meta: URL) {
        let name = path.replacingOccurrences(of: "/", with: "__")
        return (directory.appendingPathComponent(name), directory.appendingPathComponent(name + ".meta.json"))
    }

    public func load(path: String) -> CachedFile? {
        let (dataURL, metaURL) = urls(path)
        guard let data = try? Data(contentsOf: dataURL),
              let metaData = try? Data(contentsOf: metaURL),
              let meta = try? JSONDecoder().decode(Meta.self, from: metaData) else { return nil }
        return CachedFile(data: data, sha: meta.sha, etag: meta.etag)
    }

    public func store(_ file: CachedFile, path: String) {
        let (dataURL, metaURL) = urls(path)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try file.data.write(to: dataURL, options: .atomic)
            try JSONEncoder().encode(Meta(sha: file.sha, etag: file.etag)).write(to: metaURL, options: .atomic)
        } catch {
            // A failed cache write only costs a re-download next time.
        }
    }
}
