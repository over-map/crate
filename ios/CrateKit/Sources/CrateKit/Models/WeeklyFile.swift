import Foundation

/// One suggestion in the Weekly Mix, with its tags so the app can filter the mix.
public struct WeeklyTrack: Codable, Sendable, Equatable, Identifiable {
    /// Why the track was picked.
    public enum Kind: String, Codable, Sendable {
        /// Similar to the user's own tracks in a family.
        case taste
        /// From community charts for a family, without a seed from the library.
        case discovery
    }

    /// Spotify track ID.
    public var id: String
    /// Pipeline candidate key (used for feedback).
    public var key: String?
    public var uri: String?
    public var isrc: String?
    public var title: String?
    public var artists: [String]?
    public var durationMS: Int?
    public var cover: String?
    /// Ranking score (similarity blended with the feedback model).
    public var score: Double?
    public var auto: AutoTags?
    /// `taste` or `discovery`; `nil` when missing or unknown.
    public var kind: Kind?
    /// Style family the track was picked for.
    public var family: String?
    /// One-line "why this track?".
    public var reason: String?
    /// Sound-similarity vector (same 32-d space as `vectors.bin`), for "close to … which you liked".
    public var vector: [Double]?

    public init(id: String, key: String? = nil, uri: String? = nil, isrc: String? = nil, title: String? = nil,
                artists: [String]? = nil, durationMS: Int? = nil, cover: String? = nil,
                score: Double? = nil, auto: AutoTags? = nil,
                kind: Kind? = nil, family: String? = nil, reason: String? = nil) {
        self.id = id; self.key = key; self.uri = uri; self.isrc = isrc; self.title = title; self.artists = artists
        self.durationMS = durationMS; self.cover = cover; self.score = score; self.auto = auto
        self.kind = kind; self.family = family; self.reason = reason
    }

    /// Every field is optional; a missing `id` decodes as `""` (the dictionary form supplies it from the key).
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        key = try c.decodeIfPresent(String.self, forKey: .key)
        uri = try c.decodeIfPresent(String.self, forKey: .uri)
        isrc = try c.decodeIfPresent(String.self, forKey: .isrc)
        title = try c.decodeIfPresent(String.self, forKey: .title)
        artists = try c.decodeIfPresent([String].self, forKey: .artists)
        durationMS = try c.decodeIfPresent(Int.self, forKey: .durationMS)
        cover = try c.decodeIfPresent(String.self, forKey: .cover)
        score = try c.decodeIfPresent(Double.self, forKey: .score)
        auto = try c.decodeIfPresent(AutoTags.self, forKey: .auto)
        kind = (try c.decodeIfPresent(String.self, forKey: .kind)).flatMap(Kind.init(rawValue:))
        family = try c.decodeIfPresent(String.self, forKey: .family)
        reason = try c.decodeIfPresent(String.self, forKey: .reason)
        vector = try c.decodeIfPresent([Double].self, forKey: .vector)
    }

    enum CodingKeys: String, CodingKey {
        case id, key, uri, isrc, title, artists, cover, score, auto, kind, family, reason, vector
        case durationMS = "duration_ms"
    }
}

/// `data/weekly.json`: today's Crate Daily mix (built every morning; the file keeps its old name). Written by
/// Actions only.
public struct WeeklyFile: Codable, Sendable, Equatable {
    public var schema: Int
    /// ISO day the mix was built for, e.g. `2026-10-13`. Missing in files written before the daily mix.
    public var date: String?
    /// The same ISO day as `date` in daily files; an ISO week label (`2026-W42`) in older weekly files.
    public var week: String?
    public var generatedAt: String?
    public var playlistID: String?
    /// Number of candidates the mix was ranked from.
    public var candidates: Int?
    public var tracks: [WeeklyTrack]

    public init(schema: Int = CrateJSON.supportedSchema, date: String? = nil, week: String? = nil, generatedAt: String? = nil,
                playlistID: String? = nil, candidates: Int? = nil, tracks: [WeeklyTrack] = []) {
        self.schema = schema; self.date = date; self.week = week; self.generatedAt = generatedAt
        self.playlistID = playlistID; self.candidates = candidates; self.tracks = tracks
    }

    /// Accepts `week` as a string or a number, and `tracks` as an array or a dictionary keyed by ID.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schema = try c.decodeIfPresent(Int.self, forKey: .schema) ?? CrateJSON.supportedSchema
        date = try? c.decodeIfPresent(String.self, forKey: .date)
        if let text = try? c.decodeIfPresent(String.self, forKey: .week) {
            week = text
        } else if let number = try? c.decodeIfPresent(Int.self, forKey: .week) {
            week = String(number)
        } else {
            week = nil
        }
        generatedAt = try c.decodeIfPresent(String.self, forKey: .generatedAt)
        playlistID = try c.decodeIfPresent(String.self, forKey: .playlistID)
        candidates = try? c.decodeIfPresent(Int.self, forKey: .candidates)
        if let list = try? c.decodeIfPresent([WeeklyTrack].self, forKey: .tracks) {
            tracks = list
        } else if let map = try? c.decodeIfPresent([String: WeeklyTrack].self, forKey: .tracks) {
            tracks = map.keys.sorted().map { id in
                var track = map[id]!
                if track.id.isEmpty { track.id = id }
                return track
            }
        } else {
            tracks = []
        }
    }

    enum CodingKeys: String, CodingKey {
        case schema, date, week, tracks, candidates
        case generatedAt = "generated_at"
        case playlistID = "playlist_id"
    }
}

extension WeeklyFile {
    /// The calendar day the mix is for, at the start of that day in `calendar`'s time zone: `date`, else `week`
    /// when it is an ISO day (`2026-10-13`), else the local day of `generatedAt`. `nil` when none is usable
    /// (e.g. an old file with only `week: "2026-W42"`).
    public func mixDay(calendar: Calendar = .current) -> Date? {
        if let d = Self.parseISODay(date, calendar: calendar) ?? Self.parseISODay(week, calendar: calendar) { return d }
        guard let stamp = generatedAt, !stamp.isEmpty else { return nil }
        let f = ISO8601DateFormatter()
        var parsed = f.date(from: stamp)
        if parsed == nil {
            f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            parsed = f.date(from: stamp)
        }
        return parsed.map { calendar.startOfDay(for: $0) }
    }

    /// Parses `YYYY-MM-DD` as a day in `calendar` (not UTC midnight). Anything else returns `nil`.
    public static func parseISODay(_ text: String?, calendar: Calendar = .current) -> Date? {
        guard let text else { return nil }
        let parts = text.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              (1...12).contains(m), (1...31).contains(d) else { return nil }
        let comps = DateComponents(year: y, month: m, day: d)
        guard comps.isValidDate(in: calendar), let day = calendar.date(from: comps) else { return nil }
        return calendar.startOfDay(for: day)
    }
}
