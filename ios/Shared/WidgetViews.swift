import AppIntents
import CrateKit
import CrateUI
import SwiftUI
import WidgetKit

// Lives in Shared so the app's Debug widget gallery (`-screen widgets`) can render every state.
// Design: Crate Widgets 4a–4g. Dark: containerBackground bgBase, cells bgCard. Light: bgCard, cells bgBase.

struct CrateEntry: TimelineEntry {
    let date: Date
    let snapshot: PresetSnapshot?
    /// The last send, if it should still show at `date` (Sent holds 30 s; an error stays).
    let status: SendStatus?
    /// Small / Lock Screen: the configured preset (or the first widget preset).
    let presetID: String?

    var preset: SharedPreset? { presetID.flatMap { snapshot?.preset(id: $0) } }

    /// The status, only if it concerns `id`.
    func status(for id: String?) -> SendStatus? { status?.presetID == id ? status : nil }

    /// What the gallery and placeholders show.
    static let sample = CrateEntry(date: .now, snapshot: PresetSnapshot(presets: [
        SharedPreset(id: "dark", name: "Dark 138+", icon: "moon.fill", color: "violet", inWidget: true,
                     summary: "Hard Techno, Breaks · 136–142", detail: "136–142 · ϟ6–10"),
        SharedPreset(id: "italian", name: "Italian summer", icon: "sun.horizon.fill", color: "pink", inWidget: true,
                     summary: "Happy · ⌗ italian", detail: "Happy · ⌗ italian"),
        SharedPreset(id: "sunday", name: "Sunday 90–110", icon: "sun.max.fill", color: "amber", inWidget: true,
                     summary: "Downtempo · 90–110", detail: "90–110"),
        SharedPreset(id: "warehouse", name: "Warehouse", icon: "building.2.fill", color: "teal", inWidget: true,
                     summary: "Techno · Hypnotic · 126–134", detail: "126–134"),
    ]), status: nil, presetID: "dark")
}

extension Color {
    static let widgetSurface = Color.dynamic(dark: 0x000000, light: 0xFFFFFF)
    static let widgetCell = Color.dynamic(dark: 0x141414, light: 0xF4F4F5)
}

private func intent(_ p: SharedPreset) -> SendPresetToNow { SendPresetToNow(preset: PresetEntity(p)) }

@MainActor private func tile(_ p: SharedPreset, size: CGFloat) -> PresetTile {
    PresetTile(color: PresetColor(rawValue: p.color) ?? .ink, symbol: p.icon, size: size)
}

/// Retrying can't fix these; the widget opens Crate instead.
private func needsApp(_ f: SendFailure?) -> Bool { f == .loginExpired || f == .noData || f == .unknownPreset }

// MARK: - Small + Lock Screen

struct PresetWidgetView: View {
    let entry: CrateEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        switch family {
        case .accessoryCircular: CircularView(entry: entry)
        case .accessoryRectangular: RectangularView(entry: entry)
        default: SmallView(entry: entry)
        }
    }
}

struct SmallView: View {
    let entry: CrateEntry

    var body: some View {
        Group {
            if let p = entry.preset {
                let status = entry.status(for: p.id)
                switch status?.phase {
                case .sending: card(p, status: status).disabled(true)
                case .sent: card(p, status: status).widgetURL(CrateLink.now)
                case .failed where needsApp(status?.failure): card(p, status: status).widgetURL(CrateLink.open)
                default:
                    // The whole widget is the button (idle, or retry after a network error).
                    Button(intent: intent(p)) { card(p, status: status) }.buttonStyle(.plain)
                }
            } else {
                EmptyWidget(text: "Save a preset in Crate to send it from here")
            }
        }
        .containerBackground(Color.widgetSurface, for: .widget)
    }

    private func card(_ p: SharedPreset, status: SendStatus?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: Space.s) {
                switch status?.phase {
                case .sent: StatusGlyph(symbol: "checkmark", fg: .success, bg: .successFill)
                case .failed: StatusGlyph(symbol: "xmark", fg: .error, bg: .errorFill)
                default: tile(p, size: 28)
                }
                Text("CRATE").font(.panelLabel).tracking(0.8).foregroundStyle(Color.textSecondary)
            }
            Spacer(minLength: Space.s)
            Text(status?.phase == .failed ? status?.failure?.title ?? "Not sent" : p.name)
                .font(.headline).foregroundStyle(Color.textPrimary).lineLimit(2).minimumScaleFactor(0.8)
            Text(line(p, status)).font(.caption).foregroundStyle(Color.textSecondary).lineLimit(2)
                .padding(.top, Space.xxs)
            Spacer(minLength: Space.s)
            WidgetButtonLabel(text: buttonText(status), symbol: status?.phase == .sent ? "play.fill" : nil,
                              busy: status?.phase == .sending, filled: status?.phase != .failed)
        }
        .padding(Space.l)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .contentShape(Rectangle())
    }

    private func line(_ p: SharedPreset, _ s: SendStatus?) -> String {
        switch s?.phase {
        case .sending: "Filling Now…"
        case .sent: "Sent · \(s?.count ?? 0) tracks"
        case .failed: "\(p.name) wasn't sent"
        case nil: p.summary
        }
    }

    private func buttonText(_ s: SendStatus?) -> String {
        switch s?.phase {
        case .sending: "Sending…"
        case .sent: "Listen on Spotify"
        case .failed: needsApp(s?.failure) ? "Open Crate" : "Try again"
        case nil: "Send to Now"
        }
    }
}

struct CircularView: View {
    let entry: CrateEntry

    var body: some View {
        content.containerBackground(for: .widget) { AccessoryWidgetBackground() }
    }

    @ViewBuilder private var content: some View {
        if let p = entry.preset {
            let status = entry.status(for: p.id)
            switch status?.phase {
            case .sending:
                ProgressView().progressViewStyle(.circular)
                    .accessibilityLabel("Sending \(p.name)")
            case .sent:
                VStack(spacing: 0) {
                    Image(systemName: "checkmark").font(.system(size: 14, weight: .bold))
                    Text("\(status?.count ?? 0)").font(.system(.headline, design: .rounded)).monospacedDigit()
                }
                .widgetURL(CrateLink.now)
                .accessibilityLabel("Sent \(status?.count ?? 0) tracks. Open Spotify.")
            case .failed:
                VStack(spacing: 0) {
                    Image(systemName: "exclamationmark").font(.system(size: 18, weight: .bold))
                    Text("Open").font(.caption2)
                }
                .widgetURL(CrateLink.open)
                .accessibilityLabel("\(p.name) wasn't sent. Open Crate.")
            case nil:
                Button(intent: intent(p)) {
                    VStack(spacing: 0) {
                        Image(systemName: p.icon).font(.system(size: 14, weight: .semibold)).widgetAccentable()
                        Text(p.shortLabel).font(.system(.headline, design: .rounded)).minimumScaleFactor(0.6).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Send \(p.name) to Now")
            }
        } else {
            Image(systemName: "plus").widgetURL(CrateLink.open).accessibilityLabel("Save a preset in Crate")
        }
    }
}

struct RectangularView: View {
    let entry: CrateEntry

    var body: some View {
        content
            .frame(maxWidth: .infinity, alignment: .leading)
            .containerBackground(for: .widget) { Color.clear }
    }

    @ViewBuilder private var content: some View {
        if let p = entry.preset {
            let status = entry.status(for: p.id)
            switch status?.phase {
            case .sending:
                lines(symbol: p.icon, title: p.name, detail: "Filling Now…", hint: nil).disabled(true)
            case .sent:
                lines(symbol: "checkmark", title: "Sent \(status?.count ?? 0) tracks", detail: "\(p.name) → Now", hint: "Tap to open Spotify")
                    .widgetURL(CrateLink.now)
            case .failed:
                lines(symbol: "exclamationmark", title: "Not sent", detail: status?.failure?.title ?? "", hint: "Tap to open Crate")
                    .widgetURL(CrateLink.open)
            case nil:
                Button(intent: intent(p)) { lines(symbol: p.icon, title: p.name, detail: p.detail, hint: "Tap to send") }
                    .buttonStyle(.plain)
            }
        } else {
            lines(symbol: "plus", title: "Crate", detail: "Save a preset to send it from here", hint: nil).widgetURL(CrateLink.open)
        }
    }

    private func lines(symbol: String, title: String, detail: String, hint: String?) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Label(title, systemImage: symbol).font(.headline).lineLimit(1).widgetAccentable()
            Text(detail).font(.caption).lineLimit(1)
            if let hint { Text(hint).font(.caption2).foregroundStyle(.secondary).lineLimit(1) }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Medium

struct GridWidgetView: View {
    let entry: CrateEntry

    var body: some View {
        Group {
            if let presets = entry.snapshot?.widgetPresets, !presets.isEmpty {
                VStack(spacing: Space.s) {
                    let cells = (0..<4).map { $0 < presets.count ? presets[$0] : nil }
                    Grid(horizontalSpacing: Space.s, verticalSpacing: Space.s) {
                        GridRow { cell(cells[0]); cell(cells[1]) }
                        GridRow { cell(cells[2]); cell(cells[3]) }
                    }
                    if let s = entry.status, s.phase == .failed { errorStrip(s) }
                }
                .padding(Space.m)
            } else {
                EmptyWidget(text: "Save a preset in Crate to send it from here")
            }
        }
        .containerBackground(Color.widgetSurface, for: .widget)
    }

    @ViewBuilder private func cell(_ p: SharedPreset?) -> some View {
        if let p {
            let s = entry.status.flatMap { $0.presetID == p.id && $0.phase != .failed ? $0 : nil }
            let othersBusy = entry.status?.phase == .sending && entry.status?.presetID != p.id
            switch s?.phase {
            case .sent:
                Link(destination: CrateLink.now) {
                    cellBody(glyph: AnyView(StatusGlyph(symbol: "checkmark", fg: .success, bg: .successFill, size: 24)),
                             title: "Sent · \(s?.count ?? 0)", line: "Listen on Spotify", tint: .successFill)
                }
            case .sending:
                cellBody(glyph: AnyView(ProgressView().controlSize(.small).frame(width: 24, height: 24)),
                         title: p.name, line: "Sending…", tint: nil)
            default:
                Button(intent: intent(p)) {
                    cellBody(glyph: AnyView(tile(p, size: 24)), title: p.name, line: p.summary, tint: nil)
                }
                .buttonStyle(.plain)
                .disabled(othersBusy)
                .opacity(othersBusy ? 0.4 : 1)
                .accessibilityLabel("Send \(p.name) to Now")
            }
        } else {
            Link(destination: CrateLink.open) {
                cellBody(glyph: AnyView(Image(systemName: "plus").foregroundStyle(Color.textSecondary).frame(width: 24, height: 24)),
                         title: "Add preset", line: "in Crate", tint: nil)
            }
        }
    }

    private func cellBody(glyph: AnyView, title: String, line: String, tint: Color?) -> some View {
        HStack(spacing: Space.s) {
            glyph
            VStack(alignment: .leading, spacing: 0) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Color.textPrimary).lineLimit(1)
                Text(line).font(.caption2).foregroundStyle(Color.textSecondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, Space.s)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(tint ?? Color.widgetCell, in: RoundedRectangle(cornerRadius: Radius.card, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Radius.card, style: .continuous).strokeBorder(Color.strokeCard, lineWidth: 1))
        .contentShape(Rectangle())
    }

    private func errorStrip(_ s: SendStatus) -> some View {
        let name = entry.snapshot?.preset(id: s.presetID)?.name
        return Link(destination: CrateLink.open) {
            HStack(spacing: Space.xs) {
                Image(systemName: "xmark.circle.fill").foregroundStyle(Color.error)
                Text(s.failure?.title ?? "Not sent").foregroundStyle(Color.textPrimary)
                if let name { Text("· \(name)").foregroundStyle(Color.textSecondary) }
                Spacer(minLength: 0)
                Text("Open Crate").fontWeight(.semibold).foregroundStyle(Color.textPrimary)
            }
            .font(.caption)
            .lineLimit(1)
            .padding(.horizontal, Space.s)
            .frame(height: 24)
            .background(Color.errorFill, in: RoundedRectangle(cornerRadius: Radius.chip, style: .continuous))
        }
    }
}

// MARK: - Pieces

struct StatusGlyph: View {
    let symbol: String
    let fg: Color
    let bg: Color
    var size: CGFloat = 28

    var body: some View {
        RoundedRectangle(cornerRadius: Radius.chip, style: .continuous).fill(bg)
            .frame(width: size, height: size)
            .overlay(Image(systemName: symbol).font(.system(size: size * 0.5, weight: .bold)).foregroundStyle(fg))
            .accessibilityHidden(true)
    }
}

struct WidgetButtonLabel: View {
    let text: String
    let symbol: String?
    let busy: Bool
    let filled: Bool

    var body: some View {
        HStack(spacing: Space.xs) {
            if busy { ProgressView().controlSize(.mini).tint(Color.onAccent) }
            if let symbol { Image(systemName: symbol) }
            Text(text).lineLimit(1).minimumScaleFactor(0.7)
        }
        .font(.subheadline.weight(.semibold))
        .padding(.horizontal, Space.s)
        .foregroundStyle(filled ? Color.onAccent : Color.textPrimary)
        .frame(maxWidth: .infinity, minHeight: 36)
        .background(filled ? Color.accent.opacity(busy ? 0.6 : 1) : Color.fillControl, in: Capsule())
    }
}

struct EmptyWidget: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            Text("CRATE").font(.panelLabel).tracking(0.8).foregroundStyle(Color.textSecondary)
            Text(text).font(.subheadline).foregroundStyle(Color.textPrimary)
            Spacer(minLength: 0)
        }
        .padding(Space.l)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .widgetURL(CrateLink.open)
    }
}
