import Foundation
import Testing
@testable import CrateKit

@Suite("NowWriter")
struct NowWriterTests {
    private func makeWriter(_ transport: FakeTransport, allowed: Set<String>) -> NowWriter {
        let client = SpotifyClient(auth: SpotifyAuth(clientID: "c"), transport: transport,
                                   tokenStore: InMemoryTokenStore(SpotifyTokens(accessToken: "T", refreshToken: nil, expiresAt: .distantFuture)))
        return NowWriter(writer: client, allowedPlaylistIDs: allowed)
    }

    @Test func refusesPlaylistsOutsideAllowList() async {
        let transport = FakeTransport(responses: [.json(201, "{}")])
        let writer = makeWriter(transport, allowed: ["NOW", "WEEKLY", ""])
        await #expect(throws: NowWriterError.playlistNotAllowed("OTHER")) {
            try await writer.replace(playlistID: "OTHER", with: ["spotify:track:1"])
        }
        await #expect(throws: NowWriterError.playlistNotAllowed("")) {
            try await writer.replace(playlistID: "", with: [])
        }
        #expect(transport.recorded.isEmpty, "nothing is sent for a refused playlist")
    }

    @Test func putsFirst100ThenPostsBatchesOf100() async throws {
        let transport = FakeTransport(responses: [.json(201, #"{"snapshot_id":"s"}"#)])
        let uris = (0..<250).map { "spotify:track:\($0)" }
        let progress = ProgressLog()
        try await makeWriter(transport, allowed: ["NOW"]).replace(playlistID: "NOW", with: uris) { done, total in
            progress.append(done, total)
        }
        let requests = transport.recorded
        #expect(requests.map(\.method) == ["PUT", "POST", "POST"])
        #expect(requests.allSatisfy { $0.url.path == "/v1/playlists/NOW/items" })
        let batches = requests.map { jsonObject($0.body)["uris"] as? [String] ?? [] }
        #expect(batches.map(\.count) == [100, 100, 50])
        #expect(batches.flatMap { $0 } == uris, "order is preserved across batches")
        #expect(progress.values == [100, 200, 250])
    }

    @Test func emptyResultClearsWithSinglePUT() async throws {
        let transport = FakeTransport(responses: [.json(201, "{}")])
        try await makeWriter(transport, allowed: ["NOW"]).replace(playlistID: "NOW", with: [])
        #expect(transport.recorded.map(\.method) == ["PUT"])
        #expect((jsonObject(transport.recorded[0].body)["uris"] as? [String]) == [])
    }
}

private final class ProgressLog: @unchecked Sendable {
    private let lock = NSLock()
    private var log: [Int] = []
    func append(_ done: Int, _ total: Int) { lock.withLock { log.append(done) } }
    var values: [Int] { lock.withLock { log } }
}
