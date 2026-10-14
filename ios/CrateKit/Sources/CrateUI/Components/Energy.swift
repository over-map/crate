import SwiftUI

// MARK: - Display meter

/// A track's energy (1–10) as a meter plus the numeral.
///
/// - ``Style/display``: the numeral and 10 blocks (18 pt, energy ramp, unlit `fillControl`), for track detail.
/// - ``Style/micro``: a 10-tick micro meter (2 × 9 pt ticks in the level's colour) and the value,
///   for the tag line. Replaces the brief's ⚡ glyph.
public struct EnergyMeter: View {
    /// Size of the meter.
    public enum Style: Sendable { case display, micro }

    private let level: Int
    private let style: Style
    private let numeralFont: Font

    /// - Parameters:
    ///   - level: 1–10 (clamped).
    ///   - style: Display or micro.
    ///   - numeralFont: Font of the numeral in the display style.
    public init(level: Int, style: Style = .display, numeralFont: Font = .numeralHero) {
        self.level = min(10, max(1, level))
        self.style = style
        self.numeralFont = numeralFont
    }

    public var body: some View {
        Group {
            switch style {
            case .display:
                HStack(spacing: Space.m + Space.xxs) {
                    Text(verbatim: String(level)).font(numeralFont).foregroundStyle(Color.textPrimary)
                        .countTransition(level)
                    HStack(spacing: Metric.displayBlockGap) {
                        ForEach(1...10, id: \.self) { i in
                            RoundedRectangle(cornerRadius: Metric.displayBlockRadius, style: .continuous)
                                .fill(i <= level ? Color.energy(i) : Color.fillControl)
                                .frame(height: Metric.displayBlockHeight)
                        }
                    }
                }
            case .micro:
                HStack(spacing: Space.xs) {
                    EnergyMicroMeter(level: level)
                    Text(verbatim: String(level))
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Energy \(level) out of 10")
    }
}

/// Just the 10 ticks of the micro meter (no numeral).
struct EnergyMicroMeter: View {
    let level: Int
    @ScaledMetric(relativeTo: .footnote) private var tickHeight = Metric.microTickHeight

    var body: some View {
        HStack(spacing: Metric.microTickGap) {
            ForEach(1...10, id: \.self) { i in
                RoundedRectangle(cornerRadius: Metric.microTickWidth / 2, style: .continuous)
                    .fill(i <= level ? Color.energy(level) : Color.fillControlStrong)
                    .frame(width: Metric.microTickWidth, height: tickHeight)
            }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Range input

/// The Build energy filter: 10 blocks, 26 pt tall. Drag across the blocks to set a range; tap a block
/// to pick just that level (tap it again for Any).
///
/// `1...10` means Any: blocks are unlit and numbered. Lit blocks use the energy ramp and animate in with
/// `.easeOut(0.12)` staggered 20 ms. Every step plays a `.selection` haptic. VoiceOver exposes the
/// minimum and maximum as two adjustable elements ("Energy from 6 to 10 out of 10").
public struct EnergyRangeControl: View {
    @Binding private var range: ClosedRange<Int>
    @State private var dragAnchor: Int?
    @State private var rangeAtStart: ClosedRange<Int>?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// The whole scale; equal to "Any".
    public static let any: ClosedRange<Int> = 1...10

    /// - Parameter range: The selected range within 1...10.
    public init(range: Binding<ClosedRange<Int>>) {
        _range = range
    }

    private var isAny: Bool { range == Self.any }

    public var body: some View {
        GeometryReader { geo in
            HStack(spacing: Metric.inputBlockGap) {
                ForEach(1...10, id: \.self) { i in block(i) }
            }
            .minimumHitTarget(visibleHeight: blockHeight)
            .gesture(drag(width: geo.size.width))
        }
        .frame(height: blockHeight)
        .sensoryFeedback(.selection, trigger: range)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Energy")
        .accessibilityValue(isAny ? "Any" : "from \(range.lowerBound) to \(range.upperBound) out of 10")
        .accessibilityAdjustableAction { direction in
            // One-element fallback: moves the whole range. The two thumbs are below.
            switch direction {
            case .increment where range.upperBound < 10: range = (range.lowerBound + 1)...(range.upperBound + 1)
            case .decrement where range.lowerBound > 1: range = (range.lowerBound - 1)...(range.upperBound - 1)
            default: break
            }
        }
        .accessibilityAction(named: Text("Raise minimum")) { setLower(range.lowerBound + 1) }
        .accessibilityAction(named: Text("Lower minimum")) { setLower(range.lowerBound - 1) }
        .accessibilityAction(named: Text("Raise maximum")) { setUpper(range.upperBound + 1) }
        .accessibilityAction(named: Text("Lower maximum")) { setUpper(range.upperBound - 1) }
        .accessibilityAction(named: Text("Any energy")) { range = Self.any }
    }

    private var blockHeight: CGFloat {
        dynamicTypeSize.isAccessibilitySize ? Size.minTarget : Size.energyBlock
    }

    private func block(_ i: Int) -> some View {
        let lit = !isAny && range.contains(i)
        return RoundedRectangle(cornerRadius: Radius.block, style: .continuous)
            .fill(lit ? Color.energy(i) : Color.fillControl)
            .overlay {
                if isAny {
                    Text(verbatim: String(i))
                        .font(.system(.caption2, design: .rounded, weight: .semibold))
                        .foregroundStyle(Color.textTertiary)
                        .minimumScaleFactor(0.6)
                }
            }
            .frame(height: blockHeight)
            .animation(reduceMotion ? CrateMotion.reduced
                                    : CrateMotion.energy.delay(Double(i - 1) * CrateMotion.energyStagger),
                       value: lit)
    }

    private func index(at x: CGFloat, width: CGFloat) -> Int {
        guard width > 0 else { return 1 }
        return min(10, max(1, Int((x / width) * 10) + 1))
    }

    private func drag(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                let anchor = dragAnchor ?? index(at: value.startLocation.x, width: width)
                if dragAnchor == nil { dragAnchor = anchor; rangeAtStart = range }
                let current = index(at: value.location.x, width: width)
                let next = min(anchor, current)...max(anchor, current)
                if next != range { range = next }
            }
            .onEnded { value in
                defer { dragAnchor = nil; rangeAtStart = nil }
                let i = index(at: value.location.x, width: width)
                // A tap on the only lit block clears back to Any.
                if index(at: value.startLocation.x, width: width) == i, rangeAtStart == i...i { range = Self.any }
            }
    }

    private func setLower(_ value: Int) {
        let v = min(range.upperBound, max(1, value))
        range = v...range.upperBound
    }

    private func setUpper(_ value: Int) {
        let v = max(range.lowerBound, min(10, value))
        range = range.lowerBound...v
    }
}
