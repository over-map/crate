import CrateKit
import CrateUI
import SwiftUI

// MARK: - Genre & style

/// Picked genres as summary chips ("House · all styles", "House · except Garage House") or one StyleChip per
/// ticked style (1–3), separated by "OR"; "＋ Add" opens the picker.
struct BuildGenreSection: View {
    @Environment(AppModel.self) private var model
    var onOpenPicker: (String?) -> Void

    private var picked: [String] {
        let order = model.index.families.map(\.family)
        return model.build.families.filter { !$0.value.isEmpty }.keys
            .sorted { (order.firstIndex(of: $0) ?? 99) < (order.firstIndex(of: $1) ?? 99) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.labelGap) {
            BuildSectionLabel(picked.isEmpty ? "Genre & style" : "Genre & style · any of")
            if picked.isEmpty {
                HStack(spacing: Space.s) {
                    AddChip("Add genre or style") { onOpenPicker(nil) }
                    Text("Any").font(.footnote).foregroundStyle(Color.textTertiary)
                }
            } else {
                ChipFlow {
                    ForEach(Array(chips.enumerated()), id: \.offset) { i, chip in
                        if i > 0 { ChipSeparator() }
                        chipView(chip)
                    }
                    AddChip("Add") { onOpenPicker(nil) }
                }
            }
        }
    }

    private enum Chip { case family(String, GenreSummary), style(String, String) }

    private var chips: [Chip] {
        picked.flatMap { fam -> [Chip] in
            let ticked = model.build.families[fam] ?? []
            let summary = GenreSummary.make(ticked: ticked, all: model.styles(ofFamily: fam))
            if case let .styles(names) = summary { return names.map { .style(fam, $0) } }
            return [.family(fam, summary)]
        }
    }

    @ViewBuilder private func chipView(_ chip: Chip) -> some View {
        switch chip {
        case let .family(fam, summary):
            let named: GenreSummary = switch summary {
            case let .except(names): .except(names.map(model.styleName))
            default: summary
            }
            GenreSummaryChip(family: model.familyHue(fam), name: model.familyName(fam), summary: named,
                             action: { onOpenPicker(fam) },
                             onRemove: { withAnimation(CrateMotion.chip) { model.build.families[fam] = nil } })
        case let .style(fam, style):
            StyleChip(style: model.styleName(style), canonical: style, genre: model.familyName(fam),
                      family: model.familyHue(fam), action: { onOpenPicker(fam) },
                      onRemove: {
                          withAnimation(CrateMotion.chip) {
                              model.build.families[fam]?.remove(style)
                              if model.build.families[fam]?.isEmpty == true { model.build.families[fam] = nil }
                          }
                      })
        }
    }
}

// MARK: - Vibe

/// Vibe chips in the user's order, with counts (or "count if added" once filters are set).
struct BuildVibeSection: View {
    @Environment(AppModel.self) private var model
    var onEdit: (String?) -> Void
    var onManage: () -> Void

    var body: some View {
        let ids = model.snapshot.vibes.ordered.map { $0.id }
        VStack(alignment: .leading, spacing: Space.labelGap) {
            BuildSectionLabel(title: model.build.vibes.count >= 2 ? "Vibe · all of" : "Vibe") {
                if model.build.isEmpty {
                    Button(action: onManage) { Text("Your order · Edit").panelLabelStyle() }
                        .buttonStyle(.plain)
                        .frame(minHeight: Size.minTarget / 2)
                        .contentShape(Rectangle())
                        .accessibilityLabel("Edit vibe order")
                } else {
                    Text("Count if added").panelLabelStyle()
                }
            }
            ChipFlow {
                ForEach(ids, id: \.self) { id in
                    if let vibe = model.snapshot.vibes.vibes[id] { chip(id, vibe) }
                }
                AddChip("New vibe") { onEdit(nil) }
            }
        }
    }

    private func chip(_ id: String, _ vibe: Vibe) -> some View {
        let selected = model.build.vibes.contains(id)
        let hasSeeds = model.vibeHasSeeds(vibe)
        let count: Int? = selected || !hasSeeds ? nil
            : model.build.isEmpty ? (model.index.vibeMembers[id]?.count ?? 0)
            : model.count { $0.vibes.append(id) }
        return VibeChip(vibe.name, symbol: model.vibeSymbol(id: id, vibe: vibe), count: count, isSelected: selected,
                        hasSeeds: hasSeeds,
                        action: {
                            guard hasSeeds else { onEdit(id); return }
                            withAnimation(CrateMotion.chip) {
                                if selected { model.build.vibes.removeAll { $0 == id } } else { model.build.vibes.append(id) }
                            }
                        },
                        onEdit: { onEdit(id) })
    }
}

// MARK: - Tags

/// The tag field with picked TagChips inside, autocomplete card, unknown-term card and footnote (6c–6e).
struct BuildTagSection: View {
    @Environment(AppModel.self) private var model
    @Binding var text: String
    var focused: FocusState<Bool>.Binding

    private var trimmed: String { text.trimmingCharacters(in: .whitespaces) }

    private var suggestions: [(tag: String, count: Int)] {
        model.index.tagSuggestions(trimmed, limit: 12).filter { !model.build.tags.contains($0.tag) }.prefix(6).map { $0 }
    }

    var body: some View {
        let showCard = focused.wrappedValue && !trimmed.isEmpty
        let sugg = showCard ? suggestions : []
        VStack(alignment: .leading, spacing: Space.labelGap) {
            BuildSectionLabel(title: "Tags") {
                if showCard && !sugg.isEmpty { Text("If added · all").panelLabelStyle() }
            }
            field
            if showCard {
                if sugg.isEmpty {
                    unknownCard
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(sugg.enumerated()), id: \.element.tag) { i, s in
                            if i > 0 { Divider().overlay(Color.separator).padding(.leading, Space.m) }
                            TagSuggestionRow(s.tag, query: trimmed,
                                             countIfAdded: model.count { $0.tags.append(s.tag) },
                                             libraryTotal: s.count) { add(s.tag) }
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
                    .crateCard()
                }
            } else {
                Text("Community tags from Last.fm, Discogs and Beatport · \(model.communityTaggedCount.formatted()) tracks")
                    .font(.footnote)
                    .foregroundStyle(Color.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var field: some View {
        let shape = RoundedRectangle(cornerRadius: Radius.card, style: .continuous)
        return HStack(alignment: .center, spacing: Space.s) {
            Image(systemName: "magnifyingglass").font(.subheadline).foregroundStyle(Color.textSecondary)
                .accessibilityHidden(true)
            ChipFlow(alignment: .center) {
                ForEach(model.build.tags, id: \.self) { tag in
                    TagChip(tag) { withAnimation(CrateMotion.chip) { model.build.tags.removeAll { $0 == tag } } }
                }
                TextField(model.build.tags.isEmpty ? "Search tags: italian, dub techno…" : "Add tag", text: $text)
                    .font(.body)
                    .foregroundStyle(Color.textPrimary)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.done)
                    .focused(focused)
                    .onSubmit(submit)
                    .frame(minWidth: 120, minHeight: Size.chipHeight)
                    .accessibilityLabel("Search tags")
            }
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Color.textTertiary)
                        .frame(width: Size.minTarget / 1.5, height: Size.minTarget / 1.5)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, Space.m)
        .padding(.vertical, Space.xs)
        .frame(minHeight: 48)
        .background(Color.fillControl, in: shape)
        .overlay {
            if focused.wrappedValue { shape.strokeBorder(Color.textPrimary, lineWidth: Stroke.outline) }
        }
        .contentShape(shape)
        .onTapGesture { focused.wrappedValue = true }
    }

    private var unknownCard: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            Text("No tracks tagged \u{201C}\(trimmed)\u{201D}").font(.headline).foregroundStyle(Color.textPrimary)
            Text("Tags are the words listeners use for an artist on Last.fm, Discogs and Beatport. Try a broader word.")
                .font(.secondary)
                .foregroundStyle(Color.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .crateCard()
        .accessibilityElement(children: .combine)
    }

    private func submit() {
        let norm = Tags.normalise(trimmed)
        if model.index.tagCounts[norm] != nil { add(norm) } else if let first = suggestions.first { add(first.tag) } else { focused.wrappedValue = false }
    }

    private func add(_ tag: String) {
        withAnimation(CrateMotion.chip) {
            if !model.build.tags.contains(tag) { model.build.tags.append(tag) }
        }
        text = ""
        // Picking a tag is the end of the gesture: hide the keyboard so the result is visible
        // (tap the field again to add another).
        focused.wrappedValue = false
    }
}

// MARK: - BPM

struct BuildBPMSection: View {
    @Environment(AppModel.self) private var model
    /// Typed entry (design-system "RangeSlider › Typed entry"): which numeral is being edited.
    enum Bound: Hashable { case min, max }
    @FocusState private var editing: Bound?
    @State private var text = ""

    private var isAny: Bool { model.build.bpm == BuildState.bpmAny }

    /// The half and double ranges, clamped to the scale (shown as ghosts and "also 68–71").
    private var ghosts: [ClosedRange<Int>] {
        guard model.build.halfDouble, !isAny else { return [] }
        let r = model.build.bpm, b = BuildState.bpmAny
        var out: [ClosedRange<Int>] = []
        let half = Int((Double(r.lowerBound) / 2).rounded(.up))...Int((Double(r.upperBound) / 2).rounded(.down))
        let dbl = (r.lowerBound * 2)...(r.upperBound * 2)
        for g in [half, dbl] where g.lowerBound <= g.upperBound && g.overlaps(b) {
            out.append(max(g.lowerBound, b.lowerBound)...min(g.upperBound, b.upperBound))
        }
        return out
    }

    var body: some View {
        @Bindable var model = model
        VStack(alignment: .leading, spacing: Space.labelGap) {
            BuildSectionLabel(title: "BPM") {
                Toggle(isOn: $model.build.halfDouble) {
                    Text("Half/double too").font(.footnote).foregroundStyle(Color.textSecondary)
                }
                .tint(Color.accent)
                .fixedSize()
            }
            HStack(alignment: .lastTextBaseline) {
                ViewThatFits(in: .horizontal) {
                    numerals(axis: .horizontal)
                    numerals(axis: .vertical)
                }
                Spacer(minLength: Space.s)
                if let g = ghosts.first {
                    Text("also \(g.lowerBound)–\(g.upperBound)")
                        .font(.footnote)
                        .foregroundStyle(Color.textSecondary)
                        .monospacedDigit()
                }
            }
            RangeSlider(range: $model.build.bpm, bounds: BuildState.bpmAny, ghosts: ghosts)
            quickChips
        }
        .toolbar {
            if editing != nil {
                ToolbarItemGroup(placement: .keyboard) {
                    Picker("Value", selection: Binding(get: { editing ?? .min }, set: { switchTo($0) })) {
                        Text("Min").tag(Bound.min)
                        Text("Max").tag(Bound.max)
                    }
                    .pickerStyle(.segmented)
                    .fixedSize()
                    Spacer()
                    Button { step(-1) } label: { Image(systemName: "minus") }.accessibilityLabel("Lower by 1")
                    Button { step(1) } label: { Image(systemName: "plus") }.accessibilityLabel("Raise by 1")
                    Button("Done") { editing = nil }.fontWeight(.semibold)
                }
            }
        }
        .onChange(of: text) { _, new in apply(new) }
    }

    // MARK: Numerals (tap to type)

    @ViewBuilder private func numerals(axis: Axis) -> some View {
        let layout = axis == .horizontal ? AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: Space.s))
            : AnyLayout(VStackLayout(alignment: .leading, spacing: 0))
        layout {
            numeral(.min, value: model.build.bpm.lowerBound)
            Text("–").foregroundStyle(Color.textTertiary).accessibilityHidden(true)
            numeral(.max, value: model.build.bpm.upperBound)
        }
        .font(.numeralHero)
        .lineLimit(1)
        .fixedSize()
    }

    @ViewBuilder private func numeral(_ bound: Bound, value: Int) -> some View {
        let color = isAny ? Color.textTertiary : Color.textPrimary
        ZStack {
            // The field stays in the hierarchy so focus can move to it; it shows only while editing.
            TextField("", text: $text)
                .keyboardType(.numberPad)
                .focused($editing, equals: bound)
                .multilineTextAlignment(.center)
                .foregroundStyle(Color.textPrimary)
                .opacity(editing == bound ? 1 : 0)
                .accessibilityHidden(editing != bound)
            Text(value, format: .number.grouping(.never))
                .countTransition(value)
                .foregroundStyle(color)
                .opacity(editing == bound ? 0 : 1)
                .overlay(alignment: .bottom) {
                    Line().stroke(Color.textTertiary, style: StrokeStyle(lineWidth: 2, dash: [2, 3]))
                        .frame(height: 2).offset(y: 2)
                }
                .onTapGesture { switchTo(bound) }
                .accessibilityAddTraits(.isButton)
                .accessibilityLabel(bound == .min ? "Minimum BPM \(value)" : "Maximum BPM \(value)")
                .accessibilityHint("Type a value")
                .accessibilityAction { switchTo(bound) }
        }
        .padding(.horizontal, editing == bound ? Space.xs : 0)
        .overlay {
            if editing == bound {
                RoundedRectangle(cornerRadius: Radius.control, style: .continuous).strokeBorder(Color.textPrimary, lineWidth: 1.5)
            }
        }
    }

    private func switchTo(_ bound: Bound) {
        text = String(bound == .min ? model.build.bpm.lowerBound : model.build.bpm.upperBound)
        editing = bound
    }

    /// Clamped to 60–200 and min ≤ max; the slider and count update as you type.
    private func apply(_ typed: String) {
        guard let bound = editing else { return }
        let digits = String(typed.filter(\.isNumber).prefix(3))
        if digits != typed { text = digits; return }
        let b = BuildState.bpmAny
        // "13" on the way to "138" isn't applied (it would clamp to 60 and jump the slider).
        guard let v = Int(digits), v >= b.lowerBound else { return }
        let clamped = min(b.upperBound, max(b.lowerBound, v))
        let r = model.build.bpm
        switch bound {
        case .min: model.build.bpm = clamped...max(clamped, r.upperBound)
        case .max: model.build.bpm = min(clamped, r.lowerBound)...clamped
        }
    }

    private func step(_ delta: Int) {
        guard let bound = editing else { return }
        let r = model.build.bpm, b = BuildState.bpmAny
        switch bound {
        case .min:
            let v = min(r.upperBound, max(b.lowerBound, r.lowerBound + delta))
            model.build.bpm = v...r.upperBound; text = String(v)
        case .max:
            let v = max(r.lowerBound, min(b.upperBound, r.upperBound + delta))
            model.build.bpm = r.lowerBound...v; text = String(v)
        }
    }

    // MARK: Quick chips: ±3 around the centre, ±6, Any

    private var quickChips: some View {
        let r = model.build.bpm
        let centre = isAny ? 120 : (r.lowerBound + r.upperBound) / 2
        return HStack(spacing: Space.chipGap) {
            quick("±3", spoken: "Plus or minus 3 around \(centre)", range: around(centre, 3))
            quick("±6", spoken: "Plus or minus 6 around \(centre)", range: around(centre, 6))
            quick("Any", spoken: "Any BPM", range: BuildState.bpmAny)
            Spacer(minLength: 0)
        }
    }

    private func around(_ c: Int, _ d: Int) -> ClosedRange<Int> {
        let b = BuildState.bpmAny
        return max(b.lowerBound, c - d)...min(b.upperBound, c + d)
    }

    private func quick(_ title: String, spoken: String, range: ClosedRange<Int>) -> some View {
        let selected = model.build.bpm == range
        return Button {
            withAnimation(CrateMotion.chip) { model.build.bpm = range }
        } label: {
            Text(title).font(.chipLabel).monospacedDigit()
                .foregroundStyle(selected ? Color.onAccent : Color.textPrimary)
                .padding(.horizontal, Space.m)
                .frame(minHeight: Size.minTarget)
                .background(selected ? Color.accent : Color.fillControl,
                            in: RoundedRectangle(cornerRadius: Radius.chip, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(spoken)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .sensoryFeedback(.selection, trigger: selected)
    }
}

/// A horizontal line, for the dotted "tap to type" underline.
private struct Line: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.minX, y: rect.midY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
        return p
    }
}

// MARK: - Energy

struct BuildEnergySection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        let r = model.build.energy
        let isAny = r == BuildState.energyAny
        VStack(alignment: .leading, spacing: Space.labelGap) {
            BuildSectionLabel(title: "Energy") {
                Text(isAny ? "Any" : r.lowerBound == r.upperBound ? "\(r.lowerBound)" : "\(r.lowerBound)–\(r.upperBound)")
                    .font(.numeralMedium)
                    .foregroundStyle(isAny ? Color.textTertiary : Color.textPrimary)
                    .accessibilityHidden(true)
            }
            EnergyRangeControl(range: $model.build.energy)
        }
    }
}

// MARK: - Key + vocals

struct BuildKeyVocalSection: View {
    @Environment(AppModel.self) private var model
    var onOpenKey: () -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: Space.s) {
                keyButton.frame(maxWidth: .infinity)
                vocals.frame(maxWidth: .infinity)
            }
            VStack(alignment: .leading, spacing: Space.sectionGap) {
                keyButton
                vocals
            }
        }
    }

    private var keyButton: some View {
        Button(action: onOpenKey) {
            HStack(spacing: Space.s) {
                Text("Key").panelLabelStyle()
                CamelotBadge(code: model.build.key, size: .small)
                if model.build.key != nil && model.build.compatible {
                    Text("+ compatible").font(.footnote).foregroundStyle(Color.textSecondary).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, Space.m)
            .frame(maxWidth: .infinity, minHeight: Size.minTarget)
            .crateCard()
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(model.build.key.map { "Key \($0)\(model.build.compatible ? " with compatible keys" : "")" } ?? "Key, any")
        .accessibilityHint("Opens the key picker")
        .accessibilityAddTraits(.isButton)
    }

    private var vocals: some View {
        @Bindable var model = model
        return Picker("Vocals", selection: $model.build.vocal) {
            Text("Any").tag(Query.Vocal.any)
            Text("Vocal").tag(Query.Vocal.vocal)
            Text("Instr.").tag(Query.Vocal.instrumental)
        }
        .pickerStyle(.segmented)
        .frame(minHeight: Size.minTarget)
    }
}

// MARK: - Limit

struct BuildLimitSection: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        VStack(alignment: .leading, spacing: Space.labelGap) {
            BuildSectionLabel("Limit")
            Stepper(value: $model.build.limit, in: 10...500, step: 10) {
                HStack(alignment: .firstTextBaseline, spacing: Space.xs) {
                    Text(model.build.limit, format: .number).font(.numeralMedium).foregroundStyle(Color.textPrimary)
                        .countTransition(model.build.limit)
                    Text("tracks max · shuffled").font(.footnote).foregroundStyle(Color.textSecondary)
                }
            }
            .accessibilityValue("\(model.build.limit) tracks")
        }
    }
}
