import CrateKit
import CrateUI
import SwiftUI

/// Section label row: mono label on the left, an optional trailing label or action on the right.
struct BuildSectionLabel<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(alignment: .center, spacing: Space.s) {
            Text(title).panelLabelStyle().accessibilityAddTraits(.isHeader)
            Spacer(minLength: Space.s)
            trailing
        }
    }
}

extension BuildSectionLabel where Trailing == EmptyView {
    init(_ title: String) { self.init(title: title) { EmptyView() } }
}

// MARK: - Preset pills

struct BuildPresetRow: View {
    @Environment(AppModel.self) private var model
    @Binding var loadedPresetID: String?
    @State private var deleting: Preset?
    var onEdit: (String) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Space.chipGap) {
                ForEach(model.snapshot.presets.presets) { pill($0) }
            }
            .padding(.horizontal, Space.screenMargin)
            .padding(.vertical, Space.xs)
        }
        .padding(.horizontal, -Space.screenMargin)
        .confirmationDialog("Delete \(deleting?.name ?? "preset")?", isPresented: Binding(
            get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible, presenting: deleting) { p in
            Button("Delete preset", role: .destructive) { delete(p.id) }
        } message: { _ in
            Text("It's removed from Build, the widgets and Siri.")
        }
    }

    private func delete(_ id: String) {
        if loadedPresetID == id { loadedPresetID = nil }
        Task { await model.editPresets { file in file.presets.removeAll { $0.id == id } } }
    }

    private func pill(_ p: Preset) -> some View {
        let loaded = loadedPresetID == p.id
        let same = model.build == BuildState(query: p.query, allStyles: model.allStyles)
        return PresetPill(p.name, color: PresetColor(rawValue: p.color) ?? .ink, symbol: p.icon,
                          isSelected: loaded && same, isModified: loaded && !same) {
            load(p)
        }
        .contextMenu {
            Button("Send now", systemImage: "paperplane") {
                load(p)
                Task { await model.send() }
            }
            Button("Edit", systemImage: "pencil") { onEdit(p.id) }
            let id = p.id
            Button(p.inWidget ? "Remove from widget" : "Add to widget",
                   systemImage: p.inWidget ? "rectangle.badge.minus" : "rectangle.badge.plus") {
                Task {
                    await model.editPresets { file in
                        if let i = file.presets.firstIndex(where: { $0.id == id }) { file.presets[i].inWidget.toggle() }
                    }
                }
            }
            Button("Delete…", systemImage: "trash", role: .destructive) { deleting = p }
        }
    }

    private func load(_ p: Preset) {
        withAnimation(CrateMotion.chip) {
            model.build = BuildState(query: p.query, allStyles: model.allStyles)
            loadedPresetID = p.id
        }
    }
}

// MARK: - Result card

/// Sticky result card: live count, "WHOLE LIBRARY" / "TRACKS MATCH", 4 covers, chevron (opens the list).
struct BuildResultCard: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    var onOpen: (String, [String]) -> Void

    var body: some View {
        let ids = model.buildMatchingIDs
        let covers = model.coverTrackIDs(ids)
        let count = model.matchCount
        Button {
            onOpen(model.build.isEmpty ? "Library" : "\(count.formatted()) tracks", ids)
        } label: {
            HStack(spacing: Space.m) {
                VStack(alignment: .leading, spacing: Space.xxs) {
                    Text(count.formatted())
                        .font(.numeralLarge)
                        .foregroundStyle(count == 0 ? Color.textTertiary : Color.textPrimary)
                        .countTransition(count)
                    Text(model.build.isEmpty ? "Whole library" : "Tracks match").panelLabelStyle()
                }
                Spacer(minLength: Space.s)
                if !dynamicTypeSize.isAccessibilitySize {
                    HStack(spacing: Space.xs) {
                        ForEach(0..<4, id: \.self) { i in
                            if i < covers.count, let t = model.index.tracks[covers[i]] {
                                CoverImage(url: t.cover.flatMap(URL.init(string:)), size: Size.coverStrip,
                                           tint: Color.family(model.familyHue(model.index.effective[covers[i]]?.family ?? "")))
                            } else {
                                RoundedRectangle(cornerRadius: Radius.cover, style: .continuous)
                                    .fill(Color.fillControl)
                                    .frame(width: Size.coverStrip, height: Size.coverStrip)
                            }
                        }
                    }
                    .accessibilityHidden(true)
                }
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.textTertiary)
            }
            .padding(Space.m)
            .crateCard()
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(count == 0)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(model.build.isEmpty ? "Whole library, \(count) tracks" : "\(count) tracks match")
        .accessibilityHint("Shows the tracks")
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - Empty state

/// "No tracks match" with up to three one-tap relaxations and their resulting counts (6f).
struct BuildEmptyState: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let relax = model.relaxations()
        VStack(alignment: .leading, spacing: Space.s) {
            Text("No tracks match").font(.headline).foregroundStyle(Color.textPrimary)
            if !relax.isEmpty {
                Text("Try one of these:").font(.secondary).foregroundStyle(Color.textSecondary)
                ChipFlow {
                    ForEach(relax) { r in
                        Button {
                            withAnimation(CrateMotion.chip) { r.apply(&model.build) }
                        } label: {
                            HStack(spacing: 5) {
                                Text(r.title).fontWeight(.medium)
                                Text(r.count.formatted())
                                    .font(.system(.footnote, design: .rounded, weight: .medium))
                                    .foregroundStyle(Color.textTertiary)
                            }
                            .chipChrome(Color.fillControl, foreground: Color.textPrimary)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("\(r.title), \(r.count) tracks")
                    }
                }
            } else {
                Text("Remove a filter to see tracks.").font(.secondary).foregroundStyle(Color.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Send bar

/// The primary button pinned 12 pt above the tab bar, fading into `bgBase`.
struct BuildSendBar: View {
    @Environment(AppModel.self) private var model
    var openURL: (URL) -> Void

    var body: some View {
        PrimaryButton(state, secondaryTitle: "Send again", secondaryAction: { Task { await model.send() } },
                      liftOffCovers: model.lastSentCovers) {
            if case .success = model.sendState {
                openURL(model.nowURL ?? URL(string: "spotify:")!)
            } else {
                Task { await model.send() }
            }
        }
        .padding(.horizontal, Space.screenMargin)
        .padding(.top, Space.xl)
        .padding(.bottom, Space.m)
        .background {
            VStack(spacing: 0) {
                LinearGradient(colors: [Color.bgBase.opacity(0), Color.bgBase], startPoint: .top, endPoint: .bottom)
                    .frame(height: Space.xl)
                Color.bgBase
            }
            .allowsHitTesting(false)
        }
    }

    private var state: PrimaryButtonState {
        switch model.sendState {
        case .idle:
            model.sendCount == 0 ? .disabled(title: "No tracks to send") : .idle(title: "Send \(model.sendCount) to Now")
        case let .sending(done, total):
            .sending(progress: total > 0 ? Double(done) / Double(total) : 0, counter: "\(done)/\(total)")
        case let .success(n):
            .success(message: "Sent \(n) tracks to Now", title: "Listen on Spotify")
        case let .error(message):
            .error(message: message, title: "Retry")
        }
    }
}
