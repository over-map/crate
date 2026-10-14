import Foundation

/// `data/library.json`: every Liked Song with its automatic tags. Written by Actions only.
public struct LibraryFile: Codable, Sendable, Equatable {
    public var schema: Int
    public var generatedAt: String?
    /// Tracks keyed by Spotify track ID.
    public var tracks: [String: Track]

    public init(schema: Int = CrateJSON.supportedSchema, generatedAt: String? = nil, tracks: [String: Track] = [:]) {
        self.schema = schema; self.generatedAt = generatedAt; self.tracks = tracks
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schema = try c.decodeIfPresent(Int.self, forKey: .schema) ?? CrateJSON.supportedSchema
        generatedAt = try c.decodeIfPresent(String.self, forKey: .generatedAt)
        tracks = try c.decodeIfPresent([String: Track].self, forKey: .tracks) ?? [:]
    }

    enum CodingKeys: String, CodingKey {
        case schema, tracks
        case generatedAt = "generated_at"
    }
}
