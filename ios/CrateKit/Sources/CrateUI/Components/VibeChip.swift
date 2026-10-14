import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// A vibe filter chip. Vibes have no colour; each has a user-picked SF Symbol.
///
/// - Off: `fillControl`, symbol, name, count (whole library, or "count if added" once filters are set).
/// - Selected: `accent` fill, "✓ Name" (the checkmark replaces the symbol, so selection changes shape too).
/// - Count 0: dimmed to 45 %, still tappable.
/// - No seeds yet: dashed outline, "＋ seeds"; tapping opens the editor.
///
/// Tap toggles (`.selection` haptic); long-press calls `onEdit`. Counts roll with `.numericText()`.
/// For the trailing "＋ New vibe" chip use ``AddChip``.
public struct VibeChip: View {
    private let name: String
    private let symbol: String
    private let count: Int?
    private let isSelected: Bool
    private let hasSeeds: Bool
    private let action: () -> Void
    private let onEdit: (() -> Void)?
    @State private var isPressed = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// - Parameters:
    ///   - name: Display name ("Hypnotic").
    ///   - symbol: SF Symbol name; falls back to `tag` when it doesn't exist (see `VibeSymbols.defaults`).
    ///   - count: Members, or "count if added"; `nil` hides it.
    ///   - isSelected: Selected state.
    ///   - hasSeeds: `false` draws the dashed "no seeds yet" chip.
    ///   - action: Tap (toggle, or open the editor when there are no seeds).
    ///   - onEdit: Long-press (open the vibe editor).
    public init(_ name: String, symbol: String, count: Int?, isSelected: Bool = false, hasSeeds: Bool = true,
                action: @escaping () -> Void, onEdit: (() -> Void)? = nil) {
        self.name = name
        self.symbol = symbol
        self.count = count
        self.isSelected = isSelected
        self.hasSeeds = hasSeeds
        self.action = action
        self.onEdit = onEdit
    }

    public var body: some View {
        chip
            .scaleEffect(isPressed && !reduceMotion ? 0.97 : 1)
            .opacity(isDimmed ? Metric.dimmed : 1)
            .animation(CrateMotion.animation(CrateMotion.chip, reduceMotion: reduceMotion), value: isSelected)
            .animation(CrateMotion.animation(CrateMotion.chip, reduceMotion: reduceMotion), value: isPressed)
            .onTapGesture(perform: action)
            .onLongPressGesture(minimumDuration: 0.5, perform: { onEdit?() }, onPressingChanged: { isPressed = $0 })
            .sensoryFeedback(.selection, trigger: isSelected)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityLabel)
            .accessibilityAddTraits(.isButton)
            .accessibilityAddTraits(isSelected ? .isSelected : [])
            .accessibilityHint(hasSeeds ? "" : "Opens the vibe editor")
            .accessibilityAction(.default, action)
            .modifier(OptionalAccessibilityAction(name: "Edit vibe", action: onEdit))
    }

    private var isDimmed: Bool { hasSeeds && !isSelected && count == 0 }

    @ViewBuilder private var chip: some View {
        if isSelected {
            HStack(spacing: Metric.chipInnerGap) {
                Image(systemName: "checkmark").font(.footnote.weight(.bold))
                Text(name)
            }
            .chipChrome(Color.accent, foreground: Color.onAccent)
        } else if !hasSeeds {
            HStack(spacing: Metric.chipInnerGap) {
                symbolImage
                Text(name)
                Text("＋ seeds").font(.footnote.weight(.medium)).foregroundStyle(Color.textTertiary)
            }
            .chipChrome(Color.clear, foreground: Color.textSecondary, outline: Color.fillControlStrong, dashed: true)
        } else {
            HStack(spacing: Metric.chipInnerGap) {
                symbolImage
                Text(name)
                if let count {
                    Text(count.formatted())
                        .font(.system(.footnote, design: .rounded, weight: .medium))
                        .foregroundStyle(Color.textTertiary)
                        .countTransition(count)
                }
            }
            .chipChrome(isPressed ? Color.fillControlActive : Color.fillControl, foreground: Color.textSecondary)
        }
    }

    private var symbolImage: some View {
        Image(systemName: resolvedSymbol).font(.footnote).symbolRenderingMode(.hierarchical)
    }

    private var resolvedSymbol: String {
        #if canImport(UIKit)
        UIImage(systemName: symbol) == nil ? "tag" : symbol
        #else
        symbol
        #endif
    }

    private var accessibilityLabel: String {
        var label = name
        if !hasSeeds { label += ", no seeds yet" } else if let count { label += ", \(count) tracks" }
        return label
    }
}

