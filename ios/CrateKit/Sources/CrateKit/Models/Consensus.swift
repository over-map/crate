import Foundation

/// The evidence behind a track's style family (`tracks[id].consensus`, written by `crate consensus`).
/// The app shows it as confidence, e.g. "3 of 4 sources agree".
public struct Consensus: Codable, Sendable, Equatable {
    /// The winning family.
    public var family: String?
    /// Votes for the winning family.
    public var votes: Int?
    /// Number of sources that voted.
    public var sources: Int?
    /// Whether the audio model's family won; `nil` when there was no audio vote.
    public var audioAgrees: Bool?
    /// Families each source voted for (`audio`, `discogs`, `lastfm`, `beatport`, …).
    public var bySource: [String: [String]]

    public init(family: String? = nil, votes: Int? = nil, sources: Int? = nil, audioAgrees: Bool? = nil,
                bySource: [String: [String]] = [:]) {
        self.family = family; self.votes = votes; self.sources = sources
        self.audioAgrees = audioAgrees; self.bySource = bySource
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        family = try c.decodeIfPresent(String.self, forKey: .family)
        votes = try c.decodeIfPresent(Int.self, forKey: .votes)
        sources = try c.decodeIfPresent(Int.self, forKey: .sources)
        audioAgrees = try c.decodeIfPresent(Bool.self, forKey: .audioAgrees)
        bySource = try c.decodeIfPresent([String: [String]].self, forKey: .bySource) ?? [:]
    }

    enum CodingKeys: String, CodingKey {
        case family, votes, sources
        case audioAgrees = "audio_agrees"
        case bySource = "by_source"
    }
}
