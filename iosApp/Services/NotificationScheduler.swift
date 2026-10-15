import Combine
import Foundation
import StreberAlarmCore
import UserNotifications

@MainActor protocol ReminderNotificationCenter {
    func authorizationStatus() async -> UNAuthorizationStatus
    func requestAuthorization() async throws -> Bool
    func pendingRequests() async -> [UNNotificationRequest]
    func deliveredIdentifiers() async -> Set<String>
    func removePendingRequests(withIdentifiers identifiers: [String])
    func add(_ request: UNNotificationRequest) async throws
}

@MainActor final class SystemReminderNotificationCenter: ReminderNotificationCenter {
    private let center = UNUserNotificationCenter.current()

    func authorizationStatus() async -> UNAuthorizationStatus {
        await withCheckedContinuation { continuation in
            center.getNotificationSettings { continuation.resume(returning: $0.authorizationStatus) }
        }
    }

    func requestAuthorization() async throws -> Bool {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Bool, Error>) in
            center.requestAuthorization(options: [.alert, .badge, .sound]) { allowed, error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: allowed) }
            }
        }
    }

    func pendingRequests() async -> [UNNotificationRequest] {
        await withCheckedContinuation { continuation in
            center.getPendingNotificationRequests { continuation.resume(returning: $0) }
        }
    }

    func deliveredIdentifiers() async -> Set<String> {
        await withCheckedContinuation { continuation in
            center.getDeliveredNotifications { notifications in
                continuation.resume(returning: Set(notifications.map { $0.request.identifier }))
            }
        }
    }

    func removePendingRequests(withIdentifiers identifiers: [String]) {
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
    }

    func add(_ request: UNNotificationRequest) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            center.add(request) { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume(returning: ()) }
            }
        }
    }
}

@MainActor final class NotificationScheduler: ObservableObject {
    private struct Notice: Decodable {
        let key: String
        let examId: String
        let text: String
        let atMillis: Int64
    }

    private struct EstimateNotice: Decodable {
        let key: String
        let examId: String
        let atMillis: Int64
        let overdue: Bool
    }

    private struct PlannedRequest {
        let request: UNNotificationRequest
        let atMillis: Int64
        let overdue: Bool
    }

    private static let identifierPrefix = "streberalarm.reminder."
    private static let estimatePrefix = "streberalarm.estimate."
    private static let pendingLimit = 64
    private static let scheduledHistoryKey = "streberalarm.estimate.scheduled"
    private static let deliveredHistoryKey = "streberalarm.estimate.delivered"

    @Published private(set) var authorizationStatus: UNAuthorizationStatus = .notDetermined
    @Published private(set) var schedulingError: String?

    private let center: ReminderNotificationCenter
    private let plan: @MainActor (String, Int64, String) throws -> String
    private let estimatePlan: @MainActor (String, Int64, String) throws -> String
    private let now: @MainActor () -> Date
    private let zoneID: @MainActor () -> String
    private let history: UserDefaults
    private var pendingRefresh: Task<Void, Never>?
    private var refreshNumber = 0

    init(
        center: ReminderNotificationCenter? = nil,
        plan: @escaping @MainActor (String, Int64, String) throws -> String = {
            try IosBridge().notificationPlan(json: $0, nowMillis: $1, zoneId: $2)
        },
        estimatePlan: @escaping @MainActor (String, Int64, String) throws -> String = {
            try IosBridge().estimatePromptPlan(json: $0, nowMillis: $1, zoneId: $2)
        },
        now: @escaping @MainActor () -> Date = Date.init,
        zoneID: @escaping @MainActor () -> String = { TimeZone.current.identifier },
        history: UserDefaults = .standard
    ) {
        self.center = center ?? SystemReminderNotificationCenter()
        self.plan = plan
        self.estimatePlan = estimatePlan
        self.now = now
        self.zoneID = zoneID
        self.history = history
    }

    func requestAuthorization() async -> Bool {
        do {
            let allowed = try await center.requestAuthorization()
            authorizationStatus = await center.authorizationStatus()
            schedulingError = nil
            return allowed
        } catch {
            authorizationStatus = await center.authorizationStatus()
            schedulingError = "Mitteilungen konnten nicht freigegeben werden: \(error.localizedDescription)"
            return false
        }
    }

    func refresh(dataJSON: Data) async {
        refreshNumber += 1
        let number = refreshNumber
        let previous = pendingRefresh
        let work = Task { @MainActor in
            if let previous { await previous.value }
            await applyRefresh(dataJSON: dataJSON)
        }
        pendingRefresh = work
        await work.value
        if refreshNumber == number { pendingRefresh = nil }
    }

    private func applyRefresh(dataJSON: Data) async {
        var scheduled = history.dictionary(forKey: Self.scheduledHistoryKey)?
            .compactMapValues { ($0 as? NSNumber)?.int64Value } ?? [:]
        var delivered = Set(history.stringArray(forKey: Self.deliveredHistoryKey) ?? [])
        defer {
            history.set(scheduled, forKey: Self.scheduledHistoryKey)
            history.set(Array(delivered), forKey: Self.deliveredHistoryKey)
        }
        do {
            guard let json = String(data: dataJSON, encoding: .utf8) else {
                throw SchedulerError.invalidEncoding
            }
            let existing = await center.pendingRequests()
            let pendingIdentifiers = Set(existing.map(\.identifier))
            let ownIdentifiers = existing.map(\.identifier).filter {
                $0.hasPrefix(Self.identifierPrefix) || $0.hasPrefix(Self.estimatePrefix)
            }
            authorizationStatus = await center.authorizationStatus()

            guard Self.canSchedule(authorizationStatus) else {
                center.removePendingRequests(withIdentifiers: ownIdentifiers)
                scheduled.removeAll()
                schedulingError = nil
                return
            }

            let timestamp = Int64(now().timeIntervalSince1970 * 1_000)
            let deliveredNow = await center.deliveredIdentifiers()
            delivered.formUnion(deliveredNow.filter { $0.hasPrefix(Self.estimatePrefix) })
            for (identifier, atMillis) in scheduled where !pendingIdentifiers.contains(identifier) {
                if atMillis <= timestamp || deliveredNow.contains(identifier) {
                    delivered.insert(identifier)
                }
                scheduled.removeValue(forKey: identifier)
            }
            let currentZone = zoneID()
            let notices = try JSONDecoder().decode([Notice].self,
                from: Data(plan(json, timestamp, currentZone).utf8))
            let estimates = try JSONDecoder().decode([EstimateNotice].self,
                from: Data(estimatePlan(json, timestamp, currentZone).utf8))
            let activeEstimateIDs = Set(estimates.map { Self.estimatePrefix + $0.key })
            scheduled = scheduled.filter { activeEstimateIDs.contains($0.key) }
            let otherCount = existing.count - ownIdentifiers.count
            let available = max(0, Self.pendingLimit - otherCount)
            var plans: [PlannedRequest] = []
            var usedEstimateKeys = Set<String>()
            for estimate in estimates {
                guard estimate.atMillis > timestamp,
                      usedEstimateKeys.insert(estimate.key).inserted else { continue }
                let request = makeEstimateRequest(estimate, nowMillis: timestamp, zoneID: currentZone)
                if delivered.contains(request.identifier) { continue }
                plans.append(PlannedRequest(request: request, atMillis: estimate.atMillis,
                                            overdue: estimate.overdue))
            }
            var usedKeys = Set<String>()
            for notice in notices where notice.atMillis > timestamp {
                guard usedKeys.insert(notice.key).inserted else { continue }
                let content = UNMutableNotificationContent()
                content.title = "Dein Lernplan für heute"
                content.body = notice.text
                content.sound = .default
                content.userInfo = ["key": notice.key, "examId": notice.examId]
                let date = Date(timeIntervalSince1970: Double(notice.atMillis) / 1_000)
                var calendar = Calendar.current
                calendar.timeZone = TimeZone(identifier: currentZone) ?? .current
                var components = calendar.dateComponents(
                    [.year, .month, .day, .hour, .minute, .second], from: date)
                components.timeZone = calendar.timeZone
                let trigger = UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
                let request = UNNotificationRequest(identifier: Self.identifierPrefix + notice.key,
                                                    content: content, trigger: trigger)
                plans.append(PlannedRequest(request: request, atMillis: notice.atMillis,
                                            overdue: false))
            }
            let selected = plans.sorted {
                $0.atMillis == $1.atMillis
                    ? $0.request.identifier < $1.request.identifier
                    : $0.atMillis < $1.atMillis
            }.prefix(available)
            let targetIdentifiers = Set(selected.map { $0.request.identifier })
            let obsolete = ownIdentifiers.filter {
                !targetIdentifiers.contains($0)
            }
            center.removePendingRequests(withIdentifiers: obsolete)
            for identifier in obsolete { scheduled.removeValue(forKey: identifier) }
            for item in selected {
                let identifier = item.request.identifier
                if item.overdue && pendingIdentifiers.contains(identifier) {
                    if scheduled[identifier] == nil { scheduled[identifier] = item.atMillis }
                    continue
                }
                try await center.add(item.request)
                if identifier.hasPrefix(Self.estimatePrefix) {
                    scheduled[identifier] = item.atMillis
                }
            }
            schedulingError = nil
        } catch {
            schedulingError = "Erinnerungen konnten nicht geplant werden: \(error.localizedDescription)"
        }
    }

    private static func canSchedule(_ status: UNAuthorizationStatus) -> Bool {
        status == .authorized || status == .provisional || status == .ephemeral
    }

    private func makeEstimateRequest(_ estimate: EstimateNotice, nowMillis: Int64,
                                     zoneID: String) -> UNNotificationRequest {
        let date = Date(timeIntervalSince1970: Double(estimate.atMillis) / 1_000)
        let content = UNMutableNotificationContent()
        content.title = "Welche Note erwartest du?"
        content.body = "Trage deine gefühlte Note für die Prüfung ein."
        content.sound = .default
        content.userInfo = ["type": "estimate", "key": estimate.key, "examId": estimate.examId]
        var calendar = Calendar.current
        calendar.timeZone = TimeZone(identifier: zoneID) ?? .current
        var components = calendar.dateComponents(
            [.year, .month, .day, .hour, .minute, .second], from: date)
        components.timeZone = calendar.timeZone
        let trigger: UNNotificationTrigger = estimate.overdue
            ? UNTimeIntervalNotificationTrigger(
                timeInterval: max(1, Double(estimate.atMillis - nowMillis) / 1_000), repeats: false)
            : UNCalendarNotificationTrigger(dateMatching: components, repeats: false)
        return UNNotificationRequest(identifier: Self.estimatePrefix + estimate.key,
                                     content: content, trigger: trigger)
    }

    private enum SchedulerError: LocalizedError {
        case invalidEncoding
        var errorDescription: String? { "Die Schuldaten sind kein gültiger UTF-8-Text." }
    }
}
