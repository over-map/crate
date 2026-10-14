#if DEBUG
import CrateKit
import CrateUI
import Foundation
import Observation
import SwiftUI

/// `-tour`: a scripted walk through the app for the promo video (Debug only). It drives the real screens with
/// demo data: filters on Build with live counts, a send with the lift-off, a track's detail, then Daily. No taps,
/// so a Simulator recording shows clean, cursor-free usage.
@MainActor
@Observable
final class DemoTour {
    static let shared = DemoTour()
    static var isOn: Bool { DebugLaunch.args.contains("-tour") }

    /// MainTabs follows this.
    var tab: MainTabs.TabID?
    /// BuildView scrolls to this section id.
    var scrollTarget: String?

    private var started = false

    func start(_ model: AppModel) {
        guard Self.isOn, !started else { return }
        started = true
        Task { await run(model) }
    }

    private func pause(_ s: Double) async { try? await Task.sleep(for: .seconds(s)) }

    private func run(_ model: AppModel) async {
        await pause(4.0)
        let all = model.allStyles
        // 1. Genre: Techno, all styles
        withAnimation(CrateMotion.chip) { model.build.families = ["techno": all["techno"] ?? []] }
        await pause(1.4)
        // 2. Vibe: Hypnotic
        withAnimation(CrateMotion.chip) { model.build.vibes = ["hypnotic"] }
        await pause(1.4)
        // 3. Scroll to BPM and narrow it in small steps, so the slider glides
        withAnimation(.easeInOut(duration: 0.8)) { scrollTarget = "bpm" }
        await pause(1.0)
        for step in 0...12 {
            let lo = 60 + (124 - 60) * step / 12, hi = 200 - (200 - 136) * step / 12
            model.build.bpm = lo...hi
            await pause(0.06)
        }
        await pause(1.0)
        // 4. Energy 6–9
        withAnimation(CrateMotion.chip) { model.build.energy = 6...9 }
        await pause(1.4)
        // 5. Send, with the lift-off
        await model.send()
        await pause(2.6)
        // 6. A track's detail: why it fits
        if let id = model.index.matchingIDs(model.query).first(where: { model.index.tracks[$0]?.consensus != nil }) {
            model.linkedTrackID = id
            await pause(3.6)
            model.linkedTrackID = nil
            await pause(0.8)
        }
        // 7. Crate Daily
        tab = .weekly
        await pause(6.0)
    }
}
#endif
