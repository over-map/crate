import CrateKit
import CrateUI
import SwiftUI

// Shared helpers for the Weekly, Library, Presets and Onboarding screens. Owner: Weekly/Library agent.

enum LibraryText {
    /// "1,015"
    static func number(_ n: Int) -> String { n.formatted(.number) }

    /// Family display name ("Latin & Brazilian"), from the taxonomy or the design tokens.
    static func familyName(_ raw: String, taxonomy: TaxonomyFile) -> String {
        if let name = taxonomy.familyNames[raw] { return name }
        return Family(rawValue: raw)?.displayName ?? raw.prefix(1).uppercased() + raw.dropFirst()
    }

    static func family(_ raw: String?) -> Family { raw.flatMap(Family.init(rawValue:)) ?? .none }

    static func parseDate(_ iso: String?) -> Date? {
        guard let iso, !iso.isEmpty else { return nil }
        let f = ISO8601DateFormatter()
        if let d = f.date(from: iso) { return d }
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.date(from: iso)
    }

    /// "today 08:42", "yesterday 03:12", "Sat 03:12", "12 Oct".
    static func relativeDay(_ date: Date, now: Date = Date()) -> String {
        let cal = Calendar.current
        let time = date.formatted(date: .omitted, time: .shortened)
        if cal.isDateInToday(date) { return "today \(time)" }
        if cal.isDateInYesterday(date) { return "yesterday \(time)" }
        if let days = cal.dateComponents([.day], from: date, to: now).day, days < 7 {
            return "\(date.formatted(.dateTime.weekday(.abbreviated))) \(time)"
        }
        return date.formatted(.dateTime.day().month(.abbreviated))
    }

    /// URL-safe ID from a name ("Italian summer" → "italian-summer"), unique among `existing`.
    static func slug(_ name: String, existing: Set<String>) -> String {
        let folded = name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil).lowercased()
        var out = ""
        for ch in folded.unicodeScalars {
            if ch.isASCII && CharacterSet.alphanumerics.contains(ch) {
                out.unicodeScalars.append(ch)
            } else if !out.isEmpty && !out.hasSuffix("-") {
                out.append("-")
            }
        }
        while out.hasSuffix("-") { out.removeLast() }
        if out.isEmpty { out = "preset" }
        var candidate = out
        var n = 2
        while existing.contains(candidate) { candidate = "\(out)-\(n)"; n += 1 }
        return candidate
    }

    /// Runbook link for a health problem (`docs/runbook.md#spotify-token`).
    static func runbookURL(_ anchor: String?) -> URL? {
        guard let anchor, !anchor.isEmpty else { return nil }
        let path = anchor.hasPrefix("docs/") ? anchor : "docs/runbook.md#\(anchor)"
        return URL(string: "https://github.com/\(AppConfig.repoOwner)/\(AppConfig.repoName)/blob/main/\(path)")
    }

    /// "Spotify token" from "docs/runbook.md#spotify-token".
    static func runbookTitle(_ anchor: String) -> String {
        let tail = anchor.split(separator: "#").last.map(String.init) ?? anchor
        let words = tail.replacingOccurrences(of: "-", with: " ")
        return words.prefix(1).uppercased() + words.dropFirst()
    }
}

/// Opens Spotify (app if installed, else the web player).
enum SpotifyLink {
    static func playlist(_ id: String) -> URL { URL(string: "spotify:playlist:\(id)")! }
    static func playlistWeb(_ id: String) -> URL { URL(string: "https://open.spotify.com/playlist/\(id)")! }
    static let app = URL(string: "spotify:")!

    @MainActor
    static func open(_ url: URL, fallback: URL?, with openURL: OpenURLAction) {
        openURL(url) { accepted in
            if !accepted, let fallback { openURL(fallback) }
        }
    }
}

/// Small rounded "chip" look for the Weekly filter menus and other app-level chips (32 pt, 44 at AX sizes).
struct LibraryChipStyle: ViewModifier {
    var selected: Bool
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: Radius.chip, style: .continuous)
        content
            .font(.chipLabel)
            .foregroundStyle(selected ? Color.onAccent : Color.textPrimary)
            .lineLimit(1)
            .padding(.horizontal, Space.m)
            .frame(minHeight: dynamicTypeSize.isAccessibilitySize ? Size.chipHeightAX : Size.chipHeight)
            .background(selected ? Color.accent : Color.fillControl, in: shape)
            .contentShape(shape)
            .padding(.vertical, dynamicTypeSize.isAccessibilitySize ? 0 : (Size.minTarget - Size.chipHeight) / 2)
            .contentShape(Rectangle())
    }
}

extension View {
    func libraryChip(selected: Bool = false) -> some View { modifier(LibraryChipStyle(selected: selected)) }
}

/// White (accent) capsule-ish button used inside cards ("Open Spotify", "Turn on reminders").
struct CardButtonStyle: ButtonStyle {
    var prominent = true
    var fullWidth = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(prominent ? Color.onAccent : Color.textPrimary)
            .padding(.horizontal, Space.m)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: 36)
            .background(prominent ? Color.accent : Color.fillControl,
                        in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
            .opacity(configuration.isPressed ? 0.75 : 1)
            .contentShape(Rectangle())
            .frame(minHeight: Size.minTarget)
    }
}

/// Full-width 54 pt button for onboarding and secondary screen actions (accent fill, or control fill).
struct LargeButtonStyle: ButtonStyle {
    var prominent = true
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(prominent ? Color.onAccent : Color.textPrimary)
            .frame(maxWidth: .infinity, minHeight: Size.buttonHeight)
            .padding(.horizontal, Space.l)
            .background(prominent ? Color.accent : Color.fillControl,
                        in: RoundedRectangle(cornerRadius: Radius.button, style: .continuous))
            .opacity(!isEnabled ? 0.4 : configuration.isPressed ? 0.8 : 1)
            .contentShape(Rectangle())
    }
}
