import Foundation
import Testing
@testable import CrateKit

@Suite("GitHubStore")
struct GitHubStoreTests {
    private func contents(_ text: String, sha: String) -> HTTPResponse {
        .json(#"{"sha":"\#(sha)","encoding":"base64","content":"\#(Data(text.utf8).base64EncodedString())"}"#,
              headers: ["ETag": "\"etag-\(sha)\""])
    }

    private func putOK(_ sha: String) -> HTTPResponse { .json(200, #"{"content":{"sha":"\#(sha)"}}"#) }

    private func store(_ transport: FakeTransport, cache: any GitHubCache = InMemoryGitHubCache()) -> GitHubStore {
        GitHubStore(owner: "owner", repo: "crate", token: { "github_pat_TEST" }, transport: transport, cache: cache)
    }

    @Test func getSendsPATAndUsesETag() async throws {
        let transport = FakeTransport(responses: [contents("v1", sha: "s1"), .json(304, "")])
        let s = store(transport)
        let first = try await s.get("data/state.json")
        #expect(String(decoding: first.data, as: UTF8.self) == "v1" && first.sha == "s1" && !first.fromCache)
        let second = try await s.get("data/state.json")
        #expect(second.fromCache && second.sha == "s1" && second.data == first.data)

        let requests = transport.recorded
        #expect(requests[0].header("Authorization") == "Bearer github_pat_TEST")
        #expect(requests[0].url.path == "/repos/owner/crate/contents/data/state.json")
        #expect(requests[0].queryItems.first { $0.name == "ref" }?.value == "main")
        #expect(requests[0].header("If-None-Match") == nil)
        #expect(requests[1].header("If-None-Match") == "\"etag-s1\"")
    }

    @Test func offlineFallsBackToCache() async throws {
        let cache = InMemoryGitHubCache()
        cache.store(CachedFile(data: Data("cached".utf8), sha: "s0", etag: "e"), path: "data/library.json")
        struct Offline: HTTPTransport { func send(_ r: HTTPRequest) async throws -> HTTPResponse { throw URLError(.notConnectedToInternet) } }
        let s = GitHubStore(owner: "o", repo: "r", token: { "t" }, transport: Offline(), cache: cache)
        #expect(try await s.get("data/library.json").fromCache)
    }

    @Test func largeFileComesFromBlobAPI() async throws {
        let transport = FakeTransport { request, _ in
            request.url.path.contains("/git/blobs/")
                ? .json(#"{"sha":"big","encoding":"base64","content":"\#(Data("large".utf8).base64EncodedString())"}"#)
                : .json(#"{"sha":"big","encoding":"none","content":""}"#)
        }
        let file = try await store(transport).get("data/library.json")
        #expect(String(decoding: file.data, as: UTF8.self) == "large")
        #expect(transport.recorded.last?.url.path == "/repos/owner/crate/git/blobs/big")
    }

    @Test func conflictRefetchesAndReappliesChange() async throws {
        // GET (s1) → PUT 409 → GET (s2, someone else's edit) → PUT 200.
        let transport = FakeTransport(responses: [
            contents(#"{"schema":1,"tracks":{"a":{"bpm":100}}}"#, sha: "s1"),
            .json(409, #"{"message":"conflict"}"#),
            contents(#"{"schema":1,"tracks":{"a":{"bpm":100},"b":{"energy":3}}}"#, sha: "s2"),
            putOK("s3"),
        ])
        let newSHA = try await store(transport).updateJSON(
            OverridesFile.self, path: "data/overrides.json", message: "Edit tags", empty: OverridesFile()
        ) { file in
            file.tracks["c", default: TrackOverride()].bpm = 140
        }
        #expect(newSHA == "s3")
        let requests = transport.recorded
        #expect(requests.map(\.method) == ["GET", "PUT", "GET", "PUT"])
        #expect(requests[2].header("If-None-Match") == nil, "refetch bypasses the ETag")
        let put1 = jsonObject(requests[1].body), put2 = jsonObject(requests[3].body)
        #expect(put1["sha"] as? String == "s1" && put2["sha"] as? String == "s2")
        #expect(put2["branch"] as? String == "main" && put2["message"] as? String == "Edit tags")
        let written = try CrateJSON.decode(OverridesFile.self,
                                           from: Data(base64Encoded: put2["content"] as? String ?? "")!, file: "overrides.json")
        #expect(written.tracks["b"]?.energy == 3, "the other writer's change survives")
        #expect(written.tracks["c"]?.bpm == 140, "our change is re-applied")
    }

    @Test func givesUpAfterThreeRetries() async {
        let transport = FakeTransport { request, _ in
            request.method == "GET" ? self.contents("{}", sha: "s") : .json(422, "{}")
        }
        await #expect(throws: GitHubError.conflict(path: "data/presets.json")) {
            try await store(transport).update(path: "data/presets.json", message: "m") { $0 ?? Data() }
        }
        #expect(transport.recorded.filter { $0.method == "PUT" }.count == GitHubStore.maxAttempts)
    }

    @Test func missingFileIsCreatedWithoutSHA() async throws {
        let transport = FakeTransport(responses: [.json(404, #"{"message":"Not Found"}"#), putOK("new")])
        let sha = try await store(transport).update(path: "data/presets.json", message: "Create") { old in
            #expect(old == nil)
            return Data("[]".utf8)
        }
        #expect(sha == "new")
        #expect(jsonObject(transport.recorded[1].body)["sha"] == nil)
    }

    @Test func unauthorized() async {
        let transport = FakeTransport(responses: [.json(401, "{}")])
        await #expect(throws: GitHubError.unauthorized(status: 401)) {
            try await store(transport).get("data/state.json", offlineFallback: false)
        }
    }

    @Test func fileCacheRoundTrip() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("cratekit-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let cache = FileGitHubCache(directory: dir)
        #expect(cache.load(path: "data/library.json") == nil)
        let file = CachedFile(data: Data("hello".utf8), sha: "abc", etag: "W/\"1\"")
        cache.store(file, path: "data/library.json")
        #expect(FileGitHubCache(directory: dir).load(path: "data/library.json") == file)
    }
}
