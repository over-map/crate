import CrateKit
import Foundation
import UIKit
import UserNotifications

/// Local "Crate expires" reminders for the 7-day free signing: the day before (24 h ahead) and on the expiry day
/// itself (09:00, or earlier when Crate expires in the early morning). Refreshing means plugging the iPhone into the
/// Mac and pressing ⌘R in Xcode. Installed from `AppModel.init`; rescheduled whenever the app becomes active, so a
/// fresh install picks up the new expiry.
@MainActor
final class RefreshNotifications: NSObject, UNUserNotificationCenterDelegate {
    static let shared = RefreshNotifications()

    nonisolated static let dayBeforeID = "crate.signing.expires-tomorrow"
    nonisolated static let expiryDayID = "crate.signing.expires-today"
    /// Scheduled by older builds; removed on reschedule.
    nonisolated static let legacyIDs = ["crate.signing.expires-in-1h"]

    /// What to do, in the notification body and on the Library card.
    nonisolated static let howTo = "Plug your iPhone into the Mac and press ⌘R in Xcode."

    private var center: UNUserNotificationCenter { .current() }
    private var observer: NSObjectProtocol?

    /// Becomes the notification delegate and reschedules the reminders. Call once at launch.
    func install() {
        center.delegate = self
        observer = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification,
                                                          object: nil, queue: .main) { _ in
            Task { @MainActor in await RefreshNotifications.shared.reschedule() }
        }
    }

    /// Whether reminders can be delivered.
    func isAuthorized() async -> Bool {
        let status = await center.notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional || status == .ephemeral
    }

    /// Asks for permission (the Library card's "Turn on reminders"), then schedules the reminders.
    func requestPermission() async -> Bool {
        if await center.notificationSettings().authorizationStatus == .notDetermined {
            _ = try? await center.requestAuthorization(options: [.alert, .sound])
        }
        await reschedule()
        return await isAuthorized()
    }

    /// Replaces the pending reminders for the current signing expiry (fixed identifiers, so nothing piles up).
    func reschedule(now: Date = Date()) async {
        center.removePendingNotificationRequests(withIdentifiers: [Self.dayBeforeID, Self.expiryDayID] + Self.legacyIDs)
        guard await isAuthorized(), let expiry = try? SigningExpiry.fromBundle() else { return }
        let time = expiry.expirationDate.formatted(date: .omitted, time: .shortened)
        let reminders = [
            (Self.dayBeforeID, expiry.reminderDate, "Crate expires tomorrow at \(time)"),
            (Self.expiryDayID, expiry.expiryDayReminderDate(), "Crate expires today at \(time)"),
        ]
        for (id, date, title) in reminders where date > now {
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = Self.howTo
            content.sound = .default
            let parts = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
            let request = UNNotificationRequest(identifier: id, content: content,
                                                trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false))
            try? await center.add(request)
        }
    }

    // MARK: UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}
