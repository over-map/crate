import SwiftUI

/// A dual-thumb range slider with 1-step detents (BPM, 60–200 by default).
///
/// - Track: `fillControlActive`, 4 pt; the selected part is filled with `tint` (`Color.bpm`).
///   When `range` equals `bounds` (unconstrained) there is no fill.
/// - Thumbs: 28 pt system-style white discs, 34 pt while dragging.
/// - Narrow ranges: when the thumbs would overlap, each becomes a half-capsule *outside* the range,
///   so both stay operable (e.g. 136–142).
/// - Ghost ranges (half/double time) are drawn on the track at 45 % opacity.
/// - Haptics: `.selection` on every step. VoiceOver: one adjustable element per thumb, and the container
///   reads "BPM from 136 to 142".
public struct RangeSlider: View {
    @Binding private var range: ClosedRange<Int>
    private let bounds: ClosedRange<Int>
    private let ghosts: [ClosedRange<Int>]
    private let tint: Color
    private let name: String
    @State private var dragging: Thumb?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private enum Thumb { case lower, upper }

    /// - Parameters:
    ///   - range: The selected range (clamped to `bounds`).
    ///   - bounds: The scale (default 60...200).
    ///   - ghosts: Extra ranges to hint on the track (half/double time, e.g. 64...71).
    ///   - tint: Fill colour; defaults to `Color.bpm`.
    ///   - accessibilityName: Unit used by VoiceOver ("BPM").
    public init(range: Binding<ClosedRange<Int>>, bounds: ClosedRange<Int> = 60...200,
                ghosts: [ClosedRange<Int>] = [], tint: Color = .bpm, accessibilityName: String = "BPM") {
        _range = range
        self.bounds = bounds
        self.ghosts = ghosts
        self.tint = tint
        self.name = accessibilityName
    }

    public var body: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let narrow = x(range.upperBound, width) - x(range.lowerBound, width) < Size.minTarget
            ZStack(alignment: .leading) {
                Capsule().fill(Color.fillControlActive)
                    .frame(height: Metric.trackHeight)
                ForEach(Array(ghosts.enumerated()), id: \.offset) { _, ghost in
                    segment(ghost, width: width).opacity(Metric.dimmed)
                }
                if range != bounds {
                    segment(range, width: width)
                }
                thumb(.lower, width: width, narrow: narrow)
                thumb(.upper, width: width, narrow: narrow)
            }
            .frame(width: width, height: geo.size.height)
            .coordinateSpace(name: Self.space)
        }
        .frame(height: Size.minTarget)
        .sensoryFeedback(.selection, trigger: range)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(name) from \(range.lowerBound) to \(range.upperBound)")
    }

    private static let space = "RangeSlider"

    // MARK: Geometry

    private var radius: CGFloat { Size.thumb / 2 }

    private func x(_ value: Int, _ width: CGFloat) -> CGFloat {
        let span = CGFloat(max(1, bounds.upperBound - bounds.lowerBound))
        return radius + CGFloat(value - bounds.lowerBound) / span * max(0, width - 2 * radius)
    }

    private func value(at x: CGFloat, _ width: CGFloat) -> Int {
        let usable = max(1, width - 2 * radius)
        let t = min(1, max(0, (x - radius) / usable))
        return bounds.lowerBound + Int((t * CGFloat(bounds.upperBound - bounds.lowerBound)).rounded())
    }

    private func segment(_ r: ClosedRange<Int>, width: CGFloat) -> some View {
        let lo = x(max(bounds.lowerBound, r.lowerBound), width)
        let hi = x(min(bounds.upperBound, r.upperBound), width)
        return Rectangle().fill(tint)
            .frame(width: max(Stroke.outline, hi - lo), height: Metric.trackHeight)
            .offset(x: lo)
    }

    // MARK: Thumbs

    private func thumb(_ which: Thumb, width: CGFloat, narrow: Bool) -> some View {
        let current = which == .lower ? range.lowerBound : range.upperBound
        let center = x(current, width)
        let isDragging = dragging == which
        let size = isDragging ? Metric.thumbDragging : Size.thumb
        let shape: UnevenRoundedRectangle = narrow
            ? (which == .lower
                ? UnevenRoundedRectangle(topLeadingRadius: size / 2, bottomLeadingRadius: size / 2,
                                         bottomTrailingRadius: Metric.halfThumbInnerRadius,
                                         topTrailingRadius: Metric.halfThumbInnerRadius, style: .continuous)
                : UnevenRoundedRectangle(topLeadingRadius: Metric.halfThumbInnerRadius,
                                         bottomLeadingRadius: Metric.halfThumbInnerRadius,
                                         bottomTrailingRadius: size / 2, topTrailingRadius: size / 2, style: .continuous))
            : UnevenRoundedRectangle(cornerRadii: .init(topLeading: size / 2, bottomLeading: size / 2,
                                                        bottomTrailing: size / 2, topTrailing: size / 2),
                                     style: .continuous)
        // Narrow: the lower half-capsule ends at its value, the upper one starts at its value.
        let leading: CGFloat = narrow ? (which == .lower ? center - size : center) : center - size / 2

        return shape
            .fill(Color.thumb)
            .frame(width: size, height: size)
            .shadow(color: Color.black.opacity(isDragging ? 0.5 : 0.4), radius: isDragging ? 5 : 3, y: 2)
            .frame(width: max(size, Size.minTarget), height: Size.minTarget)
            .contentShape(Rectangle())
            .offset(x: leading - (max(size, Size.minTarget) - size) / 2)
            .animation(CrateMotion.animation(CrateMotion.chip, reduceMotion: reduceMotion), value: isDragging)
            .gesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .named(Self.space))
                    .onChanged { drag in
                        dragging = which
                        // Narrow thumbs sit beside their value, so correct for the offset of the grab point.
                        let target = value(at: drag.location.x + (narrow ? (which == .lower ? radius : -radius) : 0), width)
                        set(which, to: target)
                    }
                    .onEnded { _ in dragging = nil }
            )
            .accessibilityElement()
            .accessibilityLabel(which == .lower ? "Minimum \(name)" : "Maximum \(name)")
            .accessibilityValue(String(current))
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: set(which, to: current + 1)
                case .decrement: set(which, to: current - 1)
                @unknown default: break
                }
            }
    }

    private func set(_ which: Thumb, to newValue: Int) {
        switch which {
        case .lower:
            let v = min(range.upperBound, max(bounds.lowerBound, newValue))
            if v != range.lowerBound { range = v...range.upperBound }
        case .upper:
            let v = max(range.lowerBound, min(bounds.upperBound, newValue))
            if v != range.upperBound { range = range.lowerBound...v }
        }
    }
}
