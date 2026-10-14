import CrateKit
import CrateUI
import SwiftUI

/// Edit track (6o, 2k): genre override, styles, vibes, BPM, key, energy, vocals. Edits a draft; Save writes
/// `overrides.json` through `model.editOverride`. Self-contained sheet. Owner: Tracks agent.
struct EditTagsView: View {
    var trackID: String

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    @State private var draft = TrackOverrideDraft()
    @State private var original = TrackOverrideDraft()
    @State private var loaded = false
    @State private var showAllFamilies = false
    @State private var showKeyWheel = false
    @State private var addingStyle = false
    @State private var confirmRevert = false
    /// Vibes the track is in, as edited here, and as it was when the sheet opened.
    @State private var vibesIn: Set<String> = []
    @State private var vibesInitial: Set<String> = []

    private var changed: Bool { draft != original || vibesIn != vibesInitial }

    var body: some View {
        NavigationStack {
            Group {
                if let base = TrackInfo(id: trackID, model: model) {
                    form(base)
                } else {
                    ContentUnavailableView("Track not in library", systemImage: "music.note")
                }
            }
            .background(Color.bgSheet)
            .navigationTitle("Edit track")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.fontWeight(.semibold).disabled(!changed)
                }
            }
        }
        .interactiveDismissDisabled(changed)
        .onAppear {
            guard !loaded else { return }
            original = TrackOverrideDraft(model.index.overrides[trackID])
            draft = original
            vibesInitial = Set(model.tracksVibes(containing: trackID).map(\.id))
            vibesIn = vibesInitial
            loaded = true
        }
        .sheet(isPresented: $addingStyle) {
            TrackStylePickerSheet { style in addStyle(style) }
        }
    }

    // MARK: Draft → preview

    /// The track as it would look with the draft applied.
    private func preview(_ base: TrackInfo) -> TrackInfo {
        var o = base.override ?? TrackOverride()
        draft.apply(to: &o)
        return TrackInfo(id: trackID, track: base.track, override: o.isEmpty ? nil : o, taxonomy: base.taxonomy)
    }

    private func form(_ base: TrackInfo) -> some View {
        let info = preview(base)
        return ScrollView {
            VStack(alignment: .leading, spacing: Space.sectionGap) {
                genre(info)
                styles(info)
                vibes()
                if info.isAnalysed || draft.bpm != nil {
                    bpm(info)
                }
                key(info)
                energy(info)
                vocals(info)
                if base.override != nil || draft != TrackOverrideDraft() {
                    Button(role: .destructive) { confirmRevert = true } label: {
                        Text("Revert all to automatic")
                            .font(.body)
                            .foregroundStyle(Color.error)
                            .frame(maxWidth: .infinity, minHeight: Size.minTarget)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .confirmationDialog("Revert all tags to automatic?", isPresented: $confirmRevert,
                                        titleVisibility: .visible) {
                        Button("Revert to automatic", role: .destructive) { revertAll() }
                    } message: {
                        Text("Your genre, style, BPM, key, energy and vocal corrections for this track are removed.")
                    }
                }
            }
            .padding(.horizontal, Space.screenMargin)
            .padding(.top, Space.s)
            .padding(.bottom, Space.xl)
        }
        .defaultScrollAnchor(DebugLaunch.scrollAnchor)
    }

    // MARK: Genre

    private func genre(_ info: TrackInfo) -> some View {
        VStack(alignment: .leading, spacing: Space.labelGap) {
            PanelLabel("Genre")
            HStack(spacing: Space.s) {
                StyleChip(style: info.familyName(info.family), family: info.family, edited: draft.family != nil)
                if let line = sourcesLine(info) {
                    Text(line).font(.footnote).foregroundStyle(Color.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: Space.s)
                if draft.family != nil {
                    Button("Reset") { draft.family = nil }
                        .font(.subheadline)
                        .foregroundStyle(Color.textPrimary)
                        .frame(minHeight: Size.minTarget)
                        .accessibilityLabel("Reset genre to what the sources said")
                }
            }
            familyList(info)
        }
    }

    private func sourcesLine(_ info: TrackInfo) -> String? {
        guard let src = info.sourcesFamily else { return nil }
        let name = info.familyName(src)
        if let c = info.track.consensus, let v = c.votes, let n = c.sources, n > 0 {
            return "Sources said \(name) (\(v) of \(n))"
        }
        return "Sources said \(name)"
    }

    private func familyList(_ info: TrackInfo) -> some View {
        let all = allFamilies(info)
        let shown = showAllFamilies ? all : shortFamilies(info, all: all)
        return VStack(spacing: 0) {
            ForEach(shown, id: \.self) { f in
                Button { pickFamily(f, info: info) } label: {
                    HStack(spacing: Space.m) {
                        FamilyDot(f)
                        Text(info.familyName(f)).font(.body).foregroundStyle(Color.textPrimary)
                        Spacer(minLength: Space.s)
                        if f == info.sourcesFamily {
                            Text("sources").font(.footnote).foregroundStyle(Color.textTertiary)
                        }
                        if f == info.family {
                            Image(systemName: "checkmark").font(.body.weight(.semibold))
                                .foregroundStyle(Color.textPrimary)
                        }
                    }
                    .padding(.horizontal, Space.m)
                    .frame(minHeight: Size.minTarget + Space.xs)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(info.familyName(f) + (f == info.sourcesFamily ? ", what the sources said" : ""))
                .accessibilityAddTraits(f == info.family ? .isSelected : [])
                Divider().overlay(Color.separator)
            }
            Button { showAllFamilies.toggle() } label: {
                HStack(spacing: Space.xs) {
                    Text(showAllFamilies ? "Fewer genres" : "All \(all.count) genres")
                    Image(systemName: showAllFamilies ? "chevron.up" : "chevron.right").font(.caption.weight(.semibold))
                }
                .font(.footnote)
                .foregroundStyle(Color.textSecondary)
                .padding(.horizontal, Space.m)
                .frame(maxWidth: .infinity, minHeight: Size.minTarget, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .crateCard()
    }

    private func allFamilies(_ info: TrackInfo) -> [Family] {
        let ids = info.taxonomy.families.isEmpty ? Family.allCases.map(\.rawValue) : info.taxonomy.families
        return ids.compactMap(Family.init(rawValue:)).filter { $0 != .none }
    }

    /// Up to 5: the families the sources voted for (most votes first), the current pick, then the library's
    /// biggest families.
    private func shortFamilies(_ info: TrackInfo, all: [Family]) -> [Family] {
        var votes: [String: Int] = [:]
        for fams in (info.track.consensus?.bySource ?? [:]).values { for f in fams { votes[f, default: 0] += 1 } }
        var out: [Family] = []
        func add(_ f: Family?) { if let f, f != .none, !out.contains(f), out.count < 5 { out.append(f) } }
        add(info.sourcesFamily)
        add(info.family)
        for f in votes.sorted(by: { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }) {
            add(Family(rawValue: f.key))
        }
        for f in model.index.families { add(Family(rawValue: f.family)) }
        for f in all { add(f) }
        return out
    }

    private func pickFamily(_ f: Family, info: TrackInfo) {
        draft.family = (f == info.sourcesFamily) ? nil : f.rawValue
    }

    // MARK: Styles

    private func styles(_ info: TrackInfo) -> some View {
        let current = info.displayStyles
        let removed = draft.stylesRemove.filter { !current.contains($0) }
        return VStack(alignment: .leading, spacing: Space.labelGap) {
            PanelLabel("Styles")
            ChipFlow {
                ForEach(Array(current.enumerated()), id: \.element) { i, s in
                    StyleChip(style: info.styleName(s), canonical: s, family: info.family,
                              edited: info.isStyleEdited(s), onRemove: { removeStyle(s, info: info) })
                        .contextMenu {
                            if i > 0 {
                                Button("Make main style", systemImage: "arrow.up.to.line") { makePrimary(s, info: info) }
                            }
                            Button("Remove", systemImage: "minus.circle") { removeStyle(s, info: info) }
                        }
                        .accessibilityAction(named: Text("Make main style")) { makePrimary(s, info: info) }
                }
                ForEach(removed, id: \.self) { s in
                    StyleChip(style: info.styleName(s), canonical: s, family: info.family, variant: .removed,
                              onUndo: { draft.stylesRemove.removeAll { $0 == s } })
                }
                AddChip("Style") { addingStyle = true }
            }
            if current.count > 1 {
                Text("The first style decides the genre match. Touch and hold a style to make it the main one.")
                    .font(.footnote).foregroundStyle(Color.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func removeStyle(_ s: String, info: TrackInfo) {
        draft.stylesAdd.removeAll { $0 == s }
        if info.track.auto?.styles?[s] != nil, !draft.stylesRemove.contains(s) { draft.stylesRemove.append(s) }
    }

    private func makePrimary(_ s: String, info: TrackInfo) {
        draft.stylesRemove.removeAll { $0 == s }
        draft.stylesAdd = [s] + draft.stylesAdd.filter { $0 != s }
    }

    private func addStyle(_ s: String) {
        guard let base = TrackInfo(id: trackID, model: model) else { return }
        let info = preview(base)
        draft.stylesRemove.removeAll { $0 == s }
        guard !draft.stylesAdd.contains(s) else { return }
        // The first added style becomes the primary; keep the current primary first when adding a secondary.
        if draft.stylesAdd.isEmpty, let primary = info.effective.primaryStyle, primary != s {
            draft.stylesAdd = [primary, s]
        } else {
            draft.stylesAdd.append(s)
        }
    }

    // MARK: BPM

    private func bpm(_ info: TrackInfo) -> some View {
        let auto = info.track.auto
        let value = draft.bpm ?? auto?.bpm ?? 120
        let approx = draft.bpm == nil && (auto?.bpmConf ?? 1) < 0.5
        let candidates = (auto?.bpmCandidates ?? []).filter { abs($0 - value) >= 0.5 }
        return VStack(alignment: .leading, spacing: Space.s) {
            HStack(spacing: Space.xs) {
                PanelLabel("BPM")
                if draft.bpm != nil { TrackEditedDot() }
            }
            let controls = HStack(spacing: Space.s) {
                segmented([("÷2", "Halve", { setBPM(value / 2, info: info) }),
                           ("×2", "Double", { setBPM(value * 2, info: info) })])
                segmented([("−", "Lower by 1", { setBPM(value.rounded() - 1, info: info) }),
                           ("+", "Raise by 1", { setBPM(value.rounded() + 1, info: info) })])
            }
            if dynamicTypeSize.isAccessibilitySize {
                BPMText(value: Int(value.rounded()), approx: approx, font: .numeralHero)
                controls
            } else {
                HStack {
                    BPMText(value: Int(value.rounded()), approx: approx, font: .numeralHero)
                    Spacer(minLength: Space.s)
                    controls
                }
            }
            VStack(alignment: .leading, spacing: Space.xs) {
                Text(bpmCaption(info, value: value)).font(.footnote).foregroundStyle(Color.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if !candidates.isEmpty {
                    HStack(spacing: Space.s) {
                        Text("Other candidates").font(.footnote).foregroundStyle(Color.textTertiary)
                        ForEach(candidates, id: \.self) { c in
                            Button { setBPM(c, info: info) } label: {
                                Text(verbatim: String(Int(c.rounded())))
                                    .font(.footnote.weight(.semibold).monospacedDigit())
                                    .foregroundStyle(Color.textPrimary)
                                    .padding(.horizontal, Space.s)
                                    .frame(minHeight: 28)
                                    .background(Color.fillControl, in: RoundedRectangle(cornerRadius: Radius.badge))
                                    .frame(minHeight: Size.minTarget)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Use \(Int(c.rounded())) BPM")
                        }
                    }
                }
            }
        }
        .padding(Space.m)
        .crateCard()
        .accessibilityElement(children: .contain)
    }

    private func bpmCaption(_ info: TrackInfo, value: Double) -> String {
        guard let auto = info.track.auto, let detected = auto.bpm else { return "Set by you" }
        let d = Int(detected.rounded())
        var s = "Detected \(d)"
        if let c = auto.bpmConf { s += " (\(Int((c * 100).rounded()))%)" }
        s += (auto.bpmConf ?? 1) < 0.5 ? " · uncertain beat" : " · confident"
        if draft.bpm != nil { s = "Set by you · " + s }
        return s
    }

    private func setBPM(_ v: Double, info: TrackInfo) {
        let clamped = min(250, max(40, v))
        if let auto = info.track.auto?.bpm, abs(auto - clamped) < 0.5 { draft.bpm = nil } else { draft.bpm = clamped.rounded() }
    }

    private func segmented(_ items: [(String, String, () -> Void)]) -> some View {
        HStack(spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.offset) { i, item in
                if i > 0 { Rectangle().fill(Color.separator).frame(width: 1, height: 20) }
                Button(action: item.2) {
                    Text(item.0).font(.subheadline.weight(.semibold)).foregroundStyle(Color.textPrimary)
                        .frame(minWidth: Size.minTarget, minHeight: Size.minTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(item.1)
            }
        }
        .background(Color.fillControl, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
    }

    // MARK: Key

    private func key(_ info: TrackInfo) -> some View {
        let autoKey = info.track.auto?.key
        let selection = Binding<String?>(
            get: { draft.key ?? autoKey },
            set: { new in draft.key = (new == nil || new == autoKey) ? nil : new }
        )
        return VStack(alignment: .leading, spacing: Space.m) {
            Button { withAnimation(CrateMotion.chip) { showKeyWheel.toggle() } } label: {
                HStack(spacing: Space.s) {
                    PanelLabel("Key")
                    if draft.key != nil { TrackEditedDot() }
                    Spacer(minLength: Space.s)
                    CamelotBadge(code: selection.wrappedValue, size: .small, showsKeyName: true)
                    Image(systemName: showKeyWheel ? "chevron.up" : "chevron.right")
                        .font(.footnote.weight(.semibold)).foregroundStyle(Color.textTertiary)
                }
                .frame(minHeight: Size.minTarget)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Key")
            .accessibilityValue((selection.wrappedValue.flatMap { Camelot($0) }.map { "\($0.code), \($0.name)" } ?? "unknown")
                                + (draft.key != nil ? ", edited" : ""))
            .accessibilityHint(showKeyWheel ? "Hides the key wheel" : "Shows the key wheel")
            if showKeyWheel {
                CamelotWheel(selection: selection, compatible: false)
                    .frame(maxWidth: 320)
                    .aspectRatio(1, contentMode: .fit)
                    .frame(maxWidth: .infinity)
                if let k = autoKey, let c = Camelot(k) {
                    Text("Detected \(c.code) · \(c.name)" + (info.track.auto?.keyConf.map { " (\(Int(($0 * 100).rounded()))%)" } ?? ""))
                        .font(.footnote).foregroundStyle(Color.textSecondary)
                }
            }
        }
        .padding(.horizontal, Space.m)
        .padding(.vertical, Space.xs)
        .crateCard()
    }

    // MARK: Energy

    private func energy(_ info: TrackInfo) -> some View {
        let autoEnergy = info.track.auto?.energy
        let level = Binding<Int>(
            get: { draft.energy ?? autoEnergy ?? 5 },
            set: { new in draft.energy = (new == autoEnergy) ? nil : new }
        )
        return VStack(alignment: .leading, spacing: Space.s) {
            HStack(alignment: .firstTextBaseline, spacing: Space.xs) {
                PanelLabel("Energy")
                if draft.energy != nil { TrackEditedDot() }
                Spacer(minLength: Space.s)
                if draft.energy != nil, let a = autoEnergy {
                    Text("was \(a)").font(.footnote).foregroundStyle(Color.textSecondary)
                }
                Text(verbatim: String(level.wrappedValue)).font(.numeralMedium).foregroundStyle(Color.textPrimary)
                    .contentTransition(.numericText())
                segmented([("−", "Lower energy", { level.wrappedValue = max(1, level.wrappedValue - 1) }),
                           ("+", "Raise energy", { level.wrappedValue = min(10, level.wrappedValue + 1) })])
                    .accessibilityHidden(true)
            }
            TrackEnergyLevelControl(level: level)
            Text("Tap a block or use − +. Vibes use this value too.")
                .font(.footnote).foregroundStyle(Color.textSecondary)
        }
        .padding(Space.m)
        .crateCard()
    }

    // MARK: Vocals

    private func vocals(_ info: TrackInfo) -> some View {
        let autoVocal = info.track.auto?.vocal.map { $0 >= EffectiveTags.vocalThreshold }
        let isOn = Binding<Bool>(
            get: { draft.vocal ?? autoVocal ?? false },
            set: { new in draft.vocal = (new == autoVocal) ? nil : new }
        )
        return VStack(alignment: .leading, spacing: Space.xxs) {
            Toggle(isOn: isOn) {
                HStack(spacing: Space.xs) {
                    Text("Vocals").font(.body).foregroundStyle(Color.textPrimary)
                    if draft.vocal != nil { TrackEditedDot() }
                }
            }
            .tint(Color.accent)
            .frame(minHeight: Size.minTarget)
            if let p = info.track.auto?.vocal {
                Text("Detected \(p >= EffectiveTags.vocalThreshold ? "vocal" : "instrumental") (\(Int((p * 100).rounded()))% vocal)")
                    .font(.footnote).foregroundStyle(Color.textSecondary)
            }
        }
        .padding(.horizontal, Space.m)
        .padding(.vertical, Space.s)
        .crateCard()
    }

    // MARK: Vibes

    /// Every vibe as a chip; selected = this track is in it. Ticking makes the track a seed (an example of the
    /// vibe), unticking adds it to the vibe's `exclude`. Written on Save in one `editVibes`.
    private func vibes() -> some View {
        let all = model.snapshot.vibes.ordered
        return VStack(alignment: .leading, spacing: Space.labelGap) {
            HStack(spacing: Space.xs) {
                PanelLabel("Vibes", trailing: vibesIn.isEmpty ? nil : "In \(vibesIn.count)")
                if vibesIn != vibesInitial { TrackEditedDot() }
            }
            if all.isEmpty {
                Text("No vibes yet. Create them in Library → Vibes.").font(.footnote).foregroundStyle(Color.textSecondary)
            } else {
                ChipFlow {
                    ForEach(all, id: \.id) { entry in
                        let on = vibesIn.contains(entry.id)
                        VibeChip(entry.vibe.name, symbol: model.tracksVibeSymbol(id: entry.id, vibe: entry.vibe), count: nil,
                                 isSelected: on) {
                            withAnimation(CrateMotion.chip) {
                                if on { vibesIn.remove(entry.id) } else { vibesIn.insert(entry.id) }
                            }
                        }
                        .accessibilityHint(on ? "Takes this track out of the vibe" : "Adds this track to the vibe")
                    }
                }
                Text("Tick a vibe to add this track as one of its examples; untick it to keep the track out.")
                    .font(.footnote).foregroundStyle(Color.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: Save

    private func save() {
        let d = draft, tagsChanged = draft != original
        let id = trackID
        let added = vibesIn.subtracting(vibesInitial), removed = vibesInitial.subtracting(vibesIn)
        Task {
            if tagsChanged { await model.editOverride(trackID: id) { o in d.apply(to: &o) } }
            if !added.isEmpty || !removed.isEmpty {
                await model.editVibes { file in
                    for vid in added {
                        guard var v = file.vibes[vid] else { continue }
                        v.exclude.removeAll { $0 == id }
                        if !v.seeds.contains(id) { v.seeds.append(id) }
                        file.vibes[vid] = v
                    }
                    for vid in removed {
                        guard var v = file.vibes[vid] else { continue }
                        v.seeds.removeAll { $0 == id }
                        if !v.exclude.contains(id) { v.exclude.insert(id, at: 0) }
                        file.vibes[vid] = v
                    }
                }
            }
            dismiss()
        }
    }

    private func revertAll() {
        let id = trackID
        Task {
            await model.editOverride(trackID: id) { o in
                let cleared = TrackOverrideDraft()
                cleared.apply(to: &o)
            }
            dismiss()
        }
    }
}

// MARK: - Draft

/// The override fields Edit tags manages. `nil` / empty = automatic.
struct TrackOverrideDraft: Equatable, Sendable {
    var family: String?
    var stylesAdd: [String] = []
    var stylesRemove: [String] = []
    var bpm: Double?
    var key: String?
    var energy: Int?
    var vocal: Bool?

    init() {}

    init(_ o: TrackOverride?) {
        family = o?.family.flatMap { $0.isEmpty ? nil : $0 }
        stylesAdd = o?.stylesAdd ?? []
        stylesRemove = o?.stylesRemove ?? []
        bpm = o?.bpm
        key = o?.key
        energy = o?.energy
        vocal = o?.vocal
    }

    func apply(to o: inout TrackOverride) {
        o.family = family
        o.stylesAdd = stylesAdd.isEmpty ? nil : stylesAdd
        o.stylesRemove = stylesRemove.isEmpty ? nil : stylesRemove
        o.bpm = bpm
        o.key = key
        o.energy = energy
        o.vocal = vocal
    }
}

// MARK: - Energy level control

/// A single energy value 1–10: 10 blocks (26 pt, 44 at accessibility sizes); tap a block to set.
/// Each block is a button: a drag gesture here lost to the sheet's scroll view on iOS 26 (user report 2026-10-13).
struct TrackEnergyLevelControl: View {
    @Binding var level: Int
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let height = dynamicTypeSize.isAccessibilitySize ? Size.minTarget : Size.energyBlock
        HStack(spacing: 3) {
            ForEach(1...10, id: \.self) { i in
                Button { level = i } label: {
                    RoundedRectangle(cornerRadius: Radius.block, style: .continuous)
                        .fill(i <= level ? Color.energy(i) : Color.fillControl)
                        .frame(height: height)
                        .frame(maxWidth: .infinity, minHeight: Size.minTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .sensoryFeedback(.selection, trigger: level)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Energy")
        .accessibilityValue("\(level) out of 10")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: level = min(10, level + 1)
            case .decrement: level = max(1, level - 1)
            @unknown default: break
            }
        }
    }
}

// MARK: - Style picker

/// "＋ Style": search every style in the taxonomy (or, without one, every style the library uses).
struct TrackStylePickerSheet: View {
    var onPick: (String) -> Void
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                ForEach(results, id: \.style) { r in
                    Button {
                        onPick(r.style)
                        dismiss()
                    } label: {
                        HStack {
                            let label = model.snapshot.taxonomy.label(forStyle: r.style)
                            Text(label.styleDisplay).foregroundStyle(Color.textPrimary)
                            Text("· \(label.genreDisplay)").font(.footnote).foregroundStyle(Color.textSecondary)
                            Spacer()
                            if r.count > 0 {
                                Text(r.count.formatted()).font(.footnote.monospacedDigit())
                                    .foregroundStyle(Color.textTertiary)
                            }
                        }
                        .frame(minHeight: Size.minTarget)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.bgCard)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Color.bgSheet)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search styles")
            .navigationTitle("Add style")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
    }

    private var results: [(style: String, count: Int)] {
        var counts: [String: Int] = [:]
        for e in model.index.effective.values { if let s = e.primaryStyle { counts[s, default: 0] += 1 } }
        var all = Set(model.snapshot.taxonomy.genres.values.flatMap { $0 })
        if all.isEmpty {
            for t in model.index.tracks.values { if let k = t.auto?.styles?.keys { all.formUnion(k) } }
        }
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let tax = model.snapshot.taxonomy
        let hits = all.filter { s in
            guard !q.isEmpty else { return true }
            let l = tax.label(forStyle: s)
            return l.styleDisplay.lowercased().contains(q) || l.genreDisplay.lowercased().contains(q)
        }
        return hits.map { ($0, counts[$0] ?? 0) }.sorted { a, b in
            if !q.isEmpty {
                let ap = tax.label(forStyle: a.style).styleDisplay.lowercased().hasPrefix(q)
                let bp = tax.label(forStyle: b.style).styleDisplay.lowercased().hasPrefix(q)
                if ap != bp { return ap }
            }
            return a.count != b.count ? a.count > b.count : a.style < b.style
        }
    }
}
