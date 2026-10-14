import Foundation

/// The user's corrections for one track (`overrides.json → tracks[id]`). Each set field wins over `auto`.
public struct TrackOverride: Codable, Sendable, Equatable {
    public var bpm: Double?
    public var key: String?
    public var energy: Int?
    public var vocal: Bool?
    public var moodsAdd: [String]?
    public var moodsRemove: [String]?
    /// The first element becomes the track's primary style.
    public var stylesAdd: [String]?
    public var stylesRemove: [String]?
    public var genresAdd: [String]?
    public var genresRemove: [String]?
    /// Style family chosen by the user; wins over the pipeline's consensus.
    public var family: String?
    public var editedAt: String?

    public init(
        bpm: Double? = nil, key: String? = nil, energy: Int? = nil, vocal: Bool? = nil,
        moodsAdd: [String]? = nil, moodsRemove: [String]? = nil,
        stylesAdd: [String]? = nil, stylesRemove: [String]? = nil,
        genresAdd: [String]? = nil, genresRemove: [String]? = nil, family: String? = nil, editedAt: String? = nil
    ) {
        self.bpm = bpm; self.key = key; self.energy = energy; self.vocal = vocal
        self.moodsAdd = moodsAdd; self.moodsRemove = moodsRemove
        self.stylesAdd = stylesAdd; self.stylesRemove = stylesRemove
        self.genresAdd = genresAdd; self.genresRemove = genresRemove; self.family = family; self.editedAt = editedAt
    }

    /// True when the override changes nothing (and can be dropped from the file).
    public var isEmpty: Bool {
        bpm == nil && key == nil && energy == nil && vocal == nil
            && (moodsAdd ?? []).isEmpty && (moodsRemove ?? []).isEmpty
            && (stylesAdd ?? []).isEmpty && (stylesRemove ?? []).isEmpty
            && (genresAdd ?? []).isEmpty && (genresRemove ?? []).isEmpty && (family ?? "").isEmpty
    }

    enum CodingKeys: String, CodingKey {
        case bpm, key, energy, vocal, family
        case moodsAdd = "moods_add", moodsRemove = "moods_remove"
        case stylesAdd = "styles_add", stylesRemove = "styles_remove"
        case genresAdd = "genres_add", genresRemove = "genres_remove"
        case editedAt = "edited_at"
    }
}

/// `data/overrides.json`. Written by the app only.
public struct OverridesFile: Codable, Sendable, Equatable {
    public var schema: Int
    /// Overrides keyed by Spotify track ID.
    public var tracks: [String: TrackOverride]

    public init(schema: Int = CrateJSON.supportedSchema, tracks: [String: TrackOverride] = [:]) {
        self.schema = schema; self.tracks = tracks
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schema = try c.decodeIfPresent(Int.self, forKey: .schema) ?? CrateJSON.supportedSchema
        tracks = try c.decodeIfPresent([String: TrackOverride].self, forKey: .tracks) ?? [:]
    }

    enum CodingKeys: String, CodingKey { case schema, tracks }
}
