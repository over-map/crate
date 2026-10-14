import SwiftUI

// MARK: - Panel label

/// The small uppercase SF Mono label above a control group ("GENRE & STYLE", "VIBE · ALL OF").
///
/// An optional trailing label sits on the right in `textTertiary` ("IF ADDED · ALL").
/// VoiceOver treats the label as a header.
public struct PanelLabel: View {
    private let text: String
    private let trailing: String?

    /// - Parameters:
    ///   - text: The label. It is uppercased for display.
    ///   - trailing: Optional secondary label, right-aligned.
    public init(_ text: String, trailing: String? = nil) {
        self.text = text
        self.trailing = trailing
    }

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    public var body: some View {
        // At accessibility sizes the two labels don't fit side by side: stack them instead of wrapping both.
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: Space.xxs))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: Space.s))
        layout {
            Text(text).panelLabelStyle()
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if let trailing {
                if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: Space.s) }
                Text(trailing).foregroundStyle(Color.textTertiary).panelLabelStyle()
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

// MARK: - Card

/// Level-1 surface: `bgCard`, a 1 pt inset `strokeCard` hairline, `Radius.card` continuous corners.
public struct CrateCardModifier: ViewModifier {
    public init() {}

    public func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
        content
            .background(Color.bgCard, in: shape)
            .overlay(shape.strokeBorder(Color.strokeCard, lineWidth: Stroke.hairline))
    }
}

public extension View {
    /// Draws the view on a Crate card (`bgCard`, 1 pt `strokeCard` inset, radius 10). Add padding first.
    func crateCard() -> some View { modifier(CrateCardModifier()) }
}

// MARK: - Motion

/// Animation tokens (`tokens.json → motion`). Use ``CrateMotion/animation(_:reduceMotion:)`` so
/// Reduce Motion falls back to a 0.2 s crossfade.
public enum CrateMotion {
    /// Count ticker: `.snappy(duration: 0.35)`.
    public static let count = Animation.snappy(duration: 0.35)
    /// Chip state: `.snappy(duration: 0.18)`.
    public static let chip = Animation.snappy(duration: 0.18)
    /// Send success morph: `.spring(duration: 0.4, bounce: 0.15)`.
    public static let morph = Animation.spring(duration: 0.4, bounce: 0.15)
    /// Energy blocks light up with `.easeOut(duration: 0.12)`.
    public static let energy = Animation.easeOut(duration: 0.12)
    /// Delay between two energy blocks.
    public static let energyStagger: Double = 0.02
    /// Cover crossfade.
    public static let cover = Animation.easeInOut(duration: 0.25)
    /// Reduce Motion replacement: a 0.2 s crossfade.
    public static let reduced = Animation.easeInOut(duration: 0.2)

    /// `animation`, or the Reduce Motion crossfade.
    public static func animation(_ animation: Animation, reduceMotion: Bool) -> Animation {
        reduceMotion ? reduced : animation
    }
}

struct CountTransitionModifier: ViewModifier {
    let value: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .contentTransition(reduceMotion ? .opacity : .numericText(value: value))
            .animation(CrateMotion.animation(CrateMotion.count, reduceMotion: reduceMotion), value: value)
    }
}

public extension View {
    /// Rolls a count with `.numericText()` and `.snappy(0.35)`; crossfades with Reduce Motion.
    func countTransition(_ value: Int) -> some View { modifier(CountTransitionModifier(value: Double(value))) }
}

// MARK: - Hit target

struct MinimumHitTargetModifier: ViewModifier {
    let visibleHeight: CGFloat

    func body(content: Content) -> some View {
        let extra = max(0, (Size.minTarget - visibleHeight) / 2)
        content
            .padding(.vertical, extra)
            .contentShape(Rectangle())
            .padding(.vertical, -extra)
    }
}

extension View {
    /// Grows the tappable area to `Size.minTarget` without changing layout.
    func minimumHitTarget(visibleHeight: CGFloat) -> some View {
        modifier(MinimumHitTargetModifier(visibleHeight: visibleHeight))
    }
}

// MARK: - Chip chrome

/// Shared look of every 32 pt chip: height (44 at accessibility sizes), padding, continuous radius.
struct ChipChrome<Fill: ShapeStyle>: ViewModifier {
    let fill: Fill
    var foreground: Color
    var outline: Color? = nil
    var dashed = false
    var horizontalPadding: CGFloat = Metric.chipPaddingH
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: Radius.chip, style: .continuous)
        let height = dynamicTypeSize.isAccessibilitySize ? Size.chipHeightAX : Size.chipHeight
        content
            .font(.chipLabel)
            .foregroundStyle(foreground)
            .lineLimit(dynamicTypeSize.isAccessibilitySize ? 3 : 1)
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, Space.xs)
            .frame(minHeight: height)
            .background(fill, in: shape)
            .overlay {
                if let outline {
                    shape.strokeBorder(outline, style: StrokeStyle(lineWidth: dashed ? Stroke.hairline : Stroke.outline,
                                                                   dash: dashed ? [Space.xs, Space.xs - Stroke.hairline] : []))
                }
            }
            .contentShape(shape)
            .minimumHitTarget(visibleHeight: height)
    }
}

public extension View {
    /// The shared chip look: 32 pt (44 at accessibility sizes), `Radius.chip`, chip label font, 44 pt hit target.
    /// Use it for app-specific chips so they match the CrateUI ones.
    func chipChrome<S: ShapeStyle>(_ fill: S, foreground: Color, outline: Color? = nil, dashed: Bool = false,
                                    horizontalPadding: CGFloat? = nil) -> some View {
        modifier(ChipChrome(fill: fill, foreground: foreground, outline: outline, dashed: dashed,
                            horizontalPadding: horizontalPadding ?? Metric.chipPaddingH))
    }
}

/// Press feedback for chips: slight scale, and `fillControlActive` for off chips (via `pressedOverlay`).
struct ChipButtonStyle: ButtonStyle {
    var pressedOverlay: Color? = nil
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .overlay {
                if configuration.isPressed, let pressedOverlay {
                    RoundedRectangle(cornerRadius: Radius.chip, style: .continuous).fill(pressedOverlay)
                        .allowsHitTesting(false)
                }
            }
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.97 : 1)
            .animation(CrateMotion.animation(CrateMotion.chip, reduceMotion: reduceMotion), value: configuration.isPressed)
    }
}

// MARK: - Flow layout

/// Wrapping row for chips: lays children left to right and wraps, `Space.chipGap` apart by default.
public struct ChipFlow: Layout {
    private let spacing: CGFloat
    private let lineSpacing: CGFloat
    private let alignment: VerticalAlignment

    /// - Parameters:
    ///   - spacing: Gap between chips on a line.
    ///   - lineSpacing: Gap between lines.
    ///   - alignment: Vertical alignment of chips within a line.
    public init(spacing: CGFloat = Space.chipGap, lineSpacing: CGFloat = Space.chipGap,
                alignment: VerticalAlignment = .center) {
        self.spacing = spacing
        self.lineSpacing = lineSpacing
        self.alignment = alignment
    }

    public func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let lines = arrange(proposal: proposal, subviews: subviews)
        let width = lines.map(\.width).max() ?? 0
        let height = lines.map(\.height).reduce(0, +) + lineSpacing * CGFloat(max(0, lines.count - 1))
        return CGSize(width: proposal.width ?? width, height: height)
    }

    public func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for line in arrange(proposal: ProposedViewSize(width: bounds.width, height: nil), subviews: subviews) {
            var x = bounds.minX
            for item in line.items {
                let size = item.size
                let dy: CGFloat = switch alignment {
                case .top: 0
                case .bottom: line.height - size.height
                default: (line.height - size.height) / 2
                }
                subviews[item.index].place(at: CGPoint(x: x, y: y + dy), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
            y += line.height + lineSpacing
        }
    }

    private struct Line { var items: [(index: Int, size: CGSize)] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(proposal: ProposedViewSize, subviews: Subviews) -> [Line] {
        let maxWidth = proposal.width ?? .infinity
        var lines: [Line] = [Line()]
        for index in subviews.indices {
            var size = subviews[index].sizeThatFits(.unspecified)
            if size.width > maxWidth {
                size = subviews[index].sizeThatFits(ProposedViewSize(width: maxWidth, height: nil))
            }
            let needed = lines[lines.count - 1].items.isEmpty ? size.width : lines[lines.count - 1].width + spacing + size.width
            if needed > maxWidth, !lines[lines.count - 1].items.isEmpty {
                lines.append(Line())
            }
            var line = lines[lines.count - 1]
            line.width = line.items.isEmpty ? size.width : line.width + spacing + size.width
            line.height = max(line.height, size.height)
            line.items.append((index, size))
            lines[lines.count - 1] = line
        }
        return lines.filter { !$0.items.isEmpty }
    }
}

// MARK: - "OR" / "ALL OF" separators

/// The mono "OR" between genre chips.
public struct ChipSeparator: View {
    private let text: String
    /// - Parameter text: Usually "OR".
    public init(_ text: String = "OR") { self.text = text }
    public var body: some View {
        Text(text).panelLabelStyle().accessibilityHidden(true)
    }
}
