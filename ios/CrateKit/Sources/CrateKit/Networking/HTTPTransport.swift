import Foundation

/// A plain HTTP request, independent of URLSession so tests can inspect it.
public struct HTTPRequest: Sendable, Equatable {
    public var method: String
    public var url: URL
    public var headers: [String: String]
    public var body: Data?

    public init(method: String = "GET", url: URL, headers: [String: String] = [:], body: Data? = nil) {
        self.method = method; self.url = url; self.headers = headers; self.body = body
    }

    /// Header lookup, ignoring case.
    public func header(_ name: String) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }

    /// Query items of ``url``.
    public var queryItems: [URLQueryItem] {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
    }
}

/// An HTTP response.
public struct HTTPResponse: Sendable, Equatable {
    public var status: Int
    public var headers: [String: String]
    public var body: Data

    public init(status: Int, headers: [String: String] = [:], body: Data = Data()) {
        self.status = status; self.headers = headers; self.body = body
    }

    /// Header lookup, ignoring case.
    public func header(_ name: String) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }

    /// True for 2xx.
    public var isSuccess: Bool { (200..<300).contains(status) }
}

/// Sends HTTP requests. The app uses ``URLSessionTransport``; tests use a fake.
public protocol HTTPTransport: Sendable {
    func send(_ request: HTTPRequest) async throws -> HTTPResponse
}

/// ``HTTPTransport`` backed by `URLSession`.
public struct URLSessionTransport: HTTPTransport {
    private let session: URLSession

    public init(session: URLSession = .shared) { self.session = session }

    public func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        var urlRequest = URLRequest(url: request.url)
        urlRequest.httpMethod = request.method
        urlRequest.httpBody = request.body
        // ETags are handled by GitHubStore; keep URLSession from answering 304s from its own cache.
        urlRequest.cachePolicy = .reloadIgnoringLocalCacheData
        for (name, value) in request.headers { urlRequest.setValue(value, forHTTPHeaderField: name) }
        let (data, response) = try await session.data(for: urlRequest)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        var headers: [String: String] = [:]
        for (name, value) in http.allHeaderFields {
            if let name = name as? String { headers[name] = "\(value)" }
        }
        return HTTPResponse(status: http.statusCode, headers: headers, body: data)
    }
}

extension Data {
    /// Base64url without padding (RFC 4648 §5), as used by PKCE.
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

/// Form-encodes key/value pairs (`application/x-www-form-urlencoded`).
func formEncode(_ pairs: [(String, String)]) -> Data {
    let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
    let text = pairs.map { key, value in
        let k = key.addingPercentEncoding(withAllowedCharacters: allowed) ?? key
        let v = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
        return "\(k)=\(v)"
    }.joined(separator: "&")
    return Data(text.utf8)
}
