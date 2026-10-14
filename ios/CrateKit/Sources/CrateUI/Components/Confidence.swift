import SwiftUI
import CrateKit

// MARK: - Model

/// The four genre sources, in the fixed display order of the ConfidenceLine.
public enum ConfidenceSource: String, CaseIterable, Sendable, Identifiable {
    case audio, discogs, lastfm, beatport

    public var id: String { rawValue }

    /// Name in the vote table ("Last.fm").
    public var displayName: String {
        switch self {
        case .audio: "Audio"
        case .discogs: "Discogs"
        case .lastfm: "Last.fm"
        case .beatport: "Beatport"
        }
    }

    /// Name inside a sentence ("Only audio has an opinion").
    public var inlineName: String { self == .audio ? "audio" : displayName }
}

/// How many sources agree on a track's family. Build it from CrateKit's `Consensus` with
/// ``init(_:override:)`` or directly.
public struct ConfidenceSummary: Equatable, Sendable {
    /// The family shown on the track (the user's pick when `isOverride`).
    public var family: Family
    /// Votes for the winning family.
    public var votes: Int
    /// Sources that voted.
    public var sources: Int
    /// Whether the audio model voted for the winner; `nil` when it had no opinion.
    public var audioAgrees: Bool?
    /// Families each source voted for. Missing or empty = no opinion.
    public var bySource: [ConfidenceSource: [Family]]
    /// `true` when the user set the family by hand.
    public var isOverride: Bool
    /// What the sources said, shown as "Set by you · sources said House". Defaults to `family`.
    public var sourcesFamily: Family?

    public init(family: Family, votes: Int, sources: Int, audioAgrees: Bool? = nil,
                bySource: [ConfidenceSource: [Family]] = [:], isOverride: Bool = false, sourcesFamily: Family? = nil) {
        self.family = family
        self.votes = votes
        self.sources = sources
        self.audioAgrees = audioAgrees
        self.bySource = bySource
        self.isOverride = isOverride
        self.sourcesFamily = sourcesFamily
    }

    /// From library.json's consensus. `override` is the user's family, if any. `nil` without a family.
    public init?(_ consensus: Consensus, override: Family? = nil) {
        guard let raw = consensus.family, let family = Family(rawValue: raw) else { return nil }
        var by: [ConfidenceSource: [Family]] = [:]
        for (key, values) in consensus.bySource {
            guard let source = ConfidenceSource(rawValue: key) else { continue }
            by[source] = values.compactMap { Family(rawValue: $0) }
        }
        self.init(family: override ?? family, votes: consensus.votes ?? 0, sources: consensus.sources ?? by.count,
                  audioAgrees: consensus.audioAgrees, bySource: by, isOverride: override != nil,
                  sourcesFamily: override != nil ? family : nil)
    }

    /// Per-source dot state, in ``ConfidenceSource`` order.
    public enum Vote: Equatable, Sendable { case agrees, disagrees, noOpinion }

    /// The family the sources are compared against.
    var consensusFamily: Family { sourcesFamily ?? family }

    /// Dot state of one source.
    public func vote(of source: ConfidenceSource) -> Vote {
        guard let families = bySource[source], !families.isEmpty else { return .noOpinion }
        return families.contains(consensusFamily) ? .agrees : .disagrees
    }

    /// No majority for the winner.
    public var isSplit: Bool { sources > 1 && votes * 2 <= sources }
    /// Only one source had an opinion.
    public var isSingleSource: Bool { sources == 1 }

    /// The plain-words line (`tokens.json → confidence.copy`).
    public var copy: String {
        if isOverride { return ConfidenceCopy.override + " · sources said \(consensusFamily.displayName)" }
        if isSingleSource {
            let source = ConfidenceSource.allCases.first { vote(of: $0) != .noOpinion } ?? .audio
            return ConfidenceCopy.single(source.inlineName)
        }
        if isSplit { return ConfidenceCopy.split + " · " + ConfidenceCopy.say(votes, of: sources, consensusFamily.displayName) }
        if votes == sources { return ConfidenceCopy.all(sources) }
        return ConfidenceCopy.majority(votes, of: sources)
    }

    /// VoiceOver: the copy plus dissenting sources ("3 of 4 sources agree: audio says Techno").
    public var accessibilityText: String {
        let dissent = ConfidenceSource.allCases.compactMap { source -> String? in
            guard vote(of: source) == .disagrees, let first = bySource[source]?.first else { return nil }
            return "\(source.inlineName) says \(first.displayName)"
        }
        guard !dissent.isEmpty, !isOverride else { return copy }
        return copy + ": " + dissent.joined(separator: ", ")
    }
}

/// `tokens.json → confidence.copy`. The generator doesn't export these yet, so they live here.
public enum ConfidenceCopy {
    /// "All {n} sources agree".
    public static func all(_ n: Int) -> String { "All \(n) sources agree" }
    /// "{a} of {n} sources agree".
    public static func majority(_ a: Int, of n: Int) -> String { "\(a) of \(n) sources agree" }
    /// "Sources disagree" (followed by ``say(_:of:_:)``).
    public static let split = "Sources disagree"
    /// "{a} of {n} say {family}".
    public static func say(_ a: Int, of n: Int, _ family: String) -> String { "\(a) of \(n) say \(family)" }
    /// Under the expanded vote table.
    public static let footer = "The genre with most votes wins. Override it in Edit tags."
    /// "Only {source} has an opinion".
    public static func single(_ source: String) -> String { "Only \(source) has an opinion" }
    /// "Set by you".
    public static let override = "Set by you"
}

// MARK: - Dots

/// One source dot: filled 7 pt `textPrimary` (agrees), 1.5 pt `textSecondary` ring (votes for another
/// family), or a 6 × 1.5 `textTertiary` dash (no opinion).
struct ConfidenceDot: View {
    let vote: ConfidenceSummary.Vote

    var body: some View {
        switch vote {
        case .agrees:
            Circle().fill(Color.textPrimary).frame(width: Metric.confidenceDot, height: Metric.confidenceDot)
        case .disagrees:
            Circle().strokeBorder(Color.textSecondary, lineWidth: Stroke.outline)
                .frame(width: Metric.confidenceDot, height: Metric.confidenceDot)
        case .noOpinion:
            Capsule().fill(Color.textTertiary).frame(width: Metric.confidenceDash, height: Stroke.outline)
                .frame(width: Metric.confidenceDot, height: Metric.confidenceDot)
        }
    }
}

// MARK: - Line

/// "How many sources agree": four dots in fixed order Audio · Discogs · Last.fm · Beatport, then the copy.
/// Sits directly under the genre / style chips. With `expandable`, a tap reveals the vote table
/// (source, family mini chip, "differs" / "entered by you").
public struct ConfidenceLine: View {
    private let summary: ConfidenceSummary
    private let expandable: Bool
    private let footer: String?
    @State private var expanded: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// - Parameters:
    ///   - summary: The votes.
    ///   - expandable: Whether a tap shows the per-source table.
    ///   - expanded: Initial state.
    ///   - footer: Note under the expanded table (`nil` hides it).
    public init(_ summary: ConfidenceSummary, expandable: Bool = true, expanded: Bool = false,
                footer: String? = ConfidenceCopy.footer) {
        self.summary = summary
        self.expandable = expandable
        self.footer = footer
        _expanded = State(initialValue: expanded)
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            if expandable {
                Button {
                    withAnimation(CrateMotion.animation(CrateMotion.chip, reduceMotion: reduceMotion)) { expanded.toggle() }
                } label: { line }
                    .buttonStyle(.plain)
                    .accessibilityHint(expanded ? "Hides the votes" : "Shows each source's vote")
                    .accessibilityValue(expanded ? "Expanded" : "Collapsed")
            } else {
                line
            }
            if expanded {
                voteTable.transition(.opacity)
                if let footer {
                    Text(footer).font(.footnote).foregroundStyle(Color.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    private var line: some View {
        HStack(spacing: Space.s) {
            if summary.isOverride {
                Image(systemName: "pencil").font(.caption)
            } else {
                HStack(spacing: Metric.confidenceDotGap) {
                    ForEach(ConfidenceSource.allCases) { ConfidenceDot(vote: summary.vote(of: $0)) }
                }
            }
            Text(summary.copy)
                .fixedSize(horizontal: false, vertical: true)
            if expandable {
                Spacer(minLength: Space.s)
                Image(systemName: "chevron.down")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(Color.textTertiary)
                    .rotationEffect(.degrees(expanded ? 180 : 0))
            }
        }
        .font(.footnote.weight(.medium))
        .foregroundStyle(Color.textSecondary)
        .frame(minHeight: expandable ? Size.minTarget : Metric.confidenceLineHeight)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(summary.accessibilityText)
    }

    private var voteTable: some View {
        VStack(spacing: 0) {
            if summary.isOverride {
                row(dot: AnyView(Image(systemName: "pencil").font(.caption2)), name: "You",
                    families: [summary.family], note: "entered by you")
                Divider().overlay(Color.separator)
            }
            ForEach(Array(ConfidenceSource.allCases.enumerated()), id: \.element) { index, source in
                let vote = summary.vote(of: source)
                row(dot: AnyView(ConfidenceDot(vote: vote)), name: source.displayName,
                    families: summary.bySource[source] ?? [], note: vote == .disagrees ? "differs" : nil)
                if index < ConfidenceSource.allCases.count - 1 { Divider().overlay(Color.separator) }
            }
        }
        .crateCard()
    }

    private func row(dot: AnyView, name: String, families: [Family], note: String?) -> some View {
        HStack(spacing: Space.s + Space.xxs) {
            dot.frame(width: Space.s)
            Text(name).font(.secondary).foregroundStyle(Color.textPrimary)
                .frame(minWidth: Metric.voteSourceColumn, alignment: .leading)
            if families.isEmpty {
                Text("No opinion").font(.footnote).foregroundStyle(Color.textTertiary)
            } else {
                ForEach(families, id: \.self) { FamilyMiniChip($0) }
            }
            Spacer(minLength: Space.s)
            if let note { Text(note).font(.footnote).foregroundStyle(Color.textSecondary) }
        }
        .padding(.horizontal, Space.m)
        .frame(minHeight: Size.minTarget)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - List marker

/// The list marker before the style in a tag line: `circle.lefthalf.filled` when sources disagree (no
/// majority), `circle.dotted` when only one source had an opinion, nothing otherwise. 11 pt, `textSecondary`.
public struct ConfidenceMarker: View {
    /// Which marker to draw.
    public enum Kind: Sendable { case split, single }

    private let kind: Kind?
    @ScaledMetric(relativeTo: .caption2) private var size = Metric.listMarker

    /// - Parameter kind: `nil` draws nothing.
    public init(_ kind: Kind?) { self.kind = kind }

    /// The marker the summary calls for (nothing for agreement, a majority or an override).
    public init(_ summary: ConfidenceSummary?) {
        guard let summary, !summary.isOverride else { self.kind = nil; return }
        self.kind = summary.isSingleSource ? .single : (summary.isSplit ? .split : nil)
    }

    public var body: some View {
        if let kind {
            Image(systemName: kind == .split ? "circle.lefthalf.filled" : "circle.dotted")
                .font(.system(size: size))
                .foregroundStyle(Color.textSecondary)
                .accessibilityLabel(kind == .split ? ConfidenceCopy.split : "One source only")
        }
    }
}
