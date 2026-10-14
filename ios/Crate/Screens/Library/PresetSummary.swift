import CrateKit
import CrateUI
import SwiftUI

/// The preset summary line (README "Presets summary line"): genre & style · vibes ("+" = all of) · ⌗ tags · BPM ·
/// energy · key · vocals. Parts that are "any" are dropped, except in the Save sheet (`includeAny`).
struct PresetSummary {
    struct Part: Equatable {
        var text: String
        var symbol: String?
        var isAny = false
    }

    let parts: [Part]

    @MainActor
    init(_ q: Query, model: AppModel, includeAny: Bool) {
        var out: [Part] = []

        // Genre & style
        let genre = Self.genreText(q, model: model)
        if let genre { out.append(Part(text: genre)) } else if includeAny { out.append(Part(text: "Any genre", isAny: true)) }

        // Vibes (AND)
        if !q.vibesInclude.isEmpty {
            let names = q.vibesInclude.map { model.snapshot.vibes.vibes[$0]?.name ?? $0.capitalized }
            out.append(Part(text: names.joined(separator: " + ")))
        } else if includeAny { out.append(Part(text: "any vibe", isAny: true)) }

        // Tags (AND)
        if !q.tagsInclude.isEmpty {
            out.append(Part(text: q.tagsInclude.joined(separator: " + "), symbol: "tag"))
        } else if includeAny { out.append(Part(text: "no tags", isAny: true)) }

        // BPM
        if let b = q.bpm {
            out.append(Part(text: "\(Int(b.min))–\(b.max >= 1000 ? "∞" : String(Int(b.max)))" + (b.halfDouble ? " ×½" : "")))
        } else if includeAny { out.append(Part(text: "any BPM", isAny: true)) }

        // Energy
        if let e = q.energy, !(e.min <= 1 && e.max >= 10) {
            out.append(Part(text: e.min == e.max ? "\(e.min)" : "\(e.min)–\(e.max)", symbol: "bolt.fill"))
        } else if includeAny { out.append(Part(text: "any energy", isAny: true)) }

        // Key
        if let k = q.key {
            out.append(Part(text: k.camelot + (k.compatible ? " + compatible" : "")))
        } else if includeAny { out.append(Part(text: "any key", isAny: true)) }

        // Vocals
        switch q.vocal {
        case .vocal: out.append(Part(text: "vocal"))
        case .instrumental: out.append(Part(text: "instrumental"))
        case .any: if includeAny { out.append(Part(text: "vocals any", isAny: true)) }
        }

        if includeAny { out.append(Part(text: "limit \(q.limit)", isAny: true)) }
        parts = out
    }

    var isEmpty: Bool { parts.isEmpty }

    /// Styled text: "any" parts in tertiary, symbols inline.
    var text: Text {
        guard !parts.isEmpty else { return Text("Whole library") }
        var result = Text(verbatim: "")
        for (i, p) in parts.enumerated() {
            var piece: Text
            if let symbol = p.symbol {
                piece = Text("\(Image(systemName: symbol))\u{2009}\(p.text)")
            } else {
                piece = Text(verbatim: p.text)
            }
            piece = piece.foregroundStyle(p.isAny ? Color.textTertiary : Color.textSecondary)
            result = i == 0 ? Text("\(result)\(piece)") : Text("\(result)\(Text(verbatim: " · ").foregroundStyle(Color.textTertiary))\(piece)")
        }
        return result
    }

    /// Plain words for VoiceOver ("tags italian", "energy 6 to 10").
    var spoken: String {
        guard !parts.isEmpty else { return "Whole library" }
        return parts.map { p in
            switch p.symbol {
            case "tag": "tags \(p.text)"
            case "bolt.fill": "energy \(p.text.replacingOccurrences(of: "–", with: " to "))"
            default: p.text.replacingOccurrences(of: "–", with: " to ").replacingOccurrences(of: "×½", with: "half or double")
            }
        }.joined(separator: ", ")
    }

    // MARK: Genre & style

    @MainActor
    private static func genreText(_ q: Query, model: AppModel) -> String? {
        let tax = model.snapshot.taxonomy
        let order = tax.families
        var entries: [(family: String, text: String)] = []

        for f in q.familiesInclude {
            entries.append((f, LibraryText.familyName(f, taxonomy: tax)))
        }
        for (f, styles) in q.familyStyles where !styles.isEmpty {
            let name = LibraryText.familyName(f, taxonomy: tax)
            var all = model.index.families.first { $0.family == f }?.styles.map(\.style) ?? []
            all += styles.filter { !all.contains($0) }.sorted()
            let summary = all.isEmpty ? GenreSummary.styles(styles) : GenreSummary.make(ticked: Set(styles), all: all)
            let display = { (s: String) in StyleLabel(canonical: s, aliases: tax.aliases).styleDisplay }
            let text: String = switch summary {
            case .allStyles: name
            case let .except(x): "\(name) except \(x.map(display).joined(separator: ", "))"
            case let .styles(x): "\(name): \(x.map { short(display($0), family: name) }.joined(separator: ", "))"
            case let .count(t, _): "\(name): \(t) styles"
            }
            entries.append((f, text))
        }
        entries.sort { (order.firstIndex(of: $0.family) ?? 99, $0.family) < (order.firstIndex(of: $1.family) ?? 99, $1.family) }

        var texts = entries.map(\.text)
        if !q.stylesInclude.isEmpty {
            texts.append(q.stylesInclude.map { StyleLabel(canonical: $0, aliases: tax.aliases).styleDisplay }.joined(separator: ", "))
        }
        if !q.genresInclude.isEmpty {
            texts.append(q.genresInclude.map { tax.aliases[$0] ?? $0 }.joined(separator: ", "))
        }
        guard !texts.isEmpty else { return nil }
        let rich = texts.contains { $0.contains(":") || $0.contains(" except ") }
        return texts.joined(separator: rich ? "; " : ", ")
    }

    /// "Hard Techno" under Techno → "Hard"; "Techno" stays.
    private static func short(_ style: String, family: String) -> String {
        let suffix = " " + family
        if style.count > suffix.count, style.lowercased().hasSuffix(suffix.lowercased()) {
            return String(style.dropLast(suffix.count))
        }
        return style
    }
}
