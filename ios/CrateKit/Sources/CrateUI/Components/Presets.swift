import SwiftUI
import CrateKit

// MARK: - Preset tile

/// A preset's identity tile: its `PresetColor` fill with the preset symbol in black
/// (`onAccent` on the ink colour). 32 pt in the Presets manager.
public struct PresetTile: View {
    private let color: PresetColor
    private let symbol: String
    private let size: CGFloat

    /// - Parameters:
    ///   - color: The preset colour.
    ///   - symbol: SF Symbol name.
    ///   - size: Side length (default 32 pt).
    public init(color: PresetColor, symbol: String, size: CGFloat? = nil) {
        self.color = color
        self.symbol = symbol
        self.size = size ?? Metric.presetTile
    }

    /// From a CrateKit preset (unknown colours fall back to ink).
    public init(_ preset: Preset, size: CGFloat? = nil) {
        self.init(color: PresetColor(rawValue: preset.color) ?? .ink, symbol: preset.icon, size: size)
    }

    public var body: some View {
        RoundedRectangle(cornerRadius: size * Radius.chip / Metric.presetTile, style: .continuous)
            .fill(color.tile)
            .frame(width: size, height: size)
            .overlay {
                Image(systemName: symbol)
                    .font(.system(size: size * 0.5, weight: .semibold))
                    .foregroundStyle(color.onTile)
            }
            .accessibilityHidden(true)
    }
}

// MARK: - Preset pill

/// A preset in the Build pill row: small colour tile + name.
///
/// - Idle: `fillControl`, `textPrimary`.
/// - Selected (loaded): `accent` fill, `onAccent` text.
/// - Modified (loaded, then changed): idle fill with a 1.5 pt `textTertiary` outline and "MODIFIED".
///
/// Tap loads the preset. Attach a `.contextMenu` for the long-press menu (Send now · Edit · Add to
/// widget · Delete) and `.sensoryFeedback(.impact(weight: .medium), …)` for its haptic.
public struct PresetPill: View {
    private let name: String
    private let color: PresetColor
    private let symbol: String
    private let isSelected: Bool
    private let isModified: Bool
    private let action: () -> Void

    /// - Parameters:
    ///   - name: Preset name.
    ///   - color: Tile colour.
    ///   - symbol: Tile symbol.
    ///   - isSelected: Loaded and unchanged.
    ///   - isModified: Loaded, then changed.
    ///   - action: Tap.
    public init(_ name: String, color: PresetColor, symbol: String, isSelected: Bool = false,
                isModified: Bool = false, action: @escaping () -> Void) {
        self.name = name
        self.color = color
        self.symbol = symbol
        self.isSelected = isSelected
        self.isModified = isModified
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(spacing: Space.s - Space.xxs) {
                if isSelected && color == .ink {
                    // The ink tile would vanish on the accent fill, so it inverts.
                    RoundedRectangle(cornerRadius: Radius.badge, style: .continuous)
                        .fill(Color.onAccent)
                        .frame(width: Metric.presetPillTile, height: Metric.presetPillTile)
                        .overlay {
                            Image(systemName: symbol)
                                .font(.system(size: Metric.presetPillTile * 0.5, weight: .semibold))
                                .foregroundStyle(Color.accent)
                        }
                        .accessibilityHidden(true)
                } else {
                    PresetTile(color: color, symbol: symbol, size: Metric.presetPillTile)
                }
                Text(name).font(isSelected ? .chipLabel : .subheadline.weight(.medium))
                if isModified && !isSelected {
                    Text("MODIFIED").font(.panelLabel).foregroundStyle(Color.textSecondary)
                }
            }
            .chipChrome(isSelected ? Color.accent : Color.fillControl,
                        foreground: isSelected ? Color.onAccent : Color.textPrimary,
                        outline: isModified && !isSelected ? Color.textTertiary : nil,
                        horizontalPadding: Space.m - Space.xxs)
        }
        .buttonStyle(ChipButtonStyle(pressedOverlay: isSelected ? Color.onAccent.opacity(0.1) : Color.fillControlActive.opacity(0.6)))
        .sensoryFeedback(.selection, trigger: isSelected)
        .accessibilityLabel(name)
        .accessibilityValue(isModified ? "Modified" : "")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

// MARK: - Tick ring

/// The 24 pt tri-state tick used in the Genre & style picker: empty ring / ✓ (all) / – (some).
public struct TickRing: View {
    /// Tick state.
    public enum TickState: Sendable { case empty, checked, mixed }

    private let state: TickState
    @ScaledMetric(relativeTo: .body) private var size = Metric.tickRing

    /// - Parameter state: Empty, checked or mixed.
    public init(_ state: TickState) { self.state = state }

    /// Two-state convenience.
    public init(isOn: Bool) { self.state = isOn ? .checked : .empty }

    public var body: some View {
        ZStack {
            switch state {
            case .empty:
                Circle().strokeBorder(Color.textTertiary, lineWidth: Stroke.outline)
            case .checked, .mixed:
                Circle().fill(Color.accent)
                Image(systemName: state == .checked ? "checkmark" : "minus")
                    .font(.system(size: size * 0.5, weight: .bold))
                    .foregroundStyle(Color.onAccent)
            }
        }
        .frame(width: size, height: size)
        .accessibilityElement()
        .accessibilityLabel(state == .checked ? "All selected" : state == .mixed ? "Some selected" : "Not selected")
    }
}
