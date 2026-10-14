import CrateKit
import CrateUI
import SwiftUI

/// Sheets presented from Build.
enum BuildSheet: Identifiable {
    case picker(focusFamily: String?)
    case key
    case vibeEditor(id: String?)
    case vibesManager
    case savePreset(id: String?)

    var id: String {
        switch self {
        case let .picker(f): "picker-\(f ?? "")"
        case .key: "key"
        case let .vibeEditor(id): "vibe-\(id ?? "new")"
        case .vibesManager: "vibes"
        case let .savePreset(id): "preset-\(id ?? "new")"
        }
    }
}

/// Pushed from Build.
struct BuildRoute: Hashable {
    let title: String
    let trackIDs: [String]
}

/// Build tab (design 6b–6f, 2a–2e). Owner: Build agent.
///
/// Order: preset pills, sticky result card, genre & style, vibe, tags, BPM, energy, key + vocals, limit.
/// The Send button is pinned above the tab bar with a fade to `bgBase`.
struct BuildView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var sheet: BuildSheet?
    @State private var path: [BuildRoute] = []
    @State private var loadedPresetID: String?
    @State private var tagText = ""
    @FocusState private var tagFocused: Bool
    @State private var debugApplied = false

    var body: some View {
        NavigationStack(path: $path) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: Space.sectionGap, pinnedViews: .sectionHeaders) {
                        if !model.snapshot.presets.presets.isEmpty && !tagFocused {
                            BuildPresetRow(loadedPresetID: $loadedPresetID, onEdit: { sheet = .savePreset(id: $0) })
                        }
                        Section {
                            sections
                        } header: {
                            BuildResultCard(onOpen: { title, ids in path.append(BuildRoute(title: title, trackIDs: ids)) })
                                .padding(.vertical, Space.xs)
                                .background(Color.bgBase)
                        }
                    }
                    .padding(.horizontal, Space.screenMargin)
                    .padding(.bottom, Space.l)
                }
                .scrollDismissesKeyboard(.interactively)
                .defaultScrollAnchor(DebugLaunch.scrollAnchor)
                .onChange(of: tagFocused) { _, focused in
                    if focused { withAnimation { proxy.scrollTo("tags", anchor: .top) } }
                }
                #if DEBUG
                .onChange(of: DemoTour.shared.scrollTarget) { _, target in
                    if let target { withAnimation(.easeInOut(duration: 0.8)) { proxy.scrollTo(target, anchor: .top) } }
                }
                #endif
            }
            .background(Color.bgBase.ignoresSafeArea())
            .navigationTitle("Build")
            .toolbar { toolbar }
            .safeAreaInset(edge: .bottom) {
                if !tagFocused { BuildSendBar(openURL: { openURL($0) }) }
            }
            .navigationDestination(for: BuildRoute.self) { TrackListView(title: $0.title, trackIDs: $0.trackIDs) }
        }
        .sheet(item: $sheet) { sheetContent($0) }
        .task { applyDebugScreen() }
    }

    @ViewBuilder private var sections: some View {
        if model.matchCount == 0 && !model.build.isEmpty {
            BuildEmptyState()
        }
        BuildGenreSection(onOpenPicker: { sheet = .picker(focusFamily: $0) })
        BuildVibeSection(onEdit: { sheet = .vibeEditor(id: $0) }, onManage: { sheet = .vibesManager })
        BuildTagSection(text: $tagText, focused: $tagFocused).id("tags")
        if !tagFocused {
            BuildBPMSection().id("bpm")
            BuildEnergySection()
            BuildKeyVocalSection(onOpenKey: { sheet = .key })
            BuildLimitSection()
        }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button("Reset") {
                withAnimation(CrateMotion.chip) {
                    model.build = BuildState()
                    loadedPresetID = nil
                    tagText = ""
                }
            }
            .disabled(model.build.isEmpty && model.build.limit == Query.defaultLimit)
            .foregroundStyle(Color.textSecondary)
            Button {
                sheet = .savePreset(id: nil)
            } label: {
                Image(systemName: model.build.isEmpty ? "bookmark" : "bookmark.fill")
            }
            .foregroundStyle(model.build.isEmpty ? Color.textTertiary : Color.textPrimary)
            .disabled(model.build.isEmpty)
            .accessibilityLabel("Save as preset")
        }
    }

    @ViewBuilder private func sheetContent(_ s: BuildSheet) -> some View {
        switch s {
        case let .picker(focus): GenrePickerView(focusFamily: focus)
        case .key: KeyPickerView()
        case let .vibeEditor(id): VibeEditorView(vibeID: id)
        case .vibesManager: VibesManagerView()
        case let .savePreset(id): SavePresetView(presetID: id)
        }
    }

    // MARK: Debug hooks (`-screen picker|key|tags|empty|filters|sent`)

    private func applyDebugScreen() {
        #if DEBUG
        guard !debugApplied, let screen = DebugLaunch.screen else { return }
        debugApplied = true
        let all = model.allStyles
        switch screen {
        case "send":
            // Loads the first preset and sends it (demo send), to watch the lift-off delight.
            if let p = model.snapshot.presets.presets.first {
                model.build = BuildState(query: p.query, allStyles: all)
            }
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1.5))
                await model.send()
            }
        case "picker":
            if let fam = model.index.families.first {
                var ticked = all[fam.family] ?? []
                if let s = fam.styles.dropFirst(3).first?.style { ticked.remove(s) }
                model.build.families = [fam.family: ticked]
            }
            sheet = .picker(focusFamily: model.index.families.first?.family)
        case "key":
            model.build.key = "8A"
            model.build.compatible = true
            sheet = .key
        case "tags":
            model.build.vibes = ["dark", "hypnotic"].filter { model.snapshot.vibes.vibes[$0] != nil }
            tagText = "dub"
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(600))
                tagFocused = true
            }
        case "filters":
            model.build.vibes = ["happy"]
            model.build.tags = ["italian"]
        case "empty":
            // Narrow "italian + Happy" step by step until nothing matches (an impossible combination).
            var b = BuildState()
            b.vibes = ["happy"]
            b.tags = ["italian"]
            let steps: [(inout BuildState) -> Void] = [
                { $0.bpm = 120...132 }, { $0.energy = 8...10 }, { $0.vibes.append("romantic") },
                { $0.vocal = .instrumental }, { $0.key = "1B"; $0.compatible = false },
            ]
            model.build = b
            for step in steps where model.matchCount > 0 { step(&model.build) }
        case "sent":
            if let p = model.snapshot.presets.presets.first {
                model.build = BuildState(query: p.query, allStyles: all)
                loadedPresetID = p.id
            }
            Task { await model.send() }
        default:
            break
        }
        #endif
    }
}
