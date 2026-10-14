import Foundation

/// A track's tags after applying the user's override to its `auto` tags (see `docs/query-semantics.md`).
/// `nil` means unknown: the field was never tagged.
public struct EffectiveTags: Sendable, Equatable {
    /// Mood confidence at or above which a mood is present.
    public static let moodThreshold = 0.5
    /// Vocal probability at or above which a track is vocal.
    public static let vocalThreshold = 0.5

    /// The strongest style, which alone decides genre/style matching.
    public var primaryStyle: String?
    public var primaryGenre: String?
    public var bpm: Double?
    public var key: String?
    public var energy: Int?
    public var moods: Set<String>?
    public var vocal: Bool?
    /// Normalised community tags; `nil` when the track has never been tagged.
    public var communityTags: Set<String>?
    /// Effective style family: the override's `family`, else the track's consensus `family`.
    public var family: String?

    public init(primaryStyle: String? = nil, primaryGenre: String? = nil, bpm: Double? = nil, key: String? = nil,
                energy: Int? = nil, moods: Set<String>? = nil, vocal: Bool? = nil,
                communityTags: Set<String>? = nil, family: String? = nil) {
        self.primaryStyle = primaryStyle; self.primaryGenre = primaryGenre; self.bpm = bpm; self.key = key
        self.energy = energy; self.moods = moods; self.vocal = vocal
        self.communityTags = communityTags
        self.family = family
    }

    /// Merges a library track with its override, including its normalised community tags.
    public init(track: Track, override: TrackOverride?, normaliser: TagNormaliser = .bundled) {
        self.init(auto: track.auto, override: override)
        communityTags = normaliser.trackTags(track)
        family = EffectiveTags.nonEmpty(override?.family) ?? EffectiveTags.nonEmpty(track.family)
    }

    private static func nonEmpty(_ s: String?) -> String? { s?.isEmpty == false ? s : nil }

    /// Merges `auto` with `override` field by field; the override wins.
    public init(auto: AutoTags?, override: TrackOverride?) {
        let auto = auto ?? AutoTags()
        let ov = override ?? TrackOverride()
        let stylesAdd = ov.stylesAdd ?? []
        let stylesRemove = Set(ov.stylesRemove ?? [])
        let genresAdd = ov.genresAdd ?? []
        let genresRemove = Set(ov.genresRemove ?? [])

        var style: String?
        if let first = stylesAdd.first {
            style = first
        } else {
            style = EffectiveTags.rankedStyles(auto.styles ?? [:]).first {
                !stylesRemove.contains($0) && !genresRemove.contains(StyleLabel.genre(of: $0))
            }
        }

        var genre: String?
        if stylesAdd.isEmpty, let addedGenre = genresAdd.first {
            genre = addedGenre
            if let s = style, StyleLabel.genre(of: s) != addedGenre { style = nil }
        } else {
            genre = style.map(StyleLabel.genre(of:))
        }
        primaryStyle = style
        primaryGenre = genre

        let moodsAdd = ov.moodsAdd ?? []
        if auto.moods == nil && moodsAdd.isEmpty {
            moods = nil
        } else {
            let present = (auto.moods ?? [:]).filter { $0.value >= EffectiveTags.moodThreshold }.keys
            moods = Set(present).union(moodsAdd).subtracting(ov.moodsRemove ?? [])
        }

        if let v = ov.vocal {
            vocal = v
        } else if let v = auto.vocal {
            vocal = v >= EffectiveTags.vocalThreshold
        } else {
            vocal = nil
        }

        bpm = ov.bpm ?? auto.bpm
        key = ov.key ?? auto.key
        energy = ov.energy ?? auto.energy
        family = EffectiveTags.nonEmpty(ov.family) // the track's own family is added by init(track:override:)
    }

    /// Styles by activation, descending; ties broken by canonical label, ascending.
    public static func rankedStyles(_ styles: [String: Double]) -> [String] {
        styles.sorted { a, b in a.value != b.value ? a.value > b.value : a.key < b.key }.map(\.key)
    }

    /// Secondary styles the UI displays: activation ≥ ``styleDisplayThreshold``, top 3, primary excluded.
    public static let styleDisplayThreshold = 0.2
}
