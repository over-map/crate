import SwiftUI

/// "Lift-off": the small delight when tracks land in Spotify. A luminous wave in the family colours blooms out of
/// the Send button (the NameDrop glow), and covers of the sent tracks peel off the button and float up in
/// staggered arcs, tilting and fading as they fly to Spotify. Purely decorative: no hit testing, no layout, gone
/// after 1.5 s, and skipped with Reduce Motion.
///
/// ``PrimaryButton`` requests it (`liftOffCovers`); a ``View/liftOffHost()`` near the root draws it above every
/// other view, so the covers fly over the screen's content instead of under it.
struct LiftOffEffect: View {
    let covers: [URL]
    @State private var start: Date?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    static let duration: TimeInterval = 1.5
    /// The palette of the wave: family hues, so it feels like Crate's own colours.
    static let hues: [Color] = [
        Color.family(.house, shade: .deep), Color.family(.techno, shade: .deep), Color.family(.popcantautori, shade: .deep),
        Color.family(.disco, shade: .deep), Color.family(.downtempo, shade: .deep), Color.family(.house, shade: .deep),
    ]

    var body: some View {
        GeometryReader { geo in
            if let start {
                TimelineView(.animation) { context in
                    let t = min(1, context.date.timeIntervalSince(start) / Self.duration)
                    ZStack {
                        bloom(t: t, size: geo.size)
                        ForEach(Array(covers.prefix(5).enumerated()), id: \.offset) { i, url in
                            cover(url, index: i, count: min(5, covers.count), t: t, size: geo.size)
                        }
                    }
                    .frame(width: geo.size.width, height: geo.size.height)
                }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear {
            guard !reduceMotion else { return }
            start = Date()
            Task {
                try? await Task.sleep(for: .seconds(Self.duration))
                start = nil
            }
        }
    }

    // MARK: Bloom

    /// A soft colour glow and two bright rings that grow evenly out of the button's outline, plus a sheen across
    /// the button face.
    @ViewBuilder private func bloom(t: Double, size: CGSize) -> some View {
        let glow = max(0, min(1, t / 0.7))
        let glowEase = 1 - pow(1 - glow, 2)
        RoundedRectangle(cornerRadius: Radius.button + glowEase * 60, style: .continuous)
            .fill(AngularGradient(colors: Self.hues, center: .center, angle: .degrees(glowEase * 160)))
            .frame(width: size.width + glowEase * 60, height: size.height + glowEase * 150)
            .blur(radius: 26 + glowEase * 24)
            .opacity(glow == 0 ? 0 : sin(glow * .pi) * 0.75)
            .blendMode(.plusLighter)
        ForEach(0..<2, id: \.self) { ring in
            let local = max(0, min(1, (t - Double(ring) * 0.14) / 0.7))
            let eased = 1 - pow(1 - local, 3)
            let grow = eased * (ring == 0 ? 120 : 80)
            RoundedRectangle(cornerRadius: Radius.button + grow * 0.5, style: .continuous)
                .stroke(AngularGradient(colors: Self.hues, center: .center, angle: .degrees(eased * 240 + Double(ring) * 90)),
                        lineWidth: 5 * (1 - eased) + 1)
                .frame(width: size.width + grow * 2, height: size.height + grow * 2)
                .blur(radius: 1.5 + eased * 5)
                .opacity(local == 0 ? 0 : (1 - eased) * (ring == 0 ? 1 : 0.7))
                .blendMode(.plusLighter)
        }
        RoundedRectangle(cornerRadius: Radius.button, style: .continuous)
            .fill(LinearGradient(colors: [.clear, .white.opacity(0.6), .clear],
                                 startPoint: UnitPoint(x: -0.6 + t * 2.4, y: 0.5),
                                 endPoint: UnitPoint(x: -0.25 + t * 2.4, y: 0.5)))
            .frame(width: size.width, height: size.height)
            .blendMode(.plusLighter)
            .opacity(t < 0.55 ? 1 : max(0, 1 - (t - 0.55) / 0.15))
    }

    // MARK: Covers

    /// Cover `index` of `count`: starts at the button's top edge, rises along a gentle arc, tilts, shrinks, fades.
    private func cover(_ url: URL, index: Int, count: Int, t: Double, size: CGSize) -> some View {
        let delay = Double(index) * 0.07
        let local = max(0, min(1, (t - 0.1 - delay) / 0.75))
        let rise = 1 - pow(1 - local, 2.2)
        let spread = Double(index) - Double(count - 1) / 2
        let x = spread * 38 + sin(rise * .pi) * spread * 30
        let y = -size.height / 2 - rise * (220 + Double(index % 2) * 60)
        return CoverImage(url: url, size: 52, radius: Radius.cover)
            .shadow(color: .black.opacity(0.45), radius: 10, y: 6)
            .scaleEffect(0.55 + sin(min(1, rise * 1.5) * .pi / 2) * 0.5 - rise * 0.4)
            .rotationEffect(.degrees(spread * 10 * rise + (index.isMultiple(of: 2) ? -5 : 5) * rise))
            .offset(x: x, y: y)
            .opacity(local == 0 ? 0 : min(1, local * 6) * (1 - pow(rise, 3)))
    }
}

/// A lift-off request from a ``PrimaryButton``: where it is and what to fly.
struct LiftOffRequest: Equatable {
    let id: Int
    let covers: [URL]
    let anchor: Anchor<CGRect>
}

struct LiftOffKey: PreferenceKey {
    static let defaultValue: LiftOffRequest? = nil
    static func reduce(value: inout LiftOffRequest?, nextValue: () -> LiftOffRequest?) {
        value = nextValue() ?? value
    }
}

public extension View {
    /// Draws Send-button lift-offs above this view's content. Apply once near the root of a screen hierarchy.
    func liftOffHost() -> some View {
        overlayPreferenceValue(LiftOffKey.self) { request in
            GeometryReader { proxy in
                if let request {
                    let rect = proxy[request.anchor]
                    LiftOffEffect(covers: request.covers)
                        .id(request.id)
                        .frame(width: rect.width, height: rect.height)
                        .position(x: rect.midX, y: rect.midY)
                }
            }
            .allowsHitTesting(false)
        }
    }
}
