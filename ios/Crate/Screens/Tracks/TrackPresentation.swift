import CrateKit
import CrateUI
import Foundation
import SwiftUI

/// Everything the track screens show about one track, derived from the model (library + override + vibes).
/// Pure value; build it in `body` with ``init(id:model:)``.
@MainActor
struct TrackInfo {
    let id: String
    let track: Track
    let override: TrackOverride?
    let effective: EffectiveTags
    let taxonomy: TaxonomyFile

    init?(id: String, model: AppModel) {
        guard let track = model.index.tracks[id] else { return nil }
        self.init(id: id, track: track, override: model.index.overrides[id], taxonomy: model.snapshot.taxonomy)
    }

    init(id: String, track: Track, override: TrackOverride?, taxonomy: TaxonomyFile) {
        self.id = id
        self.track = track
        self.override = override
        self.taxonomy = taxonomy
        effective = EffectiveTags(track: track, override: override)
    }

    var title: String { track.title ?? "Untitled" }
    var artist: String { (track.artists ?? []).joined(separator: ", ") }
    var coverURL: URL? { track.cover.flatMap(URL.init(string:)) }
    var uri: String { track.spotifyURI(id: id) }
    var isAnalysed: Bool { track.auto != nil && track.status != .noPreview }

    /// The effective family (override, else consensus) as a hue.
    var family: Family { effective.family.flatMap(Family.init(rawValue:)) ?? .none }
    /// The family the pipeline chose, before the user's override.
    var sourcesFamily: Family? { track.family.flatMap(Family.init(rawValue:)) }
    var overrideFamily: Family? { override?.family.flatMap { $0.isEmpty ? nil : Family(rawValue: $0) } }

    func familyName(_ f: Family) -> String {
        f == .none ? f.displayName : taxonomy.displayName(family: f.rawValue).sentenceCased
    }

    func styleName(_ canonical: String) -> String { taxonomy.label(forStyle: canonical).styleDisplay }

    // MARK: Styles

    /// The strongest style first, then up to 3 secondary styles (user-added ones, then auto styles with
    /// activation ≥ 0.2), all canonical.
    var displayStyles: [String] {
        TrackInfo.displayStyles(auto: track.auto, override: override, primary: effective.primaryStyle)
    }

    static func displayStyles(auto: AutoTags?, override: TrackOverride?, primary: String?) -> [String] {
        let removed = Set(override?.stylesRemove ?? [])
        var out: [String] = primary.map { [$0] } ?? []
        var secondary: [String] = []
        for s in (override?.stylesAdd ?? []) where !out.contains(s) && !secondary.contains(s) { secondary.append(s) }
        let ranked = EffectiveTags.rankedStyles(auto?.styles ?? [:])
        for s in ranked where (auto?.styles?[s] ?? 0) >= EffectiveTags.styleDisplayThreshold
            && !removed.contains(s) && !out.contains(s) && !secondary.contains(s) {
            secondary.append(s)
        }
        out += secondary.prefix(3)
        return out
    }

    /// Styles shown before any edit (to tell which chips the user added).
    var autoDisplayStyles: [String] {
        let auto = EffectiveTags(auto: track.auto, override: nil)
        return TrackInfo.displayStyles(auto: track.auto, override: nil, primary: auto.primaryStyle)
    }

    func isStyleEdited(_ s: String) -> Bool {
        guard let added = override?.stylesAdd, added.contains(s) else { return false }
        if !autoDisplayStyles.contains(s) { return true }
        return added.first == s && autoDisplayStyles.first != s
    }

    // MARK: Numbers

    var bpm: Double? { effective.bpm }
    var bpmEdited: Bool { override?.bpm != nil }
    /// "≈" only for the detected value (a BPM the user typed is certain).
    var bpmApprox: Bool { !bpmEdited && (track.auto?.bpmConf ?? 1) < 0.5 }
    var key: String? { effective.key }
    var keyEdited: Bool { override?.key != nil }
    var energy: Int? { effective.energy }
    var energyEdited: Bool { override?.energy != nil }

    var editedFields: Set<CompactTagLine.Field> {
        var f: Set<CompactTagLine.Field> = []
        if bpmEdited { f.insert(.bpm) }
        if keyEdited { f.insert(.key) }
        if energyEdited { f.insert(.energy) }
        if override?.stylesAdd?.isEmpty == false || override?.family?.isEmpty == false { f.insert(.style) }
        return f
    }

    // MARK: Confidence

    var confidence: ConfidenceSummary? {
        guard let c = track.consensus else { return nil }
        return ConfidenceSummary(c, override: overrideFamily)
    }

    /// List marker: only for "no majority" and "one source" (nothing for overrides).
    var markerKind: ConfidenceMarker.Kind? {
        guard let s = confidence, !s.isOverride else { return nil }
        if s.isSingleSource { return .single }
        return s.isSplit ? .split : nil
    }

    // MARK: Library

    var likedDate: Date? {
        guard let s = track.likedAt else { return nil }
        return TrackInfo.iso.date(from: s) ?? TrackInfo.isoFractional.date(from: s)
    }

    var addedText: String {
        guard let d = likedDate else { return "In library" }
        return "In library · added " + d.formatted(.dateTime.month(.abbreviated).year())
    }

    /// Normalised community tags in source order, without duplicates.
    var communityTags: [String] {
        var seen = Set<String>()
        var out: [String] = []
        for raw in track.tags ?? [] {
            let t = Tags.normalise(raw)
            if !t.isEmpty, seen.insert(t).inserted { out.append(t) }
        }
        return out
    }

    /// Which outside sources gave `tag` (normalised): "LFM" Last.fm, "DSC" Discogs, "BP" Beatport, in that order.
    func tagSources(_ tag: String) -> [(code: String, name: String)] {
        let sources = track.tagSources ?? [:]
        return [("lastfm", "LFM", "Last.fm"), ("discogs", "DSC", "Discogs"), ("beatport", "BP", "Beatport")].compactMap { key, code, name in
            (sources[key] ?? []).contains { Tags.normalise($0) == tag } ? (code, name) : nil
        }
    }

    func compactTagLine(vibes: [String]) -> CompactTagLine {
        guard isAnalysed else { return .noPreview }
        return CompactTagLine(marker: markerKind, style: effective.primaryStyle.map(styleName),
                              bpm: bpm.map { Int($0.rounded()) }, bpmApprox: bpmApprox, key: key, energy: energy,
                              vibes: vibes, edited: editedFields)
    }

    nonisolated(unsafe) private static let iso = ISO8601DateFormatter()
    nonisolated(unsafe) private static let isoFractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
}

extension AppModel {
    /// Vibes (in Build order) whose members include `trackID`.
    func tracksVibes(containing trackID: String) -> [(id: String, vibe: Vibe)] {
        snapshot.vibes.ordered.filter { index.vibeMembers[$0.id]?.contains(trackID) == true }
    }

    /// The vibe's SF Symbol: the user's pick, else the default for its ID, else `tag`.
    func tracksVibeSymbol(id: String, vibe: Vibe) -> String {
        vibe.icon ?? VibeSymbols.defaults[id] ?? "tag"
    }
}

fileprivate extension String {
    /// "breaks & bass" → "Breaks & bass" (taxonomy names are already sentence case; IDs are not).
    var sentenceCased: String { prefix(1).uppercased() + dropFirst() }
}
