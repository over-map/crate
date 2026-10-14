import CrateUI
import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-gallery") {
            ComponentGallery()
        } else if DebugLaunch.screen == "widgets" { WidgetGallery()
        } else if let s = DebugLaunch.screen, s.hasPrefix("onboarding") || s.hasPrefix("preset") || s == "save-preset" { LibraryDebugRoot()
        } else if DebugLaunch.screen?.hasPrefix("track") == true || DebugLaunch.screen == "edit" || DebugLaunch.screen == "list" { TracksDebugRoot() } else {
            root
        }
        #else
        root
        #endif
    }

    private var root: some View {
        Group {
            switch model.phase {
            case .onboarding: OnboardingView()
            case .loading: ProgressView().task { await model.reload() }
            case .failed(let message): ContentUnavailableView("Couldn't load your library", systemImage: "exclamationmark.triangle",
                                                              description: Text(message))
            case .ready:
                #if DEBUG
                if DebugLaunch.screen?.hasPrefix("vibe") == true { VibesDebugRoot() } else { MainTabs() }
                #else
                MainTabs()
                #endif
            }
        }
        .background(Color.bgBase)
        .onOpenURL { model.open($0) }
    }
}

struct MainTabs: View {
    /// `weekly` is the Daily tab (raw value kept so `-tab weekly` still works).
    enum TabID: String { case build, weekly, library }
    @Environment(AppModel.self) private var model
    @State private var tab: TabID = DebugLaunch.tab ?? .build

    var body: some View {
        TabView(selection: $tab) {
            BuildView().tabItem { Label("Build", systemImage: "slider.horizontal.3") }.tag(TabID.build)
            WeeklyView().tabItem { Label("Daily", systemImage: "sun.horizon.fill") }.tag(TabID.weekly)
            LibraryView().tabItem { Label("Library", systemImage: "music.note.list") }.tag(TabID.library)
        }
        .liftOffHost()
        #if DEBUG
        .onAppear { DemoTour.shared.start(model) }
        .onChange(of: DemoTour.shared.tab) { _, new in if let new { withAnimation { tab = new } } }
        #endif
        // crate://track/<id> from the share extension
        .sheet(item: Binding(get: { model.linkedTrackID.map(LinkedTrack.init) }, set: { model.linkedTrackID = $0?.id })) {
            TrackDetailView(trackID: $0.id)
        }
        // crate://send?preset=<id> from outside Crate: confirm before replacing Now
        .alert("Send \(linkedPresetName) to Now?", isPresented: Binding(
            get: { model.linkedSendPresetID != nil }, set: { if !$0 { model.linkedSendPresetID = nil } })) {
            Button("Send") { model.confirmLinkedSend() }
            Button("Cancel", role: .cancel) { model.linkedSendPresetID = nil }
        } message: {
            Text("A link asked Crate to replace your Now playlist.")
        }
    }

    private struct LinkedTrack: Identifiable { let id: String }

    private var linkedPresetName: String {
        model.snapshot.presets.presets.first { $0.id == model.linkedSendPresetID }?.name ?? "this preset"
    }
}

/// Debug launch arguments for checking screens in the Simulator without logging in:
/// `-demo` (bundled data, `make ios-demo`), `-tab build|weekly|library`, `-screen <name>` (each screen area reads
/// its own names, e.g. `-screen picker`), `-gallery` (component gallery).
enum DebugLaunch {
    static var args: [String] { ProcessInfo.processInfo.arguments }
    static func value(_ flag: String) -> String? {
        guard let i = args.firstIndex(of: flag), i + 1 < args.count else { return nil }
        return args[i + 1]
    }
    static var isDemo: Bool {
        #if DEBUG
        args.contains("-demo")
        #else
        false
        #endif
    }
    static var tab: MainTabs.TabID? {
        #if DEBUG
        value("-tab").flatMap(MainTabs.TabID.init(rawValue:))
        #else
        nil
        #endif
    }
    /// The `-screen` value (Debug only).
    /// `-bottom`: long screens open scrolled to the end (Simulator screenshots of the lower sections).
    static var scrollAnchor: UnitPoint? {
        #if DEBUG
        args.contains("-bottom") ? .bottom : nil
        #else
        nil
        #endif
    }

    static var screen: String? {
        #if DEBUG
        value("-screen")
        #else
        nil
        #endif
    }
}
