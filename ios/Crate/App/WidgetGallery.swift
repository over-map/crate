#if DEBUG
import CrateKit
import CrateUI
import SwiftUI

/// `-screen widgets`: every widget in every state at iPhone 16 Pro sizes (design 4c–4g), for screenshots.
/// `containerBackground(for: .widget)` does nothing outside WidgetKit, so the surface is drawn here.
struct WidgetGallery: View {
    private let base = CrateEntry.sample
    private var states: [(String, SendStatus?)] {
        let all: [(String, SendStatus?)] = [("Idle", nil),
         ("Sending", SendStatus(phase: .sending, presetID: "dark")),
         ("Sent", SendStatus(phase: .sent, presetID: "dark", count: 100)),
         ("Error", SendStatus(phase: .failed, presetID: "dark", failure: .loginExpired))]
        // `-from Sent` starts the list there (the screen is taller than one screenshot).
        guard let from = DebugLaunch.value("-from"), let i = all.firstIndex(where: { $0.0 == from }) else { return all }
        return Array(all[i...])
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.l) {
                ForEach(states, id: \.0) { name, status in
                    let e = CrateEntry(date: .now, snapshot: base.snapshot, status: status, presetID: "dark")
                    PanelLabel(name)
                    HStack(spacing: Space.m) {
                        frame(SmallView(entry: e), 170, 170)
                        VStack(spacing: Space.s) {
                            frame(CircularView(entry: e), 76, 76, lock: true)
                            frame(RectangularView(entry: e), 172, 76, lock: true)
                        }
                    }
                    frame(GridWidgetView(entry: e), 364, 170)
                }
                PanelLabel("No presets")
                frame(SmallView(entry: CrateEntry(date: .now, snapshot: nil, status: nil, presetID: nil)), 170, 170)
            }
            .padding(Space.l)
        }
        .background(Color.gray.opacity(0.35))
    }

    private func frame(_ v: some View, _ w: CGFloat, _ h: CGFloat, lock: Bool = false) -> some View {
        v.frame(width: w, height: h)
            .background(lock ? Color.black.opacity(0.5) : Color.widgetSurface)
            .foregroundStyle(lock ? Color.white : Color.textPrimary)
            .clipShape(RoundedRectangle(cornerRadius: lock ? 38 : 22, style: .continuous))
    }
}
#endif
