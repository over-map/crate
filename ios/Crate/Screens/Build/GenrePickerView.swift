import CrateKit
import CrateUI
import SwiftUI

/// Genre & style picker sheet (6g, 2f–2l). Owner: Build agent.
///
/// Edits a draft of `model.build.families` (family → ticked styles). Picking a family ticks all its styles;
/// untick the ones you don't want. Done commits, Cancel discards, Clear empties the draft.
struct GenrePickerView: View {
    /// A family to expand (and scroll to) when the sheet opens.
    var focusFamily: String? = nil

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var draft: [String: Set<String>] = [:]
    @State private var expanded: Set<String> = []
    @State private var showAll: Set<String> = []
    @State private var search = ""
    @State private var loaded = false
    @FocusState private var searchFocused: Bool

    private static let topStyles = 8

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: Space.sectionGap) {
                        searchField
                        if trimmed.isEmpty {
                            mostUsed
                            library
                        } else {
                            searchResults
                        }
                    }
                    .padding(.horizontal, Space.screenMargin)
                    .padding(.bottom, Space.l)
                }
                .scrollDismissesKeyboard(.interactively)
                .onAppear {
                    // Scroll only when the genre is further down than the first rows.
                    if let focusFamily, (model.index.families.firstIndex { $0.family == focusFamily } ?? 0) > 1 {
                        proxy.scrollTo(focusFamily, anchor: .top)
                    }
                }
            }
            .background(Color.bgSheet.ignoresSafeArea())
            .navigationTitle("Genre & style")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.foregroundStyle(Color.textSecondary)
                }
                ToolbarItem(placement: .primaryAction) {
                    Button("Clear") { withAnimation(CrateMotion.chip) { draft = [:] } }
                        .foregroundStyle(Color.textPrimary)
                        .disabled(draft.isEmpty)
                }
            }
            .safeAreaInset(edge: .bottom) { bottomBar }
        }
        .presentationDragIndicator(.visible)
        .presentationBackground(Color.bgSheet)
        .onAppear {
            guard !loaded else { return }
            loaded = true
            draft = model.build.families.filter { !$0.value.isEmpty }
            if let focusFamily { expanded.insert(focusFamily) }
        }
    }

    private var trimmed: String { search.trimmingCharacters(in: .whitespaces) }

    // MARK: Search

    private var searchField: some View {
        HStack(spacing: Space.s) {
            Image(systemName: "magnifyingglass").foregroundStyle(Color.textSecondary).accessibilityHidden(true)
            TextField("Search genres and styles", text: $search)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .focused($searchFocused)
                .foregroundStyle(Color.textPrimary)
            if !search.isEmpty {
                Button { search = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Color.textTertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, Space.m)
        .frame(minHeight: Size.minTarget)
        .background(Color.fillControl, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }

    private struct StyleHit: Identifiable {
        var id: String { family + "|" + (style ?? "") }
        let family: String
        let style: String?
        let name: String
        let count: Int
    }

    private var hits: [StyleHit] {
        let q = trimmed.lowercased()
        var out: [StyleHit] = []
        for fam in model.index.families {
            let famName = model.familyName(fam.family)
            if famName.lowercased().contains(q) {
                out.append(StyleHit(family: fam.family, style: nil, name: famName, count: fam.count))
            }
            for s in fam.styles {
                let name = model.styleName(s.style)
                if name.lowercased().contains(q) {
                    out.append(StyleHit(family: fam.family, style: s.style, name: name, count: s.count))
                }
            }
        }
        return out.sorted { a, b in
            let ap = a.name.lowercased().hasPrefix(q), bp = b.name.lowercased().hasPrefix(q)
            return ap != bp ? ap : a.count > b.count
        }
    }

    @ViewBuilder private var searchResults: some View {
        let results = hits
        VStack(alignment: .leading, spacing: Space.labelGap) {
            Text(results.isEmpty ? "No matches" : "\(results.count) in your library").panelLabelStyle()
            if !results.isEmpty {
                VStack(spacing: 0) {
                    ForEach(Array(results.enumerated()), id: \.element.id) { i, hit in
                        if i > 0 { divider(indent: 34) }
                        searchRow(hit)
                    }
                }
                .crateCard()
            }
            Text("Only styles with tracks in your library are listed.")
                .font(.footnote).foregroundStyle(Color.textSecondary)
        }
    }

    private func searchRow(_ hit: StyleHit) -> some View {
        let state: TickRing.TickState = hit.style.map { draft[hit.family]?.contains($0) == true ? .checked : .empty }
            ?? familyState(hit.family)
        return Button {
            withAnimation(CrateMotion.chip) {
                if let style = hit.style { toggle(style: style, in: hit.family) } else { toggle(family: hit.family) }
            }
        } label: {
            HStack(spacing: Space.m) {
                FamilyDot(model.familyHue(hit.family))
                VStack(alignment: .leading, spacing: 0) {
                    Text(highlight(hit.name)).font(.body).foregroundStyle(Color.textPrimary)
                    Text(hit.style == nil ? "Genre · \(styleCount(hit.family)) styles" : model.familyName(hit.family))
                        .font(.footnote).foregroundStyle(Color.textSecondary)
                }
                Spacer(minLength: Space.s)
                Text(hit.count.formatted()).font(.secondary).foregroundStyle(Color.textSecondary).monospacedDigit()
                TickRing(state)
            }
            .padding(.horizontal, Space.m)
            .padding(.vertical, Space.s)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(hit.name), \(hit.style == nil ? "genre" : model.familyName(hit.family)), \(hit.count) tracks")
        .accessibilityAddTraits(state == .checked ? [.isButton, .isSelected] : .isButton)
    }

    private func highlight(_ name: String) -> AttributedString {
        var text = AttributedString(name)
        if let r = text.range(of: trimmed, options: [.caseInsensitive, .diacriticInsensitive]) {
            text[r].inlinePresentationIntent = .stronglyEmphasized
        }
        return text
    }

    // MARK: Most used

    private var mostUsed: some View {
        let top = model.index.families
            .flatMap { f in f.styles.map { (family: f.family, style: $0.style, count: $0.count) } }
            .sorted { $0.count > $1.count }
            .prefix(8)
        return VStack(alignment: .leading, spacing: Space.labelGap) {
            Text("Most used").panelLabelStyle().accessibilityAddTraits(.isHeader)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Space.chipGap) {
                    ForEach(Array(top), id: \.style) { item in
                        let on = draft[item.family]?.contains(item.style) == true
                        StyleChip(style: model.styleName(item.style), canonical: item.style,
                                  family: model.familyHue(item.family),
                                  variant: on ? .selected : .off(count: item.count),
                                  action: { withAnimation(CrateMotion.chip) { toggle(style: item.style, in: item.family) } })
                    }
                }
                .padding(.horizontal, Space.screenMargin)
                .padding(.vertical, Space.xs)
            }
            .padding(.horizontal, -Space.screenMargin)
        }
    }

    // MARK: Library list

    private var library: some View {
        VStack(alignment: .leading, spacing: Space.labelGap) {
            Text("In your library · by tracks").panelLabelStyle().accessibilityAddTraits(.isHeader)
            Text("Each track has one genre, voted by up to four sources. Picking a genre ticks all its styles; untick the ones you don't want.")
                .font(.footnote)
                .foregroundStyle(Color.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            VStack(spacing: 0) {
                ForEach(Array(model.index.families.enumerated()), id: \.element.family) { i, fam in
                    if i > 0 { divider(indent: 0) }
                    familyRow(fam).id(fam.family)
                    if expanded.contains(fam.family) { styleRows(fam) }
                }
            }
            .crateCard()
        }
    }

    private func familyRow(_ fam: LibraryIndex.FamilyEntry) -> some View {
        let state = familyState(fam.family)
        let ticked = draft[fam.family]?.count ?? 0
        let detail = state == .mixed ? "\(fam.count.formatted()) tracks · \(ticked) of \(fam.styles.count) styles"
            : "\(fam.count.formatted()) tracks · \(fam.styles.count) styles"
        let isOpen = expanded.contains(fam.family)
        return HStack(spacing: Space.m) {
            Button {
                withAnimation(CrateMotion.chip) {
                    if isOpen { expanded.remove(fam.family) } else { expanded.insert(fam.family) }
                }
            } label: {
                HStack(spacing: Space.m) {
                    FamilyDot(model.familyHue(fam.family))
                    VStack(alignment: .leading, spacing: 0) {
                        Text(model.familyName(fam.family)).font(.headline).foregroundStyle(Color.textPrimary)
                        Text(detail).font(.footnote).foregroundStyle(Color.textSecondary)
                    }
                    Spacer(minLength: Space.s)
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.textTertiary)
                        .rotationEffect(.degrees(isOpen ? 90 : 0))
                }
                .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(model.familyName(fam.family)), \(detail)")
            .accessibilityValue(isOpen ? "Expanded" : "Collapsed")
            .accessibilityHint(isOpen ? "Hides the styles" : "Shows the styles")
            .accessibilityAddTraits(.isButton)

            Button {
                withAnimation(CrateMotion.chip) { toggle(family: fam.family) }
            } label: {
                TickRing(state).frame(width: Size.minTarget, height: Size.minTarget).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .sensoryFeedback(.selection, trigger: state)
            .accessibilityLabel("Pick \(model.familyName(fam.family))")
            .accessibilityValue(state == .checked ? "All styles" : state == .mixed ? "Some styles" : "Not picked")
        }
        .padding(.leading, Space.m)
        .padding(.trailing, Space.xs)
    }

    @ViewBuilder private func styleRows(_ fam: LibraryIndex.FamilyEntry) -> some View {
        let all = fam.styles
        let visible = showAll.contains(fam.family) ? all : Array(all.prefix(Self.topStyles))
        ForEach(visible, id: \.style) { s in
            divider(indent: 34)
            let on = draft[fam.family]?.contains(s.style) == true
            Button {
                withAnimation(CrateMotion.chip) { toggle(style: s.style, in: fam.family) }
            } label: {
                HStack(spacing: Space.m) {
                    Text(model.styleName(s.style)).font(.body).foregroundStyle(Color.textPrimary)
                    Spacer(minLength: Space.s)
                    Text(s.count.formatted()).font(.secondary).foregroundStyle(Color.textSecondary).monospacedDigit()
                    TickRing(isOn: on).frame(width: Size.minTarget, height: Size.minTarget)
                }
                .padding(.leading, Space.m + 34)
                .padding(.trailing, Space.xs)
                .frame(minHeight: Size.minTarget)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .sensoryFeedback(.selection, trigger: on)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(model.styleName(s.style)), \(s.count) tracks")
            .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
        }
        if all.count > Self.topStyles {
            divider(indent: 34)
            Button {
                withAnimation(CrateMotion.chip) {
                    if showAll.contains(fam.family) { showAll.remove(fam.family) } else { showAll.insert(fam.family) }
                }
            } label: {
                Text(showAll.contains(fam.family) ? "Show fewer" : "Show \(all.count - Self.topStyles) more")
                    .font(.secondary)
                    .foregroundStyle(Color.textSecondary)
                    .padding(.leading, Space.m + 34)
                    .frame(maxWidth: .infinity, minHeight: Size.minTarget, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    private func divider(indent: CGFloat) -> some View {
        Rectangle().fill(Color.separator).frame(height: 0.5).padding(.leading, indent == 0 ? 0 : Space.m + indent)
    }

    // MARK: Bottom bar

    private var bottomBar: some View {
        let count = draft.isEmpty ? model.index.tracks.count : model.count { $0.families = draft }
        return HStack(spacing: Space.m) {
            VStack(alignment: .leading, spacing: 0) {
                Text(count.formatted()).font(.numeralMedium).foregroundStyle(Color.textPrimary).countTransition(count)
                Text(summary).font(.footnote).foregroundStyle(Color.textSecondary).lineLimit(2)
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: Space.s)
            Button {
                model.build.families = draft
                dismiss()
            } label: {
                Text("Done").font(.headline).foregroundStyle(Color.onAccent)
                    .padding(.horizontal, Space.xl)
                    .frame(minHeight: Size.buttonHeight)
                    .background(Color.accent, in: RoundedRectangle(cornerRadius: Radius.button, style: .continuous))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, Space.screenMargin)
        .padding(.vertical, Space.m)
        .background(.bar)
    }

    private var summary: String {
        if draft.isEmpty { return "Any genre" }
        let order = model.index.families.map(\.family)
        return draft.keys.sorted { (order.firstIndex(of: $0) ?? 99) < (order.firstIndex(of: $1) ?? 99) }.map { fam in
            let name = model.familyName(fam)
            switch GenreSummary.make(ticked: draft[fam] ?? [], all: model.styles(ofFamily: fam)) {
            case .allStyles: return name
            case let .except(n): return "\(name) except \(n.map(model.styleName).joined(separator: ", "))"
            case let .styles(n): return n.map(model.styleName).joined(separator: ", ")
            case let .count(t, total): return "\(name) · \(t) of \(total) styles"
            }
        }.joined(separator: " or ")
    }

    // MARK: Selection model

    private func styleCount(_ fam: String) -> Int { model.styles(ofFamily: fam).count }

    private func familyState(_ fam: String) -> TickRing.TickState {
        guard let ticked = draft[fam], !ticked.isEmpty else { return .empty }
        return ticked.count >= styleCount(fam) ? .checked : .mixed
    }

    private func toggle(family fam: String) {
        if familyState(fam) == .checked { draft[fam] = nil } else { draft[fam] = Set(model.styles(ofFamily: fam)) }
    }

    private func toggle(style: String, in fam: String) {
        var set = draft[fam] ?? []
        if set.contains(style) { set.remove(style) } else { set.insert(style) }
        draft[fam] = set.isEmpty ? nil : set
    }
}
