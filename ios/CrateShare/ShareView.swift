import CrateKit
import CrateUI
import SwiftUI

/// The share sheet (design 6s in library, 3m not in library, 3n not a track).
struct ShareView: View {
    @Bindable var model: ShareModel
    /// Opens a `crate://` or Spotify URL through the host, then closes the sheet.
    let open: (URL) -> Void
    let close: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Space.sectionGap) { content }
                    .padding(.horizontal, Space.screenMargin)
                    .padding(.vertical, Space.s)
            }
            .background(Color.bgBase)
            .navigationTitle("Crate")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { close() } label: { Image(systemName: "xmark") }.accessibilityLabel("Close")
                }
            }
        }
        .tint(Color.accent)
        .liftOffHost()
    }

    @ViewBuilder private var content: some View {
        switch model.phase {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, minHeight: 160)
        case let .inLibrary(card):
            inLibrary(card)
        case let .notInLibrary(id, liked):
            notInLibrary(id: id, liked: liked)
        case .notATrack:
            message(symbol: "music.note", title: "Not a Spotify track",
                    text: "Share a track from Spotify (⋯ → Share → Crate) to see its tags or send similar music to Now.")
        case let .noData(id):
            message(symbol: "arrow.down.circle", title: "Open Crate once",
                    text: "Crate hasn't loaded your library on this phone yet. Open it once, then share again.")
            secondary("Open in Crate", symbol: "arrow.up.forward.app") { open(CrateLink.track(id)) }
        }
    }

    // MARK: In library (6s)

    private func inLibrary(_ card: ShareModel.TrackCard) -> some View {
        VStack(alignment: .leading, spacing: Space.l) {
            VStack(alignment: .leading, spacing: Space.m) {
                HStack(spacing: Space.m) {
                    CoverImage(url: card.coverURL, size: 56, radius: Radius.cover, tint: Color.family(card.family))
                    VStack(alignment: .leading, spacing: Space.xxs) {
                        Text(card.title).font(.headline).foregroundStyle(Color.textPrimary).lineLimit(2)
                        Text(card.artist).font(.secondary).foregroundStyle(Color.textSecondary).lineLimit(1)
                    }
                    .accessibilityElement(children: .combine)
                }
                CompactTagLine(track: card.track, styleName: card.styleName)
                if let confidence = card.confidence { ConfidenceLine(confidence, expandable: false) }
                if !card.vibes.isEmpty {
                    ChipFlow {
                        ForEach(card.vibes, id: \.id) { v in
                            VibeChip(v.name, symbol: v.icon, count: nil, action: {}).allowsHitTesting(false)
                        }
                    }
                }
            }
            .crateCard()

            PrimaryButton(sendState) {
                switch model.action {
                case .done: open(CrateLink.now)
                default: Task { await model.sendMoreLikeThis() }
                }
            }
            secondary("Edit in Crate", symbol: "pencil") { open(CrateLink.track(card.id)) }
            Text("More like this sends the 100 most similar tracks, shuffled. Edit opens the track in Crate, where you change its tags and vibes.")
                .font(.footnote).foregroundStyle(Color.textSecondary)
        }
    }

    private var sendState: PrimaryButtonState {
        guard model.canSendMoreLikeThis else { return .disabled(title: "No sound data yet") }
        switch model.action {
        case .idle: return .idle(title: "More like this → Now")
        case .working: return .sending(progress: 0.5, counter: nil)
        case let .done(text): return .success(message: text, title: "Listen on Spotify")
        case let .failed(text): return .error(message: text, title: "Try again")
        }
    }

    // MARK: Not in library (3m)

    @ViewBuilder private func notInLibrary(id: String, liked: Bool) -> some View {
        if liked {
            message(symbol: "heart.fill", title: "Liked",
                    text: "It's in your Liked Songs. Crate tags it tonight, and it shows up in Build tomorrow.")
        } else {
            message(symbol: "heart", title: "Not in your library yet",
                    text: "Like it and Crate tags it tonight.")
            PrimaryButton(likeState) { Task { await model.like(id: id) } }
        }
    }

    private var likeState: PrimaryButtonState {
        switch model.action {
        case .working: .sending(progress: 0.5, counter: nil)
        case let .failed(text): .error(message: text, title: "Try again")
        default: .idle(title: "♡ Like in Spotify")
        }
    }

    // MARK: Pieces

    private func message(symbol: String, title: String, text: String) -> some View {
        VStack(alignment: .leading, spacing: Space.s) {
            Image(systemName: symbol).font(.title2).foregroundStyle(Color.textSecondary)
            Text(title).font(.headline).foregroundStyle(Color.textPrimary)
            Text(text).font(.secondary).foregroundStyle(Color.textSecondary).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .crateCard()
    }

    private func secondary(_ title: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(title, systemImage: symbol)
                .font(.chipLabel)
                .foregroundStyle(Color.textPrimary)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(Color.fillControl, in: RoundedRectangle(cornerRadius: Radius.button, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}
