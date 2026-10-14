import SwiftUI

/// Component-level sizes and colours that the design boards specify but `tokens.json` does not
/// (yet) export. They are collected here so they can move into the token generator in one step.
/// Every value is taken from `Crate Update v2.dc.html` (board 6a and screens 6b–6v) or
/// `Crate Design System.dc.html` (v1 components).
enum Metric {
    // MARK: Chips
    /// Horizontal padding inside a 32 pt chip (all chip boards use 10).
    static let chipPaddingH: CGFloat = 10
    /// Gap between symbol, label and count inside a chip.
    static let chipInnerGap: CGFloat = 5
    /// Hue dot inside an off style chip.
    static let styleDot: CGFloat = 8
    /// Hue dot in picker rows.
    static let familyDot: CGFloat = 10
    /// FamilyMiniChip height (radius = `Radius.badge`).
    static let miniChipHeight: CGFloat = 20
    /// FamilyMiniChip horizontal padding.
    static let miniChipPaddingH: CGFloat = 6

    // MARK: Confidence
    /// Filled / ring dot diameter.
    static let confidenceDot: CGFloat = 7
    /// "No opinion" dash width (height = `Stroke.outline`).
    static let confidenceDash: CGFloat = 6
    /// Gap between the four dots.
    static let confidenceDotGap: CGFloat = 3
    /// Minimum height of a ConfidenceLine row.
    static let confidenceLineHeight: CGFloat = 28
    /// Width of the source name column in the expanded vote table.
    static let voteSourceColumn: CGFloat = 74
    /// List marker size (`tokens.json → confidence.listMarker.size`).
    static let listMarker: CGFloat = 11

    // MARK: Energy
    /// Micro meter tick (tag line).
    static let microTickWidth: CGFloat = 2
    static let microTickHeight: CGFloat = 9
    static let microTickGap: CGFloat = 1
    /// Display meter (track detail): block height, radius and gap.
    static let displayBlockHeight: CGFloat = 18
    static let displayBlockRadius: CGFloat = 2
    static let displayBlockGap: CGFloat = 3
    /// Input meter gap between the 26 pt blocks.
    static let inputBlockGap: CGFloat = 4

    // MARK: Range slider
    /// Thumb diameter while dragging (`design-system.md §4`: 28 → 34).
    static let thumbDragging: CGFloat = 34
    /// Slider track thickness.
    static let trackHeight: CGFloat = 4
    /// Inner corner radius of a half-capsule thumb.
    static let halfThumbInnerRadius: CGFloat = 4

    // MARK: Camelot
    /// Badge heights: S (tag line 18 / board 20), M 28, L 48.
    static let badgeTagLine: CGFloat = 18
    static let badgeSmall: CGFloat = 20
    static let badgeMedium: CGFloat = 28
    static let badgeLarge: CGFloat = 48
    /// Wheel geometry on a 320 pt reference (A inner, B outer).
    static let wheelReference: CGFloat = 320
    static let wheelInner: ClosedRange<CGFloat> = 64...108
    static let wheelOuter: ClosedRange<CGFloat> = 110...156
    /// Selected-segment ring.
    static let wheelSelectedRing: CGFloat = 3
    /// Gap stroke between segments.
    static let wheelGap: CGFloat = 2
    /// Opacity of keys outside the compatible set.
    static let wheelDimmed: Double = 0.25

    // MARK: Misc
    /// Opacity of a chip / row whose count is 0.
    static let dimmed: Double = 0.45
    /// Tri-state tick ring diameter.
    static let tickRing: CGFloat = 24
    /// Preset icon tile (Presets manager rows).
    static let presetTile: CGFloat = 32
    /// Small tile inside a PresetPill.
    static let presetPillTile: CGFloat = 18
    /// Edited-value dot in the tag line.
    static let editedDot: CGFloat = 5
    /// Width of one placeholder stripe on a cover.
    static let coverStripe: CGFloat = 4
}

public extension Color {
    /// Text and glyphs on data colours (family, Camelot, preset tiles): always black (design-system §2).
    static let onData = Color.black
}

extension Color {
    /// System slider thumb (white in both appearances).
    static let thumb = Color.white
    /// `fillControlStrong` in the HTML (#3A3A3C): unlit micro-meter ticks, dashed outlines.
    /// Not a token, so it is derived from `textTertiary`.
    static let fillControlStrong = Color.textTertiary.opacity(0.5)
}
