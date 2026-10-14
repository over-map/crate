import Foundation

/// `data/state.json`: playlist IDs and sync bookkeeping. Written by Actions only; the app reads it.
public struct StateFile: Codable, Sendable, Equatable {
    public var schema: Int
    public var nowPlaylistID: String?
    public var weeklyPlaylistID: String?
    /// ISO-8601 time of the last successful sync.
    public var lastSync: String?

    public init(schema: Int = CrateJSON.supportedSchema, nowPlaylistID: String? = nil,
                weeklyPlaylistID: String? = nil, lastSync: String? = nil) {
        self.schema = schema; self.nowPlaylistID = nowPlaylistID
        self.weeklyPlaylistID = weeklyPlaylistID; self.lastSync = lastSync
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schema = try c.decodeIfPresent(Int.self, forKey: .schema) ?? CrateJSON.supportedSchema
        nowPlaylistID = try c.decodeIfPresent(String.self, forKey: .nowPlaylistID)
        weeklyPlaylistID = try c.decodeIfPresent(String.self, forKey: .weeklyPlaylistID)
        lastSync = try c.decodeIfPresent(String.self, forKey: .lastSync)
    }

    enum CodingKeys: String, CodingKey {
        case schema
        case nowPlaylistID = "now_playlist_id"
        case weeklyPlaylistID = "weekly_playlist_id"
        case lastSync = "last_sync"
    }
}
