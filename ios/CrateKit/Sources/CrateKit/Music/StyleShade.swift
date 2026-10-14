import Foundation

/// Which of a genre hue's three shades a style uses. Stable across launches and devices.
public enum StyleShade: Int, Sendable, CaseIterable {
    case light = 0, base = 1, deep = 2

    /// `[light, base, deep][fnv1a32(utf8(canonical)) % 3]`.
    public static func of(_ canonicalStyle: String) -> StyleShade {
        StyleShade(rawValue: Int(fnv1a32(canonicalStyle) % 3))!
    }

    /// 32-bit FNV-1a over the UTF-8 bytes of `text` (offset basis 2166136261, prime 16777619).
    public static func fnv1a32(_ text: String) -> UInt32 {
        var hash: UInt32 = 2_166_136_261
        for byte in text.utf8 {
            hash ^= UInt32(byte)
            hash = hash &* 16_777_619
        }
        return hash
    }
}
