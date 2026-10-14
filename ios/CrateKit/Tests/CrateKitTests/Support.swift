import Foundation
@testable import CrateKit

/// Locations of the shared files in the repository (`crate/spec`).
enum Repo {
    /// `crate/` — this file is `crate/ios/CrateKit/Tests/CrateKitTests/Support.swift`.
    static let root: URL = {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        return url
    }()

    static func spec(_ name: String) throws -> Data {
        try Data(contentsOf: root.appendingPathComponent("spec").appendingPathComponent(name))
    }
}

/// A scripted HTTP transport that records every request.
final class FakeTransport: HTTPTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var handler: (HTTPRequest, Int) -> HTTPResponse
    private(set) var requests: [HTTPRequest] = []

    init(_ handler: @escaping (HTTPRequest, Int) -> HTTPResponse) { self.handler = handler }

    /// Replies with `responses` in order (the last one repeats).
    convenience init(responses: [HTTPResponse]) {
        self.init { _, index in responses[min(index, responses.count - 1)] }
    }

    var recorded: [HTTPRequest] { lock.withLock { requests } }

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        lock.withLock {
            let index = requests.count
            requests.append(request)
            return handler(request, index)
        }
    }
}

extension HTTPResponse {
    static func json(_ status: Int, _ text: String, headers: [String: String] = [:]) -> HTTPResponse {
        HTTPResponse(status: status, headers: headers, body: Data(text.utf8))
    }

    static func json(_ text: String, headers: [String: String] = [:]) -> HTTPResponse {
        json(200, text, headers: headers)
    }
}

func jsonObject(_ data: Data?) -> [String: Any] {
    guard let data, let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
    return object
}
