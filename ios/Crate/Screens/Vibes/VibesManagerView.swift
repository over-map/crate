import CrateKit
import CrateUI
import SwiftUI

/// Vibes manager (6l): Library → Vibes. Lists vibes in Build order; long-press and drag to reorder (writes `order`),
/// swipe to delete, tap to edit, "＋" for a new vibe. Pushed inside the caller's NavigationStack. Owner: Vibes agent.
struct VibesManagerView: View {
    init() {}

    @Environment(AppModel.self) private var model
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var editing: VibeEditorTarget?
    @State private var pendingDelete: String?

    private var vibes: [(id: String, vibe: Vibe)] { model.snapshot.vibes.ordered }

    var body: some View {
        List {
            Section {
                ForEach(vibes, id: \.id) { entry in
                    row(id: entry.id, vibe: entry.vibe)
                }
                .onMove(perform: move)
                .onDelete { offsets in pendingDelete = offsets.first.map { vibes[$0].id } }
                Button {
                    editing = VibeEditorTarget(vibeID: nil)
                } label: {
                    Label("New vibe", systemImage: "plus")
                        .font(.body.weight(.medium))
                        .foregroundStyle(Color.textPrimary)
                        .frame(minHeight: Size.minTarget)
                }
                .listRowBackground(Color.bgCard)
            } header: {
                Text("A vibe is defined by example. The order here is the order on Build.")
                    .font(.secondary)
                    .foregroundStyle(Color.textSecondary)
                    .textCase(nil)
                    .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: Space.m, trailing: 0))
            } footer: {
                Text("Touch and hold a vibe, then drag to reorder.")
                    .font(.footnote).foregroundStyle(Color.textTertiary)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Color.bgBase)
        .navigationTitle("Vibes")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    editing = VibeEditorTarget(vibeID: nil)
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("New vibe")
            }
        }
        .sheet(item: $editing) { target in VibeEditorView(vibeID: target.vibeID) }
        .confirmationDialog(deleteTitle, isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
                            titleVisibility: .visible) {
            Button("Delete vibe", role: .destructive) { if let id = pendingDelete { delete(id) } }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("Presets that use it will skip it. This can't be undone.")
        }
        .tint(Color.accent)
    }

    private var deleteTitle: String {
        let name = pendingDelete.flatMap { model.snapshot.vibes.vibes[$0]?.name } ?? "vibe"
        return "Delete \(name)?"
    }

    private func row(id: String, vibe: Vibe) -> some View {
        let count = model.index.vibeMembers[id]?.count ?? 0
        let seeded = VibeText.hasSeeds(vibe)
        return Button {
            editing = VibeEditorTarget(vibeID: id)
        } label: {
            if typeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: Space.xxs) {
                    Label { Text(vibe.name) } icon: { Image(systemName: VibeText.symbol(id: id, vibe: vibe)) }
                        .font(.body.weight(.semibold)).foregroundStyle(Color.textPrimary)
                    Text("\(count.formatted()) tracks · \(VibeText.summary(vibe))").font(.footnote)
                        .foregroundStyle(seeded ? Color.textSecondary : Color.textTertiary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, Space.xs)
                .contentShape(Rectangle())
            } else {
            HStack(spacing: Space.m) {
                VibeSymbolTile(symbol: VibeText.symbol(id: id, vibe: vibe))
                VStack(alignment: .leading, spacing: Space.xxs) {
                    Text(vibe.name).font(.body.weight(.semibold)).foregroundStyle(Color.textPrimary)
                    Text(VibeText.summary(vibe)).font(.footnote)
                        .foregroundStyle(seeded ? Color.textSecondary : Color.textTertiary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Text(count.formatted())
                    .font(.system(.body, design: .rounded, weight: .semibold)).monospacedDigit()
                    .foregroundStyle(seeded ? Color.textPrimary : Color.textTertiary)
                Image(systemName: "line.3.horizontal")
                    .font(.body).foregroundStyle(Color.textTertiary)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: Size.minTarget + Space.s)
            .contentShape(Rectangle())
            }
        }
        .buttonStyle(.plain)
        .listRowBackground(Color.bgCard)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(vibe.name), \(seeded ? "\(count) tracks" : "no seeds yet"), \(VibeText.summary(vibe))")
        .accessibilityHint("Opens the vibe editor")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(named: "Move up") { nudge(id, by: -1) }
        .accessibilityAction(named: "Move down") { nudge(id, by: 1) }
        .accessibilityAction(named: "Delete") { pendingDelete = id }
    }

    // MARK: Edits

    private func move(from source: IndexSet, to destination: Int) {
        var ids = vibes.map(\.id)
        ids.move(fromOffsets: source, toOffset: destination)
        writeOrder(ids)
    }

    private func nudge(_ id: String, by delta: Int) {
        var ids = vibes.map(\.id)
        guard let i = ids.firstIndex(of: id) else { return }
        let j = i + delta
        guard ids.indices.contains(j) else { return }
        ids.swapAt(i, j)
        writeOrder(ids)
    }

    private func writeOrder(_ ids: [String]) {
        let order = ids
        Task { await model.editVibes { $0.order = order } }
    }

    private func delete(_ id: String) {
        pendingDelete = nil
        Task {
            await model.editVibes { file in
                file.vibes[id] = nil
                file.order.removeAll { $0 == id }
            }
        }
    }
}

/// Which vibe the editor sheet opens (`nil` = new).
struct VibeEditorTarget: Identifiable, Hashable {
    var vibeID: String?
    var seedTrackID: String? = nil
    var id: String { (vibeID ?? "new") + "|" + (seedTrackID ?? "") }
}
