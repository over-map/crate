import CrateKit
import CrateUI
import SwiftUI

/// Track list (6p): the given tracks, sortable; tap opens the track detail sheet. With `showsSend` (pushed from
/// Build) the Send button stays pinned at the bottom. Push it inside a NavigationStack. Owner: Tracks agent.
struct TrackListView: View {
    var title: String
    var trackIDs: [String]
    /// Line under the title, e.g. "Happy · italian". " · Now is shuffled when sent" is added with `showsSend`.
    var subtitle: String? = nil
    /// Keep the pinned Send button (uses `model.send()` / `sendState`, i.e. the current Build query).
    var showsSend: Bool = false

    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @State private var sort: Sort = .original
    @State private var selected: SelectedTrack?

    enum Sort: String, CaseIterable, Identifiable {
        case original, liked, bpm, energy, title
        var id: String { rawValue }
        var label: String {
            switch self {
            case .original: "Default"
            case .liked: "Recently liked"
            case .bpm: "BPM"
            case .energy: "Energy"
            case .title: "Title"
            }
        }
        var symbol: String {
            switch self {
            case .original: "list.bullet"
            case .liked: "heart"
            case .bpm: "metronome"
            case .energy: "bolt"
            case .title: "textformat"
            }
        }
    }

    struct SelectedTrack: Identifiable { let id: String }

    var body: some View {
        let infos = sorted(trackIDs.compactMap { TrackInfo(id: $0, model: model) })
        // ScrollView + LazyVStack rather than List: List evaluates rows off the main actor, which trips
        // CompactTagLine's EnergyMicroMeter isolation check (see report).
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if let line = headerLine {
                    Text(line)
                        .font(.footnote)
                        .foregroundStyle(Color.textSecondary)
                        .padding(.bottom, Space.xs)
                }
                ForEach(infos, id: \.id) { info in
                    Button { selected = SelectedTrack(id: info.id) } label: {
                        TrackRow(title: info.title, artist: info.artist, coverURL: info.coverURL,
                                 coverTint: Color.family(info.family),
                                 tagLine: info.compactTagLine(vibes: model.tracksVibes(containing: info.id).map(\.vibe.name)))
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint("Opens the track")
                    Divider().overlay(Color.separator).padding(.leading, Size.coverRow + Space.m)
                }
                if infos.isEmpty {
                    ContentUnavailableView("No tracks", systemImage: "music.note.list")
                }
            }
            .padding(.horizontal, Space.screenMargin)
        }
        .background(Color.bgBase)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Picker("Sort", selection: $sort) {
                        ForEach(Sort.allCases) { s in Label(s.label, systemImage: s.symbol).tag(s) }
                    }
                    if showsSend {
                        Section("Sorting doesn't change Now: it's shuffled when sent.") {}
                    }
                } label: {
                    Text("Sort")
                }
                .accessibilityLabel("Sort, \(sort.label)")
            }
        }
        .safeAreaInset(edge: .bottom) {
            if showsSend { sendBar }
        }
        .sheet(item: $selected) { s in
            TrackDetailView(trackID: s.id)
        }
    }

    private var headerLine: String? {
        let parts = [subtitle, showsSend ? "Now is shuffled when sent" : nil].compactMap { $0 }.filter { !$0.isEmpty }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    // MARK: Sorting

    private func sorted(_ infos: [TrackInfo]) -> [TrackInfo] {
        switch sort {
        case .original:
            return infos
        case .liked:
            return infos.sorted { ($0.likedDate ?? .distantPast) > ($1.likedDate ?? .distantPast) }
        case .bpm:
            return infos.sorted { a, b in
                switch (a.bpm, b.bpm) {
                case let (x?, y?): x != y ? x < y : a.title < b.title
                case (nil, _?): false
                case (_?, nil): true
                default: a.title < b.title
                }
            }
        case .energy:
            return infos.sorted { a, b in
                let x = a.energy ?? 0, y = b.energy ?? 0
                return x != y ? x > y : (a.bpm ?? 0) < (b.bpm ?? 0)
            }
        case .title:
            return infos.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        }
    }

    // MARK: Send

    private var sendBar: some View {
        PrimaryButton(buttonState, secondaryTitle: "Send again", secondaryAction: { Task { await model.send() } },
                      liftOffCovers: model.lastSentCovers) {
            if case .success = model.sendState, let url = model.nowURL {
                openURL(url)
            } else {
                Task { await model.send() }
            }
        }
        .padding(.horizontal, Space.screenMargin)
        .padding(.top, Space.l)
        .padding(.bottom, Space.m)
        .background(
            LinearGradient(colors: [Color.bgBase.opacity(0), Color.bgBase], startPoint: .top, endPoint: .init(x: 0.5, y: 0.35))
                .ignoresSafeArea(edges: .bottom)
        )
    }

    private var buttonState: PrimaryButtonState {
        switch model.sendState {
        case .idle:
            model.sendCount == 0 ? .disabled(title: "No tracks to send") : .idle(title: "Send \(model.sendCount) to Now")
        case let .sending(done, total):
            .sending(progress: Double(done) / Double(max(total, 1)), counter: "\(done)/\(total)")
        case let .success(n):
            .success(message: "Sent \(n) tracks to Now", title: "Listen on Spotify")
        case let .error(message):
            .error(message: message, title: "Retry")
        }
    }
}
