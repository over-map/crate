import SwiftUI

/// A BPM numeral, with "≈" before it when the beat is uncertain (`bpm_conf < 0.5`).
///
/// The prefix takes the numeral's colour (never a colour of its own). At hero and large sizes it is
/// regular weight and followed by a thin space; in smaller fonts it is tight ("≈139").
/// VoiceOver reads "about 139 BPM" (or "139 BPM").
public struct BPMText: View {
    private let value: Int
    private let approx: Bool
    private let font: Font
    private let large: Bool

    /// - Parameters:
    ///   - value: The detected BPM (filters keep using it even when uncertain).
    ///   - approx: Show "≈" (`bpm_conf < 0.5`).
    ///   - font: Usually `.numeralHero`, `.numeralLarge`, `.numeralMedium` or `.tagLine`.
    ///   - largeNumeral: Forces the hero/large treatment of "≈"; by default it is detected from `font`.
    public init(value: Int, approx: Bool, font: Font = .numeralHero, largeNumeral: Bool? = nil) {
        self.value = value
        self.approx = approx
        self.font = font
        self.large = largeNumeral ?? (font == .numeralHero || font == .numeralLarge)
    }

    /// Convenience for a CrateKit track: `approx` when `confidence < 0.5`.
    public init(value: Double, confidence: Double?, font: Font = .numeralHero) {
        self.init(value: Int(value.rounded()), approx: (confidence ?? 1) < 0.5, font: font)
    }

    public var body: some View {
        text
            .font(font)
            .monospacedDigit()
            .lineLimit(1)
            .fixedSize()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(approx ? "about \(value) BPM" : "\(value) BPM")
    }

    private var text: Text {
        let number = Text(verbatim: String(value))
        guard approx else { return number }
        if large {
            return Text("\(Text(verbatim: "≈").fontWeight(.regular))\u{2009}\(number)")
        }
        return Text("≈\(number)")
    }
}
