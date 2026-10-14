import CrateKit
import Foundation

/// One of Crate's playlists that Spotify reports as public.
struct PublicPlaylist: Equatable, Hashable {
    let id: String
    let name: String

    /// "Crate Daily is public in Spotify." / "Now and Crate Daily are public in Spotify."
    static func sentence(_ list: [PublicPlaylist]) -> String {
        let names = list.map(\.name)
        switch names.count {
        case 0: return "Spotify creates them as public playlists."
        case 1: return "\(names[0]) is public in Spotify."
        default: return "\(names.dropLast().joined(separator: ", ")) and \(names.last!) are public in Spotify."
        }
    }
}

extension SpotifyService {
    /// `GET /playlists/{id}` (for the privacy check).
    func playlist(id: String) async throws -> SpotifyPlaylist { try await client.playlist(id: id) }
}
