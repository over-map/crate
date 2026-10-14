import Foundation

/// A tag query (spec §5.5, rules in `docs/query-semantics.md`). Every field is optional; an empty query matches
/// every track.
public struct Query: Codable, Sendable, Equatable, Hashable {
    /// Inclusive BPM range, optionally also matching half or double tempo.
    public struct BPMRange: Codable, Sendable, Equatable, Hashable {
        public var min: Double
        public var max: Double
        public var halfDouble: Bool

        public init(min: Double, max: Double, halfDouble: Bool = false) {
            self.min = min; self.max = max; self.halfDouble = halfDouble
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            min = try c.decodeIfPresent(Double.self, forKey: .min) ?? 0
            max = try c.decodeIfPresent(Double.self, forKey: .max) ?? .greatestFiniteMagnitude
            halfDouble = try c.decodeIfPresent(Bool.self, forKey: .halfDouble) ?? false
        }

        /// True when `bpm` (or, with ``halfDouble``, `bpm·2` or `bpm/2`) lies in the range.
        public func contains(_ bpm: Double) -> Bool {
            let inRange = { (value: Double) in min <= value && value <= max }
            if inRange(bpm) { return true }
            return halfDouble && (inRange(bpm * 2) || inRange(bpm / 2))
        }

        enum CodingKeys: String, CodingKey {
            case min, max
            case halfDouble = "half_double"
        }
    }

    /// Inclusive energy range (1–10).
    public struct EnergyRange: Codable, Sendable, Equatable, Hashable {
        public var min: Int
        public var max: Int

        public init(min: Int, max: Int) { self.min = min; self.max = max }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            min = try c.decodeIfPresent(Int.self, forKey: .min) ?? 1
            max = try c.decodeIfPresent(Int.self, forKey: .max) ?? 10
        }

        public func contains(_ energy: Int) -> Bool { min <= energy && energy <= max }

        enum CodingKeys: String, CodingKey { case min, max }
    }

    /// A Camelot key, optionally widened to its compatible set.
    public struct KeyFilter: Codable, Sendable, Equatable, Hashable {
        public var camelot: String
        public var compatible: Bool

        public init(camelot: String, compatible: Bool = false) {
            self.camelot = camelot; self.compatible = compatible
        }

        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            camelot = try c.decode(String.self, forKey: .camelot)
            compatible = try c.decodeIfPresent(Bool.self, forKey: .compatible) ?? false
        }

        /// The Camelot codes this filter accepts.
        public var accepted: Set<String> {
            guard let key = Camelot(camelot) else { return [] }
            return compatible ? Set(key.compatible.map(\.code)) : [key.code]
        }

        enum CodingKeys: String, CodingKey { case camelot, compatible }
    }

    /// Vocal constraint.
    public enum Vocal: String, Codable, Sendable, Hashable {
        case any, vocal, instrumental
    }

    /// Default number of tracks sent to Now.
    public static let defaultLimit = 100

    /// Style families (`taxonomy.json → families`); part of the "any of" group with genres and styles.
    public var familiesInclude: [String]
    /// Family → canonical styles ticked under it. Matches when the effective family is the key **and** the
    /// primary style is in its list. Same "any of" group.
    public var familyStyles: [String: [String]]
    public var genresInclude: [String]
    public var genresExclude: [String]
    public var stylesInclude: [String]
    public var stylesExclude: [String]
    public var bpm: BPMRange?
    public var energy: EnergyRange?
    public var moodsInclude: [String]
    public var moodsExclude: [String]
    public var key: KeyFilter?
    public var vocal: Vocal
    /// Vibe IDs; the track must be a member of every one (AND). An unknown ID matches nothing.
    public var vibesInclude: [String]
    /// Community tags the track must all have (AND), normalised. Tracks with unknown tags fail.
    public var tagsInclude: [String]
    public var limit: Int

    public init(
        familiesInclude: [String] = [], familyStyles: [String: [String]] = [:], genresInclude: [String] = [], genresExclude: [String] = [],
        stylesInclude: [String] = [], stylesExclude: [String] = [],
        bpm: BPMRange? = nil, energy: EnergyRange? = nil,
        moodsInclude: [String] = [], moodsExclude: [String] = [],
        key: KeyFilter? = nil, vocal: Vocal = .any, vibesInclude: [String] = [], tagsInclude: [String] = [],
        limit: Int = Query.defaultLimit
    ) {
        self.familiesInclude = familiesInclude; self.familyStyles = familyStyles
        self.genresInclude = genresInclude; self.genresExclude = genresExclude
        self.stylesInclude = stylesInclude; self.stylesExclude = stylesExclude
        self.bpm = bpm; self.energy = energy
        self.moodsInclude = moodsInclude; self.moodsExclude = moodsExclude
        self.key = key; self.vocal = vocal; self.vibesInclude = vibesInclude
        self.tagsInclude = tagsInclude; self.limit = limit
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        familiesInclude = try c.decodeIfPresent([String].self, forKey: .familiesInclude) ?? []
        familyStyles = try c.decodeIfPresent([String: [String]].self, forKey: .familyStyles) ?? [:]
        genresInclude = try c.decodeIfPresent([String].self, forKey: .genresInclude) ?? []
        genresExclude = try c.decodeIfPresent([String].self, forKey: .genresExclude) ?? []
        stylesInclude = try c.decodeIfPresent([String].self, forKey: .stylesInclude) ?? []
        stylesExclude = try c.decodeIfPresent([String].self, forKey: .stylesExclude) ?? []
        bpm = try c.decodeIfPresent(BPMRange.self, forKey: .bpm)
        energy = try c.decodeIfPresent(EnergyRange.self, forKey: .energy)
        moodsInclude = try c.decodeIfPresent([String].self, forKey: .moodsInclude) ?? []
        moodsExclude = try c.decodeIfPresent([String].self, forKey: .moodsExclude) ?? []
        key = try c.decodeIfPresent(KeyFilter.self, forKey: .key)
        let vocalRaw = try c.decodeIfPresent(String.self, forKey: .vocal)
        vocal = vocalRaw.flatMap(Vocal.init(rawValue:)) ?? .any
        vibesInclude = try c.decodeIfPresent([String].self, forKey: .vibesInclude) ?? []
        // Normalised with the bundled synonyms on decode; matching normalises again (idempotent).
        let rawTags = try c.decodeIfPresent([String].self, forKey: .tagsInclude) ?? []
        tagsInclude = rawTags.map(Tags.normalise).filter { !$0.isEmpty }
        limit = try c.decodeIfPresent(Int.self, forKey: .limit) ?? Query.defaultLimit
    }

    /// True when no field constrains the result (only ``limit`` may differ).
    public var isUnconstrained: Bool {
        familiesInclude.isEmpty && familyStyles.isEmpty && genresInclude.isEmpty && genresExclude.isEmpty && stylesInclude.isEmpty && stylesExclude.isEmpty
            && bpm == nil && energy == nil && moodsInclude.isEmpty && moodsExclude.isEmpty
            && key == nil && vocal == .any && vibesInclude.isEmpty && tagsInclude.isEmpty
    }

    enum CodingKeys: String, CodingKey {
        case bpm, energy, key, vocal, limit
        case familiesInclude = "families_include"
        case familyStyles = "family_styles"
        case genresInclude = "genres_include", genresExclude = "genres_exclude"
        case stylesInclude = "styles_include", stylesExclude = "styles_exclude"
        case moodsInclude = "moods_include", moodsExclude = "moods_exclude"
        case vibesInclude = "vibes_include"
        case tagsInclude = "tags_include"
    }
}
