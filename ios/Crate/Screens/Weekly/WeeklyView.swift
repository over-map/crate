import CrateKit
import CrateUI
import SwiftUI

/// Daily tab (6q–6r, now "Crate Daily"): today's taste families, filters, "Your taste" and "Discovery" sections,
/// why-this-track notes and likes. The pipeline rebuilds the mix every morning; `data/weekly.json` keeps its name
/// and only holds the latest mix. Owner: Weekly/Library agent.
struct WeeklyView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL

    @State private var expanded: String?
    @State private var liked: Set<String> = []
    @State private var liking: Set<String> = []
    @State private var family: String?
    @State private var vibe: String?
    @State private var tag: String?
    @State private var likedOnly = false
    @State private var likeError: String?
    @State private var didApplyDebug = false
    /// DEBUG `-screen daily-today`: treat the bundled (older) demo mix as today's.
    @State private var debugToday = false

    private var weekly: WeeklyFile { model.snapshot.weekly }
    private var taxonomy: TaxonomyFile { model.snapshot.taxonomy }

    var body: some View {
        NavigationStack {
            Group {
                if weekly.tracks.isEmpty {
                    ContentUnavailableView("No Crate Daily yet", systemImage: "sun.horizon",
                                           description: Text("It arrives every morning before 06:00."))
                } else {
                    content
                }
            }
            .background(Color.bgBase)
            .navigationTitle("Daily")
            .toolbar {
                if playlistID != nil {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { openPlaylist() } label: { Image(systemName: "arrow.up.forward.app") }
                            .accessibilityLabel("Open Crate Daily in Spotify")
                    }
                }
            }
        }
        .task(id: (weekly.date ?? "") + (weekly.week ?? "")) { await loadLikes() }
        .onAppear(perform: applyDebug)
        .alert("Couldn't change the like", isPresented: Binding(get: { likeError != nil }, set: { if !$0 { likeError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(likeError ?? "") }
    }

    // MARK: Content

    private var content: some View {
        let taste = filtered(weekly.tracks.filter { $0.kind != .discovery })
        let discovery = filtered(weekly.tracks.filter { $0.kind == .discovery })
        return ScrollView {
            VStack(alignment: .leading, spacing: Space.sectionGap) {
                header
                filters
                if taste.isEmpty && discovery.isEmpty {
                    VStack(alignment: .leading, spacing: Space.s) {
                        Text("No tracks match these filters.").font(.body).foregroundStyle(Color.textSecondary)
                        Button("Clear filters") { clearFilters() }.font(.body.weight(.semibold)).foregroundStyle(Color.accent)
                            .frame(minHeight: Size.minTarget)
                    }
                    .padding(.top, Space.l)
                }
                if !taste.isEmpty {
                    section(PanelLabel("Your taste · \(taste.count)", trailing: perGenreLabel), note: nil, tracks: taste)
                }
                if !discovery.isEmpty {
                    section(PanelLabel("Discovery · \(discovery.count)", trailing: "Genres you have little of"),
                            note: "Ranked by how close they are to your overall sound.", tracks: discovery)
                }
                if playlistID != nil {
                    Button { openPlaylist() } label: {
                        Label("Open Crate Daily in Spotify", systemImage: "arrow.up.forward.app")
                    }
                    .buttonStyle(LargeButtonStyle(prominent: false))
                    .padding(.top, Space.s)
                }
            }
            .padding(.horizontal, Space.screenMargin)
            .padding(.bottom, Space.xl)
        }
        .scrollContentBackground(.hidden)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: Space.labelGap) {
            Text(headerLabel).panelLabelStyle().accessibilityAddTraits(.isHeader)
            ChipFlow(spacing: Space.chipGap, lineSpacing: Space.chipGap) {
                ForEach(tasteFamilies, id: \.self) { f in
                    WeeklyFamilyChip(family: LibraryText.family(f), title: LibraryText.familyName(f, taxonomy: taxonomy))
                }
            }
            Text("Genres rotate every day. Like what you love and the mix learns.")
                .font(.footnote).foregroundStyle(Color.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }

    private var filters: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Space.chipGap) {
                filterMenu(title: "Genre", value: family.map { LibraryText.familyName($0, taxonomy: taxonomy) },
                           options: allFamilies.map { ($0, LibraryText.familyName($0, taxonomy: taxonomy)) },
                           anyTitle: "Any genre", selection: $family)
                filterMenu(title: "Vibe", value: vibe.map { vibeName($0) },
                           options: vibeOptions.map { ($0, vibeName($0)) }, anyTitle: "Any vibe", selection: $vibe)
                filterMenu(title: "Tag", value: tag.map { "⌗ \($0)" },
                           options: tagOptions.map { ($0, $0) }, anyTitle: "Any tag", selection: $tag)
                Button { likedOnly.toggle() } label: {
                    HStack(spacing: Space.xs) {
                        if likedOnly { Image(systemName: "heart.fill").font(.footnote) }
                        Text("Liked")
                    }
                    .libraryChip(selected: likedOnly)
                }
                .buttonStyle(.plain)
                .sensoryFeedback(.selection, trigger: likedOnly)
                .accessibilityAddTraits(likedOnly ? .isSelected : [])
                .accessibilityLabel("Liked only")
            }
        }
        .scrollClipDisabled()
    }

    private func filterMenu(title: String, value: String?, options: [(String, String)], anyTitle: String,
                            selection: Binding<String?>) -> some View {
        Menu {
            Button(anyTitle) { selection.wrappedValue = nil }
            if options.isEmpty {
                Text(title == "Tag" ? "No tags for today's artists" : "Nothing to filter")
            }
            ForEach(options, id: \.0) { id, name in
                Button { selection.wrappedValue = id } label: {
                    if selection.wrappedValue == id { Label(name, systemImage: "checkmark") } else { Text(name) }
                }
            }
        } label: {
            HStack(spacing: Space.xs) {
                Text(value ?? title)
                Image(systemName: "chevron.down").font(.caption2.weight(.bold))
            }
            .libraryChip(selected: value != nil)
        }
        .accessibilityLabel(title)
        .accessibilityValue(value ?? "Any")
    }

    private func section(_ label: PanelLabel, note: String?, tracks: [WeeklyTrack]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            label
            if let note {
                Text(note).font(.footnote).foregroundStyle(Color.textSecondary).padding(.top, Space.xs)
            }
            VStack(spacing: 0) {
                ForEach(tracks) { t in
                    WeeklyRow(track: t, familyName: t.family.map { LibraryText.familyName($0, taxonomy: taxonomy) },
                              reason: reason(for: t), isExpanded: expanded == t.id, isLiked: liked.contains(t.id),
                              onWhy: { withAnimation(.snappy(duration: 0.25)) { expanded = expanded == t.id ? nil : t.id } },
                              onLike: { like(t) })
                    Rectangle().fill(Color.separator).frame(height: 1 / 3)
                }
            }
            .padding(.top, Space.xs)
        }
    }

    // MARK: Data

    private var playlistID: String? { weekly.playlistID ?? model.snapshot.state.weeklyPlaylistID }

    /// "TODAY · TUE OCT 13 · YOUR TASTE TODAY"; "YESTERDAY · MON OCT 12 · YOUR TASTE" or "SAT OCT 10 · YOUR TASTE"
    /// for an older mix; "WEEK 42 · OCT 12–18 · YOUR TASTE" for an old weekly file with no usable day.
    private var headerLabel: String { Self.headerLabel(for: weekly, debugToday: debugToday) }

    static func headerLabel(for file: WeeklyFile, now: Date = Date(), debugToday: Bool = false) -> String {
        let cal = Calendar.current
        if let day = debugToday ? cal.startOfDay(for: now) : file.mixDay(calendar: cal) {
            let date = "\(day.formatted(.dateTime.weekday(.abbreviated))) \(day.formatted(.dateTime.month(.abbreviated).day()))"
            // A mix stamped later than today (time zones) still counts as today's.
            if cal.isDate(day, inSameDayAs: now) || day > now { return "Today · \(date) · Your taste today" }
            if cal.isDateInYesterday(day) { return "Yesterday · \(date) · Your taste" }
            return "\(date) · Your taste"
        }
        if let wk = isoWeek(file.week) {
            let monday = wk.1
            let sunday = Calendar(identifier: .iso8601).date(byAdding: .day, value: 6, to: monday) ?? monday
            let sameMonth = cal.isDate(monday, equalTo: sunday, toGranularity: .month)
            let start = monday.formatted(.dateTime.month(.abbreviated).day())
            let end = sameMonth ? sunday.formatted(.dateTime.day()) : sunday.formatted(.dateTime.month(.abbreviated).day())
            return "Week \(wk.0) · \(start)–\(end) · Your taste"
        }
        return "Your taste"
    }

    static func isoWeek(_ label: String?) -> (Int, Date)? {
        guard let label else { return nil }
        let bits = label.components(separatedBy: "-W")
        guard bits.count == 2, let year = Int(bits[0]), let week = Int(bits[1]) else { return nil }
        let cal = Calendar(identifier: .iso8601)
        guard let monday = cal.date(from: DateComponents(weekday: 2, weekOfYear: week, yearForWeekOfYear: year)) else { return nil }
        return (week, monday)
    }

    /// The mix's taste families in mix order (5 per the design).
    private var tasteFamilies: [String] {
        var seen: [String] = []
        for t in weekly.tracks where t.kind != .discovery {
            if let f = t.family, !seen.contains(f) { seen.append(f) }
        }
        return seen
    }

    private var allFamilies: [String] {
        var seen: [String] = []
        for t in weekly.tracks { if let f = t.family, !seen.contains(f) { seen.append(f) } }
        return seen
    }

    private var perGenreLabel: String? {
        let taste = weekly.tracks.filter { $0.kind != .discovery }
        let fams = tasteFamilies.count
        guard fams > 0, taste.count % fams == 0, family == nil, vibe == nil, tag == nil, !likedOnly else { return nil }
        return "\(taste.count / fams) per genre"
    }

    private func vibeName(_ id: String) -> String { model.snapshot.vibes.vibes[id]?.name ?? id.capitalized }

    /// New tracks are not in the library, so vibes use the audio model's matching mood (same IDs) when there is one.
    private var vibeOptions: [String] {
        model.snapshot.vibes.ordered.map(\.id).filter { id in weekly.tracks.contains { hasVibe($0, id) } }
    }

    private func hasVibe(_ t: WeeklyTrack, _ id: String) -> Bool {
        (t.auto?.moods?[id] ?? 0) >= EffectiveTags.moodThreshold
    }

    /// Community tags of the same artists in the library (Last.fm tags describe the artist anyway).
    private var artistTags: [String: Set<String>] {
        let wanted = Set(weekly.tracks.flatMap { $0.artists ?? [] })
        var out: [String: Set<String>] = [:]
        for (id, track) in model.index.tracks {
            for a in track.artists ?? [] where wanted.contains(a) {
                out[a, default: []].formUnion(model.index.effective[id]?.communityTags ?? [])
            }
        }
        return out
    }

    private func tags(of t: WeeklyTrack, _ map: [String: Set<String>]) -> Set<String> {
        (t.artists ?? []).reduce(into: Set<String>()) { $0.formUnion(map[$1] ?? []) }
    }

    private var tagOptions: [String] {
        let map = artistTags
        var counts: [String: Int] = [:]
        for t in weekly.tracks { for tag in tags(of: t, map) { counts[tag, default: 0] += 1 } }
        return counts.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }.prefix(12).map(\.key)
    }

    private func filtered(_ tracks: [WeeklyTrack]) -> [WeeklyTrack] {
        let map = tag == nil ? [:] : artistTags
        return tracks.filter { t in
            if let family, t.family != family { return false }
            if let vibe, !hasVibe(t, vibe) { return false }
            if let tag, !tags(of: t, map).contains(tag) { return false }
            if likedOnly, !liked.contains(t.id) { return false }
            return true
        }
    }

    private func clearFilters() { family = nil; vibe = nil; tag = nil; likedOnly = false }

    /// "close to “Title” by Artist, which you liked" when the pick has a vector, else the pipeline's reason, else
    /// one built from what is known.
    private func reason(for t: WeeklyTrack) -> String {
        if let closest = closestLiked(to: t) {
            let name = t.family.map { LibraryText.familyName($0, taxonomy: taxonomy) } ?? "No genre"
            let kind = t.kind == .discovery ? "Discovery" : "Taste"
            return "\(kind) · \(name): sounds closest to \(closest), which you liked."
        }
        if let r = t.reason, !r.isEmpty { return r }
        let name = t.family.map { LibraryText.familyName($0, taxonomy: taxonomy) } ?? "No genre"
        let have = t.family.flatMap { f in model.index.families.first { $0.family == f }?.count } ?? 0
        if t.kind == .discovery {
            let owned = have == 0 ? "you have no \(name) tracks yet"
                : "you have \(have) \(name) track\(have == 1 ? "" : "s")"
            return "Discovery · \(name): \(owned). It sits close to your overall sound."
        }
        return "Taste · \(name): in today's rotation, close to the \(LibraryText.number(have)) \(name) tracks you liked."
    }

    /// The library track that sounds most like a Daily pick: "“Teardrop” by Massive Attack".
    private func closestLiked(to t: WeeklyTrack) -> String? {
        guard let v = t.vector, let index = model.snapshot.vectors,
              let id = index.nearest(vector: v, count: 2).first(where: { $0.id != t.id })?.id,
              let track = model.index.tracks[id], let title = track.title else { return nil }
        let artist = track.artists?.first.map { " by \($0)" } ?? ""
        return "“\(title)”\(artist)"
    }

    private func openPlaylist() {
        guard let id = playlistID else { return }
        SpotifyLink.open(SpotifyLink.playlist(id), fallback: SpotifyLink.playlistWeb(id), with: openURL)
    }

    private func like(_ t: WeeklyTrack) {
        if model.isDemo {
            if liked.contains(t.id) { liked.remove(t.id) } else { liked.insert(t.id) }
            return
        }
        // The heart toggles: like saves to Liked Songs, a second tap removes it again.
        guard !liking.contains(t.id) else { return }
        let wasLiked = liked.contains(t.id)
        let uri = t.uri ?? "spotify:track:\(t.id)"
        if wasLiked { liked.remove(t.id) } else { liked.insert(t.id) }
        liking.insert(t.id)
        Task {
            do {
                if wasLiked { try await model.spotify.unlike(uri: uri) } else { try await model.spotify.like(uri: uri) }
            } catch {
                if wasLiked { liked.insert(t.id) } else { liked.remove(t.id) }
                likeError = wasLiked ? "Spotify didn't remove the like. Try again in a moment."
                    : "Spotify didn't accept the like. Try again in a moment."
            }
            liking.remove(t.id)
        }
    }

    private func loadLikes() async {
        guard !model.isDemo, model.spotify.isConnected, !weekly.tracks.isEmpty else { return }
        let uris = weekly.tracks.map { $0.uri ?? "spotify:track:\($0.id)" }
        guard let flags = try? await model.spotify.contains(uris: uris) else { return }
        liked = Set(zip(weekly.tracks, flags).filter(\.1).map(\.0.id))
    }

    private func applyDebug() {
        #if DEBUG
        guard !didApplyDebug else { return }
        didApplyDebug = true
        if DebugLaunch.screen == "daily-today" { debugToday = true }
        if DebugLaunch.screen == "weekly-why" {
            expanded = weekly.tracks.first { $0.kind != .discovery }?.id
        }
        #endif
    }
}

/// The 28 pt family chip of the Weekly header.
struct WeeklyFamilyChip: View {
    let family: Family
    let title: String

    var body: some View {
        Text(title)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Color.onData)
            .lineLimit(1)
            .padding(.horizontal, 10)
            .frame(minHeight: 28)
            .background(Color.family(family), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
            .accessibilityLabel("Genre \(title)")
    }
}

/// One Weekly row: cover, title, family chip + artist, "?" toggle and heart; the reason note expands below.
struct WeeklyRow: View {
    let track: WeeklyTrack
    let familyName: String?
    let reason: String
    let isExpanded: Bool
    let isLiked: Bool
    let onWhy: () -> Void
    let onLike: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var artist: String { (track.artists ?? []).joined(separator: ", ") }
    private var family: Family { LibraryText.family(track.family) }

    private var cover: some View {
        CoverImage(url: track.cover.flatMap(URL.init(string:)), size: Size.coverRow, radius: Radius.cover,
                   tint: Color.family(family))
    }

    private var info: some View {
        let ax = dynamicTypeSize.isAccessibilitySize
        return VStack(alignment: .leading, spacing: Space.xs) {
            Text(track.title ?? "Unknown track").font(.body).foregroundStyle(Color.textPrimary)
                .lineLimit(ax ? 3 : 1)
            if ax {
                if let familyName { FamilyMiniChip(family, title: familyName) }
                Text(artist).font(.secondary).foregroundStyle(Color.textSecondary).lineLimit(2)
            } else {
                HStack(spacing: Space.s) {
                    if let familyName { FamilyMiniChip(family, title: familyName).fixedSize().layoutPriority(1) }
                    Text(artist).font(.secondary).foregroundStyle(Color.textSecondary).lineLimit(1)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private var whyButton: some View {
        Button(action: onWhy) {
            ZStack {
                Circle().fill(isExpanded ? Color.accent : Color.clear)
                Circle().strokeBorder(isExpanded ? Color.clear : Color.textSecondary, lineWidth: Stroke.outline)
                Text("?").font(.footnote.weight(.bold)).dynamicTypeSize(...DynamicTypeSize.xxLarge)
                    .foregroundStyle(isExpanded ? Color.onAccent : Color.textPrimary)
            }
            .frame(width: 28, height: 28)
            .frame(width: Size.minTarget, height: Size.minTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Why this track?")
        .accessibilityAddTraits(isExpanded ? .isSelected : [])
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            if dynamicTypeSize.isAccessibilitySize {
                HStack(alignment: .top, spacing: Space.m) { cover; info }
                HStack(spacing: Space.s) { Spacer(); whyButton; LikeButton(isLiked: isLiked, action: onLike) }
            } else {
                HStack(spacing: Space.m) { cover; info; whyButton; LikeButton(isLiked: isLiked, action: onLike) }
            }
            if isExpanded {
                Text(reason)
                    .font(.footnote)
                    .foregroundStyle(Color.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(Space.m)
                    .background(Color.fillControl, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
                    .padding(.leading, dynamicTypeSize.isAccessibilitySize ? 0 : Size.coverRow + Space.m)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(.vertical, Space.s)
        .frame(minHeight: Size.coverRow + Space.l)
    }
}
