import CrateKit
import CrateUI
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Vibe editor sheet (6h–6k). `vibeID == nil` creates a new vibe; `seedTrackID` pre-adds a seed. Owner: Vibes agent.
///
/// Edits a draft; the preview re-ranks the library off the main thread after every change (rules in
/// `docs/query-semantics.md#vibes`). Save writes `vibes.json` through `AppModel.editVibes`.
struct VibeEditorView: View {
    var vibeID: String?
    var seedTrackID: String?

    init(vibeID: String? = nil, seedTrackID: String? = nil) {
        self.vibeID = vibeID
        self.seedTrackID = seedTrackID
    }

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.displayScale) private var displayScale
    @Environment(\.dynamicTypeSize) private var typeSize
    private var isAX: Bool { typeSize.isAccessibilitySize }

    @State private var draft = Vibe(name: "", share: VibeSymbols.shareNormal)
    @State private var original = Vibe(name: "", share: VibeSymbols.shareNormal)
    @State private var loaded = false
    @State private var showIcons = false
    @State private var query = ""
    @State private var tagMode = false
    @State private var showSearch = false
    @State private var showAllNever = false
    @State private var preview = VibePreview()
    @State private var artists: ArtistDirectory?
    @State private var shownTrack: VibeTrackRef?
    @State private var confirmDiscard = false
    @FocusState private var searchFocused: Bool

    private var lookup: VibeLookup { VibeLookup(index: model.index) }
    private var isNew: Bool { vibeID == nil }
    private var hasSeeds: Bool { VibeText.hasSeeds(draft) }
    private var hasChanges: Bool { draft != original }
    private var trimmedName: String { draft.name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canSave: Bool { !trimmedName.isEmpty && (hasChanges || isNew) }
    private var symbol: String { VibeText.symbol(id: vibeID, vibe: draft) }
    private var displayName: String { trimmedName.isEmpty ? "this vibe" : trimmedName }
    /// The search field hides while a vibe is defined only by community tags (6i) until "＋ Tracks or artists".
    private var searchVisible: Bool {
        draft.seedTags.isEmpty || tagMode || showSearch || !draft.seeds.isEmpty || !draft.artists.isEmpty
    }
    /// A brand-new vibe whose only change is the pre-added seed counts as untouched.
    private var isNewUntouched: Bool {
        isNew && trimmedName.isEmpty && draft.artists.isEmpty && draft.seedTags.isEmpty
            && draft.seeds.count <= (seedTrackID == nil ? 0 : 1)
    }

    var body: some View {
        NavigationStack {
            List {
                nameSection
                seedsSection
                if !draft.exclude.isEmpty { neverSection }
                shareSection
                if hasSeeds { previewSection }
            }
            .listStyle(.insetGrouped)
            .listSectionSpacing(Space.m)
            .contentMargins(.top, Space.s, for: .scrollContent)
            .scrollContentBackground(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .background(Color.bgSheet)
            .navigationTitle(isNew ? "New vibe" : (trimmedName.isEmpty ? "Vibe" : trimmedName))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        if hasChanges && !isNewUntouched { confirmDiscard = true } else { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save).fontWeight(.semibold).disabled(!canSave)
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) { bottomBar }
            .confirmationDialog("Discard changes to \(displayName)?", isPresented: $confirmDiscard, titleVisibility: .visible) {
                Button("Discard changes", role: .destructive) { dismiss() }
                Button("Keep editing", role: .cancel) {}
            }
            .sheet(item: $shownTrack) { ref in TrackDetailView(trackID: ref.id) }
        }
        .interactiveDismissDisabled(hasChanges && !isNewUntouched)
        .onAppear(perform: load)
        .task(id: draft) { await recomputePreview() }
        .tint(Color.accent)
    }

    // MARK: Load / save

    private func load() {
        guard !loaded else { return }
        loaded = true
        let v = vibeID.flatMap { model.snapshot.vibes.vibes[$0] } ?? Vibe(name: "", share: VibeSymbols.shareNormal)
        original = v
        var d = v
        if let seed = seedTrackID, !d.seeds.contains(seed) {
            d.seeds.append(seed)
            d.exclude.removeAll { $0 == seed }
        }
        draft = d
        showIcons = isNew
        artists = ArtistDirectory(tracks: model.index.tracks)
        if !VibeText.hasSeeds(d) {
            Task { try? await Task.sleep(for: .milliseconds(500)); searchFocused = true }
        }
    }

    private func save() {
        guard canSave else { return }
        var v = draft
        v.name = trimmedName
        if v.icon == nil && isNew { v.icon = "tag" }
        let id = vibeID ?? VibeText.slug(v.name, existing: model.snapshot.vibes.vibes.keys)
        let vibe = v
        Task {
            await model.editVibes { file in
                if file.vibes[id] == nil && !file.order.contains(id) {
                    var order = file.ordered.map(\.id)
                    order.append(id)
                    file.order = order
                }
                file.vibes[id] = vibe
            }
        }
        dismiss()
    }

    private func recomputePreview() async {
        let vibe = draft
        guard let index = model.index.vibeIndex, VibeText.hasSeeds(vibe) else {
            if !preview.isEmpty { preview = VibePreview() }
            return
        }
        // Debounce typing and quick taps; the ranking itself is a few passes over ~1,000 vectors.
        try? await Task.sleep(for: .milliseconds(120))
        guard !Task.isCancelled else { return }
        let result = await Task.detached(priority: .userInitiated) { VibePreview.compute(vibe, index: index) }.value
        guard !Task.isCancelled else { return }
        preview = result
    }

    // MARK: Edits

    private func addSeed(_ id: String) {
        if !draft.seeds.contains(id) { draft.seeds.append(id) }
        draft.exclude.removeAll { $0 == id }
    }

    private func never(_ id: String) {
        draft.seeds.removeAll { $0 == id }
        if !draft.exclude.contains(id) { draft.exclude.insert(id, at: 0) }
    }

    private func addArtist(_ name: String) {
        if !draft.artists.contains(where: { VibeText.fold($0) == VibeText.fold(name) }) { draft.artists.append(name) }
    }

    private func addTag(_ tag: String) {
        if !draft.seedTags.contains(tag) { draft.seedTags.append(tag) }
        query = ""
        tagMode = false
    }

    private func startTagSearch() {
        tagMode = true
        query = ""
        searchFocused = true
    }

    // MARK: Name + icon

    @ViewBuilder private var nameSection: some View {
        Section {
            HStack(spacing: Space.m) {
                VibeSymbolTile(symbol: symbol)
                TextField("Vibe name", text: $draft.name, axis: isAX ? .vertical : .horizontal)
                    .font(.title3)
                    .foregroundStyle(Color.textPrimary)
                    .submitLabel(.done)
                Button {
                    withAnimation(.snappy(duration: 0.25)) { showIcons.toggle() }
                } label: {
                    HStack(spacing: Space.xs) {
                        if !isAX { Text("Icon") }
                        Image(systemName: "chevron.right").font(.footnote.weight(.semibold))
                            .rotationEffect(.degrees(showIcons ? 90 : 0))
                    }
                    .font(.secondary)
                    .foregroundStyle(Color.textSecondary)
                    .frame(minHeight: Size.minTarget)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(showIcons ? "Hide icons" : "Choose icon")
            }
            .listRowBackground(Color.bgCard)
        }
        if showIcons {
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Space.s - Space.xxs) {
                        ForEach(VibeSymbols.picker, id: \.self) { name in
                            Button {
                                draft.icon = name
                            } label: {
                                VibeSymbolTile(symbol: name, size: Size.minTarget, selected: name == symbol, dimmed: true)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Self.symbolLabel(name))
                            .accessibilityAddTraits(name == symbol ? .isSelected : [])
                        }
                    }
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
        }
    }

    private static func symbolLabel(_ name: String) -> String {
        name.replacingOccurrences(of: ".fill", with: "").replacingOccurrences(of: ".", with: " ")
    }

    // MARK: Seeds

    @ViewBuilder private var seedsSection: some View {
        let explicit = draft.seeds.count + draft.artists.count
        Section {
            if !hasSeeds {
                VStack(spacing: Space.xs) {
                    VibeSymbolTile(symbol: symbol, size: 44)
                        .padding(.bottom, Space.xs)
                    Text("No seeds yet").font(.headline).foregroundStyle(Color.textPrimary)
                    Text("Add 2–5 tracks or artists that are this vibe. Crate finds the tracks that sound closest.")
                        .font(.secondary).foregroundStyle(Color.textSecondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, Space.s)
                .accessibilityElement(children: .combine)
                .vibeClearRow()
            }
            if !draft.seedTags.isEmpty {
                VStack(alignment: .leading, spacing: Space.s) {
                    ChipFlow {
                        ForEach(draft.seedTags, id: \.self) { tag in
                            TagChip(tag) { draft.seedTags.removeAll { $0 == tag } }
                        }
                        Button(action: startTagSearch) {
                            Image(systemName: "plus").font(.footnote.weight(.semibold))
                                .foregroundStyle(Color.textPrimary)
                                .frame(width: Size.chipHeight + Space.s, height: Size.chipHeight)
                                .background(Color.fillControl, in: RoundedRectangle(cornerRadius: Radius.chip, style: .continuous))
                                .frame(minHeight: Size.minTarget)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Add community tag")
                    }
                    tagSeedLine
                }
                .vibeClearRow()
            }
        } header: {
            PanelLabel(explicit > 0 || draft.seedTags.isEmpty ? "Seeds · \(explicit)" : "Seeds",
                       trailing: isAX ? nil : (explicit == 0 && !draft.seedTags.isEmpty ? "From community tags" : (explicit < 2 ? "Add 2–5" : nil)))
                .vibeSectionHeader()
        }

        if searchVisible {
            Section {
                searchField.listRowInsets(.vibeCompactRow).listRowBackground(Color.fillControl)
            }
        }
        if !query.trimmingCharacters(in: .whitespaces).isEmpty {
            Section { searchResults }
        }
        if explicit > 0 {
            Section {
                ForEach(draft.artists, id: \.self) { name in artistSeedRow(name) }
                ForEach(draft.seeds, id: \.self) { id in trackSeedRow(id) }
            }
        }
        if draft.seedTags.isEmpty && !tagMode {
            Section {
                AddChip("Community tag", action: startTagSearch)
                    .accessibilityHint("Every track the community tags this way becomes a seed")
                    .vibeClearRow()
            }
        }
    }

    private var tagSeedLine: some View {
        let n = preview.tagSeedCount
        let lead = n == 1 ? "1 seed track: artists the community tags this way"
            : "\(n.formatted()) seed tracks: artists the community tags this way"
        return Group {
            if searchVisible {
                Text(lead).foregroundStyle(Color.textSecondary)
            } else {
                Button {
                    showSearch = true
                    searchFocused = true
                } label: {
                    (Text(lead + " · ").foregroundStyle(Color.textSecondary)
                        + Text("＋ Tracks or artists").foregroundStyle(Color.textPrimary).fontWeight(.medium))
                        .multilineTextAlignment(.leading)
                }
                .buttonStyle(.borderless)
                .accessibilityHint("Adds tracks or artists as seeds")
            }
        }
        .font(.footnote)
        .fixedSize(horizontal: false, vertical: true)
    }

    private var searchField: some View {
        HStack(spacing: Space.s) {
            Image(systemName: tagMode ? "tag" : "magnifyingglass")
                .font(.subheadline).foregroundStyle(Color.textTertiary)
                .accessibilityHidden(true)
            TextField(tagMode ? "Add a community tag" : "Add tracks or artists", text: $query)
                .focused($searchFocused)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .submitLabel(.search)
                .foregroundStyle(Color.textPrimary)
            if !query.isEmpty || tagMode {
                Button {
                    query = ""
                    if tagMode { tagMode = false; searchFocused = false }
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Color.textTertiary)
                        .frame(minWidth: Size.minTarget, minHeight: Size.minTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(tagMode ? "Cancel tag search" : "Clear search")
            }
        }
        .frame(minHeight: Size.minTarget)
    }

    @ViewBuilder private var searchResults: some View {
        let q = query.trimmingCharacters(in: .whitespaces)
        if tagMode {
            let hits = model.index.tagSuggestions(q, limit: 6).filter { !draft.seedTags.contains($0.tag) }
            if hits.isEmpty {
                TagUnknownRow(term: q).listRowInsets(EdgeInsets()).listRowBackground(Color.bgCard)
            } else {
                ForEach(hits, id: \.tag) { hit in
                    TagSuggestionRow(hit.tag, query: q, countIfAdded: nil, libraryTotal: hit.count) { addTag(hit.tag) }
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.bgCard)
                }
            }
        } else {
            let artistHits = (artists?.search(q, limit: 3) ?? []).filter { a in
                !draft.artists.contains { VibeText.fold($0) == a.folded }
            }
            let trackHits = searchTracks(q, limit: 6)
            if artistHits.isEmpty && trackHits.isEmpty {
                Text("Nothing in your library matches \u{201C}\(q)\u{201D}")
                    .font(.secondary).foregroundStyle(Color.textSecondary)
                    .frame(minHeight: Size.minTarget)
                    .listRowBackground(Color.bgCard)
            }
            ForEach(artistHits, id: \.self) { a in
                resultRow(title: a.name, subtitle: a.count == 1 ? "Artist · 1 track" : "Artist · \(a.count) tracks") {
                    InitialsCircle(name: a.name)
                } add: {
                    addArtist(a.name); query = ""
                }
            }
            ForEach(trackHits, id: \.self) { id in
                resultRow(title: lookup.title(id), subtitle: "Track · \(lookup.artist(id))") {
                    CoverImage(url: lookup.cover(id), size: 32, tint: lookup.family(id).map { Color.family($0) })
                } add: {
                    addSeed(id); query = ""
                }
            }
        }
    }

    private func searchTracks(_ q: String, limit: Int) -> [String] {
        let f = VibeText.fold(q)
        guard !f.isEmpty else { return [] }
        var prefix: [String] = [], contains: [String] = []
        let seeds = Set(draft.seeds)
        for id in model.index.ids where !seeds.contains(id) {
            guard let t = model.index.tracks[id] else { continue }
            let title = VibeText.fold(t.title ?? "")
            if title.hasPrefix(f) { prefix.append(id) } else if title.contains(f) { contains.append(id) }
        }
        let l = lookup
        let byTitle: (String, String) -> Bool = { l.title($0) < l.title($1) }
        return Array((prefix.sorted(by: byTitle) + contains.sorted(by: byTitle)).prefix(limit))
    }

    private func resultRow<Leading: View>(title: String, subtitle: String, @ViewBuilder leading: () -> Leading,
                                          add: @escaping () -> Void) -> some View {
        Button(action: add) {
            HStack(spacing: Space.m) {
                leading()
                VStack(alignment: .leading, spacing: Space.xxs) {
                    Text(title).font(.body).foregroundStyle(Color.textPrimary).lineLimit(1)
                    Text(subtitle).font(.footnote).foregroundStyle(Color.textSecondary).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "plus")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.onAccent)
                    .frame(width: 28, height: 28)
                    .background(Color.accent, in: Circle())
                    .accessibilityHidden(true)
            }
            .frame(minHeight: Size.minTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(Color.bgCard)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Add \(title), \(subtitle)")
        .accessibilityAddTraits(.isButton)
    }

    private func artistSeedRow(_ name: String) -> some View {
        let count = artists?.entries.first { $0.folded == VibeText.fold(name) }?.count ?? 0
        let tracks = count == 1 ? "1 track" : "\(count) tracks"
        return seedRow(label: "Artist \(name), \(tracks)", remove: { draft.artists.removeAll { $0 == name } }) {
            InitialsCircle(name: name)
        } text: {
            Text(name).foregroundStyle(Color.textPrimary)
                + Text(" · artist · \(tracks)").font(.footnote).foregroundStyle(Color.textSecondary)
        }
    }

    private func trackSeedRow(_ id: String) -> some View {
        seedRow(label: "\(lookup.title(id)) by \(lookup.artist(id))", remove: { draft.seeds.removeAll { $0 == id } }) {
            CoverImage(url: lookup.cover(id), size: 32, tint: lookup.family(id).map { Color.family($0) })
        } text: {
            Text(lookup.title(id)).foregroundStyle(Color.textPrimary)
                + Text(" · \(lookup.artist(id))").foregroundStyle(Color.textSecondary)
        }
    }

    private func seedRow<Leading: View>(label: String, remove: @escaping () -> Void, @ViewBuilder leading: () -> Leading,
                                        text: () -> Text) -> some View {
        HStack(spacing: Space.m) {
            leading()
            text().font(.body).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
            VibeMinusButton(label: "Remove \(label)", action: remove)
        }
        .frame(minHeight: Size.minTarget)
        .listRowInsets(.vibeCompactRow)
        .listRowBackground(Color.bgCard)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Seed: \(label)")
        .accessibilityAction(named: "Remove", remove)
    }

    // MARK: Never

    private var neverSection: some View {
        let visible = showAllNever ? draft.exclude : Array(draft.exclude.prefix(2))
        return Section {
            ForEach(visible, id: \.self) { id in
                let allow = { draft.exclude.removeAll { $0 == id } }
                HStack(spacing: Space.m) {
                    Image(systemName: "nosign").font(.footnote).foregroundStyle(Color.textSecondary)
                        .accessibilityHidden(true)
                    (Text(lookup.title(id)).foregroundStyle(Color.textPrimary)
                        + Text(" · \(lookup.artist(id))").foregroundStyle(Color.textSecondary))
                        .font(.body).lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    VibeMinusButton(label: "Allow \(lookup.title(id)) again", action: allow)
                }
                .frame(minHeight: Size.minTarget)
                .listRowInsets(.vibeCompactRow)
                .listRowBackground(Color.bgCard)
                .accessibilityElement(children: .combine)
                .accessibilityLabel("Never: \(lookup.title(id)) by \(lookup.artist(id))")
                .accessibilityAction(named: "Allow again", allow)
            }
            if draft.exclude.count > 2 {
                Button(showAllNever ? "Show less" : "Show \(draft.exclude.count - 2) more") {
                    withAnimation(.snappy(duration: 0.25)) { showAllNever.toggle() }
                }
                .font(.body)
                .foregroundStyle(Color.textSecondary)
                .frame(minHeight: Size.minTarget)
                .listRowBackground(Color.bgCard)
            }
        } header: {
            PanelLabel("Never · \(draft.exclude.count)").vibeSectionHeader()
        }
    }

    // MARK: Share

    private var shareSection: some View {
        Section {
            Picker("How much of the library", selection: Binding(
                get: { VibeShare(share: draft.share) },
                set: { if let s = $0 { draft.share = s.value } }
            )) {
                ForEach(VibeShare.allCases) { s in Text(s.label).tag(Optional(s)) }
            }
            .pickerStyle(.segmented)
            .controlSize(.large)
            .vibeClearRow()
        } header: {
            PanelLabel("How much of the library").vibeSectionHeader()
        }
    }

    // MARK: Preview

    private var previewSection: some View {
        Section {
            if preview.isEmpty {
                Text(model.index.vibeIndex == nil ? "Sound fingerprints aren't loaded yet." : "Finding the closest tracks…")
                    .font(.secondary).foregroundStyle(Color.textSecondary)
                    .frame(minHeight: Size.minTarget)
                    .listRowBackground(Color.bgCard)
            }
            ForEach(Array(visibleMembers.enumerated()), id: \.element) { rank, id in
                previewRow(rank: rank + 1, id: id)
            }
        } header: {
            PanelLabel("Preview · \(visibleMembers.count.formatted()) tracks",
                       trailing: isAX ? nil : (isNew && draft.exclude.isEmpty ? "Closest first" : "Swipe ← never"))
                .vibeSectionHeader()
        } footer: {
            if !preview.isEmpty {
                Text("\u{201C}Never\u{201D} also pushes tracks that sound like it down the list.")
                    .font(.footnote).foregroundStyle(Color.textTertiary)
            }
        }
    }

    /// Members, with tracks just marked Never hidden right away (the re-ranking follows a moment later).
    private var visibleMembers: [String] {
        guard !draft.exclude.isEmpty else { return preview.members }
        let never = Set(draft.exclude)
        return preview.members.filter { !never.contains($0) }
    }

    private func previewRow(rank: Int, id: String) -> some View {
        let isSeed = draft.seeds.contains(id)
        let title = lookup.title(id), artist = lookup.artist(id)
        let toggleSeed = { if isSeed { draft.seeds.removeAll { $0 == id } } else { addSeed(id) } }
        return HStack(spacing: Space.m) {
            Text("\(rank)")
                .font(.system(.footnote, design: .rounded, weight: .medium)).monospacedDigit()
                .foregroundStyle(Color.textTertiary)
                .frame(minWidth: 24, alignment: .trailing)
            CoverImage(url: lookup.cover(id), size: 40, tint: lookup.family(id).map { Color.family($0) })
            VStack(alignment: .leading, spacing: Space.xxs) {
                Text(title).font(.body).foregroundStyle(Color.textPrimary).lineLimit(1)
                Text(artist).font(.secondary).foregroundStyle(Color.textSecondary).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if isSeed {
                Text("Seed").font(.caption.weight(.semibold)).foregroundStyle(Color.textSecondary)
            }
            if let family = lookup.family(id) { FamilyMiniChip(family) }
        }
        .listRowInsets(EdgeInsets(top: Space.s - Space.xxs, leading: Space.l, bottom: Space.s - Space.xxs, trailing: Space.l))
        .listRowBackground(Color.bgCard)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(rank). \(title) by \(artist)\(isSeed ? ", seed" : "")")
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button { never(id) } label: { neverSwipeLabel }
                .tint(Color.accent)
                .accessibilityLabel("Never in \(displayName)")
        }
        .contextMenu {
            Button(isSeed ? "Remove seed" : "Add as seed", systemImage: isSeed ? "minus.circle" : symbol, action: toggleSeed)
            Button("Never in \(displayName)", systemImage: "nosign") { never(id) }
            Button("Show track", systemImage: "music.note") { shownTrack = VibeTrackRef(id: id) }
        }
        .accessibilityAction(named: "Never in \(displayName)") { never(id) }
        .accessibilityAction(named: isSeed ? "Remove seed" : "Add as seed", toggleSeed)
        .accessibilityAction(named: "Show track") { shownTrack = VibeTrackRef(id: id) }
    }

    /// Swipe actions draw their label in white; the accent action needs `onAccent` (black in dark mode), so the
    /// label is a pre-rendered original-colour image.
    @ViewBuilder private var neverSwipeLabel: some View {
        #if canImport(UIKit)
        if let image = Self.renderNeverLabel(dark: colorScheme == .dark, scale: displayScale) {
            Image(uiImage: image)
        } else {
            Label("Never", systemImage: "nosign")
        }
        #else
        Label("Never", systemImage: "nosign")
        #endif
    }

    #if canImport(UIKit)
    @MainActor private static func renderNeverLabel(dark: Bool, scale: CGFloat) -> UIImage? {
        let content = VStack(spacing: Space.xs) {
            Image(systemName: "nosign").font(.system(size: 17, weight: .semibold))
            Text("Never").font(.system(size: 15, weight: .semibold))
        }
        .foregroundStyle(dark ? Color.black : Color.white)
        .frame(width: 72)
        let renderer = ImageRenderer(content: content)
        renderer.scale = scale
        return renderer.uiImage?.withRenderingMode(.alwaysOriginal)
    }
    #endif

    // MARK: Bottom bar

    private var bottomBar: some View {
        let total = max(1, model.index.tracks.count)
        let n = visibleMembers.count
        let pct = (Double(n) / Double(total) * 100).formatted(.number.precision(.fractionLength(1)))
        let shareName = VibeShare(share: draft.share)?.name ?? "Custom"
        return HStack(spacing: Space.l) {
            VStack(alignment: .leading, spacing: Space.xxs) {
                Text(hasSeeds ? n.formatted() : "0")
                    .font(.numeralLarge).foregroundStyle(hasSeeds ? Color.textPrimary : Color.textTertiary)
                    .countTransition(n)
                Text(hasSeeds ? "\(pct)% of the library · \(shareName)" : "Add seeds to see tracks")
                    .font(.footnote).foregroundStyle(Color.textSecondary)
                    .lineLimit(isAX ? 3 : 2)
            }
            .accessibilityElement(children: .combine)
            .frame(maxWidth: .infinity, alignment: .leading)
            // At accessibility sizes the toolbar Save is enough; the bar keeps only the live count.
            if !isAX { saveButton }
        }
        .padding(.horizontal, Space.screenMargin)
        .padding(.vertical, Space.m)
        .background(Color.bgSheet)
        .overlay(alignment: .top) { Rectangle().fill(Color.separator).frame(height: Stroke.hairline) }
    }

    private var saveButton: some View {
            Button(action: save) {
                Text("Save")
                    .font(.headline)
                    .foregroundStyle(canSave ? Color.onAccent : Color.textTertiary)
                    .padding(.horizontal, Space.xxl + Space.s)
                    .frame(minHeight: Size.buttonHeight)
                    .background(canSave ? Color.accent : Color.fillControl,
                                in: RoundedRectangle(cornerRadius: Radius.button, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(!canSave)
    }
}

/// Identifiable wrapper for presenting a track by ID.
struct VibeTrackRef: Identifiable, Hashable { let id: String }

/// The "−" remove control of seed and Never rows (44 pt target).
struct VibeMinusButton: View {
    var label: String
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: "minus")
                .font(.body.weight(.medium))
                .foregroundStyle(Color.textSecondary)
                .frame(minWidth: Size.minTarget, minHeight: Size.minTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(label)
    }
}

extension EdgeInsets {
    /// Rows whose trailing control already supplies the 44 pt height.
    static let vibeCompactRow = EdgeInsets(top: Space.xxs, leading: Space.l, bottom: Space.xxs, trailing: Space.s)
}

extension View {
    /// A list row without card chrome (chips, segmented control, notes between cards).
    func vibeClearRow() -> some View {
        listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: Space.xs, leading: 0, bottom: Space.xs, trailing: 0))
            .listRowSeparator(.hidden)
    }
}
