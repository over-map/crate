import Foundation

/// A key on the Camelot wheel: number 1–12 around the circle of fifths, letter A (minor) or B (major).
public struct Camelot: Hashable, Sendable, Comparable, CustomStringConvertible {
    /// Wheel position, 1–12.
    public let number: Int
    /// `true` for the B ring (major), `false` for A (minor).
    public let isMajor: Bool

    /// Creates a key from its parts; `nil` unless `number` is 1–12.
    public init?(number: Int, isMajor: Bool) {
        guard (1...12).contains(number) else { return nil }
        self.number = number; self.isMajor = isMajor
    }

    /// Parses a code such as `8A` or `12B`. Surrounding spaces and lowercase letters are accepted.
    public init?(_ code: String) {
        let text = code.trimmingCharacters(in: .whitespaces).uppercased()
        guard let letter = text.last, letter == "A" || letter == "B" else { return nil }
        let digits = text.dropLast()
        guard (1...2).contains(digits.count), digits.allSatisfy(\.isASCII), digits.first != "0",
              let n = Int(digits) else { return nil }
        self.init(number: n, isMajor: letter == "B")
    }

    /// Converts a musical key such as `("A", "minor")` or `("Bb", "major")`. Accepts `#`/`♯` and `b`/`♭`,
    /// and scales `minor`/`min`/`m` or `major`/`maj`.
    public init?(tonic: String, scale: String) {
        guard let pitch = Camelot.pitchClass(of: tonic) else { return nil }
        let s = scale.trimmingCharacters(in: .whitespaces).lowercased()
        let major: Bool
        if s.hasPrefix("maj") { major = true } else if s.hasPrefix("min") || s == "m" { major = false } else { return nil }
        // C major is 8B; each step round the circle of fifths (+7 semitones) adds 1.
        // A minor key shares its number with its relative major (three semitones up).
        let majorRoot = major ? pitch : (pitch + 3) % 12
        let fifths = (majorRoot * 7) % 12
        let n = (8 - 1 + fifths) % 12 + 1
        self.init(number: n, isMajor: major)
    }

    /// The code, e.g. `8A`.
    public var code: String { "\(number)\(isMajor ? "B" : "A")" }
    public var description: String { code }

    /// Display name, e.g. `A minor`. Spelling follows the prototype: flats, except `F♯ minor` (11A) and
    /// `F♯ major` (2B).
    public var name: String {
        let minor = ["A♭", "E♭", "B♭", "F", "C", "G", "D", "A", "E", "B", "F♯", "D♭"]
        let major = ["B", "F♯", "D♭", "A♭", "E♭", "B♭", "F", "C", "G", "D", "A", "E"]
        return "\((isMajor ? major : minor)[number - 1]) \(isMajor ? "major" : "minor")"
    }

    /// The key itself, its two neighbours on the same ring (12 wraps to 1) and the relative key on the other ring.
    public var compatible: [Camelot] {
        let down = (number + 10) % 12 + 1
        let up = number % 12 + 1
        return [self,
                Camelot(number: down, isMajor: isMajor)!,
                Camelot(number: up, isMajor: isMajor)!,
                Camelot(number: number, isMajor: !isMajor)!]
    }

    /// True when `other` is in ``compatible``.
    public func isCompatible(with other: Camelot) -> Bool { compatible.contains(other) }

    /// True for a valid Camelot code.
    public static func isValid(_ code: String) -> Bool { Camelot(code) != nil }

    /// All 24 keys, 1A…12A then 1B…12B.
    public static let all: [Camelot] = [false, true].flatMap { major in
        (1...12).map { Camelot(number: $0, isMajor: major)! }
    }

    public static func < (a: Camelot, b: Camelot) -> Bool {
        (a.number, a.isMajor ? 1 : 0) < (b.number, b.isMajor ? 1 : 0)
    }

    /// Pitch class (C = 0) of a tonic spelled with sharps or flats.
    static func pitchClass(of tonic: String) -> Int? {
        let t = tonic.trimmingCharacters(in: .whitespaces)
        guard let letter = t.first?.uppercased() else { return nil }
        let naturals: [String: Int] = ["C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11]
        guard var pc = naturals[letter] else { return nil }
        for accidental in t.dropFirst() {
            switch accidental {
            case "#", "♯": pc += 1
            case "b", "♭": pc -= 1
            default: return nil
            }
        }
        return (pc % 12 + 12) % 12
    }
}
