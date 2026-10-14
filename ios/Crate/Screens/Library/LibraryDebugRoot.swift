#if DEBUG
import CrateKit
import CrateUI
import SwiftUI

/// Debug-only root for `-screen onboarding-1|2|3`, `presets`, `preset-edit` and `save-preset` screenshots.
/// Owner: Weekly/Library agent.
struct LibraryDebugRoot: View {
    @Environment(AppModel.self) private var model
    @State private var loaded = false
    @State private var showSave = false

    private var screen: String { DebugLaunch.screen ?? "" }

    var body: some View {
        Group {
            if screen.hasPrefix("onboarding") {
                OnboardingView()
            } else if !loaded {
                ProgressView()
            } else {
                NavigationStack {
                    PresetsManagerView()
                }
                .sheet(isPresented: $showSave) { SavePresetView(presetID: nil, initialName: "Italian summer") }
            }
        }
        .background(Color.bgBase)
        .task {
            if model.index.tracks.isEmpty { await model.reload() }
            if screen == "save-preset", let p = model.snapshot.presets.presets.first(where: { $0.id == "italian-summer" }) {
                model.build = BuildState(query: p.query, allStyles: model.allStyles)
            }
            loaded = true
            if screen == "save-preset" { showSave = true }
        }
    }
}
#endif
