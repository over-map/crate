import CrateKit
import CrateUI
import SwiftUI

/// Track detail sheet (6m–6n): header, styles + confidence, BPM / key / energy, vibes, community tags, actions.
/// Self-contained sheet (own NavigationStack: Close / Edit). Owner: Tracks agent.
struct TrackDetailView: View {
    var trackID: String
    /// Debug/screenshots: open with the confidence table and the community tags expanded.
    var startsExpanded: Bool = false

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var sheet: DetailSheet?
    @State private var tagsExpanded = false
    @State private var showAllTags = false
    @State private var confirmUnlike = false
    @State private var unliked = false
    @State private var unlikeError: String?
    @State private var sentMoreLikeThis = false
    @State private var didApplyStart = false

    enum DetailSheet: Identifiable {
        case edit
        case vibe(String)
        var id: String {
            switch self {
            case .edit: "edit"
            case let .vibe(v): "vibe-" + v
            }
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                if let info = TrackInfo(id: trackID, model: model) {
                    content(info)
                } else {
                    ContentUnavailableView("Track not in library", systemImage: "music.note")
                }
            }
            .background(Color.bgSheet)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button("Edit") { sheet = .edit }.accessibilityLabel("Edit tags and vibes")
                }
            }
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear {
            if startsExpanded, !didApplyStart { tagsExpanded = true; didApplyStart = true }
        }
        .sheet(item: $sheet) { s in
            switch s {
            case .edit: EditTagsView(trackID: trackID)
            case let .vibe(id): VibeEditorView(vibeID: id, seedTrackID: nil)
            }
        }
    }

    // MARK: Content

    private func content(_ info: TrackInfo) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Space.sectionGap) {
                header(info)
                styles(info)
                if info.isAnalysed {
                    numbers(info)
                } else {
                    Text("No preview · tag by hand")
                        .font(.secondary).foregroundStyle(Color.textSecondary)
                }
                vibes(info)
                tags(info)
                actions(info)
            }
            .padding(.horizontal, Space.screenMargin)
            .padding(.bottom, Space.xl)
        }
    }

    private func header(_ info: TrackInfo) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: Space.m))
            : AnyLayout(HStackLayout(alignment: .center, spacing: Space.l))
        return layout {
            CoverImage(url: info.coverURL, size: 96, radius: Radius.coverLarge, tint: Color.family(info.family))
            VStack(alignment: .leading, spacing: Space.xxs) {
                Text(info.title)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Color.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                Text(info.artist).font(.body).foregroundStyle(Color.textSecondary)
                Text(info.addedText).font(.footnote).foregroundStyle(Color.textTertiary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
        }
        .padding(.top, Space.s)
    }

    @ViewBuilder private func styles(_ info: TrackInfo) -> some View {
        let all = info.displayStyles
        VStack(alignment: .leading, spacing: Space.xs) {
            if !all.isEmpty {
                ChipFlow {
                    ForEach(Array(all.enumerated()), id: \.element) { i, s in
                        StyleChip(style: info.styleName(s), canonical: s,
                                  genre: i == 0 ? info.familyName(info.family) : nil,
                                  family: info.family, edited: info.isStyleEdited(s))
                    }
                }
            } else if info.family != .none {
                ChipFlow {
                    StyleChip(style: info.familyName(info.family), family: info.family,
                              edited: info.overrideFamily != nil)
                }
            }
            if let summary = info.confidence {
                ConfidenceLine(summary, expanded: startsExpanded)
                    .id(summary.copy + summary.family.rawValue)
            } else if info.overrideFamily != nil {
                Label(ConfidenceCopy.override, systemImage: "pencil")
                    .font(.footnote.weight(.medium)).foregroundStyle(Color.textSecondary)
            }
        }
    }

    // MARK: Numbers

    @ViewBuilder private func numbers(_ info: TrackInfo) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: Space.s))
            : AnyLayout(HStackLayout(alignment: .top, spacing: Space.s))
        layout {
            numberCard("BPM", edited: info.bpmEdited) {
                if let bpm = info.bpm {
                    BPMText(value: Int(bpm.rounded()), approx: info.bpmApprox, font: .numeralLarge)
                    if info.bpmApprox {
                        Text("Uncertain beat").font(.caption).foregroundStyle(Color.textSecondary)
                    }
                } else {
                    unknownValue
                }
            }
            numberCard("Key", edited: info.keyEdited) {
                if let key = info.key, let k = Camelot(key) {
                    CamelotBadge(code: k.code, size: .medium)
                    Text(k.name).font(.caption).foregroundStyle(Color.textSecondary)
                        .accessibilityHidden(true)
                } else {
                    unknownValue
                }
            }
            numberCard("Energy", edited: info.energyEdited) {
                if let e = info.energy {
                    Text(verbatim: String(e)).font(.numeralLarge).foregroundStyle(Color.textPrimary)
                        .accessibilityLabel("Energy \(e) out of 10")
                    TrackEnergyBlocks(level: e).accessibilityHidden(true)
                } else {
                    unknownValue
                }
            }
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private var unknownValue: some View {
        Text("—").font(.numeralLarge).foregroundStyle(Color.textTertiary).accessibilityLabel("Unknown")
    }

    private func numberCard<Content: View>(_ label: String, edited: Bool,
                                           @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            HStack(spacing: Space.xs) {
                PanelLabel(label)
                if edited { TrackEditedDot() }
            }
            content()
        }
        .padding(Space.m)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .crateCard()
        .accessibilityElement(children: .combine)
        .accessibilityValue(edited ? "edited" : "")
    }

    // MARK: Vibes

    private func vibes(_ info: TrackInfo) -> some View {
        let list = model.tracksVibes(containing: trackID)
        return VStack(alignment: .leading, spacing: Space.labelGap) {
            PanelLabel(list.isEmpty ? "Vibes" : "Vibes · in \(list.count)")
            if list.isEmpty {
                Text("Not in any vibe yet. Add it in Edit.").font(.footnote).foregroundStyle(Color.textSecondary)
            } else {
                ChipFlow {
                    ForEach(list, id: \.id) { v in
                        VibeChip(v.vibe.name, symbol: model.tracksVibeSymbol(id: v.id, vibe: v.vibe), count: nil,
                                 action: { sheet = .vibe(v.id) })
                            .accessibilityHint("Opens the vibe")
                    }
                }
            }
        }
    }

    // MARK: Community tags

    @ViewBuilder private func tags(_ info: TrackInfo) -> some View {
        let tags = info.communityTags
        if tags.isEmpty {
            HStack {
                Text("Community tags").foregroundStyle(Color.textPrimary)
                Spacer()
                Text(info.track.tags == nil ? "Not looked up yet" : "None").foregroundStyle(Color.textTertiary)
            }
            .font(.body)
            .padding(.horizontal, Space.m)
            .frame(minHeight: Size.minTarget + Space.xs)
            .crateCard()
            .accessibilityElement(children: .combine)
        } else if !tagsExpanded {
            Button { toggleTags() } label: {
                HStack(spacing: Space.s) {
                    Text("Community tags").foregroundStyle(Color.textPrimary)
                    Spacer(minLength: Space.s)
                    Text("\(tags.count) · community").foregroundStyle(Color.textSecondary)
                    Image(systemName: "chevron.right").font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.textTertiary)
                }
                .font(.body)
                .padding(.horizontal, Space.m)
                .frame(minHeight: Size.minTarget + Space.xs)
                .contentShape(Rectangle())
                .crateCard()
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Community tags, \(tags.count)")
            .accessibilityHint("Shows the tags")
        } else {
            VStack(alignment: .leading, spacing: Space.labelGap) {
                Button { toggleTags() } label: {
                    HStack {
                        PanelLabel("Community tags · \(tags.count)")
                        Spacer()
                        Image(systemName: "chevron.down").font(.caption2.weight(.semibold))
                            .foregroundStyle(Color.textTertiary)
                    }
                    .frame(minHeight: Size.minTarget)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Community tags, \(tags.count)")
                .accessibilityValue("Expanded")
                let shown = showAllTags ? tags : Array(tags.prefix(5))
                VStack(spacing: 0) {
                    ForEach(Array(shown.enumerated()), id: \.element) { i, tag in
                        HStack(spacing: Space.s) {
                            Image(systemName: "number").font(.footnote).foregroundStyle(Color.textTertiary)
                            Text(tag).font(.body).foregroundStyle(Color.textPrimary)
                            Spacer(minLength: Space.s)
                            let from = info.tagSources(tag)
                            Text(from.isEmpty ? "COMMUNITY" : from.map(\.code).joined(separator: " · "))
                                .panelLabelStyle().foregroundStyle(Color.textTertiary)
                        }
                        .padding(.horizontal, Space.m)
                        .frame(minHeight: Size.minTarget)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(tagLabel(tag, info))
                        if i < shown.count - 1 || tags.count > 5 { Divider().overlay(Color.separator) }
                    }
                    if tags.count > 5 {
                        Button { showAllTags.toggle() } label: {
                            Text(showAllTags ? "Show fewer" : "Show \(tags.count - 5) more")
                                .font(.footnote).foregroundStyle(Color.textSecondary)
                                .frame(maxWidth: .infinity, minHeight: Size.minTarget, alignment: .leading)
                                .padding(.horizontal, Space.m)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .crateCard()
                Text("LFM Last.fm describes the artist, not this exact track · DSC Discogs, the release · BP Beatport, entered by you.")
                    .font(.footnote).foregroundStyle(Color.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func tagLabel(_ tag: String, _ info: TrackInfo) -> String {
        let names = info.tagSources(tag).map(\.name)
        return names.isEmpty ? "Tag \(tag)" : "Tag \(tag), from \(ListFormatter.localizedString(byJoining: names))"
    }

    private func toggleTags() {
        withAnimation(CrateMotion.animation(CrateMotion.chip, reduceMotion: reduceMotion)) { tagsExpanded.toggle() }
    }

    // MARK: Actions

    private func actions(_ info: TrackInfo) -> some View {
        VStack(spacing: 0) {
            moreLikeThisRow
            Divider().overlay(Color.separator)
            actionRow("Open in Spotify", systemImage: "arrow.up.right") {
                if let url = URL(string: info.uri) { openURL(url) }
            }
            Divider().overlay(Color.separator)
            actionRow(unliked ? "Removed from Liked Songs" : "Remove from Liked Songs", systemImage: "heart.slash",
                      disabled: unliked || model.isDemo) { confirmUnlike = true }
        }
        .crateCard()
        .confirmationDialog("Remove “\(info.title)” from Liked Songs?", isPresented: $confirmUnlike, titleVisibility: .visible) {
            Button("Remove from Liked Songs", role: .destructive) { unlike(info) }
        } message: {
            Text("It leaves Crate after tonight's sync. Your tag edits are kept in case you like it again.")
        }
        .alert("Couldn't remove the like", isPresented: Binding(get: { unlikeError != nil }, set: { if !$0 { unlikeError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(unlikeError ?? "") }
    }

    private func unlike(_ info: TrackInfo) {
        Task {
            do {
                try await model.spotify.unlike(uri: info.uri)
                unliked = true
            } catch {
                unlikeError = "Spotify didn't accept it. Try again in a moment."
            }
        }
    }

    private func actionRow(_ title: String, systemImage: String, disabled: Bool = false,
                           action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: Space.s) {
                Text(title).foregroundStyle(disabled ? Color.textTertiary : Color.textPrimary)
                Spacer(minLength: Space.s)
                Image(systemName: systemImage)
                    .font(.footnote.weight(.semibold)).foregroundStyle(Color.textTertiary)
                    .accessibilityHidden(true)
            }
            .font(.body)
            .padding(.horizontal, Space.m)
            .frame(minHeight: Size.minTarget + Space.xs)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
    }

    @ViewBuilder private var moreLikeThisRow: some View {
        let available = model.canSendMoreLikeThis(trackID: trackID)
        if sentMoreLikeThis {
            switch model.sendState {
            case let .sending(done, total):
                HStack(spacing: Space.s) {
                    ProgressView(value: Double(done), total: Double(max(total, 1)))
                        .tint(Color.accent)
                    Text("Sending… \(done)/\(total)").font(.footnote.monospacedDigit())
                        .foregroundStyle(Color.textSecondary)
                }
                .padding(.horizontal, Space.m)
                .frame(minHeight: Size.minTarget + Space.xs)
                .accessibilityElement(children: .combine)
            case let .success(n):
                HStack(spacing: Space.s) {
                    Label("Sent \(n) tracks to Now", systemImage: "checkmark")
                        .font(.footnote.weight(.semibold)).foregroundStyle(Color.success)
                    Spacer(minLength: Space.s)
                    if let url = model.nowURL {
                        Button("Listen") { openURL(url) }.font(.footnote.weight(.semibold))
                            .frame(minHeight: Size.minTarget)
                    }
                }
                .padding(.horizontal, Space.m)
                .frame(minHeight: Size.minTarget + Space.xs)
            case let .error(message):
                Button { startMoreLikeThis() } label: {
                    HStack(alignment: .firstTextBaseline, spacing: Space.s) {
                        Image(systemName: "xmark").foregroundStyle(Color.error).accessibilityHidden(true)
                        Text(message).foregroundStyle(Color.textPrimary).fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: Space.s)
                        Text("Retry").fontWeight(.semibold).foregroundStyle(Color.textPrimary)
                    }
                    .font(.footnote)
                    .padding(Space.m)
                    .frame(minHeight: Size.minTarget + Space.xs)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            case .idle:
                actionRow("More like this → Now", systemImage: "chevron.right", disabled: !available) { startMoreLikeThis() }
            }
        } else {
            actionRow("More like this → Now", systemImage: "chevron.right", disabled: !available) { startMoreLikeThis() }
                .accessibilityHint(available ? "Replaces Now with 100 tracks that sound like this one"
                                             : "No sound data for this track yet")
        }
    }

    private func startMoreLikeThis() {
        sentMoreLikeThis = true
        Task { await model.sendMoreLikeThis(trackID: trackID) }
    }
}

// MARK: - Small pieces

/// The 10 small energy blocks in the Energy number card.
struct TrackEnergyBlocks: View {
    let level: Int
    @ScaledMetric(relativeTo: .caption) private var height: CGFloat = 6

    var body: some View {
        HStack(spacing: 2) {
            ForEach(1...10, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1.5, style: .continuous)
                    .fill(i <= level ? Color.energy(i) : Color.fillControlActive)
                    .frame(height: height)
            }
        }
        .frame(maxWidth: 96)
    }
}

/// The "edited" mark next to a value the user corrected.
struct TrackEditedDot: View {
    var body: some View {
        Circle().fill(Color.textPrimary).frame(width: 5, height: 5).accessibilityHidden(true)
    }
}
