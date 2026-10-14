import Foundation

/// `data/taxonomy.json`: the genre → style vocabulary and optional display aliases. Written by Actions only.
public struct TaxonomyFile: Codable, Sendable, Equatable {
    public var schema: Int
    /// Canonical genre → its canonical style labels (`Genre---Style`).
    public var genres: [String: [String]]
    /// Optional user-facing renames, keyed by canonical genre or style label (e.g. `Funk / Soul` → `Funk & Soul`).
    public var aliases: [String: String]
    /// Style families in display order (`techno`, `house`, …), the values of `tracks[id].family`.
    public var families: [String]
    /// UI names for families, sentence case (`breaks & bass` → `Breaks & bass`).
    public var familyNames: [String: String]

    public init(schema: Int = CrateJSON.supportedSchema, genres: [String: [String]] = [:],
                aliases: [String: String] = [:], families: [String] = [], familyNames: [String: String] = [:]) {
        self.schema = schema; self.genres = genres; self.aliases = aliases
        self.families = families; self.familyNames = familyNames
    }

    /// The UI name of a family, falling back to its ID.
    public func displayName(family: String) -> String { familyNames[family] ?? family }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schema = try c.decodeIfPresent(Int.self, forKey: .schema) ?? CrateJSON.supportedSchema
        genres = try c.decodeIfPresent([String: [String]].self, forKey: .genres) ?? [:]
        aliases = try c.decodeIfPresent([String: String].self, forKey: .aliases) ?? [:]
        families = try c.decodeIfPresent([String].self, forKey: .families) ?? []
        familyNames = try c.decodeIfPresent([String: String].self, forKey: .familyNames) ?? [:]
    }

    /// Canonical genres, sorted.
    public var genreNames: [String] { genres.keys.sorted() }

    /// The genre a style belongs to, from the vocabulary, else from the label prefix.
    public func genre(ofStyle style: String) -> String {
        for (genre, styles) in genres where styles.contains(style) { return genre }
        return StyleLabel(canonical: style).genre
    }

    /// The display label for a style, using ``aliases``.
    public func label(forStyle style: String) -> StyleLabel {
        StyleLabel(canonical: style, aliases: aliases)
    }

    enum CodingKeys: String, CodingKey {
        case schema, genres, aliases, families
        case familyNames = "family_names"
    }
}
