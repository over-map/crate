import Foundation

/// Analysis state of a library track.
public enum TrackStatus: String, Codable, Sendable, CaseIterable {
    case tagged
    case noPreview = "no_preview"
    case pending
    case error

    /// Unknown future values decode as ``error`` rather than failing the whole library.
    public init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = TrackStatus(rawValue: raw) ?? .error
    }
}

/// Automatic tags written by the pipeline (`tracks[id].auto` in library.json). Every field is optional.
public struct AutoTags: Codable, Sendable, Equatable {
    /// Genre scores, derived from the styles.
    public var genres: [String: Double]?
    /// Style activations keyed by canonical label (`Genre---Style`).
    public var styles: [String: Double]?
    public var bpm: Double?
    public var bpmConf: Double?
    /// Octave candidates for the BPM (e.g. `[138, 69]`).
    public var bpmCandidates: [Double]?
    /// Camelot code such as `8A`.
    public var key: String?
    public var keyConf: Double?
    /// Library-relative energy decile, 1–10.
    public var energy: Int?
    /// Mood confidences keyed by mood name.
    public var moods: [String: Double]?
    /// Vocal probability, 0–1.
    public var vocal: Double?

    public init(
        genres: [String: Double]? = nil, styles: [String: Double]? = nil,
        bpm: Double? = nil, bpmConf: Double? = nil, bpmCandidates: [Double]? = nil,
        key: String? = nil, keyConf: Double? = nil, energy: Int? = nil,
        moods: [String: Double]? = nil, vocal: Double? = nil
    ) {
        self.genres = genres; self.styles = styles
        self.bpm = bpm; self.bpmConf = bpmConf; self.bpmCandidates = bpmCandidates
        self.key = key; self.keyConf = keyConf; self.energy = energy
        self.moods = moods; self.vocal = vocal
    }

    enum CodingKeys: String, CodingKey {
        case genres, styles, bpm, key, energy, moods, vocal
        case bpmConf = "bpm_conf"
        case bpmCandidates = "bpm_candidates"
        case keyConf = "key_conf"
    }
}

/// Where a track's analysis came from (`tracks[id].source`).
public struct TrackSource: Codable, Sendable, Equatable {
    public var deezerID: Int?
    public var analysedAt: String?
    public var modelVersion: String?

    public init(deezerID: Int? = nil, analysedAt: String? = nil, modelVersion: String? = nil) {
        self.deezerID = deezerID; self.analysedAt = analysedAt; self.modelVersion = modelVersion
    }

    enum CodingKeys: String, CodingKey {
        case deezerID = "deezer_id"
        case analysedAt = "analysed_at"
        case modelVersion = "model_version"
    }
}

/// One Liked Song in library.json. Identity fields come from Spotify; tags live in ``auto``.
public struct Track: Codable, Sendable, Equatable {
    public var uri: String?
    public var isrc: String?
    public var title: String?
    public var artists: [String]?
    public var durationMS: Int?
    public var cover: String?
    public var likedAt: String?
    /// Analysis status; `pending` when missing.
    public var status: TrackStatus
    public var source: TrackSource?
    public var auto: AutoTags?
    /// Community tags, merged from every outside source (raw; normalise with ``TagNormaliser``).
    /// `nil` = unknown (no source); `[]` = known, none.
    public var tags: [String]?
    /// Which source gave which (normalised) tag: `lastfm`, `discogs`, `beatport` → tags. Display only.
    public var tagSources: [String: [String]]?
    /// Style family chosen by the pipeline's consensus (one of `taxonomy.json → families`).
    public var family: String?
    /// Evidence for ``family``.
    public var consensus: Consensus?

    public init(
        uri: String? = nil, isrc: String? = nil, title: String? = nil, artists: [String]? = nil,
        durationMS: Int? = nil, cover: String? = nil, likedAt: String? = nil,
        status: TrackStatus = .pending, source: TrackSource? = nil, auto: AutoTags? = nil, tags: [String]? = nil,
        family: String? = nil, consensus: Consensus? = nil
    ) {
        self.uri = uri; self.isrc = isrc; self.title = title; self.artists = artists
        self.durationMS = durationMS; self.cover = cover; self.likedAt = likedAt
        self.status = status; self.source = source; self.auto = auto; self.tags = tags
        self.family = family; self.consensus = consensus
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        uri = try c.decodeIfPresent(String.self, forKey: .uri)
        isrc = try c.decodeIfPresent(String.self, forKey: .isrc)
        title = try c.decodeIfPresent(String.self, forKey: .title)
        artists = try c.decodeIfPresent([String].self, forKey: .artists)
        durationMS = try c.decodeIfPresent(Int.self, forKey: .durationMS)
        cover = try c.decodeIfPresent(String.self, forKey: .cover)
        likedAt = try c.decodeIfPresent(String.self, forKey: .likedAt)
        status = try c.decodeIfPresent(TrackStatus.self, forKey: .status) ?? .pending
        source = try c.decodeIfPresent(TrackSource.self, forKey: .source)
        auto = try c.decodeIfPresent(AutoTags.self, forKey: .auto)
        tags = try c.decodeIfPresent([String].self, forKey: .tags)
        tagSources = try c.decodeIfPresent([String: [String]].self, forKey: .tagSources)
        family = try c.decodeIfPresent(String.self, forKey: .family)
        consensus = try c.decodeIfPresent(Consensus.self, forKey: .consensus)
    }

    /// The Spotify URI, falling back to `spotify:track:<id>`.
    public func spotifyURI(id: String) -> String { uri ?? "spotify:track:\(id)" }

    enum CodingKeys: String, CodingKey {
        case uri, isrc, title, artists, cover, status, source, auto, tags, family, consensus
        case tagSources = "tag_sources"
        case durationMS = "duration_ms"
        case likedAt = "liked_at"
    }
}
