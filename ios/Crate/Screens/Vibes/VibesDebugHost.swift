#if DEBUG
import CrateUI
import SwiftUI

/// Opens a Vibes screen directly for Simulator checks (`-screen <name>`, DEBUG only). Hooked from `RootView` with
/// `if DebugLaunch.screen?.hasPrefix("vibe") == true { VibesDebugRoot() }`.
///
/// Names: `vibe-new`, `vibe-new-seeded` (first track pre-added), `vibe-<id>` (e.g. `vibe-hypnotic`,
/// `vibe-melancholic`), `vibes-manager`, `vibe-chooser` / `vibe-chooser-never` (first track).
struct VibesDebugRoot: View {
    @Environment(AppModel.self) private var model
    @State private var sheet: Sheet?

    enum Sheet: Identifiable {
        case editor(VibeEditorTarget)
        var id: String {
            switch self {
            case let .editor(t): "editor-\(t.id)"
            }
        }
    }

    private var screen: String { DebugLaunch.screen ?? "" }
    private var firstTrack: String {
        // A track with a title and a vector, so the seeded editor shows something real.
        model.index.ids.first { model.index.tracks[$0]?.title != nil && model.index.vibeIndex?.vectors[$0] != nil }
            ?? model.index.ids.first ?? ""
    }

    var body: some View {
        if screen == "vibes-manager" {
            NavigationStack { VibesManagerView() }
        } else {
            Color.bgBase.ignoresSafeArea()
                .onAppear { sheet = initialSheet }
                .sheet(item: $sheet) { s in
                    switch s {
                    case let .editor(t): VibeEditorView(vibeID: t.vibeID, seedTrackID: t.seedTrackID)
                    }
                }
        }
    }

    private var initialSheet: Sheet? {
        switch screen {
        case "vibe-new": .editor(VibeEditorTarget(vibeID: nil))
        case "vibe-new-seeded": .editor(VibeEditorTarget(vibeID: nil, seedTrackID: firstTrack))
        default:
            screen.hasPrefix("vibe-") ? .editor(VibeEditorTarget(vibeID: String(screen.dropFirst(5)))) : nil
        }
    }
}
#endif
