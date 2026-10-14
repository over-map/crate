import CrateKit
import CrateUI
import SwiftUI
import UIKit

/// Onboarding, 3 steps (3a–3c): connect Spotify, connect the GitHub data repo, optional ntfy notifications.
/// Ends with `model.phase = .loading`. Owner: Weekly/Library agent.
struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL

    @State private var step: Int
    @State private var spotifyConnected = false
    @State private var connecting = false
    @State private var spotifyError: String?
    @State private var token = ""
    @State private var check: TokenCheck = .idle
    @State private var whatsThisOpen = false
    @AppStorage("ntfy.topic") private var topic = ""
    @State private var copied = false
    @State private var didApplyDebug = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    init(startStep: Int = 1) { _step = State(initialValue: min(3, max(1, startStep))) }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            ScrollView {
                VStack(alignment: .leading, spacing: Space.sectionGap) {
                    switch step {
                    case 1: spotifyStep
                    case 2: dataStep
                    default: notificationsStep
                    }
                }
                .padding(.horizontal, Space.screenMargin)
                .padding(.top, Space.xl)
                .padding(.bottom, Space.xl)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .scrollDismissesKeyboard(.interactively)
            bottomBar
        }
        .background(Color.bgBase.ignoresSafeArea())
        .animation(.snappy(duration: 0.25), value: step)
        .onAppear {
            spotifyConnected = model.spotify.isConnected
            if topic.isEmpty { topic = Self.randomTopic() }
            applyDebug()
        }
    }

    // MARK: Chrome

    private var topBar: some View {
        HStack(spacing: Space.m) {
            Group {
                if step > 1 {
                    Button { step -= 1 } label: {
                        HStack(spacing: Space.xxs) { Image(systemName: "chevron.left"); Text("Back") }
                            .font(.body).foregroundStyle(Color.textSecondary)
                            .frame(minHeight: Size.minTarget)
                    }
                    .buttonStyle(.plain)
                } else {
                    Color.clear.frame(width: 1, height: Size.minTarget)
                }
            }
            HStack(spacing: Space.xs) {
                ForEach(1...3, id: \.self) { i in
                    Capsule().fill(i <= step ? Color.accent : Color.fillControlActive).frame(width: 18, height: 3)
                }
                Text("Step \(step) of 3").panelLabelStyle().padding(.leading, Space.xs)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Step \(step) of 3")
            Spacer()
            if step == 3 {
                Button("Skip") { finish() }.font(.body).foregroundStyle(Color.textSecondary).frame(minHeight: Size.minTarget)
            }
        }
        .padding(.horizontal, Space.screenMargin)
        .padding(.top, Space.s)
    }

    @ViewBuilder private var bottomBar: some View {
        VStack(spacing: Space.s) {
            switch step {
            case 1:
                if spotifyConnected {
                    Button("Continue") { step = 2 }.buttonStyle(LargeButtonStyle())
                } else {
                    Button { connectSpotify() } label: {
                        HStack(spacing: Space.s) {
                            if connecting { ProgressView().tint(Color.onAccent) }
                            Text("Connect Spotify")
                        }
                    }
                    .buttonStyle(LargeButtonStyle())
                    .disabled(connecting)
                }
                Text("Opens Spotify to sign in. Crate never posts or follows.")
                    .font(.footnote).foregroundStyle(Color.textTertiary).multilineTextAlignment(.center)
                demoButton
            case 2:
                Button {
                    if case .ok = check { step = 3 } else { Task { if await validate() { step = 3 } } }
                } label: {
                    HStack(spacing: Space.s) {
                        if check == .checking { ProgressView().tint(Color.onAccent) }
                        Text("Continue")
                    }
                }
                .buttonStyle(LargeButtonStyle())
                .disabled(token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || check == .checking)
                demoButton
            default:
                Button("Done") { finish() }.buttonStyle(LargeButtonStyle())
            }
        }
        .padding(.horizontal, Space.screenMargin)
        .padding(.top, Space.m)
        .padding(.bottom, Space.s)
        .background(Color.bgBase)
    }

    @ViewBuilder private var demoButton: some View {
        #if DEBUG
        if DemoDataSource.isAvailable {
            Button("Use demo data") { model.useDemoData() }
                .font(.subheadline.weight(.semibold)).foregroundStyle(Color.textSecondary)
                .frame(minHeight: Size.minTarget)
        }
        #endif
    }

    // MARK: Step 1 · Spotify

    @ViewBuilder private var spotifyStep: some View {
        HStack(spacing: Space.m) {
            Image(systemName: "square.stack.3d.down.forward.fill")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(Color.onAccent)
                .frame(width: 56, height: 56)
                .background(Color.accent, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                .accessibilityHidden(true)
            Text("Crate").font(.system(.largeTitle, weight: .bold)).foregroundStyle(Color.textPrimary)
        }
        .padding(.top, Space.xl)
        .padding(.bottom, Space.l)
        Text("Your liked songs, sorted by what they sound like.")
            .font(.system(.title, weight: .bold)).foregroundStyle(Color.textPrimary)
            .fixedSize(horizontal: false, vertical: true)
        Text("Pick a genre, a vibe and a tempo, then send the matches to Spotify.")
            .font(.body).foregroundStyle(Color.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
        VStack(spacing: 0) {
            permissionRow("Reads", Text("Your Liked Songs"))
            Rectangle().fill(Color.separator).frame(height: 1 / 3)
            permissionRow("Writes", Text("Only two playlists: \(Text("Now").bold()) and \(Text("Crate Daily").bold())"))
        }
        .crateCard()
        if spotifyConnected {
            Label("Spotify connected", systemImage: "checkmark.circle.fill")
                .font(.subheadline.weight(.semibold)).foregroundStyle(Color.success)
        }
        if let spotifyError {
            Label(spotifyError, systemImage: "exclamationmark.triangle.fill")
                .font(.subheadline).foregroundStyle(Color.error)
        }
    }

    private func permissionRow(_ label: String, _ text: Text) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: Space.xs))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: Space.m))
        return layout {
            Text(label).panelLabelStyle().frame(minWidth: 64, alignment: .leading)
            text.font(.subheadline).foregroundStyle(Color.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Space.m)
        .accessibilityElement(children: .combine)
    }

    // MARK: Step 2 · GitHub data

    @ViewBuilder private var dataStep: some View {
        Text("Connect your data").font(.system(.title, weight: .bold)).foregroundStyle(Color.textPrimary)
        Text("Your tags live in a private GitHub repo. Paste the access token for it.")
            .font(.body).foregroundStyle(Color.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
        VStack(alignment: .leading, spacing: Space.labelGap) {
            PanelLabel("GitHub token")
            GitHubTokenField(token: $token) { Task { await validate() } }
            TokenCheckLine(state: check)
        }
        .onChange(of: token) { if check != .checking { check = .idle } }

        VStack(alignment: .leading, spacing: Space.s) {
            Button { withAnimation(.snappy(duration: 0.25)) { whatsThisOpen.toggle() } } label: {
                HStack {
                    Text("What's this?").font(.subheadline.weight(.semibold)).foregroundStyle(Color.textPrimary)
                    Spacer()
                    Image(systemName: "chevron.down").font(.footnote.weight(.semibold)).foregroundStyle(Color.textSecondary)
                        .rotationEffect(.degrees(whatsThisOpen ? 180 : 0))
                }
                .frame(minHeight: Size.minTarget - Space.s)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(whatsThisOpen ? .isSelected : [])
            if whatsThisOpen {
                Text("A fine-grained token with read and write access to the \(Text("crate-data").font(.system(.subheadline, design: .monospaced))) repo only. Crate uses it to read tags and save your corrections. It's stored in the Keychain.")
                    .font(.subheadline).foregroundStyle(Color.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let url = LibraryText.runbookURL("first-time-setup-m0") {
                    Link(destination: url) {
                        Text("How to create one (runbook)").underline().font(.subheadline).foregroundStyle(Color.textPrimary)
                    }
                    .frame(minHeight: Size.minTarget, alignment: .leading)
                }
            }
        }
        .padding(.horizontal, Space.m)
        .padding(.vertical, Space.s)
        .crateCard()
    }

    // MARK: Step 3 · Notifications

    @ViewBuilder private var notificationsStep: some View {
        Text("Get notified if Crate needs you").font(.system(.title, weight: .bold)).foregroundStyle(Color.textPrimary)
            .fixedSize(horizontal: false, vertical: true)
        Text("Crate runs by itself and stays quiet. If something breaks, it sends an alert through the free ntfy app. Subscribe to your topic there.")
            .font(.body).foregroundStyle(Color.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
        VStack(alignment: .leading, spacing: Space.labelGap) {
            PanelLabel("Your topic")
            HStack(spacing: Space.s) {
                TextField("Topic", text: $topic)
                    .font(.system(.body, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.horizontal, Space.m)
                    .frame(minHeight: Size.minTarget + Space.xs)
                    .background(Color.fillControl, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
                    .accessibilityLabel("ntfy topic")
                Button(copied ? "Copied" : "Copy") {
                    UIPasteboard.general.string = topic
                    copied = true
                }
                .buttonStyle(CardButtonStyle(prominent: false))
                .frame(minHeight: Size.minTarget + Space.xs)
                .sensoryFeedback(.success, trigger: copied)
            }
            Text("Use the same topic as NTFY_TOPIC in the repo's Actions secrets.")
                .font(.footnote).foregroundStyle(Color.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                let t = topic.trimmingCharacters(in: .whitespaces)
                if let url = URL(string: "https://ntfy.sh/\(t.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? t)") { openURL(url) }
            } label: {
                Text("Open ntfy \(Image(systemName: "arrow.up.right"))")
                    .font(.subheadline.weight(.semibold)).foregroundStyle(Color.textPrimary)
                    .frame(maxWidth: .infinity, minHeight: Size.minTarget)
                    .overlay(RoundedRectangle(cornerRadius: Radius.control, style: .continuous)
                        .strokeBorder(Color.textPrimary, lineWidth: Stroke.outline))
            }
            .buttonStyle(.plain)
            .disabled(topic.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        VStack(alignment: .leading, spacing: Space.labelGap) {
            PanelLabel("You'll get")
            sampleNotification("ntfy · only if something breaks", "Spotify login rejected. See runbook: Rotate Spotify credentials")
        }
    }

    private func sampleNotification(_ meta: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: Space.xxs) {
            Text(meta).font(.caption).foregroundStyle(Color.textSecondary)
            Text(text).font(.subheadline.weight(.semibold)).foregroundStyle(Color.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(Space.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .crateCard()
        .accessibilityElement(children: .combine)
    }

    // MARK: Actions

    private func connectSpotify() {
        connecting = true
        spotifyError = nil
        Task {
            do {
                try await model.spotify.connect()
                spotifyConnected = model.spotify.isConnected
                if spotifyConnected { step = 2 }
            } catch {
                if (error as? URLError)?.code != .cancelled {
                    spotifyError = "Couldn't connect to Spotify. Check your connection and try again."
                }
            }
            connecting = false
        }
    }

    @discardableResult
    private func validate() async -> Bool {
        let t = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return false }
        check = .checking
        do {
            try model.saveGitHubToken(t)
            let n = try await model.validateGitHub()
            check = .ok(n)
            return true
        } catch {
            check = .failed(TokenCheck.message(for: error))
            return false
        }
    }

    private func finish() { model.phase = .loading }

    static func randomTopic() -> String {
        let chars = Array("abcdefghijkmnpqrstuvwxyz23456789")
        return "crate-" + String((0..<10).map { _ in chars.randomElement()! })
    }

    private func applyDebug() {
        #if DEBUG
        guard !didApplyDebug else { return }
        didApplyDebug = true
        switch DebugLaunch.screen {
        case "onboarding-1": step = 1
        case "onboarding-2":
            step = 2
            whatsThisOpen = true
            token = "github_pat_11ADEMO0example0token0for0screens"
            // Validate against whatever source is set (the demo data with `-demo`) without saving the fake token.
            check = .checking
            Task {
                do { check = .ok(try await model.validateGitHub()) } catch { check = .failed(TokenCheck.message(for: error)) }
            }
        case "onboarding-3": step = 3
        default: break
        }
        #endif
    }
}

// MARK: - Shared token UI (onboarding step 2, Library → GitHub token)

enum TokenCheck: Equatable {
    case idle, checking, ok(Int), failed(String)

    static func message(for error: Error) -> String {
        switch error as? GitHubError {
        case .unauthorized?: "GitHub rejected this token. Check it has read and write access to the crate-data repo."
        case .notFound?: "The token works, but the crate repo has no library yet. Run the backfill first."
        default: "Couldn't reach GitHub. Check your connection and try again."
        }
    }
}

/// Token field: masked after the first 14 characters unless focused, with a Paste button.
struct GitHubTokenField: View {
    @Binding var token: String
    var onSubmit: () -> Void = {}
    @FocusState private var focused: Bool

    private var masked: String {
        token.count <= 14 ? token : String(token.prefix(14)) + String(repeating: "•", count: min(12, token.count - 14))
    }

    var body: some View {
        HStack(spacing: Space.s) {
            ZStack(alignment: .leading) {
                TextField("github_pat_…", text: $token)
                    .font(.system(.subheadline, design: .monospaced))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.password)
                    .focused($focused)
                    .submitLabel(.go)
                    .onSubmit(onSubmit)
                    .opacity(focused || token.isEmpty ? 1 : 0)
                if !focused && !token.isEmpty {
                    Text(masked).font(.system(.subheadline, design: .monospaced)).foregroundStyle(Color.textPrimary)
                        .lineLimit(1)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, Space.m)
            .frame(minHeight: Size.minTarget + Space.xs)
            .background(Color.fillControl, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
            .contentShape(Rectangle())
            .onTapGesture { focused = true }
            .accessibilityLabel("GitHub token")

            PasteButton(payloadType: String.self) { strings in
                guard let s = strings.first else { return }
                Task { @MainActor in
                    token = s.trimmingCharacters(in: .whitespacesAndNewlines)
                    onSubmit()
                }
            }
            .labelStyle(.titleOnly)
            .buttonBorderShape(.roundedRectangle(radius: Radius.control))
            .tint(Color.fillControlActive)
            .foregroundStyle(Color.textPrimary)
        }
    }
}

/// "✓ 1,015 tracks found · 982 tagged", or the error.
struct TokenCheckLine: View {
    let state: TokenCheck
    @Environment(AppModel.self) private var model

    var body: some View {
        switch state {
        case .idle: EmptyView()
        case .checking:
            HStack(spacing: Space.s) { ProgressView(); Text("Checking…").font(.subheadline).foregroundStyle(Color.textSecondary) }
        case let .ok(n):
            line(icon: "checkmark.circle.fill", tint: Color.success, fill: Color.successFill,
                 text: Text("\(Text("\(LibraryText.number(n)) tracks found").bold()) · \(LibraryText.number(model.index.taggedCount)) tagged"))
        case let .failed(message):
            line(icon: "exclamationmark.triangle.fill", tint: Color.error, fill: Color.errorFill, text: Text(message))
        }
    }

    private func line(icon: String, tint: Color, fill: Color, text: Text) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.s) {
            Image(systemName: icon).foregroundStyle(tint)
            text.font(.subheadline).foregroundStyle(Color.textPrimary).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Space.m)
        .padding(.vertical, Space.s)
        .frame(minHeight: Size.minTarget)
        .background(fill, in: RoundedRectangle(cornerRadius: Radius.control, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}
