import AppIntents
import CrateKit
import CrateUI
import SwiftUI
import WidgetKit

// Compiled into the app (Siri, Shortcuts, Action Button) and the widget (its buttons run the intent in the widget
// process). Both read presets and write the send status through the App Group (`SharedStore`).

/// A preset, as Siri, Shortcuts and the widget configuration list it.
struct PresetEntity: AppEntity, Identifiable {
    static var typeDisplayRepresentation: TypeDisplayRepresentation { "Preset" }
    static var defaultQuery: PresetEntityQuery { PresetEntityQuery() }

    let id: String
    let name: String
    let icon: String

    init(id: String, name: String, icon: String = "bookmark") { self.id = id; self.name = name; self.icon = icon }
    init(_ preset: SharedPreset) { self.init(id: preset.id, name: preset.name, icon: preset.icon) }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)", image: DisplayRepresentation.Image(systemName: icon))
    }
}

struct PresetEntityQuery: EntityStringQuery {
    private var presets: [SharedPreset] { SharedStore.appGroup()?.loadSnapshot()?.presets ?? [] }

    func entities(for identifiers: [String]) async throws -> [PresetEntity] {
        presets.filter { identifiers.contains($0.id) }.map(PresetEntity.init)
    }

    /// "Crate, send Run 160": the spoken name, forgiving case, spaces and punctuation.
    func entities(matching string: String) async throws -> [PresetEntity] {
        let snapshot = PresetSnapshot(presets: presets)
        return snapshot.preset(named: string).map { [PresetEntity($0)] } ?? []
    }

    func suggestedEntities() async throws -> [PresetEntity] { presets.map(PresetEntity.init) }

    /// New widgets start on the first "In widget" preset.
    func defaultResult() async -> PresetEntity? {
        let all = presets
        return (all.first(where: \.inWidget) ?? all.first).map(PresetEntity.init)
    }
}

/// `SendPresetToNow(preset)` (spec §7.5): fills Now with a preset's tracks, without opening Crate.
struct SendPresetToNow: AppIntent {
    static var title: LocalizedStringResource { "Send preset to Now" }
    static var description: IntentDescription {
        IntentDescription("Fills your Spotify playlist Now with tracks from a Crate preset, shuffled.")
    }
    static var openAppWhenRun: Bool { false }
    static var parameterSummary: some ParameterSummary { Summary("Send \(\.$preset) to Now") }

    @Parameter(title: "Preset", requestValueDialog: "Which preset?")
    var preset: PresetEntity

    init() {}
    init(preset: PresetEntity) { self.preset = preset }

    func perform() async throws -> some IntentResult & ProvidesDialog & ShowsSnippetView {
        guard let store = SharedStore.appGroup() else {
            return .result(dialog: IntentDialog(stringLiteral: SendFailure.noData.explanation(preset: preset.name)),
                           view: SendSnippet(outcome: .failed(.noData), presetName: preset.name, preset: nil))
        }
        let sender = PresetSender(store: store, spotify: CrateIdentity.spotifyClient())
        let result = await sender.send(presetID: preset.id, onStatus: { WidgetCenter.shared.reloadAllTimelines() })
        let shared = store.loadSnapshot()?.preset(id: preset.id)
        switch result {
        case .success(let sent):
            return .result(dialog: "Sent \(sent.count) tracks to Now.",
                           view: SendSnippet(outcome: .sent(sent.count, playlistID: sent.playlistID), presetName: preset.name, preset: shared))
        case .failure(let failure):
            return .result(dialog: IntentDialog(stringLiteral: failure.explanation(preset: preset.name)),
                           view: SendSnippet(outcome: .failed(failure), presetName: preset.name, preset: shared))
        }
    }
}

/// The Siri / Action Button card (design 4h sent, 4i error).
struct SendSnippet: View {
    enum Outcome { case sent(Int, playlistID: String), failed(SendFailure) }
    let outcome: Outcome
    let presetName: String
    let preset: SharedPreset?

    var body: some View {
        VStack(alignment: .leading, spacing: Space.m) {
            HStack(spacing: Space.s) {
                PresetTile(color: PresetColor(rawValue: preset?.color ?? "ink") ?? .ink, symbol: preset?.icon ?? "bookmark", size: 28)
                Text("CRATE").font(.panelLabel).tracking(0.8).foregroundStyle(Color.textSecondary)
            }
            switch outcome {
            case let .sent(count, playlistID):
                Text("\(presetName) sent to Now").font(.headline).foregroundStyle(Color.textPrimary)
                HStack(alignment: .firstTextBaseline, spacing: Space.s) {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.success)
                    Text("\(count)").font(.system(.largeTitle, design: .rounded, weight: .bold)).monospacedDigit()
                        .foregroundStyle(Color.textPrimary)
                    Text(detail).font(.footnote).foregroundStyle(Color.textSecondary).lineLimit(2)
                }
                Link(destination: URL(string: "spotify:playlist:\(playlistID)")!) {
                    Label("Listen on Spotify", systemImage: "play.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).tint(Color.accent).foregroundStyle(Color.onAccent)
            case let .failed(failure):
                HStack(spacing: Space.s) {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Color.error)
                    Text("\(presetName) wasn't sent").font(.headline).foregroundStyle(Color.textPrimary)
                }
                Text(failure.explanation(preset: presetName)).font(.subheadline).foregroundStyle(Color.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                Link(destination: CrateLink.open) { Text("Open Crate").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered).tint(Color.textPrimary)
            }
        }
        .padding(Space.l)
    }

    /// "tracks · shuffled · Breaks & bass, Hard Techno · 150–176"
    private var detail: String {
        (["tracks", "shuffled"] + [preset?.summary].compactMap { $0 }.filter { !$0.isEmpty }).joined(separator: " · ")
    }
}
