import Foundation

/// Normalises community tags (Last.fm, Discogs, Beatport) so that "Hip-Hop", "hiphop" and "rap" compare equal.
///
/// The steps and synonym table are shared with Python through `spec/tag_synonyms.json`; the package bundles a
/// byte-identical copy (`Resources/tag_synonyms.json`, checked by a test).
public struct TagNormaliser: Sendable, Equatable {
    /// Exact-match synonyms applied after the text steps (`italy` → `italian`).
    public let synonyms: [String: String]

    public init(synonyms: [String: String]) { self.synonyms = synonyms }

    /// Reads the `synonyms` object of a `tag_synonyms.json` file.
    public init(json data: Data) throws {
        struct File: Decodable { let synonyms: [String: String] }
        self.init(synonyms: try JSONDecoder().decode(File.self, from: data).synonyms)
    }

    /// The copy bundled with CrateKit. Falls back to no synonyms if the resource is missing (a test guards this).
    public static let bundled: TagNormaliser = {
        guard let url = Bundle.module.url(forResource: "tag_synonyms", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let normaliser = try? TagNormaliser(json: data) else { return TagNormaliser(synonyms: [:]) }
        return normaliser
    }()

    /// NFKD, drop combining marks, lower-case, remove `'` and `’`, `&` → ` and `, each of `- _ / . ,` → space,
    /// collapse whitespace, trim, then map synonyms. `"  Hip-Hop "` → `"hip hop"`.
    public func normalise(_ tag: String) -> String {
        var scalars = String.UnicodeScalarView()
        for scalar in tag.decomposedStringWithCompatibilityMapping.unicodeScalars
        where scalar.properties.canonicalCombiningClass == .notReordered {
            scalars.append(scalar)
        }
        var text = String(scalars).lowercased()
        text = text.replacingOccurrences(of: "'", with: "")
            .replacingOccurrences(of: "\u{2019}", with: "")
            .replacingOccurrences(of: "&", with: " and ")
        var out = ""
        var pendingSpace = false
        for ch in text {
            let isSpace = "-_/.,".contains(ch) || ch.unicodeScalars.allSatisfy(\.properties.isWhitespace)
            if isSpace {
                pendingSpace = true
            } else {
                if pendingSpace && !out.isEmpty { out.append(" ") }
                pendingSpace = false
                out.append(ch)
            }
        }
        return synonyms[out] ?? out
    }

    /// Normalises every tag and drops the ones that become empty.
    public func normaliseAll(_ tags: [String]) -> Set<String> {
        Set(tags.lazy.map(normalise).filter { !$0.isEmpty })
    }

    /// A track's normalised tags, or `nil` when it has never been tagged (no `tags` key).
    public func trackTags(_ track: Track) -> Set<String>? {
        track.tags.map(normaliseAll)
    }
}

/// Shorthand for the bundled ``TagNormaliser``.
public enum Tags {
    /// Normalises `tag` with the bundled synonym table.
    public static func normalise(_ tag: String) -> String { TagNormaliser.bundled.normalise(tag) }
}
