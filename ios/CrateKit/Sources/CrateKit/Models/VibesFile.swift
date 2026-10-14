import Foundation

/// A user-named mood defined by example (`data/vibes.json → vibes[id]`). See `docs/query-semantics.md#vibes`.
public struct Vibe: Codable, Sendable, Equatable {
    /// Share of the tracks with a vector that a vibe keeps when `share` is missing.
    public static let defaultShare = 0.2

    public var name: String
    /// SF Symbol name (user-picked; nil = the default for the vibe ID, else "tag").
    public var icon: String?
    /// Seed track IDs.
    public var seeds: [String]
    /// Artist names; every track by one of them (case-insensitive) is also a seed.
    public var artists: [String]
    /// Track IDs never in the vibe (they are not replaced).
    public var exclude: [String]
    /// Community tags; every track with any of them (normalised) is also a seed.
    public var seedTags: [String]
    /// Fraction of the tracks with a vector kept by similarity, 0–1.
    public var share: Double

    public init(name: String, icon: String? = nil, seeds: [String] = [], artists: [String] = [], seedTags: [String] = [],
                exclude: [String] = [], share: Double = Vibe.defaultShare) {
        self.name = name; self.icon = icon; self.seeds = seeds; self.artists = artists; self.seedTags = seedTags
        self.exclude = exclude; self.share = share
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        icon = try c.decodeIfPresent(String.self, forKey: .icon)
        seeds = try c.decodeIfPresent([String].self, forKey: .seeds) ?? []
        artists = try c.decodeIfPresent([String].self, forKey: .artists) ?? []
        seedTags = try c.decodeIfPresent([String].self, forKey: .seedTags) ?? []
        exclude = try c.decodeIfPresent([String].self, forKey: .exclude) ?? []
        share = try c.decodeIfPresent(Double.self, forKey: .share) ?? Vibe.defaultShare
    }

    enum CodingKeys: String, CodingKey {
        case name, icon, seeds, artists, exclude, share
        case seedTags = "seed_tags"
    }
}

/// `data/vibes.json`. Written by the app only (read-modify-write through ``GitHubStore/updateJSON(_:path:message:empty:mutate:)``).
public struct VibesFile: Codable, Sendable, Equatable {
    public var schema: Int
    /// Vibes keyed by ID (the ID is what `vibes_include` lists).
    public var vibes: [String: Vibe]
    /// Vibe IDs in Build order (set by the vibes manager).
    public var order: [String]

    public init(schema: Int = CrateJSON.supportedSchema, vibes: [String: Vibe] = [:], order: [String] = []) {
        self.schema = schema; self.vibes = vibes; self.order = order
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schema = try c.decodeIfPresent(Int.self, forKey: .schema) ?? CrateJSON.supportedSchema
        vibes = try c.decodeIfPresent([String: Vibe].self, forKey: .vibes) ?? [:]
        order = try c.decodeIfPresent([String].self, forKey: .order) ?? []
    }

    /// Vibes in Build order: those listed in ``order`` first (unknown and repeated IDs skipped), then the rest
    /// sorted by name (ties by ID).
    public var ordered: [(id: String, vibe: Vibe)] {
        var seen = Set<String>()
        var out: [(id: String, vibe: Vibe)] = []
        for id in order where !seen.contains(id) {
            if let vibe = vibes[id] { out.append((id, vibe)); seen.insert(id) }
        }
        let rest = vibes.filter { !seen.contains($0.key) }.sorted { a, b in
            a.value.name != b.value.name ? a.value.name < b.value.name : a.key < b.key
        }
        return out + rest.map { (id: $0.key, vibe: $0.value) }
    }

    enum CodingKeys: String, CodingKey { case schema, vibes, order }
}
