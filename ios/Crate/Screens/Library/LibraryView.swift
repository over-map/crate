import CrateKit
import CrateUI
import SwiftUI

/// Library tab (6t, 3g–3i): search, privacy hint, signing and health status, sources, your music, settings.
/// Owner: Weekly/Library agent.
struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL

    @State private var search = ""
    @State private var detail: PresetSheetID?
    @State private var signing: SigningExpiry?
    @State private var showToken = false
    @State private var confirmDisconnect = false
    @State private var reloading = false
    @State private var spotifyConnected = false
    @State private var spotifyError: String?
    @State private var didApplyDebug = false
    /// Names of Crate's playlists that Spotify reports as public ("Now", "Crate Daily"), from the last check.
    @State private var publicPlaylists: [PublicPlaylist] = []
    /// "Done" hides the privacy card until the next check.
    @State private var privacyHidden = false
    @State private var privacyCheck = 0
    /// Debug screenshots force the privacy card on or off.
    @State private var debugPrivacy: Bool?

    private var showsPrivacyHint: Bool { debugPrivacy ?? (!privacyHidden && !publicPlaylists.isEmpty) }

    var body: some View {
        NavigationStack {
            List {
                if search.trimmingCharacters(in: .whitespaces).isEmpty {
                    overview
                } else {
                    results
                }
            }
            .listStyle(.insetGrouped)
            .listSectionSpacing(.compact)
            .scrollContentBackground(.hidden)
            .background(Color.bgBase)
            .navigationTitle("Library")
            .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search title or artist")
            .navigationDestination(for: LibraryRoute.self) { route in
                switch route {
                case .vibes: VibesManagerView()
                case .presets: PresetsManagerView()
                }
            }
        }
        .sheet(item: $detail) { TrackDetailView(trackID: $0.id) }
        .sheet(isPresented: $showToken) { GitHubTokenSheet() }
        .onAppear {
            spotifyConnected = model.spotify.isConnected
            applyDebug()
            if signing == nil { signing = try? SigningExpiry.fromBundle() }
            privacyCheck += 1
        }
        .task(id: privacyCheck) { await checkPrivacy() }
        .alert("Spotify", isPresented: Binding(get: { spotifyError != nil }, set: { if !$0 { spotifyError = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(spotifyError ?? "") }
    }

    // MARK: Overview

    @ViewBuilder private var overview: some View {
        if showsPrivacyHint {
            Section { privacyCard }.listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
        }
        if let signing {
            Section { SigningCard(expiry: signing) }
                .listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
        }
        ForEach(Array(model.snapshot.health.problems.enumerated()), id: \.offset) { _, p in
            Section { HealthProblemCard(problem: p) }.listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
        }
        Section { statusCard }.listRowInsets(EdgeInsets()).listRowBackground(Color.clear)

        Section {
            sourceRow("Spotify", ok: spotifyConnected,
                      value: model.isDemo ? "\(LibraryText.number(model.index.tracks.count)) tracks · demo"
                          : spotifyConnected ? "\(LibraryText.number(model.index.tracks.count)) tracks" : "Not connected")
            sourceRow("Audio model", ok: model.index.taggedCount > 0, value: "\(LibraryText.number(model.index.taggedCount)) tagged")
            sourceRow("Discogs", ok: sourceCount("discogs") > 0, value: "\(LibraryText.number(sourceCount("discogs"))) matched")
            sourceRow("Last.fm", ok: lastfmCount > 0, value: "\(LibraryText.number(lastfmCount)) with tags")
            sourceRow("Beatport", ok: sourceCount("beatport") > 0,
                      value: "\(LibraryText.number(sourceCount("beatport"))) entered by hand")
        } header: {
            PanelLabel("Sources")
        } footer: {
            Text("Beatport genres are entered outside the app.").font(.footnote).foregroundStyle(Color.textTertiary)
        }
        .listRowBackground(Color.bgCard)

        Section {
            NavigationLink(value: LibraryRoute.vibes) {
                LabeledContent("Vibes", value: "\(model.snapshot.vibes.vibes.count)")
            }
            NavigationLink(value: LibraryRoute.presets) {
                LabeledContent("Presets", value: "\(model.snapshot.presets.presets.count)")
            }
            LabeledContent("Signing") {
                Text(signingValue).foregroundStyle(signingColor)
            }
            .accessibilityElement(children: .combine)
        } header: {
            PanelLabel("Your music")
        }
        .listRowBackground(Color.bgCard)

        Section {
            Button { showToken = true } label: {
                LabeledContent("GitHub token") { Text(model.isDemo ? "Demo data" : "Replace").foregroundStyle(Color.textSecondary) }
            }
            .foregroundStyle(Color.textPrimary)
            if spotifyConnected {
                Button("Disconnect Spotify", role: .destructive) { confirmDisconnect = true }
                    .foregroundStyle(Color.error)
                    .confirmationDialog("Disconnect Spotify?", isPresented: $confirmDisconnect, titleVisibility: .visible) {
                        Button("Disconnect", role: .destructive) {
                            model.spotify.disconnect()
                            spotifyConnected = model.spotify.isConnected
                        }
                    } message: {
                        Text("Send and likes stop working until you connect again. Your tags and playlists stay safe.")
                    }
                Button("Reconnect Spotify") { connectSpotify() }.foregroundStyle(Color.textPrimary)
            } else {
                Button("Connect Spotify") { connectSpotify() }.foregroundStyle(Color.textPrimary)
            }
            Button {
                reloading = true
                Task { await model.reload(); reloading = false; privacyCheck += 1 }
            } label: {
                HStack {
                    Text("Reload data").foregroundStyle(Color.textPrimary)
                    Spacer()
                    if reloading { ProgressView() }
                }
            }
            .disabled(reloading)
        } header: {
            PanelLabel("Settings")
        }
        .listRowBackground(Color.bgCard)
    }

    private var privacyCard: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            HStack(alignment: .firstTextBaseline, spacing: Space.s) {
                Image(systemName: "lock.fill").font(.subheadline).foregroundStyle(Color.textPrimary)
                Text("Make Now and Crate Daily private").font(.headline).foregroundStyle(Color.textPrimary)
                Spacer(minLength: 0)
                Button { dismissPrivacyHint() } label: {
                    Image(systemName: "xmark").font(.footnote.weight(.semibold)).foregroundStyle(Color.textSecondary)
                        .frame(width: Size.minTarget, height: Size.minTarget)
                }
                .buttonStyle(.plain)
                .padding(.vertical, -Space.m)
                .padding(.trailing, -Space.m)
                .accessibilityLabel("Dismiss")
            }
            Text("\(PublicPlaylist.sentence(publicPlaylists)) In Spotify: ⋯ → Make private.")
                .font(.subheadline).foregroundStyle(Color.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: Space.s) {
                Button("Open Spotify") {
                    let id = publicPlaylists.first?.id ?? model.nowPlaylistID
                    SpotifyLink.open(id.map(SpotifyLink.playlist) ?? SpotifyLink.app, fallback: id.map(SpotifyLink.playlistWeb),
                                     with: openURL)
                }
                .buttonStyle(CardButtonStyle())
                Button("Done") { dismissPrivacyHint() }.buttonStyle(CardButtonStyle(prominent: false))
            }
        }
        .padding(Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .crateCard()
    }

    private var statusCard: some View {
        let total = model.index.tracks.count
        let tagged = model.index.taggedCount
        let notAnalysable = model.index.tracks.values.filter { $0.status == .noPreview || $0.status == .error }.count
        let health = model.snapshot.health
        return VStack(alignment: .leading, spacing: Space.s) {
            HStack(alignment: .firstTextBaseline, spacing: Space.s) {
                Text(LibraryText.number(total)).font(.numeralLarge).foregroundStyle(Color.textPrimary).countTransition(total)
                Text("tracks").font(.subheadline).foregroundStyle(Color.textSecondary)
            }
            .accessibilityElement(children: .combine)
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.fillControlActive)
                    Capsule().fill(Color.textPrimary)
                        .frame(width: total == 0 ? 0 : geo.size.width * CGFloat(tagged) / CGFloat(total))
                }
            }
            .frame(height: 6)
            .accessibilityHidden(true)
            HStack {
                Text("\(LibraryText.number(tagged)) tagged").font(.footnote).foregroundStyle(Color.textSecondary)
                Spacer()
                if notAnalysable > 0 {
                    Text("\(notAnalysable) not analysable").font(.footnote).foregroundStyle(Color.textSecondary)
                }
            }
            Text(syncLine).panelLabelStyle()
                .foregroundStyle(health.effectiveLevel == .broken ? Color.error : Color.textSecondary)

            if health.problems.isEmpty {
                Divider().overlay(Color.separator)
                HStack(alignment: .top, spacing: Space.s) {
                    Image(systemName: health.effectiveLevel == .healthy ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(health.effectiveLevel == .healthy ? Color.success : Color.warning)
                    VStack(alignment: .leading, spacing: Space.xxs) {
                        Text(health.effectiveLevel == .healthy ? "All good" : "Needs a look").font(.subheadline.weight(.semibold))
                        Text(healthDetail).font(.footnote).foregroundStyle(Color.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
        .padding(Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .crateCard()
    }

    private var syncLine: String {
        let date = LibraryText.parseDate(model.snapshot.state.lastSync)
            ?? LibraryText.parseDate(model.snapshot.health.lastNightlySuccess) ?? model.lastSync
        guard let date else { return "Not synced yet" }
        var line = "Synced \(LibraryText.relativeDay(date))"
        if let days = Calendar.current.dateComponents([.day], from: date, to: Date()).day, days >= 2 { line += " · \(days) days ago" }
        if model.snapshot.fromCache { line += " · offline copy" }
        return line
    }

    private var healthDetail: String {
        var parts: [String] = []
        if let d = LibraryText.parseDate(model.snapshot.health.lastNightlySuccess) {
            parts.append("Last nightly run \(LibraryText.relativeDay(d))")
        }
        for c in model.snapshot.health.credentials {
            if let days = c.daysLeft { parts.append("\(c.name) expires in \(days) days") }
        }
        if parts.isEmpty { parts.append("No problems reported") }
        return parts.joined(separator: " · ")
    }

    private func sourceRow(_ name: String, ok: Bool, value: String) -> some View {
        HStack(spacing: Space.m) {
            Circle().fill(ok ? Color.success : Color.textTertiary).frame(width: 8, height: 8).accessibilityHidden(true)
            Text(name).foregroundStyle(Color.textPrimary)
            Spacer()
            Text(value).font(.subheadline).foregroundStyle(Color.textSecondary)
        }
        .frame(minHeight: Size.minTarget)
        .accessibilityElement(children: .combine)
        .accessibilityValue(ok ? "" : "not available")
    }

    private func sourceCount(_ key: String) -> Int {
        model.index.tracks.values.filter { !($0.consensus?.bySource[key] ?? []).isEmpty }.count
    }

    private var lastfmCount: Int { model.index.tracks.values.filter { !($0.tags ?? []).isEmpty }.count }

    private var signingValue: String {
        guard let signing else { return "Not signed (Simulator)" }
        if signing.isExpired() { return "Expired" }
        let d = signing.daysRemaining()
        return d == 0 ? "Today" : "\(d) day\(d == 1 ? "" : "s")"
    }

    private var signingColor: Color {
        switch signing?.level() {
        case .critical: Color.error
        case .warning: Color.warning
        default: Color.textSecondary
        }
    }

    // MARK: Search

    @ViewBuilder private var results: some View {
        let hits = matches
        if hits.isEmpty {
            ContentUnavailableView.search(text: search).listRowBackground(Color.clear)
        } else {
            Section {
                ForEach(hits, id: \.self) { id in
                    if let t = model.index.tracks[id] {
                        Button { detail = PresetSheetID(id: id) } label: {
                            TrackRow(title: t.title ?? "Unknown track", artist: (t.artists ?? []).joined(separator: ", "),
                                     coverURL: t.cover.flatMap(URL.init(string:)),
                                     coverTint: Color.family(LibraryText.family(model.index.effective[id]?.family)),
                                     tagLine: CompactTagLine(track: t, styleName: styleName(id)))
                        }
                        .buttonStyle(.plain)
                    }
                }
            } header: {
                PanelLabel("\(hits.count == 100 ? "100+" : String(hits.count)) tracks")
            }
            .listRowBackground(Color.bgCard)
        }
    }

    private var matches: [String] {
        let q = search.trimmingCharacters(in: .whitespaces).folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil)
        guard !q.isEmpty else { return [] }
        let fold = { (s: String) in s.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: nil) }
        let hits = model.index.ids.filter { id in
            guard let t = model.index.tracks[id] else { return false }
            return fold(t.title ?? "").contains(q) || (t.artists ?? []).contains { fold($0).contains(q) }
        }
        let sorted = hits.sorted {
            let a = model.index.tracks[$0]?.title ?? "", b = model.index.tracks[$1]?.title ?? ""
            let ap = fold(a).hasPrefix(q), bp = fold(b).hasPrefix(q)
            return ap != bp ? ap : a.localizedCaseInsensitiveCompare(b) == .orderedAscending
        }
        return Array(sorted.prefix(100))
    }

    private func styleName(_ id: String) -> String? {
        model.index.effective[id]?.primaryStyle.map { StyleLabel(canonical: $0, aliases: model.snapshot.taxonomy.aliases).styleDisplay }
    }

    // MARK: Actions

    private func dismissPrivacyHint() {
        withAnimation { privacyHidden = true; debugPrivacy = nil }
    }

    /// Asks Spotify whether Now and Crate Daily are public. Demo mode, no login, errors and offline: no card.
    private func checkPrivacy() async {
        guard debugPrivacy == nil else { return }
        guard !model.isDemo, model.spotify.isConnected else { publicPlaylists = []; return }
        let mixID = model.snapshot.weekly.playlistID ?? model.snapshot.state.weeklyPlaylistID
        let candidates = [(model.nowPlaylistID, "Now"), (mixID, "Crate Daily")]
            .compactMap { id, name in id.flatMap { $0.isEmpty ? nil : PublicPlaylist(id: $0, name: name) } }
        var found: [PublicPlaylist] = []
        for c in candidates {
            if let p = try? await model.spotify.playlist(id: c.id), p.isPublic == true { found.append(c) }
        }
        guard !Task.isCancelled else { return }
        withAnimation {
            publicPlaylists = found
            privacyHidden = false
        }
    }

    private func connectSpotify() {
        Task {
            do {
                try await model.spotify.connect()
            } catch {
                if (error as? URLError)?.code != .cancelled { spotifyError = "Couldn't connect to Spotify. Try again." }
            }
            spotifyConnected = model.spotify.isConnected
        }
    }

    private func applyDebug() {
        #if DEBUG
        guard !didApplyDebug else { return }
        didApplyDebug = true
        switch DebugLaunch.screen {
        case "library-warning": signing = SigningExpiry(expirationDate: Date().addingTimeInterval(2 * 86_400 - 3_600))
        case "library-critical": signing = SigningExpiry(expirationDate: Date().addingTimeInterval(5 * 3_600))
        case "library-ok": signing = SigningExpiry(expirationDate: Date().addingTimeInterval(4 * 86_400 + 3_600))
        case "library-search": search = "quantic"
        default: break
        }
        if DebugLaunch.screen == "library-hint" {
            debugPrivacy = true
            publicPlaylists = [PublicPlaylist(id: model.snapshot.weekly.playlistID ?? "", name: "Crate Daily")]
        } else if DebugLaunch.screen?.hasPrefix("library-") == true {
            debugPrivacy = false
        }
        #endif
    }
}

enum LibraryRoute: Hashable { case vibes, presets }

// MARK: - Signing card (3h, 3i)

/// The app-signing countdown: neutral card above 2 days, `warningFill` at ≤ 2 days, `errorFill` on the last day.
/// Refreshing is done from Xcode (cable, ⌘R); the card says how and offers to turn on the reminders.
struct SigningCard: View {
    let expiry: SigningExpiry
    @State private var remindersOn: Bool?

    static func deadline(_ date: Date) -> String {
        "\(date.formatted(.dateTime.weekday(.abbreviated))) \(date.formatted(date: .omitted, time: .shortened))"
    }

    private var level: SigningExpiry.Level { expiry.level() }
    private var critical: Bool { level == .critical }
    private var time: String { expiry.expirationDate.formatted(date: .omitted, time: .shortened) }

    private var title: String {
        if expiry.isExpired() { return "App signing expired" }
        if critical { return Calendar.current.isDateInToday(expiry.expirationDate) ? "App expires today at \(time)" : "App expires tomorrow at \(time)" }
        let d = max(1, Int((expiry.timeRemaining() / 86_400).rounded(.up)))
        return "App expires in \(d) day\(d == 1 ? "" : "s")"
    }

    private var message: String {
        let how = RefreshNotifications.howTo
        if expiry.isExpired() { return "\(how) Your tags and playlists stay safe." }
        if critical { return "\(how) After \(time) Crate won't open until you do." }
        if level == .warning {
            return "Before \(Self.deadline(expiry.expirationDate)): \(how.prefix(1).lowercased() + how.dropFirst()) Your tags and playlists stay safe."
        }
        return "Before \(Self.deadline(expiry.expirationDate)): \(how.prefix(1).lowercased() + how.dropFirst())"
    }

    private var icon: (String, Color) {
        switch level {
        case .critical: ("clock.badge.exclamationmark.fill", Color.error)
        case .warning: ("exclamationmark.triangle.fill", Color.warning)
        case .ok: ("clock", Color.textSecondary)
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            HStack(alignment: .firstTextBaseline, spacing: Space.s) {
                Image(systemName: icon.0).foregroundStyle(icon.1)
                Text(title).font(.headline).foregroundStyle(Color.textPrimary)
            }
            .accessibilityElement(children: .combine)
            Text(message).font(.subheadline)
                .foregroundStyle(level == .ok ? Color.textSecondary : Color.textPrimary.opacity(0.85))
                .fixedSize(horizontal: false, vertical: true)
            switch remindersOn {
            case true?:
                Label("Reminders the day before and on the day", systemImage: "bell")
                    .font(.footnote).foregroundStyle(Color.textSecondary)
            case false?:
                Button {
                    Task { remindersOn = await RefreshNotifications.shared.requestPermission() }
                } label: {
                    Label("Turn on reminders", systemImage: "bell.badge").lineLimit(1)
                }
                .buttonStyle(CardButtonStyle(fullWidth: true))
            case nil:
                EmptyView()
            }
        }
        .padding(Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .signingCardBackground(level)
        .accessibilityElement(children: .contain)
        .task { remindersOn = await RefreshNotifications.shared.isAuthorized() }
    }
}

private extension View {
    /// The regular Crate card when there is time left; the warning or error fill as expiry nears.
    @ViewBuilder func signingCardBackground(_ level: SigningExpiry.Level) -> some View {
        switch level {
        case .ok: crateCard()
        case .warning: background(Color.warningFill, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        case .critical: background(Color.errorFill, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        }
    }
}

// MARK: - Health problem card

/// One active problem from `health.json`, with its runbook step.
struct HealthProblemCard: View {
    let problem: HealthProblem
    private var broken: Bool { problem.level == .broken }

    var body: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            HStack(alignment: .firstTextBaseline, spacing: Space.s) {
                Image(systemName: broken ? "xmark.octagon.fill" : "exclamationmark.triangle.fill")
                    .foregroundStyle(broken ? Color.error : Color.warning)
                Text(problem.message).font(.subheadline.weight(.semibold)).foregroundStyle(Color.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let anchor = problem.runbook, !anchor.isEmpty, let url = LibraryText.runbookURL(anchor) {
                Link(destination: url) {
                    Text("Runbook: \(Text(LibraryText.runbookTitle(anchor)).underline())")
                        .font(.footnote).foregroundStyle(Color.textSecondary)
                }
                .frame(minHeight: Size.minTarget, alignment: .leading)
            }
        }
        .padding(Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(broken ? Color.errorFill : Color.warningFill, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
    }
}

// MARK: - GitHub token sheet

/// Replace the GitHub token: paste, save to the Keychain, validate by loading the library.
struct GitHubTokenSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var token = ""
    @State private var state: TokenCheck = .idle

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: Space.sectionGap) {
                Text("Paste a fine-grained token with read and write access to the crate repo only. It's stored in the Keychain.")
                    .font(.subheadline).foregroundStyle(Color.textSecondary)
                GitHubTokenField(token: $token)
                TokenCheckLine(state: state)
                Spacer()
            }
            .padding(Space.screenMargin)
            .background(Color.bgSheet)
            .navigationTitle("GitHub token")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }
                        .fontWeight(.semibold)
                        .disabled(token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || state == .checking)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(Color.bgSheet)
    }

    private func save() async {
        state = .checking
        do {
            try model.saveGitHubToken(token)
            let n = try await model.validateGitHub()
            state = .ok(n)
            await model.reload()
            dismiss()
        } catch {
            state = .failed(TokenCheck.message(for: error))
        }
    }
}
