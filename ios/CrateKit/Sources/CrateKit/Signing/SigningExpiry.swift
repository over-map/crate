import Foundation

/// Errors reading `embedded.mobileprovision`.
public enum SigningExpiryError: Error, Equatable, Sendable {
    case profileNotFound
    case plistNotFound
    case expirationDateMissing
}

/// The app's signing expiry, read from `embedded.mobileprovision` (spec §7.5). Free Personal Team builds
/// expire after 7 days.
public struct SigningExpiry: Sendable, Equatable {
    /// How urgent the SigningCard is (design-system §8).
    public enum Level: String, Sendable {
        /// More than 2 days left.
        case ok
        /// 2 days or fewer.
        case warning
        /// Last day, or already expired.
        case critical
    }

    public let expirationDate: Date
    public let creationDate: Date?
    public let name: String?
    /// App Group identifiers the profile grants (`Entitlements → com.apple.security.application-groups`).
    /// Re-signing tools may rename groups, so apps should read the real one from here.
    public let appGroups: [String]

    public init(expirationDate: Date, creationDate: Date? = nil, name: String? = nil, appGroups: [String] = []) {
        self.expirationDate = expirationDate; self.creationDate = creationDate; self.name = name
        self.appGroups = appGroups
    }

    /// Parses the profile: a CMS-signed blob whose payload is an XML plist, found between `<?xml` and `</plist>`.
    public init(mobileprovision data: Data) throws {
        guard let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex) else {
            throw SigningExpiryError.plistNotFound
        }
        let plistData = data.subdata(in: start.lowerBound..<end.upperBound)
        guard let plist = try? PropertyListSerialization.propertyList(from: plistData, format: nil) as? [String: Any] else {
            throw SigningExpiryError.plistNotFound
        }
        guard let expiration = plist["ExpirationDate"] as? Date else { throw SigningExpiryError.expirationDateMissing }
        let entitlements = plist["Entitlements"] as? [String: Any]
        let groups = entitlements?["com.apple.security.application-groups"] as? [String] ?? []
        self.init(expirationDate: expiration, creationDate: plist["CreationDate"] as? Date, name: plist["Name"] as? String,
                  appGroups: groups)
    }

    /// Reads `embedded.mobileprovision` from `bundle`. Simulator and App Store builds have none.
    public static func fromBundle(_ bundle: Bundle = .main) throws -> SigningExpiry {
        guard let url = bundle.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url) else { throw SigningExpiryError.profileNotFound }
        return try SigningExpiry(mobileprovision: data)
    }

    /// Time left until expiry (negative once expired).
    public func timeRemaining(now: Date = Date()) -> TimeInterval { expirationDate.timeIntervalSince(now) }

    /// Whole days left, rounded down (0 on the last day, negative once expired).
    public func daysRemaining(now: Date = Date()) -> Int {
        Int((timeRemaining(now: now) / 86_400).rounded(.down))
    }

    public func isExpired(now: Date = Date()) -> Bool { timeRemaining(now: now) <= 0 }

    /// Card level: ok above 2 days, warning at 2 days or fewer, critical in the last 24 hours.
    public func level(now: Date = Date()) -> Level {
        let remaining = timeRemaining(now: now)
        if remaining <= 86_400 { return .critical }
        if remaining <= 2 * 86_400 { return .warning }
        return .ok
    }

    /// When to fire the local "signing expires tomorrow" notification: 24 h before expiry.
    public var reminderDate: Date { expirationDate.addingTimeInterval(-86_400) }

    /// The reminder on the expiry day itself: 09:00 that day, or, when the app expires before 10:00, one hour
    /// before expiry but never before midnight, so it always lands on the day.
    public func expiryDayReminderDate(calendar: Calendar = .current) -> Date {
        let day = calendar.startOfDay(for: expirationDate)
        let nine = calendar.date(bySettingHour: 9, minute: 0, second: 0, of: expirationDate) ?? day
        if nine.addingTimeInterval(3_600) <= expirationDate { return nine }
        return max(day, expirationDate.addingTimeInterval(-3_600))
    }
}

public extension SigningExpiry {
    /// The App Group to use: the first granted group starting with `preferredPrefix` (re-signing tools may append a suffix),
    /// else `fallback`. Works the same in the app and its extensions, which share the group.
    static func appGroup(preferredPrefix: String, fallback: String, bundle: Bundle = .main) -> String {
        guard let groups = try? fromBundle(bundle).appGroups else { return fallback }
        return groups.first { $0.hasPrefix(preferredPrefix) } ?? groups.first ?? fallback
    }
}
