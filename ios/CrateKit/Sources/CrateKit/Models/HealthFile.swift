import Foundation

/// Overall health level shown on the Library tab.
public enum HealthLevel: String, Codable, Sendable {
    case healthy, warning, broken

    /// Unknown values decode as ``warning``.
    public init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = HealthLevel(rawValue: raw) ?? .warning
    }
}

/// One active problem, in plain words, with the runbook step that fixes it.
public struct HealthProblem: Codable, Sendable, Equatable {
    public var id: String?
    public var level: HealthLevel?
    public var message: String
    /// Runbook anchor, e.g. `docs/runbook.md#spotify-token`.
    public var runbook: String?

    public init(id: String? = nil, level: HealthLevel? = nil, message: String, runbook: String? = nil) {
        self.id = id; self.level = level; self.message = message; self.runbook = runbook
    }
}

/// Expiry of one credential (GitHub PAT, Spotify refresh token, …).
public struct CredentialExpiry: Codable, Sendable, Equatable {
    public var name: String
    public var expiresAt: String?
    public var daysLeft: Int?

    public init(name: String, expiresAt: String? = nil, daysLeft: Int? = nil) {
        self.name = name; self.expiresAt = expiresAt; self.daysLeft = daysLeft
    }

    enum CodingKeys: String, CodingKey {
        case name
        case expiresAt = "expires_at"
        case daysLeft = "days_left"
    }
}

/// `data/health.json`, written daily by `health.yml`. Every field is optional.
public struct HealthFile: Codable, Sendable, Equatable {
    public var schema: Int
    public var checkedAt: String?
    public var status: HealthLevel?
    public var lastNightlySuccess: String?
    /// Share of tracks with tags, 0–1.
    public var coverage: Double?
    public var problems: [HealthProblem]
    public var credentials: [CredentialExpiry]

    public init(schema: Int = CrateJSON.supportedSchema, checkedAt: String? = nil, status: HealthLevel? = nil,
                lastNightlySuccess: String? = nil, coverage: Double? = nil,
                problems: [HealthProblem] = [], credentials: [CredentialExpiry] = []) {
        self.schema = schema; self.checkedAt = checkedAt; self.status = status
        self.lastNightlySuccess = lastNightlySuccess; self.coverage = coverage
        self.problems = problems; self.credentials = credentials
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schema = try c.decodeIfPresent(Int.self, forKey: .schema) ?? CrateJSON.supportedSchema
        checkedAt = try c.decodeIfPresent(String.self, forKey: .checkedAt)
        status = try c.decodeIfPresent(HealthLevel.self, forKey: .status)
        lastNightlySuccess = try c.decodeIfPresent(String.self, forKey: .lastNightlySuccess)
        coverage = try c.decodeIfPresent(Double.self, forKey: .coverage)
        problems = (try? c.decodeIfPresent([HealthProblem].self, forKey: .problems)) ?? []
        credentials = (try? c.decodeIfPresent([CredentialExpiry].self, forKey: .credentials)) ?? []
    }

    /// The level to display: the explicit status, else derived from the problems.
    public var effectiveLevel: HealthLevel {
        if let status { return status }
        if problems.contains(where: { $0.level == .broken }) { return .broken }
        return problems.isEmpty ? .healthy : .warning
    }

    enum CodingKeys: String, CodingKey {
        case schema, status, coverage, problems, credentials
        case checkedAt = "checked_at"
        case lastNightlySuccess = "last_nightly_success"
    }
}
