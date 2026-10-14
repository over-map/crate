import AppIntents
import CrateKit
import SwiftUI
import WidgetKit

@main
struct CrateWidgetBundle: WidgetBundle {
    var body: some Widget {
        PresetWidget()
        GridWidget()
    }
}

/// Small Home Screen and Lock Screen widgets: one preset, picked when editing the widget.
struct PresetWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(kind: "preset", intent: SelectPresetIntent.self, provider: PresetProvider()) { entry in
            PresetWidgetView(entry: entry)
        }
        .configurationDisplayName("Preset")
        .description("One tap fills Now with a preset.")
        .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular])
        .contentMarginsDisabled()
    }
}

/// Medium widget: the first four "In widget" presets from the Presets manager, in order.
struct GridWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "grid", provider: GridProvider()) { entry in
            GridWidgetView(entry: entry)
        }
        .configurationDisplayName("Presets")
        .description("Your four widget presets. Tap one to fill Now.")
        .supportedFamilies([.systemMedium])
        .contentMarginsDisabled()
    }
}

/// The preset a small or Lock Screen widget sends.
struct SelectPresetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource { "Choose preset" }
    static var description: IntentDescription { IntentDescription("The preset this widget sends to Now.") }

    @Parameter(title: "Preset")
    var preset: PresetEntity?

    init() {}
    init(preset: PresetEntity?) { self.preset = preset }
}

// MARK: Timeline

enum Timelines {
    static func load(presetID: String?, now: Date = .now) -> (entries: [CrateEntry], policy: TimelineReloadPolicy) {
        let store = SharedStore.appGroup()
        let snapshot = store?.loadSnapshot()
        let raw = store?.loadStatus()
        let id = presetID.flatMap { snapshot?.preset(id: $0)?.id }
            ?? snapshot?.widgetPresets.first?.id ?? snapshot?.presets.first?.id
        var entries = [CrateEntry(date: now, snapshot: snapshot, status: raw?.visible(at: now), presetID: id)]
        // Sent and Sending end on their own: add the idle frame for that moment.
        if let raw, raw.visible(at: now) != nil, raw.phase != .failed {
            let end = raw.at.addingTimeInterval(raw.phase == .sent ? SendStatus.sentHold : SendStatus.sendingTimeout)
            entries.append(CrateEntry(date: end, snapshot: snapshot, status: nil, presetID: id))
        }
        // The app reloads widgets when presets change; refresh daily anyway in case it hasn't run.
        return (entries, .after(now.addingTimeInterval(24 * 3600)))
    }
}

struct PresetProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> CrateEntry { .sample }

    func snapshot(for configuration: SelectPresetIntent, in context: Context) async -> CrateEntry {
        let t = Timelines.load(presetID: configuration.preset?.id)
        return context.isPreview && t.entries[0].snapshot == nil ? .sample : t.entries[0]
    }

    func timeline(for configuration: SelectPresetIntent, in context: Context) async -> Timeline<CrateEntry> {
        let t = Timelines.load(presetID: configuration.preset?.id)
        return Timeline(entries: t.entries, policy: t.policy)
    }
}

struct GridProvider: TimelineProvider {
    func placeholder(in context: Context) -> CrateEntry { .sample }

    func getSnapshot(in context: Context, completion: @escaping (CrateEntry) -> Void) {
        let t = Timelines.load(presetID: nil)
        completion(context.isPreview && t.entries[0].snapshot == nil ? .sample : t.entries[0])
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<CrateEntry>) -> Void) {
        let t = Timelines.load(presetID: nil)
        completion(Timeline(entries: t.entries, policy: t.policy))
    }
}
