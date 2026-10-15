import Foundation
import StreberAlarmCore
import Darwin
import UIKit

enum SchoolDates {
    static func formatter(_ format: String) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = format
        return formatter
    }

    static func currentYear(at date: Date = Date()) -> Int {
        Calendar(identifier: .gregorian).component(.year, from: date)
    }

    static func yearLabel(_ year: Int) -> String {
        "\(String(year))/\(String(year + 1))"
    }
}

struct SchoolSnapshot: Decodable {
    let profiles: [SchoolProfile]
    let activeProfileId: String?
    let subjects: [SchoolSubject]
    let assessments: [SchoolAssessment]
    let officials: [SchoolOfficial]
    let timetables: [SchoolTimetable]
    let daysOff: [SchoolDayOff]
    let exceptions: [SchoolException]
    let studies: [SchoolStudy]
    let documents: [SchoolDocument]

    var activeProfile: SchoolProfile? { profiles.first { $0.id == activeProfileId } }
    var activeSubjects: [SchoolSubject] { subjects.filter { $0.profileId == activeProfileId } }
    var activeAssessments: [SchoolAssessment] {
        let ids = Set(activeSubjects.map(\.id))
        return assessments.filter { ids.contains($0.subjectId) }
    }
    var latestTimetable: SchoolTimetable? {
        timetables.filter { $0.profileId == activeProfileId }.max { $0.validFrom < $1.validFrom }
    }
}

struct SchoolProfile: Decodable, Identifiable {
    let id: String
    let state: String
    let school: String
    let year: Int
    let grade: Int
    let track: String
    let term: String
}

struct SchoolSubject: Decodable, Identifiable {
    let id: String
    let profileId: String
    let name: String
    let core: Bool
    let promotion: Bool
    let annualSchoolworks: Int?
    let weightsConfirmed: Bool
    let advanced: Bool
    let examSubject: Bool
    let smallWrittenWeight: Double
    let oralWeight: Double
    let practicalWeight: Double
}

struct SchoolAssessment: Decodable, Identifiable {
    let id: String
    let subjectId: String
    let title: String
    let date: String
    let kind: String
    let stage: String
    let notes: String
    let material: String
    let weight: Double
    let estimate: Int?
    let actual: Int?
    var learningNotes: String { [material, notes].filter { !$0.isEmpty }.joined(separator: "\n\n") }
}

struct SchoolLesson: Codable, Identifiable {
    let id: String
    let subjectId: String
    let day: Int
    let start: String
    let end: String
    let week: Int
    let isBreak: Bool
    let slot: Int
}

struct SchoolTimetable: Decodable, Identifiable {
    let id: String
    let profileId: String
    let validFrom: String
    let anchorMonday: String
    let blocks: [SchoolLesson]
}

struct SchoolDayOff: Decodable, Identifiable {
    let id: String
    let profileId: String
    let from: String
    let to: String
    let reason: String
}

struct SchoolException: Decodable, Identifiable {
    let id: String
    let subjectId: String
    let date: String
    let start: String
    let end: String
    let cancelled: Bool
}

struct SchoolStudy: Decodable, Identifiable {
    let id: String
    let assessmentId: String
    let start: Int64
    let end: Int64?
    let correctedMinutes: Int?
    let correction: String

    func seconds(at date: Date) -> Int64 {
        if let correctedMinutes { return Int64(correctedMinutes) * 60 }
        return max(0, ((end ?? Int64(date.timeIntervalSince1970 * 1000)) - start) / 1000)
    }
}

struct SchoolDocument: Decodable, Identifiable {
    let id: String
    let assessmentId: String
    let name: String
    let mime: String
    let file: String
    let size: Int64
}

struct SchoolDayLesson: Decodable, Identifiable {
    let id: String
    let subjectId: String
    let subjectName: String
    let start: String
    let end: String
    let isBreak: Bool
}

struct SchoolEstimatePrompt: Decodable, Identifiable {
    let id: String
    let title: String
    let date: String
    let key: String
}

struct SchoolOfficial: Decodable, Identifiable {
    let id: String
    let subjectId: String
    let period: String
    let value: Int?
    let omitted: Bool
}

struct SchoolTransferOverview: Decodable {
    let average: Double?
    let values: [Double?]
    let official: Bool
    let gymnasium: String
    let realschule: String
    let supported: Bool
    let duplicateSubjects: Bool
}

struct SchoolTransferProbeGrades: Decodable {
    let german: Int?
    let math: Int?
}

struct SchoolQualiField: Decodable, Identifiable {
    let key: String
    let weight: Int
    var id: String { key }
}

struct SchoolRuleResult: Decodable {
    let verdict: String
    let title: String
    let details: [String]
    let sources: [String]
    let value: String?
}

struct SchoolAbiturCourse: Codable {
    var name: String
    var area: Int
    var group: String
    var points: [Int?]
    var included: [Bool]
    var requiredTerms: Int
    var advanced: Bool
}

struct SchoolAbiturExam: Codable {
    var name: String
    var written: Bool
    var points: Int?
    var oralAddition: Int?
    var practical: Int?
}

struct SchoolGraduationInput: Codable {
    var procedure: String
    var numbers: [String: Int?] = [:]
    var confirmations: [String: Bool?] = [:]
    var courses: [SchoolAbiturCourse] = []
    var exams: [SchoolAbiturExam] = []
}

struct GradeSummary: Decodable {
    struct Standing: Decodable, Identifiable {
        let subjectId: String
        let name: String
        let average: Double?
        let grade: Int?
        let annual: Bool
        var id: String { subjectId }
    }
    let title: String
    let basis: String
    let reason: String
    let advice: String
    let light: String
    let average: Double?
    let averageCount: Int
    let subjectCount: Int
    let standings: [Standing]
}

@MainActor final class SchoolStore: ObservableObject {
    struct DocumentImportToken: Equatable {
        fileprivate let owner: UUID
        fileprivate let generation: UInt64
        fileprivate let demo: Bool
        fileprivate let profileID: String
        fileprivate let assessmentID: String
    }

    @Published private(set) var snapshot: SchoolSnapshot
    @Published private(set) var summary: GradeSummary?
    @Published private(set) var dueEstimates: [SchoolEstimatePrompt] = []
    @Published private(set) var revision = 0
    @Published private(set) var needsRecovery = false
    @Published private(set) var isDemo = false
    @Published var errorMessage: String?

    private let bridge = IosBridge()
    private let owner = UUID()
    private let realFileURL: URL
    private let selectorURL: URL
    private var fileURL: URL
    private var generation: UInt64 = 0
    private var json: String

    init(fileURL overrideFileURL: URL? = nil) {
        var folder = overrideFileURL?.deletingLastPathComponent() ??
            FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("NotenManager", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-uiTestingReset") {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent("SchoolData.json"))
            try? FileManager.default.removeItem(at: folder.appendingPathComponent("Demo"))
            try? FileManager.default.removeItem(at: folder.appendingPathComponent("Workspace.txt"))
        }
        if ProcessInfo.processInfo.arguments.contains("-uiTestingCorruptSchoolData") {
            try? Data([0xff, 0xfe, 0x00]).write(
                to: folder.appendingPathComponent("SchoolData.json"), options: .atomic)
        }
        #endif
        var resource = URLResourceValues()
        resource.isExcludedFromBackup = true
        try? folder.setResourceValues(resource)
        realFileURL = overrideFileURL ?? folder.appendingPathComponent("SchoolData.json")
        selectorURL = folder.appendingPathComponent("Workspace.txt")
        let selectedDemo = (try? String(contentsOf: selectorURL, encoding: .utf8)) == "demo"
        isDemo = selectedDemo
        fileURL = selectedDemo ? folder.appendingPathComponent("Demo/SchoolData.json") : realFileURL
        let savedFileExists = FileManager.default.fileExists(atPath: fileURL.path)
        let savedJSON = try? String(contentsOf: fileURL, encoding: .utf8)
        let initial = savedJSON ?? bridge.emptyData()
        let normalized = try? bridge.normalizeData(json: initial)
        json = normalized ?? initial
        let decoded = try? Self.decodeSnapshot(json)
        snapshot = decoded ?? Self.emptySnapshot()
        refreshSummary()
        refreshDueEstimates()
        needsRecovery = savedFileExists && (savedJSON == nil || normalized == nil || decoded == nil)
        if needsRecovery {
            errorMessage = "Gespeicherte Daten konnten nicht geladen werden. Die Datei wurde nicht verändert."
        }
    }

    var documentsDirectory: URL { fileURL.deletingLastPathComponent().appendingPathComponent("Documents", isDirectory: true) }
    var dataJSON: Data { Data(json.utf8) }
    var notificationJSON: Data {
        guard isDemo else { return dataJSON }
        return (try? Data(contentsOf: realFileURL)) ?? Data(bridge.emptyData().utf8)
    }

    func documentToken(for assessmentID: String) -> DocumentImportToken? {
        guard let profileID = snapshot.activeProfileId,
              snapshot.activeAssessments.contains(where: { $0.id == assessmentID }) else { return nil }
        return DocumentImportToken(owner: owner, generation: generation, demo: isDemo,
                                   profileID: profileID, assessmentID: assessmentID)
    }

    func acceptsDocumentToken(_ token: DocumentImportToken) -> Bool {
        token.owner == owner && token.generation == generation && token.demo == isDemo &&
            token.profileID == snapshot.activeProfileId &&
            snapshot.activeAssessments.contains(where: { $0.id == token.assessmentID })
    }

    func loadDemo() throws {
        if !FileManager.default.fileExists(atPath: demoFileURL.path) {
            try seedDemo()
        } else {
            do {
                let saved = try String(contentsOf: demoFileURL, encoding: .utf8)
                _ = try bridge.normalizeData(json: saved)
                let decoded = try Self.decodeSnapshot(saved)
                guard decoded.documents.allSatisfy({ FileManager.default.fileExists(atPath:
                    demoDirectory.appendingPathComponent("Documents/\($0.file)").path) }) else {
                    throw StoreError.failedLoad
                }
            } catch {
                guard !isDemo else { throw error }
                try FileManager.default.removeItem(at: demoDirectory)
                try seedDemo()
            }
        }
        if !isDemo { try switchWorkspace(toDemo: true) }
    }

    func returnToReal() throws {
        guard isDemo else { return }
        try switchWorkspace(toDemo: false)
    }

    func deleteDemo() throws {
        if isDemo { try switchWorkspace(toDemo: false) }
        if FileManager.default.fileExists(atPath: demoDirectory.path) {
            try FileManager.default.removeItem(at: demoDirectory)
        }
    }

    private var demoDirectory: URL { realFileURL.deletingLastPathComponent().appendingPathComponent("Demo", isDirectory: true) }
    private var demoFileURL: URL { demoDirectory.appendingPathComponent("SchoolData.json") }

    private func switchWorkspace(toDemo: Bool) throws {
        let target = toDemo ? demoFileURL : realFileURL
        let saved = try? String(contentsOf: target, encoding: .utf8)
        let initial = saved ?? bridge.emptyData()
        let normalized = try? bridge.normalizeData(json: initial)
        let decoded = try? Self.decodeSnapshot(normalized ?? initial)
        // The durable selector changes before the in-memory view. A crash at either side of
        // this boundary reopens a complete workspace without writing to the other workspace.
        try Data((toDemo ? "demo" : "real").utf8).write(to: selectorURL, options: .atomic)
        generation &+= 1
        isDemo = toDemo
        fileURL = target
        json = normalized ?? initial
        snapshot = decoded ?? Self.emptySnapshot()
        needsRecovery = FileManager.default.fileExists(atPath: target.path) &&
            (saved == nil || normalized == nil || decoded == nil)
        refreshSummary()
        refreshDueEstimates()
        revision += 1
    }

    private func seedDemo() throws {
        guard let math = NSDataAsset(name: "DemoMath")?.data,
              let english = NSDataAsset(name: "DemoEnglish")?.data else {
            throw StoreError.missingDemoResource
        }
        var fixture = try bridge.createProfile(json: bridge.emptyData(), state: "Bayern",
            school: "REALSCHULE", grade: 7, track: "I", year: 2026, term: "Ganzes Schuljahr")
        let profile = try Self.decodeSnapshot(fixture)
        func subject(_ name: String) throws -> String {
            guard let id = profile.activeSubjects.first(where: { $0.name == name })?.id else {
                throw StoreError.failedLoad
            }
            return id
        }
        let matrix = [
            ["Mathematik", "Mathematik", "Deutsch", "Englisch", "Geschichte", "Sport"],
            ["Englisch", "Biologie", "Biologie", "Deutsch", "Kunst", "Kunst"],
            ["Physik", "Physik", "Mathematik", "Geschichte", "Sport"],
            ["Deutsch", "Englisch", "Mathematik", "IT · Informationstechnologie", "IT · Informationstechnologie", "Musik"],
            ["Geographie", "Geographie", "Deutsch", "Englisch", "Ethik/Religion"]
        ]
        let times = [("08:00", "08:45"), ("08:45", "09:30"), ("09:50", "10:35"),
                     ("10:35", "11:20"), ("11:35", "12:20"), ("12:20", "13:05")]
        var blocks: [SchoolLesson] = []
        for (day, names) in matrix.enumerated() {
            for (slot, name) in names.enumerated() {
                blocks.append(SchoolLesson(id: UUID().uuidString.lowercased(), subjectId: try subject(name),
                    day: day + 1, start: times[slot].0, end: times[slot].1, week: 0,
                    isBreak: false, slot: slot + 1))
            }
        }
        fixture = try bridge.saveTimetable(json: fixture, timetableId: "", validFrom: "2026-09-15",
            anchorMonday: "2026-10-05", blocksJson: String(decoding: JSONEncoder().encode(blocks), as: UTF8.self))
        let examples: [(String, String, String, String, String, Int32, String)] = [
            ("Mathematik", "Brüche und Gleichungen", "2026-10-09", "SCHOOLWORK", "GRADED", 2, "Fiktive Beispielarbeit: 18 von 20 Punkten."),
            ("Englisch", "Simple Past", "2026-10-08", "ANNOUNCED_TEST", "GRADED", 1, "Fiktiver Beispieltest: 12 von 12 Punkten."),
            ("Deutsch", "Erörterung", "2026-09-21", "SCHOOLWORK", "GRADED", 3, ""),
            ("Biologie", "Zellen und Organe", "2026-09-26", "EX", "GRADED", 2, ""),
            ("Geschichte", "Industrialisierung", "2026-10-01", "ORAL", "GRADED", 2, ""),
            ("Physik", "Kräfte", "2026-10-05", "EX", "GRADED", 3, ""),
            ("Mathematik", "Lineare Funktionen", "2026-10-22", "SCHOOLWORK", "PLANNED", -1,
             "Geraden zeichnen, Steigung berechnen, Textaufgaben lösen."),
            ("Englisch", "Unit 3", "2026-10-30", "ANNOUNCED_TEST", "PLANNED", -1,
             "Vokabeln aus Unit 3 und Simple Past wiederholen.")
        ]
        for (name, title, date, kind, stage, grade, notes) in examples {
            fixture = try bridge.saveAssessment(json: fixture, assessmentId: "", subjectId: subject(name),
                title: title, date: date, kind: kind, stage: stage, notes: notes,
                actual: grade, estimate: -1, weight: 1)
        }
        let assessments = try Self.decodeSnapshot(fixture).activeAssessments
        let images: [(String, String, Data)] = [
            ("Brüche und Gleichungen", "Mathe · fiktiver Scan · 18/20.png", math),
            ("Simple Past", "Englisch · fiktiver Scan · 12/12.png", english)
        ]
        let staging = realFileURL.deletingLastPathComponent()
            .appendingPathComponent(".Demo-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: staging.appendingPathComponent("Documents"),
                                                withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: staging) }
        for (index, image) in images.enumerated() {
            guard let assessment = assessments.first(where: { $0.title == image.0 }) else {
                throw StoreError.failedLoad
            }
            let filename = String(format: "00000000-0000-0000-0000-%012d.png", index + 101)
            try image.2.write(to: staging.appendingPathComponent("Documents/\(filename)"), options: .atomic)
            fixture = try bridge.addDocument(json: fixture, assessmentId: assessment.id,
                name: image.1, mime: "image/png", file: filename, size: Int64(image.2.count))
        }
        fixture = try bridge.normalizeData(json: fixture)
        _ = try Self.decodeSnapshot(fixture)
        try Data(fixture.utf8).write(to: staging.appendingPathComponent("SchoolData.json"), options: .atomic)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var mutableStaging = staging; try mutableStaging.setResourceValues(values)
        if FileManager.default.fileExists(atPath: demoDirectory.path) {
            try FileManager.default.removeItem(at: demoDirectory)
        }
        try FileManager.default.moveItem(at: staging, to: demoDirectory)
    }

    @discardableResult
    func createProfile(state: String, school: String, grade: Int, track: String, year: Int, term: String) -> Bool {
        do {
            try persist(bridge.createProfile(json: json, state: state, school: school,
                grade: Self.checkedInt32(grade), track: track, year: Self.checkedInt32(year), term: term))
            errorMessage = nil
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func selectProfile(_ id: String) {
        guard id != snapshot.activeProfileId else { return }
        change { try bridge.selectProfile(json: $0, profileId: id) }
        generation &+= 1
    }

    func deleteProfile(_ profile: SchoolProfile) {
        let subjectIds = Set(snapshot.subjects.filter { $0.profileId == profile.id }.map(\.id))
        let assessmentIds = Set(snapshot.assessments.filter { subjectIds.contains($0.subjectId) }.map(\.id))
        let removedDocuments = snapshot.documents.filter { assessmentIds.contains($0.assessmentId) }
        do {
            try persist(bridge.deleteProfile(json: json, profileId: profile.id))
            for document in removedDocuments { try? FileManager.default.removeItem(at: documentURL(document)) }
            errorMessage = nil
        } catch { errorMessage = error.localizedDescription }
    }

    func addSubject(name: String, core: Bool, promotion: Bool, annualSchoolworks: Int?) {
        change { try bridge.addSubject(json: $0, name: name, core: core, promotion: promotion,
                                       annualSchoolworks: Self.checkedInt32(annualSchoolworks ?? -1)) }
    }

    func saveSubject(id: String, name: String, core: Bool, promotion: Bool,
                     annualSchoolworks: Int?, advanced: Bool, examSubject: Bool,
                     smallWrittenWeight: Double, oralWeight: Double, practicalWeight: Double,
                     weightsConfirmed: Bool) {
        change { try bridge.saveSubject(json: $0, subjectId: id, name: name, core: core,
            promotion: promotion, annualSchoolworks: Self.checkedInt32(annualSchoolworks ?? -1),
            advanced: advanced, examSubject: examSubject, smallWrittenWeight: smallWrittenWeight,
            oralWeight: oralWeight, practicalWeight: practicalWeight,
            weightsConfirmed: weightsConfirmed) }
    }

    func saveAssessment(id: String, subjectId: String, title: String, date: String, kind: String,
                        stage: String, notes: String, actual: Int?, estimate: Int?, weight: Double) {
        change { try bridge.saveAssessment(json: $0, assessmentId: id, subjectId: subjectId,
            title: title, date: date, kind: kind, stage: stage, notes: notes,
            actual: Self.checkedInt32(actual ?? -1), estimate: Self.checkedInt32(estimate ?? -1),
            weight: weight) }
    }

    func deleteAssessment(_ id: String) {
        let removed = snapshot.documents.filter { $0.assessmentId == id }
        do {
            try persist(bridge.deleteAssessment(json: json, assessmentId: id))
            for document in removed { try? FileManager.default.removeItem(at: documentURL(document)) }
            errorMessage = nil
        } catch { errorMessage = error.localizedDescription }
    }

    func importDocument(data: Data, name: String, mime: String, assessmentId: String,
                        token: DocumentImportToken) {
        guard token.assessmentID == assessmentId, acceptsDocumentToken(token) else { return }
        let file: String
        switch mime {
        case "application/pdf": file = UUID().uuidString.lowercased() + ".pdf"
        case "image/jpeg": file = UUID().uuidString.lowercased() + ".jpg"
        case "image/png": file = UUID().uuidString.lowercased() + ".png"
        default: errorMessage = StoreError.unsupportedDocument.localizedDescription; return
        }
        do {
            guard data.count > 0 && data.count <= 40 * 1024 * 1024 else { throw StoreError.documentTooLarge }
            guard Self.validSignature(data, mime: mime) else { throw StoreError.invalidDocument }
            let directory = documentsDirectory
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var resource = URLResourceValues(); resource.isExcludedFromBackup = true
            var mutableDirectory = directory; try mutableDirectory.setResourceValues(resource)
            let target = directory.appendingPathComponent(file)
            try data.write(to: target, options: .atomic)
            do {
                try persist(bridge.addDocument(json: json, assessmentId: assessmentId, name: name,
                    mime: mime, file: file, size: Int64(data.count)))
                errorMessage = nil
            } catch {
                try? FileManager.default.removeItem(at: target)
                throw error
            }
        } catch { errorMessage = error.localizedDescription }
    }

    func deleteDocument(_ document: SchoolDocument) {
        do {
            try persist(bridge.deleteDocument(json: json, documentId: document.id))
            try? FileManager.default.removeItem(at: documentURL(document))
            errorMessage = nil
        } catch { errorMessage = error.localizedDescription }
    }

    func documentURL(_ document: SchoolDocument) -> URL {
        documentsDirectory.appendingPathComponent(document.file)
    }

    func saveTimetable(id: String, validFrom: String, anchorMonday: String, blocks: [SchoolLesson]) {
        change {
            let blockJSON = String(decoding: try JSONEncoder().encode(blocks), as: UTF8.self)
            return try bridge.saveTimetable(json: $0, timetableId: id, validFrom: validFrom,
                                            anchorMonday: anchorMonday, blocksJson: blockJSON)
        }
    }

    func saveDayOff(id: String = "", from: String, to: String, reason: String) {
        change { try bridge.saveDayOff(json: $0, dayOffId: id, from: from, to: to, reason: reason) }
    }

    func deleteDayOff(_ id: String) { change { try bridge.deleteDayOff(json: $0, dayOffId: id) } }

    func saveException(id: String = "", subjectId: String, date: String, start: String, end: String,
                       cancelled: Bool) {
        change { try bridge.saveException(json: $0, exceptionId: id, subjectId: subjectId,
                                          date: date, start: start, end: end, cancelled: cancelled) }
    }

    func deleteException(_ id: String) { change { try bridge.deleteException(json: $0, exceptionId: id) } }

    func dayLessons(on date: Date) -> [SchoolDayLesson] {
        let formatter = SchoolDates.formatter("yyyy-MM-dd")
        guard let result = try? bridge.dayLessons(json: json, date: formatter.string(from: date)) else { return [] }
        return (try? JSONDecoder().decode([SchoolDayLesson].self, from: Data(result.utf8))) ?? []
    }

    func remainingLessons(for assessmentId: String, at date: Date) -> Int? {
        let formatter = SchoolDates.formatter("yyyy-MM-dd'T'HH:mm:ss")
        return try? Int(bridge.remainingLessons(json: json, assessmentId: assessmentId,
                                                now: formatter.string(from: date)))
    }

    func startStudy(for assessmentId: String) {
        guard let boot = bootId() else { errorMessage = "Gerätestart konnte nicht bestimmt werden."; return }
        change { try bridge.startStudy(json: $0, assessmentId: assessmentId, wall: wallMillis(),
                                       elapsed: uptimeMillis(), boot: boot) }
    }

    func stopStudy() {
        guard let boot = bootId() else { errorMessage = "Gerätestart konnte nicht bestimmt werden."; return }
        change { try bridge.stopStudy(json: $0, wall: wallMillis(), elapsed: uptimeMillis(), boot: boot) }
    }

    func correctStudy(id: String, minutes: Int, reason: String) {
        change { try bridge.correctStudy(json: $0, studyId: id,
            minutes: Self.checkedInt32(minutes), reason: reason) }
    }

    func setEstimate(id: String, grade: Int) {
        change { try bridge.setEstimate(json: $0, assessmentId: id, estimate: Self.checkedInt32(grade)) }
    }

    func skipEstimate(id: String) {
        change { try bridge.skipEstimate(json: $0, assessmentId: id) }
    }

    func saveOfficial(subjectId: String, period: String, value: Int?, omitted: Bool = false) {
        change { try bridge.saveOfficial(json: $0, subjectId: subjectId, period: period,
                                         value: Self.checkedInt32(value ?? -1), omitted: omitted) }
    }

    func transferOverview() -> SchoolTransferOverview? {
        guard let result = try? bridge.transferOverview(json: json) else { return nil }
        return try? JSONDecoder().decode(SchoolTransferOverview.self, from: Data(result.utf8))
    }

    func saveTransferProbe(route: String, german: Int?, math: Int?) {
        change { try bridge.saveTransferProbe(json: $0, route: route,
            german: Self.checkedInt32(german ?? -1), math: Self.checkedInt32(math ?? -1)) }
    }

    func transferProbeGrades(route: String) -> SchoolTransferProbeGrades? {
        guard let result = try? bridge.transferProbeGrades(json: json, route: route) else { return nil }
        return try? JSONDecoder().decode(SchoolTransferProbeGrades.self, from: Data(result.utf8))
    }

    func promotionOverview(rosterComplete: Bool, previousCompensation: Bool?,
                           socialInternship: Bool?) -> SchoolRuleResult? {
        guard let result = try? bridge.promotionOverview(json: json, rosterComplete: rosterComplete,
            previousCompensation: previousCompensation.map { $0 ? 1 : 0 } ?? -1,
            socialInternship: socialInternship.map { $0 ? 1 : 0 } ?? -1) else { return nil }
        return try? JSONDecoder().decode(SchoolRuleResult.self, from: Data(result.utf8))
    }

    func saveGraduation(_ input: SchoolGraduationInput) {
        change { try bridge.saveGraduation(json: $0,
            inputJson: String(decoding: JSONEncoder().encode(input), as: UTF8.self)) }
    }

    func graduationInput(procedure: String) -> SchoolGraduationInput {
        guard let result = try? bridge.graduationInput(json: json, procedure: procedure),
              let input = try? JSONDecoder().decode(SchoolGraduationInput.self, from: Data(result.utf8)) else {
            return SchoolGraduationInput(procedure: procedure)
        }
        return input
    }

    func qualiFieldNames(input: SchoolGraduationInput) -> [SchoolQualiField] {
        guard let encoded = try? JSONEncoder().encode(input),
              let result = try? bridge.qualiFieldNames(json: json,
                  inputJson: String(decoding: encoded, as: UTF8.self)) else { return [] }
        return (try? JSONDecoder().decode([SchoolQualiField].self, from: Data(result.utf8))) ?? []
    }

    func certificateOverview(procedure: String) -> SchoolRuleResult? {
        guard let result = try? bridge.certificateOverview(json: json, procedure: procedure) else { return nil }
        return try? JSONDecoder().decode(SchoolRuleResult.self, from: Data(result.utf8))
    }

    func refreshDueEstimates() {
        guard let result = try? bridge.estimatePromptsDue(json: json,
            nowMillis: wallMillis(), zoneId: TimeZone.current.identifier) else {
            dueEstimates = []; return
        }
        dueEstimates = (try? JSONDecoder().decode([SchoolEstimatePrompt].self,
            from: Data(result.utf8))) ?? []
    }

    func restoreValidatedJSON(_ data: Data) throws {
        guard !isDemo else { throw StoreError.demoBackupDisabled }
        guard let checked = String(data: try validateRestoreJSON(data), encoding: .utf8) else {
            throw StoreError.invalidEncoding
        }
        try persist(checked, recovering: true)
        generation &+= 1
        errorMessage = nil
    }

    func validateRestoreJSON(_ data: Data) throws -> Data {
        guard let incoming = String(data: data, encoding: .utf8) else { throw StoreError.invalidEncoding }
        let checked = try bridge.normalizeData(json: incoming)
        _ = try Self.decodeSnapshot(checked)
        return Data(checked.utf8)
    }

    private func change(_ update: (String) throws -> String) {
        do { try persist(update(json)); errorMessage = nil }
        catch { errorMessage = error.localizedDescription }
    }

    private func persist(_ next: String, recovering: Bool = false) throws {
        guard !needsRecovery || recovering else { throw StoreError.failedLoad }
        let checked = try bridge.normalizeData(json: next)
        let decoded = try Self.decodeSnapshot(checked)
        try Data(checked.utf8).write(to: fileURL, options: .atomic)
        var resource = URLResourceValues()
        resource.isExcludedFromBackup = true
        var target = fileURL
        try? target.setResourceValues(resource)
        json = checked
        snapshot = decoded
        needsRecovery = false
        refreshSummary()
        refreshDueEstimates()
        revision += 1
    }

    private func refreshSummary() {
        summary = try? JSONDecoder().decode(GradeSummary.self, from: Data(try bridge.overview(json: json).utf8))
    }

    private static func decodeSnapshot(_ json: String) throws -> SchoolSnapshot {
        try JSONDecoder().decode(SchoolSnapshot.self, from: Data(json.utf8))
    }

    private static func checkedInt32(_ value: Int) throws -> Int32 {
        guard let converted = Int32(exactly: value) else { throw StoreError.invalidNumber }
        return converted
    }

    private static func emptySnapshot() -> SchoolSnapshot {
        SchoolSnapshot(profiles: [], activeProfileId: nil, subjects: [], assessments: [], officials: [],
                       timetables: [], daysOff: [], exceptions: [], studies: [], documents: [])
    }

    private static func validSignature(_ data: Data, mime: String) -> Bool {
        switch mime {
        case "application/pdf": return data.starts(with: Data("%PDF-".utf8))
        case "image/jpeg": return data.starts(with: [0xff, 0xd8])
        case "image/png": return data.starts(with: [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
        default: return false
        }
    }

    private func wallMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
    private func uptimeMillis() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }
    private func bootId() -> Int32? {
        var boot = timeval()
        var length = MemoryLayout<timeval>.size
        guard sysctlbyname("kern.boottime", &boot, &length, nil, 0) == 0 else { return nil }
        return Int32(truncatingIfNeeded: boot.tv_sec)
    }

    enum StoreError: LocalizedError {
        case failedLoad
        case invalidEncoding
        case unsupportedDocument
        case documentTooLarge
        case invalidDocument
        case invalidNumber
        case missingDemoResource
        case demoBackupDisabled
        var errorDescription: String? {
            switch self {
            case .failedLoad: "Gespeicherte Daten konnten nicht geladen werden. Erst ein gültiges Backup wiederherstellen."
            case .invalidEncoding: "Die Daten sind kein gültiger UTF-8-Text."
            case .unsupportedDocument: "Nur PDF, JPEG und PNG werden unterstützt."
            case .documentTooLarge: "Dokument ist leer oder größer als 40 MiB."
            case .invalidDocument: "Dateiinhalt passt nicht zum Dokumenttyp."
            case .invalidNumber: "Zahl liegt außerhalb des erlaubten Bereichs."
            case .missingDemoResource: "Die Beispielbilder fehlen in dieser App-Version."
            case .demoBackupDisabled: "Backups sind bei Testdaten ausgeschaltet."
            }
        }
    }
}
