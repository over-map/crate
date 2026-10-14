#if DEBUG
import SwiftUI
import CrateKit

/// Every CrateUI component in every state, for design review (DEBUG builds only).
///
/// Launch the app with `-gallery` to show it instead of the normal root; add `-gallerySection <id>`
/// (foundation, button, style, vibe, tag, confidence, bpm, energy, slider, camelot, track, preset)
/// to start scrolled to a section.
public struct ComponentGallery: View {
    public init() {}

    private static let sections: [(id: String, title: String)] = [
        ("foundation", "Foundation"), ("button", "PrimaryButton"), ("style", "StyleChip · Genre"),
        ("vibe", "VibeChip"), ("tag", "TagChip · suggestions"), ("confidence", "ConfidenceLine"),
        ("bpm", "≈ BPM"), ("energy", "Energy"), ("slider", "RangeSlider · BPM"),
        ("camelot", "Camelot"), ("track", "TrackRow · tag line"), ("preset", "Presets · ticks"),
    ]

    public var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: Space.xl) {
                    Text("CrateUI").font(.screenTitle).foregroundStyle(Color.textPrimary)
                    ForEach(Self.sections, id: \.id) { section in
                        VStack(alignment: .leading, spacing: Space.m) {
                            Text(section.title).font(.sheetTitle).foregroundStyle(Color.textPrimary)
                            content(for: section.id)
                        }
                        .id(section.id)
                    }
                }
                .padding(.horizontal, Space.screenMargin)
                .padding(.vertical, Space.l)
            }
            .background(Color.bgBase.ignoresSafeArea())
            .onAppear {
                let args = ProcessInfo.processInfo.arguments
                if let i = args.firstIndex(of: "-gallerySection"), i + 1 < args.count {
                    proxy.scrollTo(args[i + 1], anchor: .top)
                }
            }
        }
    }

    @ViewBuilder private func content(for id: String) -> some View {
        switch id {
        case "foundation": FoundationDemo()
        case "button": ButtonDemo()
        case "style": StyleDemo()
        case "vibe": VibeDemo()
        case "tag": TagDemo()
        case "confidence": ConfidenceDemo()
        case "bpm": BPMDemo()
        case "energy": EnergyDemo()
        case "slider": SliderDemo()
        case "camelot": CamelotDemo()
        case "track": TrackDemo()
        case "preset": PresetDemo()
        default: EmptyView()
        }
    }
}

/// A captioned group of states, on a card.
private struct Specimen<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s) {
            PanelLabel(title)
            content
        }
        .padding(Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .crateCard()
    }
}

private struct Caption: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View { Text(text).font(.caption2.monospaced()).foregroundStyle(Color.textTertiary) }
}

// MARK: - Foundation

private struct FoundationDemo: View {
    var body: some View {
        Specimen(title: "Panel label · card") {
            PanelLabel("Genre & style · any of")
            PanelLabel("Tags", trailing: "If added · all")
            Text("Cards: bgCard, 1 pt strokeCard inset, radius 10.")
                .font(.footnote).foregroundStyle(Color.textSecondary)
        }
    }
}

// MARK: - Button

private struct ButtonDemo: View {
    @State private var demo: PrimaryButtonState = .idle(title: "Send 8 to Now")
    @State private var task: Task<Void, Never>?

    var body: some View {
        Specimen(title: "States") {
            PrimaryButton(.idle(title: "Send 100 to Now")) {}
            Caption("IDLE")
            PrimaryButton(.sending(progress: 3.0 / 8, counter: "3/8")) {}
            Caption("SENDING · DETERMINATE")
            PrimaryButton(.success(message: "Sent 8 tracks to Now", title: "Listen on Spotify"),
                          secondaryTitle: "Send again", secondaryAction: {}) {}
            Caption("SUCCESS")
            PrimaryButton(.error(message: "Couldn't reach Spotify. Check your connection; nothing in Now was changed.",
                                 title: "Retry")) {}
            Caption("ERROR")
            PrimaryButton(.disabled(title: "No tracks to send")) {}
            Caption("DISABLED")
        }
        Specimen(title: "Live (tap)") {
            PrimaryButton(demo, secondaryTitle: "Send again", secondaryAction: { demo = .idle(title: "Send 8 to Now") }) {
                run()
            }
        }
    }

    private func run() {
        if case .success = demo { demo = .idle(title: "Send 8 to Now"); return }
        task?.cancel()
        task = Task { @MainActor in
            for step in 0...8 {
                demo = .sending(progress: Double(step) / 8, counter: "\(step)/8")
                try? await Task.sleep(for: .milliseconds(180))
            }
            demo = .success(message: "Sent 8 tracks to Now", title: "Listen on Spotify")
        }
    }
}

// MARK: - Style

private struct StyleDemo: View {
    @State private var removed = true

    var body: some View {
        Specimen(title: "Selected · shades") {
            ChipFlow {
                StyleChip(style: "Deep House", genre: "House", family: .house, shade: .light)
                StyleChip(style: "House", genre: "House", family: .house, shade: .base)
                StyleChip(style: "Tech House", genre: "House", family: .house, shade: .deep)
                StyleChip(style: "Breakbeat", canonical: "Electronic---Breakbeat", genre: "Breaks & bass", family: .breaksbass)
                StyleChip(style: "Nu-Disco", family: .disco, shade: .light, edited: true)
            }
        }
        Specimen(title: "Off · most used / filters") {
            ChipFlow {
                StyleChip(style: "Techno", family: .techno, variant: .off(count: 412)) {}
                StyleChip(style: "Downtempo", family: .downtempo, variant: .off(count: 196)) {}
                StyleChip(style: "Boom Bap", family: .hiphop, variant: .off(count: 41)) {}
            }
        }
        Specimen(title: "Genre summary · OR") {
            ChipFlow {
                GenreSummaryChip(family: .house, summary: .allStyles) {}
                ChipSeparator()
                GenreSummaryChip(family: .house, summary: .except(["Garage House"]), action: {}, onRemove: {})
                ChipSeparator()
                GenreSummaryChip(family: .techno, summary: .count(ticked: 4, total: 9)) {}
                AddChip("Add") {}
            }
        }
        Specimen(title: "Edit tags · removed") {
            ChipFlow {
                StyleChip(style: "Deep House", family: .house, shade: .light)
                if removed {
                    StyleChip(style: "Tech House", family: .house, variant: .removed, onUndo: { removed = false })
                } else {
                    StyleChip(style: "Tech House", family: .house, shade: .deep, onRemove: { removed = true })
                }
                AddChip("Style") {}
            }
        }
        Specimen(title: "Family mini chips · 17 + none") {
            ChipFlow(spacing: Space.xs, lineSpacing: Space.xs) {
                ForEach(Family.allCases) { FamilyMiniChip($0) }
            }
        }
        Specimen(title: "Family hues · light / base / deep / outline") {
            VStack(spacing: Space.xs) {
                ForEach(Family.allCases) { family in
                    HStack(spacing: Space.s) {
                        Text(family.displayName).font(.footnote.weight(.medium)).foregroundStyle(Color.textPrimary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        ForEach(StyleShade.allCases, id: \.self) { shade in
                            RoundedRectangle(cornerRadius: Radius.badge).fill(Color.family(family, shade: shade))
                                .frame(width: Space.xxl, height: Space.xl - Space.xxs)
                        }
                        RoundedRectangle(cornerRadius: Radius.badge).strokeBorder(Color.familyOutline(family), lineWidth: Stroke.outline)
                            .frame(width: Space.xl - Space.xxs, height: Space.xl - Space.xxs)
                    }
                }
            }
        }
    }
}

// MARK: - Vibe

private struct VibeDemo: View {
    @State private var selected: Set<String> = ["Happy"]
    private let vibes: [(name: String, key: String, count: Int)] = [
        ("Hypnotic", "hypnotic", 147), ("Happy", "happy", 58), ("Party", "party", 24), ("Aggressive", "aggressive", 0),
    ]

    var body: some View {
        Specimen(title: selected.count >= 2 ? "Vibe · all of" : "Vibe") {
            ChipFlow {
                ForEach(vibes, id: \.name) { vibe in
                    VibeChip(vibe.name, symbol: VibeSymbols.defaults[vibe.key] ?? "tag",
                             count: selected.isEmpty ? vibe.count : max(0, vibe.count - selected.count * 9),
                             isSelected: selected.contains(vibe.name)) {
                        if selected.contains(vibe.name) { selected.remove(vibe.name) } else { selected.insert(vibe.name) }
                    }
                }
                VibeChip("Melancholic", symbol: VibeSymbols.defaults["melancholic"] ?? "tag", count: nil, hasSeeds: false) {}
                AddChip("New vibe") {}
            }
            Caption("OFF · SELECTED · 0 DIMMED · NO SEEDS (DASHED) · NEW VIBE — TAP TO TOGGLE, COUNTS ROLL")
        }
    }
}

// MARK: - Tags

private struct TagDemo: View {
    @State private var tags = ["italian", "dub techno"]

    var body: some View {
        Specimen(title: "Picked tags") {
            ChipFlow {
                ForEach(tags, id: \.self) { tag in TagChip(tag) { tags.removeAll { $0 == tag } } }
                if tags.isEmpty { AddChip("Reset") { tags = ["italian", "dub techno"] } }
            }
        }
        Specimen(title: "Suggestions · \u{201C}dub\u{201D}") {
            VStack(spacing: 0) {
                let rows: [(String, Int, Int)] = [("dub techno", 22, 28), ("dub", 9, 41), ("deep dub", 6, 8), ("dub house", 2, 7), ("dubstep", 0, 11)]
                ForEach(Array(rows.enumerated()), id: \.offset) { index, row in
                    TagSuggestionRow(row.0, query: "dub", countIfAdded: row.1, libraryTotal: row.2) {}
                    if index < rows.count - 1 { Divider().overlay(Color.separator) }
                }
            }
            .crateCard()
            VStack(spacing: 0) { TagUnknownRow(term: "vaporwave") }.crateCard()
        }
    }
}

// MARK: - Confidence

private struct ConfidenceDemo: View {
    private let all = ConfidenceSummary(family: .house, votes: 4, sources: 4, audioAgrees: true,
                                        bySource: [.audio: [.house], .discogs: [.house], .lastfm: [.house], .beatport: [.house]])
    private let three = ConfidenceSummary(family: .house, votes: 3, sources: 4, audioAgrees: false,
                                          bySource: [.audio: [.techno], .discogs: [.house], .lastfm: [.house], .beatport: [.house]])
    private let twoOfThree = ConfidenceSummary(family: .techno, votes: 2, sources: 3, audioAgrees: true,
                                               bySource: [.audio: [.techno], .discogs: [.techno], .lastfm: [.house]])
    private let split = ConfidenceSummary(family: .popcantautori, votes: 1, sources: 2, audioAgrees: true,
                                          bySource: [.audio: [.popcantautori], .discogs: [.disco]])
    private let single = ConfidenceSummary(family: .downtempo, votes: 1, sources: 1, audioAgrees: true,
                                           bySource: [.audio: [.downtempo]])
    private let override = ConfidenceSummary(family: .disco, votes: 3, sources: 4, bySource: [.audio: [.techno], .discogs: [.house], .lastfm: [.house], .beatport: [.house]],
                                             isOverride: true, sourcesFamily: .house)

    var body: some View {
        Specimen(title: "How many sources agree") {
            ConfidenceLine(all, expandable: false)
            ConfidenceLine(three, expandable: false)
            ConfidenceLine(twoOfThree, expandable: false)
            ConfidenceLine(split, expandable: false)
            ConfidenceLine(single, expandable: false)
            ConfidenceLine(override, expandable: false)
        }
        Specimen(title: "Expanded (tap to toggle)") {
            ChipFlow {
                StyleChip(style: "Deep House", genre: "House", family: .house, shade: .light)
                StyleChip(style: "Disco", family: .disco, shade: .light)
            }
            ConfidenceLine(three, expanded: true)
            ConfidenceLine(override)
        }
        Specimen(title: "List markers") {
            HStack(spacing: Space.l) {
                HStack(spacing: Space.xs) { ConfidenceMarker(.split); Text("split").font(.footnote).foregroundStyle(Color.textSecondary) }
                HStack(spacing: Space.xs) { ConfidenceMarker(.single); Text("one source").font(.footnote).foregroundStyle(Color.textSecondary) }
                HStack(spacing: Space.xs) { ConfidenceMarker(all); Text("(none for agreement)").font(.footnote).foregroundStyle(Color.textTertiary) }
            }
        }
    }
}

// MARK: - BPM

private struct BPMDemo: View {
    var body: some View {
        Specimen(title: "≈ BPM numeral") {
            HStack(alignment: .firstTextBaseline, spacing: Space.l + Space.xxs) {
                BPMText(value: 139, approx: true, font: .numeralHero).foregroundStyle(Color.textPrimary)
                BPMText(value: 139, approx: true, font: .numeralMedium).foregroundStyle(Color.textPrimary)
                BPMText(value: 139, approx: true, font: .tagLine).foregroundStyle(Color.textSecondary)
            }
            HStack(alignment: .firstTextBaseline, spacing: Space.l + Space.xxs) {
                BPMText(value: 124, approx: false, font: .numeralLarge).foregroundStyle(Color.textPrimary)
                BPMText(value: 96, approx: true, font: .numeralLarge).foregroundStyle(Color.textPrimary)
            }
            Caption("HERO · MEDIUM · TAG LINE · LARGE · LARGE ≈")
        }
    }
}

// MARK: - Energy

private struct EnergyDemo: View {
    @State private var range: ClosedRange<Int> = 6...10
    @State private var any: ClosedRange<Int> = EnergyRangeControl.any

    var body: some View {
        Specimen(title: "Input · drag across") {
            HStack {
                PanelLabel("Energy")
                Spacer()
                Text(range == EnergyRangeControl.any ? "Any" : "\(range.lowerBound)–\(range.upperBound)")
                    .font(.numeralMedium).foregroundStyle(Color.textPrimary)
                    .contentTransition(.numericText())
                    .animation(CrateMotion.count, value: range)
            }
            EnergyRangeControl(range: $range)
            Caption("RANGE SET")
            EnergyRangeControl(range: $any)
            Caption("ANY · UNLIT, NUMBERED")
        }
        Specimen(title: "Display") {
            EnergyMeter(level: 7)
            Caption("SINGLE VALUE · TRACK DETAIL")
            HStack(spacing: Space.l) {
                EnergyMeter(level: 3, style: .micro)
                EnergyMeter(level: 6, style: .micro)
                EnergyMeter(level: 9, style: .micro)
            }
            .font(.tagLine)
            .foregroundStyle(Color.textSecondary)
            Caption("MICRO · TAG LINE")
        }
    }
}

// MARK: - Slider

private struct SliderDemo: View {
    @State private var any: ClosedRange<Int> = 60...200
    @State private var set: ClosedRange<Int> = 128...142
    @State private var narrow: ClosedRange<Int> = 136...142

    var body: some View {
        Specimen(title: "Unconstrained") {
            header(any, constrained: false)
            RangeSlider(range: $any)
        }
        Specimen(title: "Set · half/double ghosted") {
            header(set, constrained: true, hint: "also \(set.lowerBound / 2)–\(set.upperBound / 2)")
            RangeSlider(range: $set, ghosts: [(set.lowerBound / 2)...(set.upperBound / 2)])
        }
        Specimen(title: "Narrow · half-capsule thumbs") {
            header(narrow, constrained: true)
            RangeSlider(range: $narrow)
        }
    }

    private func header(_ r: ClosedRange<Int>, constrained: Bool, hint: String? = nil) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: Space.s) {
            Text(verbatim: "\(r.lowerBound)")
            Text(verbatim: "–").fontWeight(.regular).foregroundStyle(Color.textTertiary)
            Text(verbatim: "\(r.upperBound)")
            Spacer()
            if let hint { Text(hint).font(.footnote).foregroundStyle(Color.textSecondary) }
        }
        .font(.numeralHero)
        .foregroundStyle(constrained ? Color.textPrimary : Color.textTertiary)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(constrained ? "BPM from \(r.lowerBound) to \(r.upperBound)" : "BPM any")
    }
}

// MARK: - Camelot

private struct CamelotDemo: View {
    @State private var selection: String? = "8A"
    @State private var compatible = true

    var body: some View {
        Specimen(title: "Badges") {
            ChipFlow(spacing: Space.m, lineSpacing: Space.m, alignment: .center) {
                CamelotBadge(code: "8A", size: .tagLine)
                CamelotBadge(code: "8A", size: .small)
                CamelotBadge(code: "8A", size: .medium)
                CamelotBadge(code: "8B", size: .medium)
                CamelotBadge(code: nil, size: .medium)
                CamelotBadge(code: "8A", size: .medium, withCompatible: true)
                CamelotBadge(code: "8A", size: .medium, showsKeyName: true)
                CamelotBadge(code: "8A", size: .large, confidence: 0.72)
            }
            Caption("TAG LINE · S · M · B · ANY · COMPATIBLE · KEY NAME · L")
        }
        Specimen(title: "Wheel · tap a key") {
            CamelotWheel(selection: $selection, compatible: compatible)
                .frame(maxWidth: Metric.wheelReference)
                .frame(maxWidth: .infinity)
            Toggle("Include compatible keys", isOn: $compatible)
                .font(.body).foregroundStyle(Color.textPrimary).tint(Color.accent)
        }
    }
}

// MARK: - Tracks

private struct TrackDemo: View {
    @State private var liked = true

    var body: some View {
        Specimen(title: "Compact tag line") {
            CompactTagLine(style: "Breakbeat", bpm: 138, bpmApprox: true, key: "8A", energy: 7, vibes: ["Dark", "Hypnotic"])
            CompactTagLine(marker: .split, style: "Deep House", bpm: 124, key: "8A", energy: 6)
            CompactTagLine(marker: .single, style: "Cantautori", bpm: 96, bpmApprox: true, key: "5B", energy: 3)
            CompactTagLine(style: "Techno", bpm: 140, key: "9A", energy: 9, edited: [.bpm])
            CompactTagLine.noPreview
        }
        Specimen(title: "Track rows") {
            VStack(spacing: 0) {
                TrackRow(title: "Windowlicker", artist: "Aphex Twin", coverTint: Color.family(.breaksbass),
                         tagLine: CompactTagLine(style: "Breakbeat", bpm: 138, bpmApprox: true, key: "8A", energy: 7,
                                                 vibes: ["Dark", "Hypnotic"]))
                Divider().overlay(Color.separator)
                TrackRow(title: "Un'estate al mare", artist: "Giuni Russo", coverTint: Color.family(.popcantautori),
                         tagLine: CompactTagLine(marker: .split, style: "Italo Pop", bpm: 121, bpmApprox: true, key: "11B", energy: 7))
                Divider().overlay(Color.separator)
                TrackRow(title: "Energy Flash", artist: "Joey Beltram", coverTint: Color.family(.techno),
                         tagLine: CompactTagLine(style: "Techno", bpm: 140, key: "9A", energy: 9, edited: [.bpm]))
                Divider().overlay(Color.separator)
                TrackRow(title: "Only Shallow", artist: "My Bloody Valentine", tagLine: .noPreview)
                Divider().overlay(Color.separator)
                TrackRow(title: "Shook Ones, Pt. II", artist: "Mobb Deep", coverTint: Color.family(.hiphop),
                         tagLine: CompactTagLine(style: "Boom Bap", bpm: 93, key: "1A", energy: 5)) {
                    LikeButton(isLiked: liked) { liked.toggle() }
                }
            }
        }
        Specimen(title: "Covers") {
            HStack(spacing: Space.m) {
                CoverImage(url: nil, size: Size.coverStrip)
                CoverImage(url: nil, size: Size.coverRow, tint: Color.family(.house))
                CoverImage(url: nil, size: Size.coverRow * 2, radius: Radius.coverLarge, tint: Color.family(.disco))
            }
        }
    }
}

// MARK: - Presets

private struct PresetDemo: View {
    @State private var active = "Dark 138+"

    var body: some View {
        Specimen(title: "Preset pills") {
            ChipFlow {
                PresetPill("Dark 138+", color: .ink, symbol: "moon.fill", isSelected: active == "Dark 138+") { active = "Dark 138+" }
                PresetPill("Run 160", color: .red, symbol: "bolt.fill", isSelected: active == "Run 160") { active = "Run 160" }
                PresetPill("Sunday 90–110", color: .amber, symbol: "sun.max.fill", isModified: true) { active = "" }
                PresetPill("Warehouse", color: .teal, symbol: "circle.dotted.circle", isSelected: active == "Warehouse") { active = "Warehouse" }
            }
            Caption("SELECTED · IDLE · MODIFIED")
        }
        Specimen(title: "Preset colours") {
            HStack(spacing: Space.s) {
                ForEach(PresetColor.allCases, id: \.self) { PresetTile(color: $0, symbol: "music.note") }
            }
        }
        Specimen(title: "Tick ring · tri-state") {
            HStack(spacing: Space.l) {
                TickRing(.empty)
                TickRing(.checked)
                TickRing(.mixed)
            }
            Caption("NONE · ALL · SOME")
        }
    }
}

#Preview("Gallery · dark") { ComponentGallery().preferredColorScheme(.dark) }
#Preview("Gallery · light") { ComponentGallery().preferredColorScheme(.light) }
#endif
