#if DEBUG
import CrateKit
import CrateUI
import SwiftUI

/// `-screen track | track-approx | track-expanded | track-override | edit | list` (Debug only): opens the Tracks
/// screens directly for screenshots. Loads the data itself, since it replaces the root view.
struct TracksDebugRoot: View {
    @Environment(AppModel.self) private var model
    @State private var prepared = false

    var body: some View {
        Group {
            if model.phase == .ready, prepared {
                content
            } else {
                ProgressView().task {
                    if model.phase != .ready { await model.reload() }
                    await prepare()
                    prepared = true
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.bgBase)
    }

    private var screen: String { DebugLaunch.screen ?? "track" }

    @ViewBuilder private var content: some View {
        switch screen {
        case "list":
            NavigationStack {
                let ids = technoIDs
                TrackListView(title: "\(ids.count) tracks", trackIDs: ids, subtitle: "Techno", showsSend: true)
            }
        case "edit", "track-override":
            Color.bgBase.sheet(isPresented: .constant(true)) { EditTagsView(trackID: pick() ?? "") }
        case "track-expanded":
            Color.bgBase.sheet(isPresented: .constant(true)) {
                TrackDetailView(trackID: pick() ?? "", startsExpanded: true)
            }
        default:
            Color.bgBase.sheet(isPresented: .constant(true)) { TrackDetailView(trackID: pick() ?? "") }
        }
    }

    /// Techno tracks, as the Build agent would pass them (Build sets the family filter → matching IDs).
    private var technoIDs: [String] {
        model.index.ids.filter { model.index.effective[$0]?.family == Family.techno.rawValue }
    }

    private var taggedByTitle: [(id: String, track: Track)] {
        model.index.tracks.filter { $0.value.status == .tagged }
            .sorted { ($0.value.title ?? "").localizedStandardCompare($1.value.title ?? "") == .orderedAscending }
            .map { ($0.key, $0.value) }
    }

    private func pick() -> String? {
        let tagged = taggedByTitle
        switch screen {
        case "track-approx":
            return tagged.first { ($0.track.auto?.bpmConf ?? 1) < 0.5 }?.id
        case "track-expanded", "track-override":
            return tagged.first { ($0.track.consensus?.sources ?? 0) >= 3 && ($0.track.tags?.count ?? 0) >= 7 }?.id
                ?? tagged.first?.id
        default:
            return tagged.first?.id
        }
    }

    /// `track-override`: an in-memory override (demo data) so the edited states show: another genre, a removed
    /// style, an added style and a corrected BPM.
    private func prepare() async {
        guard screen == "track-override", let id = pick(), let t = model.index.tracks[id] else { return }
        let ranked = EffectiveTags.rankedStyles(t.auto?.styles ?? [:])
        let other = model.index.families.map(\.family).first { $0 != t.family } ?? "disco"
        let removed = ranked.dropFirst().first
        let bpm = t.auto?.bpm.map { ($0 / 2).rounded() }
        await model.editOverride(trackID: id) { o in
            o.family = other
            if let removed { o.stylesRemove = [removed] }
            o.bpm = bpm
            o.energy = 8
        }
    }
}
#endif
