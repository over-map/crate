import SwiftUI
import CrateKit

// MARK: - Badge

/// A Camelot key badge: the key's fill (`Color.camelot`, same in both modes) and black rounded text.
///
/// Sizes: ``Style/tagLine`` (18 pt, in tag lines), ``Style/small`` (20), ``Style/medium`` (28) and
/// ``Style/large`` (48, with the musical key name and optional confidence, for track detail).
/// `code == nil` draws the outlined "Any key" badge. `withCompatible` appends "+ 7A 9A 8B".
public struct CamelotBadge: View {
    /// Badge size.
    public enum Style: Sendable { case tagLine, small, medium, large }

    private let code: String?
    private let size: Style
    private let showsKeyName: Bool
    private let confidence: Double?
    private let withCompatible: Bool
    @ScaledMetric private var scale: CGFloat = 1

    /// - Parameters:
    ///   - code: A Camelot code ("8A"); `nil` = Any key.
    ///   - size: Badge size.
    ///   - showsKeyName: Adds the musical key ("A minor") beside the badge. Always on for `.large`.
    ///   - confidence: 0–1, shown under the key name at `.large` ("72% confidence").
    ///   - withCompatible: Appends the compatible keys chip.
    public init(code: String?, size: Style = .medium, showsKeyName: Bool = false, confidence: Double? = nil,
                withCompatible: Bool = false) {
        self.code = code
        self.size = size
        self.showsKeyName = showsKeyName || size == .large
        self.confidence = confidence
        self.withCompatible = withCompatible
    }

    private var key: Camelot? { code.flatMap { Camelot($0) } }

    public var body: some View {
        HStack(spacing: size == .large ? Space.s + Space.xxs : Space.xs) {
            if let key {
                badge(key)
                if withCompatible {
                    Text("+ " + key.compatible.dropFirst().map(\.code).joined(separator: " "))
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.textPrimary)
                        .padding(.horizontal, Space.s)
                        .frame(minHeight: height)
                        .background(Color.fillControl, in: RoundedRectangle(cornerRadius: Radius.badge, style: .continuous))
                }
                if showsKeyName {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(key.name).font(size == .large ? .headline : .footnote.weight(.medium))
                            .foregroundStyle(size == .large ? Color.textPrimary : Color.textSecondary)
                        if size == .large, let confidence {
                            Text("\(Int((confidence * 100).rounded()))% confidence")
                                .font(.footnote).foregroundStyle(Color.textSecondary)
                        }
                    }
                }
            } else {
                Text("Any key")
                    .font(size == .tagLine ? .caption2.weight(.semibold) : .chipLabel)
                    .foregroundStyle(Color.textSecondary)
                    .padding(.horizontal, Space.s + Space.xxs)
                    .frame(minHeight: height)
                    .overlay(RoundedRectangle(cornerRadius: Radius.badge, style: .continuous)
                        .strokeBorder(Color.textTertiary, lineWidth: Stroke.outline))
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
    }

    private var height: CGFloat {
        let base: CGFloat = switch size {
        case .tagLine: Metric.badgeTagLine
        case .small: Metric.badgeSmall
        case .medium: Metric.badgeMedium
        case .large: Metric.badgeLarge
        }
        return base * scale
    }

    private var font: Font {
        switch size {
        case .tagLine: .system(.caption2, design: .rounded, weight: .bold)
        case .small: .system(.caption, design: .rounded, weight: .bold)
        case .medium: .system(.subheadline, design: .rounded, weight: .bold)
        case .large: .system(.title, design: .rounded, weight: .bold)
        }
    }

    private func badge(_ key: Camelot) -> some View {
        Text(verbatim: key.code)
            .font(font)
            .monospacedDigit()
            .foregroundStyle(Color.onData)
            .lineLimit(1)
            .padding(.horizontal, size == .tagLine ? Space.xs + 1 : Space.s - Space.xxs)
            .frame(minWidth: height * (size == .large ? 4.0 / 3 : 1.43), minHeight: height)
            .background(Color.camelot(key.code),
                        in: RoundedRectangle(cornerRadius: size == .large ? Radius.chip : Radius.badge, style: .continuous))
    }

    private var accessibilityLabel: String {
        guard let key else { return "Any key" }
        var label = "Key \(key.code), \(key.name)"
        if withCompatible { label += ", with compatible keys " + key.compatible.dropFirst().map(\.code).joined(separator: ", ") }
        if let confidence { label += ", \(Int((confidence * 100).rounded())) percent confidence" }
        return label
    }
}

// MARK: - Wheel

/// The 24-segment Camelot key picker: A (minor) on the inner ring, B (major) on the outer ring, 12 at the top.
///
/// The selected segment gets a 3 pt `accent` ring. With `compatible` on, the selected key's compatible
/// keys (n±1 on the same ring, and the same number on the other ring) stay at full colour and the rest dim
/// to 25 %; with it off, only the selected key stays lit. Tapping the selected key clears it.
/// Each segment is a VoiceOver button ("8A, A minor").
public struct CamelotWheel: View {
    @Binding private var selection: String?
    private let compatible: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// - Parameters:
    ///   - selection: The selected code, or `nil` for any key.
    ///   - compatible: Whether compatible keys are included (and highlighted).
    public init(selection: Binding<String?>, compatible: Bool) {
        _selection = selection
        self.compatible = compatible
    }

    private var selectedKey: Camelot? { selection.flatMap { Camelot($0) } }

    public var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            let scale = side / Metric.wheelReference
            ZStack {
                ForEach(Camelot.all, id: \.self) { key in
                    segment(key, scale: scale, side: side)
                }
                centerLabel
            }
            .frame(width: side, height: side)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .aspectRatio(1, contentMode: .fit)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        .sensoryFeedback(.selection, trigger: selection)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Camelot wheel")
    }

    private func isLit(_ key: Camelot) -> Bool {
        guard let selectedKey else { return true }
        return compatible ? selectedKey.isCompatible(with: key) : key == selectedKey
    }

    private func segment(_ key: Camelot, scale: CGFloat, side: CGFloat) -> some View {
        let shape = WheelSegment(number: key.number, outer: key.isMajor)
        let selected = key == selectedKey
        let radii = key.isMajor ? Metric.wheelOuter : Metric.wheelInner
        let mid = (radii.lowerBound + radii.upperBound) / 2 * scale
        let angle = Angle.degrees(-90 + Double(key.number) * 30).radians
        return ZStack {
            shape.fill(Color.camelot(key.code))
            if selected {
                shape.stroke(Color.accent, lineWidth: Metric.wheelSelectedRing)
            }
            Text(verbatim: key.code)
                .font(.system(.footnote, design: .rounded, weight: .bold))
                .foregroundStyle(Color.onData)
                .minimumScaleFactor(0.5)
                .position(x: side / 2 + mid * cos(angle), y: side / 2 + mid * sin(angle))
        }
        .opacity(isLit(key) ? 1 : Metric.wheelDimmed)
        .contentShape(shape)
        .onTapGesture {
            withAnimation(CrateMotion.animation(CrateMotion.chip, reduceMotion: reduceMotion)) {
                selection = selected ? nil : key.code
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(key.code), \(key.name)")
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
        .accessibilityAction { selection = selected ? nil : key.code }
    }

    private var centerLabel: some View {
        VStack(spacing: Space.xxs) {
            if let selectedKey {
                Text(verbatim: selectedKey.code).font(.system(.title, design: .rounded, weight: .bold))
                    .foregroundStyle(Color.textPrimary)
                Text(selectedKey.name).font(.footnote.weight(.medium)).foregroundStyle(Color.textSecondary)
            } else {
                Text("Any key").font(.headline).foregroundStyle(Color.textSecondary)
            }
        }
        .accessibilityHidden(true)
    }
}

/// One ring segment of the wheel, on a 320 pt reference scaled to the rect.
struct WheelSegment: Shape {
    let number: Int
    let outer: Bool

    func path(in rect: CGRect) -> Path {
        let side = min(rect.width, rect.height)
        let scale = side / Metric.wheelReference
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let radii = outer ? Metric.wheelOuter : Metric.wheelInner
        let r0 = radii.lowerBound * scale, r1 = radii.upperBound * scale
        let mid = Angle.degrees(-90 + Double(number) * 30)
        // Half the gap stroke on each side, measured at the inner radius.
        let inset = Angle.radians(Double(Metric.wheelGap / 2 / r0))
        let a0 = mid - .degrees(15) + inset, a1 = mid + .degrees(15) - inset
        var path = Path()
        path.addArc(center: center, radius: r1, startAngle: a0, endAngle: a1, clockwise: false)
        path.addArc(center: center, radius: r0, startAngle: a1, endAngle: a0, clockwise: true)
        path.closeSubpath()
        return path
    }
}
