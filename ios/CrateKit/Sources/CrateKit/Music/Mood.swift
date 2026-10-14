import Foundation

/// The mood vocabulary and its SF Symbols (design-system §2 "Mood"). Moods are stored as plain strings;
/// this type only adds presentation.
public enum Mood: String, Sendable, CaseIterable {
    case dark, energetic, melancholic, dreamy, uplifting, relaxing, aggressive, party
    case vocal, instrumental, hypnotic, happy

    /// SF Symbol name for the mood chip.
    public var symbol: String {
        switch self {
        case .dark: "moon.fill"
        case .energetic: "bolt.fill"
        case .melancholic: "cloud.rain.fill"
        case .dreamy: "sparkles"
        case .uplifting: "sunrise.fill"
        case .relaxing: "leaf.fill"
        case .aggressive: "flame.fill"
        case .party: "party.popper.fill"
        case .vocal: "mic.fill"
        case .instrumental: "pianokeys"
        case .hypnotic: "circle.dotted.circle"
        case .happy: "face.smiling"
        }
    }

    /// Symbol used for moods without a dedicated one.
    public static let fallbackSymbol = "tag"

    /// The SF Symbol for any mood name, falling back to ``fallbackSymbol``.
    public static func symbol(for name: String) -> String {
        Mood(rawValue: name)?.symbol ?? fallbackSymbol
    }

    /// Moods shown as chips on Build ("vocal" and "instrumental" are covered by the Vocals control).
    public static func isChip(_ name: String) -> Bool { name != "vocal" && name != "instrumental" }
}
