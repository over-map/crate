import SwiftUI
import CrateKit

// MARK: - Cover

/// Album artwork from a URL, with the striped placeholder while it loads (or when there is none).
/// The image crossfades in over 0.25 s (instantly with Reduce Motion).
public struct CoverImage: View {
    private let url: URL?
    private let size: CGFloat
    private let radius: CGFloat
    private let tint: Color?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// - Parameters:
    ///   - url: Artwork URL (Spotify), or `nil` for the placeholder.
    ///   - size: Side length; `Size.coverRow` (48), `Size.coverStrip` (38) or `Size.coverDetail` (240).
    ///   - radius: Corner radius; `Radius.cover` (4) or `Radius.coverLarge` (10).
    ///   - tint: Optional colour mixed into the placeholder stripes (the track's family).
    public init(url: URL?, size: CGFloat = Size.coverRow, radius: CGFloat = Radius.cover, tint: Color? = nil) {
        self.url = url
        self.size = size
        self.radius = radius
        self.tint = tint
    }

    public var body: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        AsyncImage(url: url, transaction: Transaction(animation: reduceMotion ? nil : CrateMotion.cover)) { phase in
            switch phase {
            case let .success(image):
                image.resizable().scaledToFill().transition(.opacity)
            default:
                StripedPlaceholder(tint: tint)
            }
        }
        .frame(width: size, height: size)
        .clipShape(shape)
        .accessibilityHidden(true)
    }
}

/// 45° stripes, 4 pt wide, in two control fills (optionally tinted).
struct StripedPlaceholder: View {
    let tint: Color?

    var body: some View {
        Canvas { context, canvas in
            context.fill(Path(CGRect(origin: .zero, size: canvas)), with: .color(.fillControl))
            let stripe = Metric.coverStripe
            let diagonal = canvas.width + canvas.height
            var path = Path()
            var offset: CGFloat = -canvas.height
            while offset < diagonal {
                path.move(to: CGPoint(x: offset, y: canvas.height))
                path.addLine(to: CGPoint(x: offset + canvas.height, y: 0))
                path.addLine(to: CGPoint(x: offset + canvas.height + stripe, y: 0))
                path.addLine(to: CGPoint(x: offset + stripe, y: canvas.height))
                path.closeSubpath()
                offset += stripe * 2
            }
            context.fill(path, with: .color(.fillControlActive))
            if let tint {
                context.fill(Path(CGRect(origin: .zero, size: canvas)), with: .color(tint.opacity(0.18)))
            }
        }
    }
}

// MARK: - Compact tag line

/// The one-line track summary under the artist: `[marker] Style · [≈]BPM · [key] · energy · vibes`.
///
/// Vibes truncate first, then the style; BPM, key and energy never truncate. At accessibility sizes the
/// line wraps (energy and vibes move to the second line). Edited values get a dot. Without analysis it
/// reads "No preview · tag by hand". Used by ``TrackRow`` and the share sheet.
public struct CompactTagLine: View {
    /// Values that can carry the "edited" dot.
    public enum Field: Hashable, Sendable { case style, bpm, key, energy }

    private let marker: ConfidenceMarker.Kind?
    private let style: String?
    private let bpm: Int?
    private let bpmApprox: Bool
    private let key: String?
    private let energy: Int?
    private let vibes: [String]
    private let edited: Set<Field>
    private let noPreview: Bool
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    /// - Parameters:
    ///   - marker: Confidence list marker (only for split / single-source genres).
    ///   - style: Primary style display name.
    ///   - bpm: Detected BPM.
    ///   - bpmApprox: Shows "≈" (`bpm_conf < 0.5`).
    ///   - key: Camelot code.
    ///   - energy: 1–10.
    ///   - vibes: Vibe names, joined with ", ".
    ///   - edited: Fields the user corrected.
    public init(marker: ConfidenceMarker.Kind? = nil, style: String?, bpm: Int?, bpmApprox: Bool = false,
                key: String?, energy: Int?, vibes: [String] = [], edited: Set<Field> = []) {
        self.marker = marker
        self.style = style
        self.bpm = bpm
        self.bpmApprox = bpmApprox
        self.key = key
        self.energy = energy
        self.vibes = vibes
        self.edited = edited
        self.noPreview = false
    }

    /// The "No preview · tag by hand" line for tracks without analysis.
    public static var noPreview: CompactTagLine { CompactTagLine() }

    private init() {
        marker = nil; style = nil; bpm = nil; bpmApprox = false; key = nil; energy = nil
        vibes = []; edited = []; noPreview = true
    }

    /// From a CrateKit track: primary style, BPM (≈ when `bpm_conf < 0.5`), key and energy.
    public init(track: Track, styleName: String?, vibes: [String] = [], marker: ConfidenceMarker.Kind? = nil) {
        if track.auto == nil || track.status == .noPreview {
            self.init()
            return
        }
        self.init(marker: marker, style: styleName, bpm: track.auto?.bpm.map { Int($0.rounded()) },
                  bpmApprox: (track.auto?.bpmConf ?? 1) < 0.5, key: track.auto?.key, energy: track.auto?.energy,
                  vibes: vibes)
    }

    public var body: some View {
        Group {
            if noPreview {
                Text("No preview · tag by hand")
            } else if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: Space.xxs) {
                    HStack(spacing: Space.chipGap) { head }
                    HStack(spacing: Space.chipGap) { tail(leadingDot: false) }
                }
            } else {
                // Vibes go first when space runs out, then the style truncates.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: Space.chipGap) {
                        head
                        tail(leadingDot: true, includeVibes: true)
                    }
                    HStack(spacing: Space.chipGap) {
                        head
                        tail(leadingDot: true, includeVibes: false)
                    }
                }
            }
        }
        .font(.tagLine)
        .foregroundStyle(Color.textSecondary)
        .lineLimit(1)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    @ViewBuilder private var head: some View {
        if let marker { ConfidenceMarker(marker) }
        if let style {
            Text(style).truncationMode(.tail).layoutPriority(1)
            editedDot(.style)
        }
        if let bpm {
            if style != nil { dot }
            BPMText(value: bpm, approx: bpmApprox, font: .tagLine).layoutPriority(3)
            editedDot(.bpm)
        }
        if let key, Camelot(key) != nil {
            if style != nil || bpm != nil { dot }
            CamelotBadge(code: key, size: .tagLine).fixedSize().layoutPriority(3)
            editedDot(.key)
        }
    }

    @ViewBuilder private func tail(leadingDot: Bool, includeVibes: Bool = true) -> some View {
        if let energy {
            if leadingDot, style != nil || bpm != nil || key != nil { dot }
            HStack(spacing: Space.xs) {
                EnergyMicroMeter(level: min(10, max(1, energy)))
                Text(verbatim: String(energy))
            }
            .fixedSize()
            .layoutPriority(3)
            editedDot(.energy)
        }
        if includeVibes, !vibes.isEmpty {
            if leadingDot || energy != nil { dot }
            Text(vibes.joined(separator: ", ")).truncationMode(.tail).layoutPriority(0)
        }
    }

    private var dot: some View {
        Text(verbatim: "·").foregroundStyle(Color.textTertiary).accessibilityHidden(true).fixedSize()
    }

    @ViewBuilder private func editedDot(_ field: Field) -> some View {
        if edited.contains(field) {
            Circle().fill(Color.textPrimary).frame(width: Metric.editedDot, height: Metric.editedDot)
                .padding(.leading, -Space.xxs)
        }
    }

    private var accessibilityText: String {
        if noPreview { return "No preview, tag by hand" }
        var parts: [String] = []
        if marker == .split { parts.append(ConfidenceCopy.split) }
        if marker == .single { parts.append("One source only") }
        if let style { parts.append(style + (edited.contains(.style) ? ", edited" : "")) }
        if let bpm { parts.append((bpmApprox ? "about \(bpm) BPM" : "\(bpm) BPM") + (edited.contains(.bpm) ? ", edited" : "")) }
        if let key, let k = Camelot(key) { parts.append("key \(k.code), \(k.name)") }
        if let energy { parts.append("energy \(energy)") }
        if !vibes.isEmpty { parts.append(vibes.joined(separator: ", ")) }
        return parts.joined(separator: ", ")
    }
}

// MARK: - Track row

/// A track in a list: 48 pt cover, title, artist, compact tag line, and a trailing accessory
/// (chevron, heart, "?" …). At least 64 pt tall.
public struct TrackRow<Accessory: View>: View {
    private let title: String
    private let artist: String
    private let coverURL: URL?
    private let coverTint: Color?
    private let tagLine: CompactTagLine?
    private let accessory: Accessory

    /// - Parameters:
    ///   - title: Track title.
    ///   - artist: Artists, joined.
    ///   - coverURL: Artwork.
    ///   - coverTint: Tint for the placeholder (family colour).
    ///   - tagLine: The compact tag line; `nil` hides it.
    ///   - accessory: Trailing view.
    public init(title: String, artist: String, coverURL: URL? = nil, coverTint: Color? = nil,
                tagLine: CompactTagLine? = nil, @ViewBuilder accessory: () -> Accessory) {
        self.title = title
        self.artist = artist
        self.coverURL = coverURL
        self.coverTint = coverTint
        self.tagLine = tagLine
        self.accessory = accessory()
    }

    public var body: some View {
        HStack(spacing: Space.m) {
            CoverImage(url: coverURL, size: Size.coverRow, radius: Radius.cover, tint: coverTint)
            VStack(alignment: .leading, spacing: Space.xxs) {
                Text(title).font(.body).foregroundStyle(Color.textPrimary).lineLimit(1)
                Text(artist).font(.secondary).foregroundStyle(Color.textSecondary).lineLimit(1)
                if let tagLine { tagLine }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            accessory
        }
        .padding(.vertical, Space.s)
        .frame(minHeight: Size.coverRow + Space.l)
        .contentShape(Rectangle())
    }
}

public extension TrackRow where Accessory == TrackRowChevron {
    /// A row with the default disclosure chevron.
    init(title: String, artist: String, coverURL: URL? = nil, coverTint: Color? = nil, tagLine: CompactTagLine? = nil) {
        self.init(title: title, artist: artist, coverURL: coverURL, coverTint: coverTint, tagLine: tagLine) {
            TrackRowChevron()
        }
    }
}

/// The trailing chevron of a navigable row (`textTertiary`).
public struct TrackRowChevron: View {
    public init() {}
    public var body: some View {
        Image(systemName: "chevron.right")
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Color.textTertiary)
            .accessibilityHidden(true)
    }
}

/// The Weekly heart toggle, 44 pt target.
public struct LikeButton: View {
    private let isLiked: Bool
    private let action: () -> Void

    /// - Parameters:
    ///   - isLiked: Filled heart when `true`.
    ///   - action: Toggle.
    public init(isLiked: Bool, action: @escaping () -> Void) {
        self.isLiked = isLiked
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Image(systemName: isLiked ? "heart.fill" : "heart")
                .font(.title3)
                .foregroundStyle(isLiked ? Color.accent : Color.textSecondary)
                .contentTransition(.symbolEffect(.replace))
                .frame(width: Size.minTarget, height: Size.minTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .sensoryFeedback(.selection, trigger: isLiked)
        .accessibilityLabel("Like")
        .accessibilityAddTraits(isLiked ? .isSelected : [])
    }
}
