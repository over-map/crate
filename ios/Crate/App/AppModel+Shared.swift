import AppIntents
import CrateKit
import Foundation
import UIKit
import WidgetKit

/// What the widget and Siri read: each preset's match set and display lines, written to the App Group whenever the
/// library, overrides, vibes or presets change (spec §7.6).
extension AppModel {
    static let shared = SharedStore.appGroup()

    func publishShared() {
        guard let store = Self.shared, !index.tracks.isEmpty else { return }
        let presets = snapshot.presets.presets.map { p -> SharedPreset in
            let ids = index.matchingIDs(p.query)
            let uris = ids.compactMap { id in index.tracks[id].map { $0.spotifyURI(id: id) } }
            let lines = Self.widgetLines(PresetSummary(p.query, model: self, includeAny: false))
            return SharedPreset(id: p.id, name: p.name, icon: p.icon, color: p.color, inWidget: p.inWidget,
                                summary: lines.summary, detail: lines.detail, uris: uris, limit: p.query.limit)
        }
        let next = PresetSnapshot(nowPlaylistID: nowPlaylistID, presets: presets)
        if let old = store.loadSnapshot(), old.presets == next.presets, old.nowPlaylistID == next.nowPlaylistID { return }
        store.save(next)
        WidgetCenter.shared.reloadAllTimelines()
        CrateShortcuts.updateAppShortcutParameters()
    }

    /// A Now playlist the widget or Siri created before the app knew one: adopt it and record it in presets.json.
    func adoptSharedNowPlaylist() {
        guard nowPlaylistID == nil, let id = Self.shared?.loadNowPlaylistID() else { return }
        nowPlaylistID = id
        Task { try? await source?.updatePresets { $0.nowPlaylistID = id } }
    }

    /// Small widget: "Hard Techno, Breaks · 136–142" (everything but energy). Lock Screen: "136–142 · ϟ6–10".
    private static func widgetLines(_ s: PresetSummary) -> (summary: String, detail: String) {
        func plain(_ p: PresetSummary.Part) -> String {
            switch p.symbol {
            case "tag": "⌗ " + p.text
            case "bolt.fill": "ϟ" + p.text
            default: p.text
            }
        }
        let summary = s.parts.filter { $0.symbol != "bolt.fill" }.map(plain).joined(separator: " · ")
        let bpm = s.parts.first { $0.symbol == nil && $0.text.first?.isNumber == true && $0.text.contains("–") }
        let energy = s.parts.first { $0.symbol == "bolt.fill" }
        let detail = [bpm, energy].compactMap { $0 }.map(plain).joined(separator: " · ")
        return (summary.isEmpty ? "Whole library" : summary, detail.isEmpty ? summary : detail)
    }

    // MARK: crate:// links (widget, share extension)

    func open(_ url: URL) {
        guard phase == .ready else { pendingLink = url; return }
        switch CrateLink.parse(url) {
        case .now:
            let target = nowURL ?? URL(string: "spotify:")!
            UIApplication.shared.open(target)
        case .track(let id):
            if index.tracks[id] != nil { linkedTrackID = id }
        case .send(let presetID):
            // Any web page or app can open a crate:// link, so a link-triggered send always asks first
            // (the widget and Siri send through the App Intent, not this link).
            guard snapshot.presets.presets.contains(where: { $0.id == presetID }) else { return }
            linkedSendPresetID = presetID
        case .open, nil:
            break
        }
    }

    /// Confirmed from the "Send … to Now?" alert.
    func confirmLinkedSend() {
        guard let id = linkedSendPresetID,
              let preset = snapshot.presets.presets.first(where: { $0.id == id }) else { return }
        linkedSendPresetID = nil
        build = BuildState(query: preset.query, allStyles: allStyles)
        Task { await send() }
    }
}

/// "Crate, send Run 160" (design 4h). The preset is a parameter, so every preset works without setup.
struct CrateShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: SendPresetToNow(), phrases: [
            "\(.applicationName), send \(\.$preset)",
            "Send \(\.$preset) with \(.applicationName)",
            "Send \(\.$preset) to Now in \(.applicationName)",
        ], shortTitle: "Send preset", systemImageName: "paperplane.fill")
    }
}
