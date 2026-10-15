import Foundation
import UserNotifications
import XCTest
@testable import NotenManager

@MainActor final class NotificationSchedulerTests: XCTestCase {
    func testRefreshSchedulesFutureNoticesInOrderAndCapsPendingRequests() async throws {
        let center = FakeReminderNotificationCenter()
        center.requests = (0..<2).map { index in
            UNNotificationRequest(identifier: "other.\(index)",
                                  content: UNMutableNotificationContent(), trigger: nil)
        }
        let clock = Date(timeIntervalSince1970: 1_800_000_000)
        let notices = (0..<70).reversed().map { index in
            notice("key-\(index)", at: 1_800_001_000_000 + Int64(index) * 60_000)
        } + [notice("expired", at: 1_799_999_000_000)]
        let scheduler = NotificationScheduler(center: center,
            plan: { _, _, _ in try self.json(notices) }, estimatePlan: { _, _, _ in "[]" },
            now: { clock }, zoneID: { "Europe/Berlin" })

        await scheduler.refresh(dataJSON: Data("{}".utf8))

        XCTAssertEqual(center.requests.count, 64)
        XCTAssertEqual(center.requests.filter { $0.identifier.hasPrefix("other.") }.count, 2)
        XCTAssertEqual(center.added.count, 62)
        XCTAssertEqual(center.added.first?.identifier, "streberalarm.reminder.key-0")
        XCTAssertEqual(center.added.last?.identifier, "streberalarm.reminder.key-61")
        XCTAssertEqual(center.added.first?.content.userInfo["examId"] as? String, "exam-key-0")
        XCTAssertEqual(center.added.first?.content.body, "Text key-0")
        XCTAssertNil(scheduler.schedulingError)
    }

    func testRefreshCancelsDeletedAndReschedulesAfterTimeZoneChange() async throws {
        let center = FakeReminderNotificationCenter()
        let clock = Date(timeIntervalSince1970: 1_800_000_000)
        var zone = "Europe/Berlin"
        var planned = [notice("exam-a", at: 1_800_100_000_000)]
        var plannedZones: [String] = []
        let scheduler = NotificationScheduler(center: center,
            plan: { _, _, zoneID in
                plannedZones.append(zoneID)
                return try self.json(planned)
            }, estimatePlan: { _, _, _ in "[]" }, now: { clock }, zoneID: { zone })

        await scheduler.refresh(dataJSON: Data("{}".utf8))
        XCTAssertEqual(center.requests.map(\.identifier), ["streberalarm.reminder.exam-a"])

        zone = "America/New_York"
        planned = [notice("exam-b", at: 1_800_110_000_000)]
        await scheduler.refresh(dataJSON: Data("{}".utf8))

        XCTAssertEqual(plannedZones, ["Europe/Berlin", "America/New_York"])
        XCTAssertEqual(center.removed.last, ["streberalarm.reminder.exam-a"])
        XCTAssertEqual(center.requests.map(\.identifier), ["streberalarm.reminder.exam-b"])
    }

    func testDeniedPermissionCancelsPendingAndDoesNotRequestAuthorization() async throws {
        let center = FakeReminderNotificationCenter()
        center.status = .denied
        center.requests = [UNNotificationRequest(identifier: "streberalarm.reminder.old",
            content: UNMutableNotificationContent(), trigger: nil)]
        let scheduler = NotificationScheduler(center: center,
            plan: { _, _, _ in try self.json([self.notice("new", at: 1_800_100_000_000)]) },
            estimatePlan: { _, _, _ in "[]" },
            now: { Date(timeIntervalSince1970: 1_800_000_000) })

        await scheduler.refresh(dataJSON: Data("{}".utf8))

        XCTAssertEqual(scheduler.authorizationStatus, .denied)
        XCTAssertTrue(center.requests.isEmpty)
        XCTAssertTrue(center.added.isEmpty)
        XCTAssertEqual(center.authorizationRequests, 0)
        XCTAssertNil(scheduler.schedulingError)
    }

    func testExplicitAuthorizationAllowsScheduling() async throws {
        let center = FakeReminderNotificationCenter()
        center.status = .notDetermined
        let scheduler = NotificationScheduler(center: center,
            plan: { _, _, _ in try self.json([self.notice("new", at: 1_800_100_000_000)]) },
            estimatePlan: { _, _, _ in "[]" },
            now: { Date(timeIntervalSince1970: 1_800_000_000) })

        await scheduler.refresh(dataJSON: Data("{}".utf8))
        XCTAssertTrue(center.added.isEmpty)
        XCTAssertEqual(center.authorizationRequests, 0)

        let permitted = await scheduler.requestAuthorization()
        XCTAssertTrue(permitted)
        await scheduler.refresh(dataJSON: Data("{}".utf8))
        XCTAssertEqual(center.authorizationRequests, 1)
        XCTAssertEqual(center.added.map(\.identifier), ["streberalarm.reminder.new"])
    }

    func testEstimateNotificationsCoverMondayAndWednesdayAndOverdueUsesFutureTrigger() async throws {
        let center = FakeReminderNotificationCenter()
        let domain = "NotificationSchedulerTests.\(UUID().uuidString)"
        let history = try XCTUnwrap(UserDefaults(suiteName: domain))
        defer { history.removePersistentDomain(forName: domain) }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = try XCTUnwrap(TimeZone(identifier: "UTC"))
        let monday = try XCTUnwrap(utc.nextDate(after: Date(),
            matching: DateComponents(hour: 16, minute: 0, second: 0, weekday: 2),
            matchingPolicy: .nextTime))
        let wednesday = try XCTUnwrap(utc.date(byAdding: .day, value: 2, to: monday))
        let mondayDate = dateString(monday)
        let wednesdayDate = dateString(wednesday)
        var clock = monday.addingTimeInterval(-86_400)
        var planned = [
            estimate("a", date: mondayDate, at: millis(monday), overdue: false),
            estimate("b", date: wednesdayDate, at: millis(wednesday), overdue: false)
        ]
        let scheduler = NotificationScheduler(center: center,
            plan: { _, _, _ in "[]" }, estimatePlan: { _, _, _ in try self.json(planned) },
            now: { clock }, zoneID: { "UTC" }, history: history)

        await scheduler.refresh(dataJSON: Data("{}".utf8))
        XCTAssertEqual(center.requests.map(\.identifier),
            ["streberalarm.estimate.estimate:a:\(mondayDate)",
             "streberalarm.estimate.estimate:b:\(wednesdayDate)"])
        XCTAssertEqual((center.requests[0].trigger as? UNCalendarNotificationTrigger)?.dateComponents.hour, 16)
        XCTAssertEqual((center.requests[1].trigger as? UNCalendarNotificationTrigger)?.dateComponents.hour, 16)
        let mondayCalendarTrigger = try XCTUnwrap(center.requests[0].trigger as? UNCalendarNotificationTrigger)
        let mondayTrigger = try XCTUnwrap(mondayCalendarTrigger.nextTriggerDate())
        XCTAssertEqual(mondayTrigger.timeIntervalSince1970, monday.timeIntervalSince1970, accuracy: 2)

        // One second before 16:00 the still-future request remains pending.
        clock = monday.addingTimeInterval(-1)
        await scheduler.refresh(dataJSON: Data("{}".utf8))
        XCTAssertEqual(center.requests.count, 2)

        // Monday's alert has fired while the app was closed; Wednesday remains scheduled.
        center.requests.removeAll { $0.identifier.contains("estimate:a:") }
        clock = monday.addingTimeInterval(10)
        planned[0] = estimate("a", date: mondayDate, at: millis(clock) + 1_000, overdue: true)
        await scheduler.refresh(dataJSON: Data("{}".utf8))
        XCTAssertEqual(center.requests.count, 1)
        XCTAssertEqual(center.requests.first?.identifier,
            "streberalarm.estimate.estimate:b:\(wednesdayDate)")

        // A newly due candidate receives a trigger relative to now, never a past calendar time.
        planned.append(estimate("c", date: mondayDate, at: millis(clock) + 1_000, overdue: true))
        await scheduler.refresh(dataJSON: Data("{}".utf8))
        let overdue = try XCTUnwrap(center.requests.first { $0.identifier.contains("estimate:c:") })
        let trigger = try XCTUnwrap(overdue.trigger as? UNTimeIntervalNotificationTrigger)
        let nextTriggerDate = try XCTUnwrap(trigger.nextTriggerDate())
        XCTAssertGreaterThan(nextTriggerDate, Date())
        XCTAssertLessThan(nextTriggerDate, Date().addingTimeInterval(3))

        // Recording or skipping the estimates removes their pending notifications.
        planned = []
        await scheduler.refresh(dataJSON: Data("{}".utf8))
        XCTAssertTrue(center.requests.isEmpty)
    }

    func testOverdueEstimateSurvivesRepeatedRefreshUntilDeliveryAndProfileSwitchClearsPending() async throws {
        let center = FakeReminderNotificationCenter()
        let domain = "NotificationSchedulerTests.\(UUID().uuidString)"
        let history = try XCTUnwrap(UserDefaults(suiteName: domain))
        defer { history.removePersistentDomain(forName: domain) }
        var clock = Date(timeIntervalSince1970: 1_800_000_000)
        var active = true
        let scheduler = NotificationScheduler(center: center,
            plan: { _, _, _ in "[]" },
            estimatePlan: { _, nowMillis, _ in
                try self.json(active ? [self.estimate("a", date: "2027-01-01",
                    at: nowMillis + 1_000, overdue: true)] : [])
            }, now: { clock }, zoneID: { "UTC" }, history: history)

        await scheduler.refresh(dataJSON: Data("{}".utf8))
        await scheduler.refresh(dataJSON: Data("{}".utf8))
        XCTAssertEqual(center.requests.map(\.identifier),
            ["streberalarm.estimate.estimate:a:2027-01-01"])
        XCTAssertEqual(center.added.count, 1)

        active = false // another profile has no such exam
        await scheduler.refresh(dataJSON: Data("{}".utf8))
        XCTAssertTrue(center.requests.isEmpty)
        active = true
        await scheduler.refresh(dataJSON: Data("{}".utf8))
        XCTAssertEqual(center.requests.count, 1)
        XCTAssertEqual(center.added.count, 2)

        center.requests.removeAll() // notification was delivered while the app was closed
        clock.addTimeInterval(2)
        await scheduler.refresh(dataJSON: Data("{}".utf8))
        XCTAssertTrue(center.requests.isEmpty)
        XCTAssertEqual(center.added.count, 2)
    }

    func testPendingLimitKeepsTodaysStudyAheadOf64LaterEstimates() async throws {
        let center = FakeReminderNotificationCenter()
        let clock = Date(timeIntervalSince1970: 1_800_000_000)
        let later = Int64(clock.timeIntervalSince1970 * 1_000) + 7 * 86_400_000
        let estimates = (0..<64).map { index in
            estimate("exam-\(index)", date: "2027-02-01",
                     at: later + Int64(index) * 60_000, overdue: false)
        }
        let study = notice("study-today", at: Int64(clock.timeIntervalSince1970 * 1_000) + 3_600_000)
        let scheduler = NotificationScheduler(center: center,
            plan: { _, _, _ in try self.json([study]) },
            estimatePlan: { _, _, _ in try self.json(estimates) },
            now: { clock }, zoneID: { "UTC" })

        await scheduler.refresh(dataJSON: Data("{}".utf8))

        XCTAssertEqual(center.requests.count, 64)
        XCTAssertEqual(center.added.first?.identifier, "streberalarm.reminder.study-today")
        XCTAssertEqual(center.requests.filter { $0.identifier.hasPrefix("streberalarm.estimate.") }.count, 63)
        XCTAssertFalse(center.requests.contains { $0.identifier == "streberalarm.estimate.estimate:exam-63:2027-02-01" })
    }

    private func notice(_ key: String, at: Int64) -> [String: Any] {
        ["key": key, "examId": "exam-\(key)", "text": "Text \(key)", "atMillis": at]
    }

    private func estimate(_ examId: String, date: String, at: Int64, overdue: Bool) -> [String: Any] {
        ["key": "estimate:\(examId):\(date)",
         "examId": examId, "atMillis": at, "overdue": overdue]
    }

    private func millis(_ date: Date) -> Int64 { Int64(date.timeIntervalSince1970 * 1_000) }

    private func dateString(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }

    private func json(_ notices: [[String: Any]]) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: notices), as: UTF8.self)
    }
}

@MainActor private final class FakeReminderNotificationCenter: ReminderNotificationCenter {
    var status: UNAuthorizationStatus = .authorized
    var requests: [UNNotificationRequest] = []
    var delivered: Set<String> = []
    var added: [UNNotificationRequest] = []
    var removed: [[String]] = []
    var authorizationRequests = 0

    func authorizationStatus() async -> UNAuthorizationStatus { status }

    func requestAuthorization() async throws -> Bool {
        authorizationRequests += 1
        status = .authorized
        return true
    }

    func pendingRequests() async -> [UNNotificationRequest] { requests }

    func deliveredIdentifiers() async -> Set<String> { delivered }

    func removePendingRequests(withIdentifiers identifiers: [String]) {
        removed.append(identifiers)
        requests.removeAll { identifiers.contains($0.identifier) }
    }

    func add(_ request: UNNotificationRequest) async throws {
        added.append(request)
        requests.removeAll { $0.identifier == request.identifier }
        requests.append(request)
    }
}

final class SchoolDateLocaleTests: XCTestCase {
    func testPersistedDatesAndSchoolYearUseGregorianCalendar() throws {
        var buddhist = Calendar(identifier: .buddhist)
        buddhist.timeZone = TimeZone(secondsFromGMT: 0)!
        let date = try XCTUnwrap(buddhist.date(from: DateComponents(year: 2569, month: 10, day: 3, hour: 12)))

        let day = SchoolDates.formatter("yyyy-MM-dd")
        day.timeZone = buddhist.timeZone
        XCTAssertEqual(day.string(from: date), "2026-10-03")
        XCTAssertEqual(day.date(from: "2026-10-03").map { day.string(from: $0) }, "2026-10-03")

        let timestamp = SchoolDates.formatter("yyyy-MM-dd'T'HH:mm:ss")
        timestamp.timeZone = buddhist.timeZone
        XCTAssertEqual(timestamp.string(from: date), "2026-10-03T12:00:00")
        XCTAssertEqual(SchoolDates.currentYear(at: date), 2026)
        XCTAssertEqual(SchoolDates.yearLabel(2026), "2026/2027")
    }
}

final class GermanAverageTests: XCTestCase {
    func testAverageUsesGermanDecimalCommaAndTwoDigits() {
        XCTAssertEqual(germanAverage(1.78), "1,78")
        XCTAssertEqual(germanAverage(2), "2,00")
    }
}
