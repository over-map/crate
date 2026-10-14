import SwiftUI
import CrateKit

// MARK: - Style chip

/// A style (or genre) chip, always "Style · Genre" (design principle 6).
///
/// Variants:
/// - ``Variant/selected``: the style's family shade fill (`Color.family(_:shade:)`), black text.
/// - ``Variant/off(count:)``: `fillControl`, a hue dot and the track count (Most used, filters).
/// - ``Variant/removed``: a style removed from a track in Edit tags, drawn as a 1.5 pt
///   `Color.familyOutline` outline: "− Tech House  Undo".
public struct StyleChip: View {
    /// How the chip is drawn.
    public enum Variant: Equatable, Sendable {
        /// Picked: family shade fill.
        case selected
        /// Not picked: `fillControl`, hue dot and optional count.
        case off(count: Int?)
        /// Removed from a track (Edit tags), with an Undo action.
        case removed
    }

    private let style: String
    private let genre: String?
    private let family: Family
    private let shade: StyleShade
    private let variant: Variant
    private let edited: Bool
    private let action: (() -> Void)?
    private let onRemove: (() -> Void)?
    private let onUndo: (() -> Void)?

    /// - Parameters:
    ///   - style: Style display name ("Deep House").
    ///   - genre: Parent shown after a "·" ("House"); `nil` hides it.
    ///   - family: The family whose hue the chip takes.
    ///   - shade: Which of the family's three shades; use ``StyleChip/init(style:canonical:genre:family:variant:edited:action:onRemove:onUndo:)``
    ///     to derive it from the canonical label.
    ///   - variant: Selected, off or removed.
    ///   - edited: Shows a ✎ after the name (style added by the user).
    ///   - action: Tap (opens the picker at this genre). `nil` makes the chip static.
    ///   - onRemove: Adds a trailing × that removes the chip.
    ///   - onUndo: The Undo action of the removed variant.
    public init(style: String, genre: String? = nil, family: Family, shade: StyleShade = .base,
                variant: Variant = .selected, edited: Bool = false, action: (() -> Void)? = nil,
                onRemove: (() -> Void)? = nil, onUndo: (() -> Void)? = nil) {
        self.style = style
        self.genre = genre
        self.family = family
        self.shade = shade
        self.variant = variant
        self.edited = edited
        self.action = action
        self.onRemove = onRemove
        self.onUndo = onUndo
    }

    /// Same, with the shade picked by `StyleShade.of(canonical)` (stable FNV-1a).
    public init(style: String, canonical: String, genre: String? = nil, family: Family, variant: Variant = .selected,
                edited: Bool = false, action: (() -> Void)? = nil, onRemove: (() -> Void)? = nil,
                onUndo: (() -> Void)? = nil) {
        self.init(style: style, genre: genre, family: family, shade: StyleShade.of(canonical), variant: variant,
                  edited: edited, action: action, onRemove: onRemove, onUndo: onUndo)
    }

    public var body: some View {
        Group {
            if let action {
                Button(action: action) { chip }
                    .buttonStyle(ChipButtonStyle(pressedOverlay: pressedOverlay))
            } else {
                chip
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityAddTraits(variant == .selected ? .isSelected : [])
        .accessibilityAddTraits(action != nil ? .isButton : [])
        .modifier(OptionalAccessibilityAction(name: "Remove", action: onRemove))
        .modifier(OptionalAccessibilityAction(name: "Undo", action: onUndo))
    }

    private var pressedOverlay: Color? {
        if case .off = variant { return Color.fillControlActive.opacity(0.6) }
        return Color.onData.opacity(0.08)
    }

    @ViewBuilder private var chip: some View {
        switch variant {
        case .selected:
            content(textColor: Color.onData)
                .chipChrome(Color.family(family, shade: shade), foreground: Color.onData)
        case let .off(count):
            HStack(spacing: Space.chipGap) {
                Circle().fill(Color.family(family)).frame(width: Metric.styleDot, height: Metric.styleDot)
                Text(style)
                if let count {
                    Text(count.formatted())
                        .font(.system(.footnote, design: .rounded, weight: .medium))
                        .foregroundStyle(Color.textSecondary)
                        .countTransition(count)
                }
            }
            .chipChrome(Color.fillControl, foreground: Color.textPrimary)
        case .removed:
            HStack(spacing: Metric.chipInnerGap) {
                Text("− \(style)")
                if let onUndo {
                    Button(action: onUndo) {
                        Text("Undo").fontWeight(.regular).underline()
                    }
                    .buttonStyle(.plain)
                }
            }
            .chipChrome(Color.clear, foreground: Color.familyOutline(family), outline: Color.familyOutline(family))
        }
    }

    private func content(textColor: Color) -> some View {
        HStack(spacing: Metric.chipInnerGap) {
            Text(style)
            if let genre {
                Text("· \(genre)")
                    .font(.footnote)
                    .opacity(0.7)
            }
            if edited {
                Image(systemName: "pencil").font(.caption)
            }
            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "xmark").font(.caption.weight(.semibold)).opacity(0.6)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var accessibilityLabel: String {
        var parts = [style]
        if let genre { parts.append(genre) }
        if edited { parts.append("edited") }
        switch variant {
        case let .off(count?): parts.append("\(count) tracks")
        case .removed: parts.append("removed")
        default: break
        }
        return parts.joined(separator: ", ")
    }
}

// MARK: - Genre summary chip

/// One chip summarising a picked genre's ticked styles: "House · all styles",
/// "House · except Garage House", or "Electronic · 24 of 61 styles". Family base fill, black text.
///
/// When only 1–3 styles are ticked, show one ``StyleChip`` per style instead; ``GenreSummary/make(ticked:all:)``
/// decides which.
public struct GenreSummaryChip: View {
    private let family: Family
    private let name: String
    private let summary: GenreSummary
    private let action: (() -> Void)?
    private let onRemove: (() -> Void)?

    /// - Parameters:
    ///   - family: The picked family.
    ///   - name: Display name; defaults to `family.displayName`.
    ///   - summary: What the chip says after the "·".
    ///   - action: Tap opens the picker at this genre.
    ///   - onRemove: Adds a trailing × that removes the genre.
    public init(family: Family, name: String? = nil, summary: GenreSummary, action: (() -> Void)? = nil,
                onRemove: (() -> Void)? = nil) {
        self.family = family
        self.name = name ?? family.displayName
        self.summary = summary
        self.action = action
        self.onRemove = onRemove
    }

    public var body: some View {
        let chip = HStack(spacing: Metric.chipInnerGap) {
            Text(name)
            Text("· \(summary.text)").font(.footnote).opacity(0.7)
            if let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "xmark").font(.caption.weight(.semibold)).opacity(0.6)
                }
                .buttonStyle(.plain)
            }
        }
        .chipChrome(Color.family(family), foreground: Color.onData)

        Group {
            if let action {
                Button(action: action) { chip }.buttonStyle(ChipButtonStyle(pressedOverlay: Color.onData.opacity(0.08)))
            } else {
                chip
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(name), \(summary.text)")
        .accessibilityAddTraits(action != nil ? [.isButton, .isSelected] : .isSelected)
        .modifier(OptionalAccessibilityAction(name: "Remove", action: onRemove))
    }
}

/// How a picked genre's ticked styles are summarised.
public enum GenreSummary: Equatable, Sendable {
    /// Every style ticked.
    case allStyles
    /// All but these (1–3) ticked.
    case except([String])
    /// `ticked` of `total` ticked.
    case count(ticked: Int, total: Int)
    /// 1–3 ticked: show these as separate style chips.
    case styles([String])

    /// Chip text after the "·".
    public var text: String {
        switch self {
        case .allStyles: "all styles"
        case let .except(names): "except " + names.joined(separator: ", ")
        case let .count(ticked, total): "\(ticked) of \(total) styles"
        case let .styles(names): names.joined(separator: ", ")
        }
    }

    /// The design-system rule: all → `.allStyles`; 1–3 unticked → `.except`; 1–3 ticked → `.styles`;
    /// otherwise `.count`. `all` is the genre's styles in display order.
    public static func make(ticked: Set<String>, all: [String]) -> GenreSummary {
        let unticked = all.filter { !ticked.contains($0) }
        let tickedInOrder = all.filter { ticked.contains($0) }
        if unticked.isEmpty { return .allStyles }
        if unticked.count <= 3 { return .except(unticked) }
        if (1...3).contains(tickedInOrder.count) { return .styles(tickedInOrder) }
        return .count(ticked: tickedInOrder.count, total: all.count)
    }
}

// MARK: - Family mini chip

/// The small 20 pt family chip used in Weekly rows, vote tables and vibe previews:
/// family base fill, radius 5, 12 pt semibold black.
public struct FamilyMiniChip: View {
    private let family: Family
    private let title: String

    /// - Parameters:
    ///   - family: The family.
    ///   - title: Text; defaults to the family's display name.
    public init(_ family: Family, title: String? = nil) {
        self.family = family
        self.title = title ?? family.displayName
    }

    public var body: some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundStyle(Color.onData)
            .lineLimit(1)
            .padding(.horizontal, Metric.miniChipPaddingH)
            .frame(minHeight: Metric.miniChipHeight)
            .background(Color.family(family), in: RoundedRectangle(cornerRadius: Radius.badge, style: .continuous))
            .accessibilityLabel("Genre \(title)")
    }
}

// MARK: - Family dot

/// The 10 pt hue dot used in picker rows and Library lists.
public struct FamilyDot: View {
    private let family: Family
    private let shade: StyleShade
    private let size: CGFloat

    /// - Parameters:
    ///   - family: The family.
    ///   - shade: Which shade (styles use their own shade).
    ///   - large: `true` for the 10 pt picker dot, `false` for the 8 pt chip dot.
    public init(_ family: Family, shade: StyleShade = .base, large: Bool = true) {
        self.family = family
        self.shade = shade
        self.size = large ? Metric.familyDot : Metric.styleDot
    }

    public var body: some View {
        Circle().fill(Color.family(family, shade: shade)).frame(width: size, height: size).accessibilityHidden(true)
    }
}

// MARK: - Add chip

/// A plain `fillControl` chip with a "＋" title: "＋ Style", "＋ Add", "＋ New vibe".
public struct AddChip: View {
    private let title: String
    private let action: () -> Void

    /// - Parameters:
    ///   - title: Text after the plus ("New vibe").
    ///   - action: Tap.
    public init(_ title: String, action: @escaping () -> Void) {
        self.title = title
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(spacing: Space.xs) {
                Image(systemName: "plus").font(.footnote.weight(.semibold))
                Text(title)
            }
            .chipChrome(Color.fillControl, foreground: Color.textPrimary)
        }
        .buttonStyle(ChipButtonStyle(pressedOverlay: Color.fillControlActive.opacity(0.6)))
        .accessibilityLabel(title)
    }
}

// MARK: - Helpers

struct OptionalAccessibilityAction: ViewModifier {
    let name: String
    let action: (() -> Void)?

    func body(content: Content) -> some View {
        if let action {
            content.accessibilityAction(named: Text(name), action)
        } else {
            content
        }
    }
}
