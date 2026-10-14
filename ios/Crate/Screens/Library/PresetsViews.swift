import CrateKit
import CrateUI
import SwiftUI
import UniformTypeIdentifiers

// MARK: - Presets manager (6v, 3j)

/// Presets manager: "In widget" (max 4) and "Other". Drag to reorder or between sections, swipe to delete, tap to
/// edit. Push it inside a NavigationStack. Owner: Weekly/Library agent.
struct PresetsManagerView: View {
    @Environment(AppModel.self) private var model
    @State private var editing: PresetSheetID?
    @State private var creating = false
    @State private var didApplyDebug = false

    static let widgetSlots = 4

    private var presets: [Preset] { model.snapshot.presets.presets }
    private var widgetIDs: [String] { Array(presets.filter(\.inWidget).prefix(Self.widgetSlots).map(\.id)) }
    private var otherIDs: [String] { let w = Set(widgetIDs); return presets.map(\.id).filter { !w.contains($0) } }

    var body: some View {
        List {
            Section {
                ForEach(widgetIDs, id: \.self) { id in row(id, inWidget: true) }
                    .onMove { from, to in var w = widgetIDs; w.move(fromOffsets: from, toOffset: to); apply(widget: w, other: otherIDs) }
                    .onDelete { delete(widgetIDs, at: $0) }
                    .onInsert(of: [.plainText]) { index, providers in receive(providers, toWidget: true, at: index) }
                if widgetIDs.isEmpty {
                    Text("Drag a preset here to show it in the widget.")
                        .font(.footnote).foregroundStyle(Color.textTertiary)
                        .listRowBackground(Color.bgCard)
                }
            } header: {
                PanelLabel("In widget · \(widgetIDs.count) of \(Self.widgetSlots)")
            }
            Section {
                ForEach(otherIDs, id: \.self) { id in row(id, inWidget: false) }
                    .onMove { from, to in var o = otherIDs; o.move(fromOffsets: from, toOffset: to); apply(widget: widgetIDs, other: o) }
                    .onDelete { delete(otherIDs, at: $0) }
                    .onInsert(of: [.plainText]) { index, providers in receive(providers, toWidget: false, at: index) }
            } header: {
                PanelLabel("Other")
            } footer: {
                Text("Drag between sections to add or remove from the widget. Tap a preset to rename it or change its icon.")
                    .font(.footnote).foregroundStyle(Color.textTertiary)
            }
        }
        .listStyle(.insetGrouped)
        .listSectionSpacing(.compact)
        .scrollContentBackground(.hidden)
        .background(Color.bgBase)
        .navigationTitle("Presets")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { EditButton() }
            ToolbarItem(placement: .topBarTrailing) {
                Button { creating = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Save Build filters as a preset")
            }
        }
        .overlay {
            if presets.isEmpty {
                ContentUnavailableView("No presets yet", systemImage: "bookmark",
                                       description: Text("Set filters in Build, then save them as a preset."))
            }
        }
        .sheet(item: $editing) { SavePresetView(presetID: $0.id) }
        .sheet(isPresented: $creating) { SavePresetView(presetID: nil) }
        .onAppear(perform: applyDebug)
    }

    private func row(_ id: String, inWidget: Bool) -> some View {
        Group {
            if let p = presets.first(where: { $0.id == id }) {
                Button { editing = PresetSheetID(id: id) } label: {
                    PresetManagerRow(preset: p, summary: PresetSummary(p.query, model: model, includeAny: false))
                }
                .buttonStyle(.plain)
                .listRowBackground(Color.bgCard)
                .itemProvider { NSItemProvider(object: id as NSString) }
                .swipeActions(edge: .leading) {
                    Button(inWidget ? "Remove from widget" : "Add to widget") { toggleWidget(id) }
                        .tint(Color.accent)
                        .disabled(!inWidget && widgetIDs.count >= Self.widgetSlots)
                }
                .accessibilityAction(named: inWidget ? "Remove from widget" : "Add to widget") { toggleWidget(id) }
            }
        }
    }

    // MARK: Edits

    private func toggleWidget(_ id: String) {
        var w = widgetIDs, o = otherIDs
        if let i = w.firstIndex(of: id) { w.remove(at: i); o.insert(id, at: 0) } else if w.count < Self.widgetSlots {
            o.removeAll { $0 == id }; w.append(id)
        }
        apply(widget: w, other: o)
    }

    private func receive(_ providers: [NSItemProvider], toWidget: Bool, at index: Int) {
        guard let provider = providers.first else { return }
        _ = provider.loadObject(ofClass: NSString.self) { object, _ in
            guard let id = (object as? NSString).map(String.init) else { return }
            Task { @MainActor in move(id, toWidget: toWidget, at: index) }
        }
    }

    private func move(_ id: String, toWidget: Bool, at index: Int) {
        var w = widgetIDs.filter { $0 != id }, o = otherIDs.filter { $0 != id }
        if toWidget {
            w.insert(id, at: min(index, w.count))
            // Full: the last widget preset moves to the top of Other.
            while w.count > Self.widgetSlots { o.insert(w.removeLast(), at: 0) }
        } else {
            o.insert(id, at: min(index, o.count))
        }
        apply(widget: w, other: o)
    }

    private func apply(widget: [String], other: [String]) {
        let order = widget + other
        let inWidget = Set(widget)
        Task {
            await model.editPresets { file in
                var byID: [String: Preset] = [:]
                for p in file.presets where byID[p.id] == nil { byID[p.id] = p }
                var next = order.compactMap { byID[$0] }
                next += file.presets.filter { !order.contains($0.id) }
                for i in next.indices { next[i].inWidget = inWidget.contains(next[i].id) }
                file.presets = next
            }
        }
    }

    private func delete(_ ids: [String], at offsets: IndexSet) {
        let gone = Set(offsets.map { ids[$0] })
        Task { await model.editPresets { $0.presets.removeAll { gone.contains($0.id) } } }
    }

    private func applyDebug() {
        #if DEBUG
        guard !didApplyDebug else { return }
        didApplyDebug = true
        if DebugLaunch.screen == "preset-edit" { editing = presets.first { $0.inWidget }.map { PresetSheetID(id: $0.id) } }
        #endif
    }
}

struct PresetSheetID: Identifiable, Hashable { let id: String }

/// A row in the presets manager: 32 pt tile, name, summary line, drag glyph.
struct PresetManagerRow: View {
    let preset: Preset
    let summary: PresetSummary
    @Environment(\.editMode) private var editMode

    var body: some View {
        HStack(spacing: Space.m) {
            PresetTile(preset)
            VStack(alignment: .leading, spacing: Space.xxs) {
                Text(preset.name).font(.body.weight(.semibold)).foregroundStyle(Color.textPrimary)
                summary.text.font(.footnote).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if editMode?.wrappedValue.isEditing != true {
                Image(systemName: "line.3.horizontal").font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.textTertiary).accessibilityHidden(true)
            }
        }
        .frame(minHeight: Size.minTarget + Space.xs)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(preset.name)
        .accessibilityValue(summary.spoken)
        .accessibilityHint("Opens the preset to rename it or change its icon.")
    }
}

// MARK: - Save / edit preset sheet (6u, 3k)

/// Save as preset (`presetID == nil`, saves the current Build filters) or edit an existing preset's name, icon,
/// colour and widget slot. Presents its own NavigationStack. Owner: Weekly/Library agent.
struct SavePresetView: View {
    var presetID: String?
    /// Prefill for a new preset's name.
    var initialName: String = ""

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var icon = "bookmark"
    @State private var color: PresetColor = .ink
    @State private var inWidget = false
    @State private var loaded = false
    @State private var confirmDelete = false
    @FocusState private var nameFocused: Bool

    static let icons: [String] = {
        var seen = Set<String>()
        let extra = ["bookmark", "figure.run", "sun.horizon.fill", "headphones", "music.note", "heart.fill",
                     "star.fill", "dumbbell.fill", "bed.double.fill", "airplane", "beach.umbrella.fill", "tram.fill"]
        return (VibeSymbols.picker + extra).filter { seen.insert($0).inserted }
    }()

    private var existing: Preset? { presetID.flatMap { id in model.snapshot.presets.presets.first { $0.id == id } } }
    private var query: Query { existing?.query ?? model.query }
    private var widgetFull: Bool {
        model.snapshot.presets.presets.filter { $0.inWidget && $0.id != presetID }.count >= PresetsManagerView.widgetSlots
    }
    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Space.sectionGap) {
                    PresetTile(color: color, symbol: icon, size: 64)
                        .frame(maxWidth: .infinity)
                        .padding(.top, Space.s)

                    TextField("Name", text: $name)
                        .font(.body.weight(.semibold))
                        .focused($nameFocused)
                        .submitLabel(.done)
                        .padding(.horizontal, Space.m)
                        .frame(minHeight: Size.minTarget + Space.xs)
                        .crateCard()
                        .accessibilityLabel("Preset name")

                    VStack(alignment: .leading, spacing: Space.xs) {
                        PanelLabel("Filters")
                        PresetSummary(query, model: model, includeAny: true).text
                            .font(.subheadline)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityLabel(PresetSummary(query, model: model, includeAny: true).spoken)
                        Text(existing == nil ? "Saves the filters set in Build now."
                             : "Load it in Build to change filters, then save again.")
                            .font(.footnote).foregroundStyle(Color.textTertiary)
                    }

                    VStack(alignment: .leading, spacing: Space.labelGap) {
                        PanelLabel("Colour")
                        HStack(spacing: Space.s) {
                            ForEach(PresetColor.allCases, id: \.self) { c in
                                Button { color = c } label: {
                                    Circle().fill(c.tile)
                                        .overlay { if c == color { Image(systemName: "checkmark").font(.footnote.weight(.bold)).foregroundStyle(c.onTile) } }
                                        .overlay { Circle().strokeBorder(Color.strokeCard, lineWidth: Stroke.hairline) }
                                        .frame(width: 32, height: 32)
                                        .frame(maxWidth: .infinity, minHeight: Size.minTarget)
                                        .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(c.rawValue.capitalized)
                                .accessibilityAddTraits(c == color ? .isSelected : [])
                            }
                        }
                        .sensoryFeedback(.selection, trigger: color)
                    }

                    VStack(alignment: .leading, spacing: Space.labelGap) {
                        PanelLabel("Icon")
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: Size.minTarget), spacing: Space.s)], spacing: Space.s) {
                            ForEach(Self.icons, id: \.self) { s in
                                Button { icon = s } label: {
                                    Image(systemName: s)
                                        .font(.body.weight(.semibold))
                                        .foregroundStyle(s == icon ? Color.onAccent : Color.textPrimary)
                                        .frame(width: Size.minTarget, height: Size.minTarget)
                                        .background(s == icon ? Color.accent : Color.fillControl,
                                                    in: RoundedRectangle(cornerRadius: Radius.chip, style: .continuous))
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(s.replacingOccurrences(of: ".fill", with: "").replacingOccurrences(of: ".", with: " "))
                                .accessibilityAddTraits(s == icon ? .isSelected : [])
                            }
                        }
                        .sensoryFeedback(.selection, trigger: icon)
                    }

                    VStack(alignment: .leading, spacing: Space.labelGap) {
                        PanelLabel("Widget")
                        Toggle("Show in widget", isOn: $inWidget)
                            .disabled(widgetFull && !inWidget)
                            .padding(.horizontal, Space.m)
                            .frame(minHeight: Size.minTarget + Space.xs)
                            .crateCard()
                        if widgetFull && !inWidget {
                            Text("The widget shows \(PresetsManagerView.widgetSlots) presets. Remove one in Presets first.")
                                .font(.footnote).foregroundStyle(Color.textTertiary)
                        }
                    }

                    if existing != nil {
                        Button("Delete preset", role: .destructive) { confirmDelete = true }
                            .font(.body)
                            .foregroundStyle(Color.error)
                            .frame(maxWidth: .infinity, minHeight: Size.minTarget)
                            .confirmationDialog("Delete \(trimmedName.isEmpty ? "this preset" : trimmedName)?",
                                                isPresented: $confirmDelete, titleVisibility: .visible) {
                                Button("Delete preset", role: .destructive) { delete() }
                            }
                    }
                }
                .padding(.horizontal, Space.screenMargin)
                .padding(.bottom, Space.xl)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Color.bgSheet)
            .navigationTitle(existing == nil ? "Save as preset" : "Edit preset")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.fontWeight(.semibold).disabled(trimmedName.isEmpty)
                }
            }
        }
        .presentationBackground(Color.bgSheet)
        .onAppear(perform: load)
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        if let p = existing {
            name = p.name
            icon = p.icon
            color = PresetColor(rawValue: p.color) ?? .ink
            inWidget = p.inWidget
        } else {
            name = initialName
            let used = Set(model.snapshot.presets.presets.map(\.color))
            color = PresetColor.allCases.first { $0 != .ink && !used.contains($0.rawValue) } ?? .ink
            if name.isEmpty { nameFocused = true }
        }
    }

    private func save() {
        let name = trimmedName, icon = icon, color = color.rawValue
        let inWidget = inWidget && !(widgetFull && existing?.inWidget != true)
        if let id = presetID, existing != nil {
            Task {
                await model.editPresets { file in
                    guard let i = file.presets.firstIndex(where: { $0.id == id }) else { return }
                    file.presets[i].name = name
                    file.presets[i].icon = icon
                    file.presets[i].color = color
                    file.presets[i].inWidget = inWidget
                }
            }
        } else {
            let preset = Preset(id: LibraryText.slug(name, existing: Set(model.snapshot.presets.presets.map(\.id))),
                                name: name, icon: icon, color: color, query: model.query, inWidget: inWidget)
            Task { await model.editPresets { $0.presets.append(preset) } }
        }
        dismiss()
    }

    private func delete() {
        guard let id = presetID else { return }
        Task { await model.editPresets { $0.presets.removeAll { $0.id == id } } }
        dismiss()
    }
}
