import CrateKit
import CrateUI
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

// Shared helpers for the Vibes screens (editor, manager, chooser). Owner: Vibes agent.

enum VibeText {
    /// SF Symbol for a vibe: the user's pick, else the default for its ID, else `tag`. Unknown symbols fall back to `tag`.
    static func symbol(id: String?, vibe: Vibe?) -> String {
        let name = vibe?.icon ?? id.flatMap { VibeSymbols.defaults[$0] } ?? "tag"
        #if canImport(UIKit)
        return UIImage(systemName: name) == nil ? "tag" : name
        #else
        return name
        #endif
    }

    /// URL-safe ID from a name: "Sunday morning" → "sunday-morning", unique among `existing`.
    static func slug(_ name: String, existing: some Collection<String>) -> String {
        let folded = name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil).lowercased()
        var out = ""
        var dash = false
        for ch in folded.unicodeScalars {
            if CharacterSet.alphanumerics.contains(ch) && ch.isASCII {
                out.unicodeScalars.append(ch); dash = false
            } else if !out.isEmpty && !dash {
                out.append("-"); dash = true
            }
        }
        while out.hasSuffix("-") { out.removeLast() }
        if out.isEmpty { out = "vibe" }
        let taken = Set(existing)
        guard taken.contains(out) else { return out }
        var n = 2
        while taken.contains("\(out)-\(n)") { n += 1 }
        return "\(out)-\(n)"
    }

    /// "BI" for "Bon Iver", "BO" for "Bonobo".
    static func initials(_ name: String) -> String {
        let words = name.split(whereSeparator: { $0 == " " || $0 == "-" }).filter { $0.first?.isLetter ?? false }
        if words.count >= 2 { return String(words[0].prefix(1) + words[1].prefix(1)).uppercased() }
        return String((words.first ?? Substring(name)).prefix(2)).uppercased()
    }

    /// Manager subtitle: "5 seed tracks", "3 tags · 4 never", "No seeds yet".
    static func summary(_ vibe: Vibe) -> String {
        var parts: [String] = []
        if !vibe.seeds.isEmpty { parts.append(vibe.seeds.count == 1 ? "1 seed track" : "\(vibe.seeds.count) seed tracks") }
        if !vibe.artists.isEmpty { parts.append(vibe.artists.count == 1 ? "1 artist" : "\(vibe.artists.count) artists") }
        if !vibe.seedTags.isEmpty { parts.append(vibe.seedTags.count == 1 ? "1 tag" : "\(vibe.seedTags.count) tags") }
        if parts.isEmpty { return "No seeds yet" }
        if !vibe.exclude.isEmpty { parts.append("\(vibe.exclude.count) never") }
        return parts.joined(separator: " · ")
    }

    static func hasSeeds(_ vibe: Vibe) -> Bool {
        !vibe.seeds.isEmpty || !vibe.artists.isEmpty || !vibe.seedTags.isEmpty
    }

    static func fold(_ s: String) -> String {
        s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
    }
}

/// The three library shares of the editor's segmented control.
enum VibeShare: Double, CaseIterable, Identifiable {
    case tight, normal, wide
    var id: Self { self }
    var value: Double {
        switch self {
        case .tight: VibeSymbols.shareTight
        case .normal: VibeSymbols.shareNormal
        case .wide: VibeSymbols.shareWide
        }
    }
    var name: String {
        switch self {
        case .tight: "Tight"
        case .normal: "Normal"
        case .wide: "Wide"
        }
    }
    var label: String { "\(name) \(Int((value * 100).rounded()))%" }
    init?(share: Double) {
        guard let s = Self.allCases.first(where: { abs($0.value - share) < 0.001 }) else { return nil }
        self = s
    }
}

/// Track lookups shared by the vibe screens (a namespace, so other areas' `LibraryIndex` extensions can't collide).
struct VibeLookup {
    let index: LibraryIndex
    func title(_ id: String) -> String { index.tracks[id]?.title ?? "Unknown track" }
    func artist(_ id: String) -> String { index.tracks[id]?.artists?.joined(separator: ", ") ?? "" }
    func cover(_ id: String) -> URL? { index.tracks[id]?.cover.flatMap(URL.init(string:)) }
    func family(_ id: String) -> Family? { index.effective[id]?.family.flatMap(Family.init(rawValue:)) }
}

/// Result of ranking a draft vibe off the main thread.
struct VibePreview: Sendable, Equatable {
    /// Members, closest first.
    var members: [String] = []
    /// Tracks that `seed_tags` pull in (before the "must sound like it" check).
    var tagSeedCount = 0
    var isEmpty: Bool { members.isEmpty }

    static func compute(_ vibe: Vibe, index: VibeIndex) -> VibePreview {
        let memberSet = index.members(vibe)
        let order = index.ranked(vibe)
        var members: [String] = order.map(\.id).filter { memberSet.contains($0) }
        if members.count < memberSet.count {
            let seen = Set(members)
            members += memberSet.subtracting(seen).sorted()
        }
        return VibePreview(members: members, tagSeedCount: index.tagSeeds(vibe).count)
    }
}

/// Searchable artist list (name → track count), built once per library.
struct ArtistDirectory {
    struct Entry: Hashable { let name: String; let count: Int; let folded: String }
    let entries: [Entry]

    init(tracks: [String: Track]) {
        var counts: [String: (name: String, count: Int)] = [:]
        for t in tracks.values {
            for a in t.artists ?? [] {
                let key = VibeText.fold(a)
                counts[key, default: (a, 0)].count += 1
            }
        }
        entries = counts.map { Entry(name: $0.value.name, count: $0.value.count, folded: $0.key) }
            .sorted { $0.count != $1.count ? $0.count > $1.count : $0.name < $1.name }
    }

    func search(_ q: String, limit: Int) -> [Entry] {
        let f = VibeText.fold(q)
        let prefix = entries.filter { $0.folded.hasPrefix(f) }
        let contains = entries.filter { !$0.folded.hasPrefix(f) && $0.folded.contains(f) }
        return Array((prefix + contains).prefix(limit))
    }
}

/// The vibe's symbol on a rounded tile (36 pt in the editor and manager).
struct VibeSymbolTile: View {
    var symbol: String
    var size: CGFloat = 36
    var selected = false
    var dimmed = false

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.45, weight: .medium))
            .symbolRenderingMode(.hierarchical)
            .foregroundStyle(selected ? Color.onAccent : (dimmed ? Color.textSecondary : Color.textPrimary))
            .frame(width: size, height: size)
            .background(selected ? Color.accent : Color.fillControl,
                        in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
            .accessibilityHidden(true)
    }
}

/// 32 pt initials circle for artist seeds.
struct InitialsCircle: View {
    var name: String
    var size: CGFloat = 32
    var body: some View {
        Text(VibeText.initials(name))
            .font(.system(size: size * 0.38, weight: .semibold, design: .rounded))
            .foregroundStyle(Color.textSecondary)
            .frame(width: size, height: size)
            .background(Color.fillControl, in: Circle())
            .accessibilityHidden(true)
    }
}

extension View {
    /// Section header in the Crate panel-label style, without the list's default uppercase/padding.
    func vibeSectionHeader() -> some View {
        self.textCase(nil).listRowInsets(EdgeInsets(top: Space.l, leading: 0, bottom: Space.s, trailing: 0))
    }
}
