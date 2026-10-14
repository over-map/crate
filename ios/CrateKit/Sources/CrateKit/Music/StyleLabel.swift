import Foundation

/// A canonical Discogs label `Genre---Style`, split into its parts, with display names from the taxonomy aliases.
public struct StyleLabel: Hashable, Sendable {
    /// Separator between genre and style in canonical labels.
    public static let separator = "---"

    /// The full canonical label, e.g. `Electronic---Techno`. Queries and storage always use this.
    public let canonical: String
    /// Canonical genre, e.g. `Electronic`.
    public let genre: String
    /// Canonical style part, e.g. `Techno` (the whole label when there is no separator).
    public let style: String
    /// User-facing genre name (alias applied), e.g. `Funk & Soul`.
    public let genreDisplay: String
    /// User-facing style name (alias applied).
    public let styleDisplay: String

    /// Splits `canonical`. `aliases` may rename the full label, the genre or the style part.
    public init(canonical: String, aliases: [String: String] = [:]) {
        self.canonical = canonical
        if let range = canonical.range(of: StyleLabel.separator) {
            genre = String(canonical[..<range.lowerBound])
            style = String(canonical[range.upperBound...])
        } else {
            genre = canonical
            style = canonical
        }
        genreDisplay = aliases[genre] ?? genre
        styleDisplay = aliases[canonical] ?? aliases[style] ?? style
    }

    /// "Style · Genre", as shown on chips (design principle 6).
    public var display: String { "\(styleDisplay) · \(genreDisplay)" }

    /// The parent genre of a canonical style label (`Electronic---Techno` → `Electronic`).
    public static func genre(of canonical: String) -> String {
        guard let range = canonical.range(of: separator) else { return canonical }
        return String(canonical[..<range.lowerBound])
    }

    /// Joins a genre and style into a canonical label.
    public static func canonical(genre: String, style: String) -> String { genre + separator + style }
}
