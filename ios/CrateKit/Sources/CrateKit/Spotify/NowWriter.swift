import Foundation

/// The single low-level write to playlist items (`PUT`/`POST /playlists/{id}/items`).
public protocol SpotifyPlaylistItemsWriting: Sendable {
    func writePlaylistItems(method: String, playlistID: String, uris: [String]) async throws
}

/// Errors from ``NowWriter``.
public enum NowWriterError: Error, Equatable, Sendable {
    /// The playlist is not on the allow-list; nothing was written.
    case playlistNotAllowed(String)
}

/// Replaces the contents of an allow-listed playlist (spec §7.4 step 3 and §13).
///
/// Every destructive playlist call in the app goes through here. Only IDs in ``allowedPlaylistIDs`` (Now and
/// Crate Daily) can be modified; anything else is refused before any request is sent.
public struct NowWriter: Sendable {
    /// Max URIs per playlist-items request.
    public static let batchSize = 100

    public let allowedPlaylistIDs: Set<String>
    private let writer: any SpotifyPlaylistItemsWriting

    public init(writer: any SpotifyPlaylistItemsWriting, allowedPlaylistIDs: Set<String>) {
        self.writer = writer
        self.allowedPlaylistIDs = allowedPlaylistIDs.filter { !$0.isEmpty }
    }

    /// Replaces the playlist with `uris`: `PUT` the first 100 (clearing what was there), then `POST` the rest in
    /// batches of 100. `progress` receives (written, total) after each request.
    public func replace(playlistID: String, with uris: [String],
                        progress: (@Sendable (Int, Int) -> Void)? = nil) async throws {
        guard allowedPlaylistIDs.contains(playlistID) else { throw NowWriterError.playlistNotAllowed(playlistID) }
        let batches = uris.chunked(NowWriter.batchSize)
        try await writer.writePlaylistItems(method: "PUT", playlistID: playlistID, uris: batches.first ?? [])
        var written = batches.first?.count ?? 0
        progress?(written, uris.count)
        for batch in batches.dropFirst() {
            try await writer.writePlaylistItems(method: "POST", playlistID: playlistID, uris: batch)
            written += batch.count
            progress?(written, uris.count)
        }
    }
}
