import Foundation

/// A saved query with its identity on the Build screen and in widgets.
public struct Preset: Codable, Sendable, Equatable, Identifiable {
    public var id: String
    public var name: String
    /// SF Symbol name for the preset tile.
    public var icon: String
    /// Preset colour name (`ink, red, amber, lime, teal, blue, violet, pink`).
    public var color: String
    public var query: Query
    /// Shown in the medium widget (max 4 are used).
    public var inWidget: Bool

    public init(id: String, name: String, icon: String = "bookmark", color: String = "ink",
                query: Query = Query(), inWidget: Bool = false) {
        self.id = id; self.name = name; self.icon = icon; self.color = color
        self.query = query; self.inWidget = inWidget
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        icon = try c.decodeIfPresent(String.self, forKey: .icon) ?? "bookmark"
        color = try c.decodeIfPresent(String.self, forKey: .color) ?? "ink"
        query = try c.decodeIfPresent(Query.self, forKey: .query) ?? Query()
        inWidget = try c.decodeIfPresent(Bool.self, forKey: .inWidget) ?? false
    }

    enum CodingKeys: String, CodingKey {
        case id, name, icon, color, query
        case inWidget = "in_widget"
    }
}

/// `data/presets.json`. Written by the app only.
///
/// Spec §5.4 describes a bare array of presets; spec §7.4 also stores the Now playlist ID in this file's
/// metadata. Both forms decode. Encoding always uses the object form
/// `{"schema": 1, "now_playlist_id": "…", "presets": […]}`.
public struct PresetsFile: Codable, Sendable, Equatable {
    public var schema: Int
    /// The Now playlist ID when the app created or found it (the app may not write state.json).
    public var nowPlaylistID: String?
    public var presets: [Preset]

    public init(schema: Int = CrateJSON.supportedSchema, nowPlaylistID: String? = nil, presets: [Preset] = []) {
        self.schema = schema; self.nowPlaylistID = nowPlaylistID; self.presets = presets
    }

    public init(from decoder: Decoder) throws {
        if let array = try? decoder.singleValueContainer().decode([Preset].self) {
            self = PresetsFile(presets: array)
            return
        }
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schema = try c.decodeIfPresent(Int.self, forKey: .schema) ?? CrateJSON.supportedSchema
        nowPlaylistID = try c.decodeIfPresent(String.self, forKey: .nowPlaylistID)
        presets = try c.decodeIfPresent([Preset].self, forKey: .presets) ?? []
    }

    /// Presets shown in the medium widget: the first four with `in_widget`.
    public var widgetPresets: [Preset] { Array(presets.filter(\.inWidget).prefix(4)) }

    enum CodingKeys: String, CodingKey {
        case schema, presets
        case nowPlaylistID = "now_playlist_id"
    }
}
