import Foundation
import Testing
@testable import CrateKit

@Suite("Signing expiry")
struct SigningExpiryTests {
    private let expiry = Date(timeIntervalSince1970: 1_800_000_000)

    /// A fake CMS blob: binary junk, the XML plist payload, then a binary signature tail.
    private func profile() throws -> Data {
        let plist: [String: Any] = ["ExpirationDate": expiry, "CreationDate": expiry.addingTimeInterval(-7 * 86_400),
                                    "Name": "iOS Team Provisioning Profile: com.example.crate"]
        let xml = try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0)
        return Data([0x30, 0x80, 0x06, 0x09, 0x2A, 0x86, 0x48, 0xFF, 0x00]) + xml + Data([0xA0, 0x82, 0x00, 0x3C, 0xFF])
    }

    @Test func parsesExpirationDate() throws {
        let parsed = try SigningExpiry(mobileprovision: profile())
        #expect(parsed.expirationDate == expiry)
        #expect(parsed.name?.contains("crate") == true)
        #expect(parsed.reminderDate == expiry.addingTimeInterval(-86_400))
    }

    @Test func daysAndLevels() {
        let s = SigningExpiry(expirationDate: expiry)
        #expect(s.daysRemaining(now: expiry.addingTimeInterval(-6.5 * 86_400)) == 6)
        #expect(s.level(now: expiry.addingTimeInterval(-6.5 * 86_400)) == .ok)
        #expect(s.level(now: expiry.addingTimeInterval(-2 * 86_400)) == .warning)
        #expect(s.daysRemaining(now: expiry.addingTimeInterval(-3600)) == 0)
        #expect(s.level(now: expiry.addingTimeInterval(-3600)) == .critical)
        #expect(s.isExpired(now: expiry.addingTimeInterval(1)))
        #expect(s.daysRemaining(now: expiry.addingTimeInterval(1)) == -1)
    }

    @Test func malformedProfiles() {
        #expect(throws: SigningExpiryError.plistNotFound) { try SigningExpiry(mobileprovision: Data([1, 2, 3])) }
        let noDate = Data(#"<?xml version="1.0"?><plist version="1.0"><dict><key>Name</key><string>x</string></dict></plist>"#.utf8)
        #expect(throws: SigningExpiryError.expirationDateMissing) { try SigningExpiry(mobileprovision: noDate) }
    }
}

import Testing
@testable import CrateKit
import Foundation

@Suite("Signing app groups")
struct SigningAppGroupTests {
    @Test func readsAppGroupsFromEntitlements() throws {
        let xml = """
        <?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict>
        <key>ExpirationDate</key><date>2030-01-01T00:00:00Z</date>
        <key>Entitlements</key><dict><key>com.apple.security.application-groups</key>
        <array><string>group.com.overmap.crate.F2ZGNLP242</string></array></dict>
        </dict></plist>
        """
        let profile = try SigningExpiry(mobileprovision: Data(("CMSjunk" + xml + "trailer").utf8))
        #expect(profile.appGroups == ["group.com.overmap.crate.F2ZGNLP242"])
    }

    @Test func expiryDayReminderAlwaysFallsOnTheDay() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "Europe/Amsterdam")!
        func at(_ h: Int, _ m: Int) -> Date { cal.date(from: DateComponents(year: 2026, month: 10, day: 20, hour: h, minute: m))! }
        #expect(SigningExpiry(expirationDate: at(18, 40)).expiryDayReminderDate(calendar: cal) == at(9, 0))
        #expect(SigningExpiry(expirationDate: at(10, 0)).expiryDayReminderDate(calendar: cal) == at(9, 0))
        #expect(SigningExpiry(expirationDate: at(9, 30)).expiryDayReminderDate(calendar: cal) == at(8, 30))
        #expect(SigningExpiry(expirationDate: at(0, 20)).expiryDayReminderDate(calendar: cal) == at(0, 0))
    }
}
