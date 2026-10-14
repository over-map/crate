import CrateKit
import Foundation

/// Everything the app reads from `data/`, decoded.
struct DataSnapshot: Sendable {
    var library = LibraryFile()
    var overrides = OverridesFile()
    var presets = PresetsFile()
    var vibes = VibesFile()
    var taxonomy = TaxonomyFile()
    var weekly = WeeklyFile()
    var state = StateFile()
    var health = HealthFile()
    var vectors: VectorIndex?
    var fromCache = false
}

/// Where data comes from: the private GitHub repo (with ETag cache in the App Group container), or — Debug builds
/// only — a bundled demo copy so screens can be checked without logging in (`make ios-demo` copies it).
protocol DataSource: Sendable {
    func load() async throws -> DataSnapshot
    func updateVibes(_ mutate: @escaping @Sendable (inout VibesFile) -> Void) async throws
    func updateOverrides(_ mutate: @escaping @Sendable (inout OverridesFile) -> Void) async throws
    func updatePresets(_ mutate: @escaping @Sendable (inout PresetsFile) -> Void) async throws
}

struct GitHubDataSource: DataSource {
    let store: GitHubStore

    init(token: @escaping @Sendable () throws -> String) {
        let cache: any GitHubCache = FileGitHubCache.appGroup(AppConfig.appGroup)
            ?? FileGitHubCache(directory: URL.cachesDirectory.appending(path: "github"))
        store = GitHubStore(owner: AppConfig.repoOwner, repo: AppConfig.repoName, token: token,
                            transport: URLSessionTransport(), cache: cache)
    }

    func load() async throws -> DataSnapshot {
        var s = DataSnapshot()
        async let library = store.get(AppConfig.Path.library)
        async let overrides = optional(AppConfig.Path.overrides)
        async let presets = optional(AppConfig.Path.presets)
        async let vibes = optional(AppConfig.Path.vibes)
        async let taxonomy = optional(AppConfig.Path.taxonomy)
        async let weekly = optional(AppConfig.Path.weekly)
        async let state = optional(AppConfig.Path.state)
        async let health = optional(AppConfig.Path.health)
        async let bin = optional(AppConfig.Path.vectorsBin)
        async let idx = optional(AppConfig.Path.vectorsIdx)
        let lib = try await library
        s.library = try CrateJSON.decode(LibraryFile.self, from: lib.data, file: "library.json")
        s.fromCache = lib.fromCache
        if let f = try await overrides { s.overrides = try CrateJSON.decode(OverridesFile.self, from: f.data, file: "overrides.json") }
        if let f = try await presets { s.presets = try CrateJSON.decode(PresetsFile.self, from: f.data, file: "presets.json") }
        if let f = try await vibes { s.vibes = try CrateJSON.decode(VibesFile.self, from: f.data, file: "vibes.json") }
        if let f = try await taxonomy { s.taxonomy = try CrateJSON.decode(TaxonomyFile.self, from: f.data, file: "taxonomy.json") }
        if let f = try await weekly { s.weekly = try CrateJSON.decode(WeeklyFile.self, from: f.data, file: "weekly.json") }
        if let f = try await state { s.state = try CrateJSON.decode(StateFile.self, from: f.data, file: "state.json") }
        if let f = try await health { s.health = try CrateJSON.decode(HealthFile.self, from: f.data, file: "health.json") }
        if let b = try await bin, let i = try await idx { s.vectors = try? VectorIndex(bin: b.data, idsJSON: i.data) }
        return s
    }

    private func optional(_ path: String) async throws -> GitHubFile? {
        do { return try await store.get(path) } catch GitHubError.notFound { return nil }
    }

    func updateVibes(_ mutate: @escaping @Sendable (inout VibesFile) -> Void) async throws {
        _ = try await store.updateJSON(VibesFile.self, path: AppConfig.Path.vibes, message: "app: edit vibes", empty: VibesFile()) { mutate(&$0) }
    }
    func updateOverrides(_ mutate: @escaping @Sendable (inout OverridesFile) -> Void) async throws {
        _ = try await store.updateJSON(OverridesFile.self, path: AppConfig.Path.overrides, message: "app: edit tags", empty: OverridesFile()) { mutate(&$0) }
    }
    func updatePresets(_ mutate: @escaping @Sendable (inout PresetsFile) -> Void) async throws {
        _ = try await store.updateJSON(PresetsFile.self, path: AppConfig.Path.presets, message: "app: edit presets", empty: PresetsFile()) { mutate(&$0) }
    }
}

/// Bundled copy of `data/` (Debug only). Writes are kept in memory so editing screens can be exercised.
actor DemoDataSource: DataSource {
    static var isAvailable: Bool { Bundle.main.url(forResource: "demo-library", withExtension: "json") != nil }
    private var snapshot: DataSnapshot?

    func load() async throws -> DataSnapshot {
        if let snapshot { return snapshot }
        func file(_ name: String, _ ext: String) -> Data? {
            Bundle.main.url(forResource: "demo-" + name, withExtension: ext).flatMap { try? Data(contentsOf: $0) }
        }
        var s = DataSnapshot()
        if let d = file("library", "json") { s.library = try CrateJSON.decode(LibraryFile.self, from: d, file: "library.json") }
        if let d = file("overrides", "json") { s.overrides = try CrateJSON.decode(OverridesFile.self, from: d, file: "overrides.json") }
        if let d = file("presets", "json") { s.presets = try CrateJSON.decode(PresetsFile.self, from: d, file: "presets.json") }
        if let d = file("vibes", "json") { s.vibes = try CrateJSON.decode(VibesFile.self, from: d, file: "vibes.json") }
        if let d = file("taxonomy", "json") { s.taxonomy = try CrateJSON.decode(TaxonomyFile.self, from: d, file: "taxonomy.json") }
        if let d = file("weekly", "json") { s.weekly = try CrateJSON.decode(WeeklyFile.self, from: d, file: "weekly.json") }
        if let d = file("state", "json") { s.state = try CrateJSON.decode(StateFile.self, from: d, file: "state.json") }
        if let d = file("health", "json") { s.health = try CrateJSON.decode(HealthFile.self, from: d, file: "health.json") }
        if let b = file("vectors", "bin"), let i = file("vectors.idx", "json") { s.vectors = try? VectorIndex(bin: b, idsJSON: i) }
        snapshot = s
        return s
    }

    func updateVibes(_ mutate: @escaping @Sendable (inout VibesFile) -> Void) async throws {
        var s = try await load(); mutate(&s.vibes); snapshot = s
    }
    func updateOverrides(_ mutate: @escaping @Sendable (inout OverridesFile) -> Void) async throws {
        var s = try await load(); mutate(&s.overrides); snapshot = s
    }
    func updatePresets(_ mutate: @escaping @Sendable (inout PresetsFile) -> Void) async throws {
        var s = try await load(); mutate(&s.presets); snapshot = s
    }
}
