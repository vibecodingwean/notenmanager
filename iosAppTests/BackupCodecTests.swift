import Foundation
import XCTest
#if canImport(NotenManager)
@testable import NotenManager
#endif

final class BackupCodecTests: XCTestCase {
    private let unicodePassword = "päss🔐wort"
    private let androidVector = [
        "U0FMTTAwMDEAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRob",
        "cFbZJ9GPT1oVSFLE8jPvc1ly2WWDPBzdyO2/i+04lp4AKFh5",
        "jr4LozksmZGozgjoKkab7mTODVq0vWivNU4mX2fwQvO7P7nQ",
        "gEZ8VOBLTOmDn2K84cvYtUDsvK6FDY5Wn8FmnNZMtLtfjFW8",
        "uMpme5KkNuu6VnfAL5UvLFKH4i9hlSEVo6/JgTY887JwA8lw",
        "OqCtNk7EytRbAlMxKXjh7Jo8K37b9IQDhTSVK5X/XLnyKN35",
        "05LvHBiv+FtUQCwpZ5wLo1G5oRRGZKAyZMPJwur/7VEH9OPA",
        "oBuC9ukZ7Qz3kYs1nbnGxUt2r5KVF3/5AFs=",
    ].joined()
    // Generated with Java 17 password-based key derivation, AES-GCM and ZIP.
    private let androidDocumentVector = [
        "U0FMTTAwMDEAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRob",
        "cFbZJ9GPT1oVSOrE8jPvc1ly2WWDPBzdyO2/i+04lp4AKFh5",
        "jr4Lw+XW25XYzvKwZsjSSYICspLIKg204/WEu3UEdfEYTxvS",
        "s08V7hDxXNPYcxonYFlcn9gst6VU1tYkmm25vsklc9nPdG8j",
        "ojcg4BBrV3Z903dqaQB/ZWxHpijqZBJFti/RqqFUIFLNegX6",
        "GBVPX3ZGcJrtz7exnD8VjEQCkxDsp5f0dkZnyeT4RySR4gXe",
        "YqR+IDHZMw45YnHMoiWuZl+OgUMwwWq0UPGLSowFepvExYxz",
        "kkZtbA+Eb3mGdbqqieGSa5eFmg4i3mgDlUwiNPTxx+UnPAEf",
        "YE/+n5H3xFDLntMp9p0Ud9B8ZDl0tXVaVBdhZyufHJcmDScB",
        "VaIn1955XMB8J1r3VJZ1Cr1OR+4gozWvGV8x4AKNMbppMOg7",
        "3JUNqfhRQaUcmwsFm4btx8LjMfGu2WTuaRDMqZ+cfKWhKDZ4",
        "vQw5Z0PyivzxfF7FVUv7W6aib/Klrcwf+mZkMa8Fjxuu2PNo",
        "cWlUXwK3elLjtY7ZxNv5QFSIr//ILhbTVWMi5XBBk0W5rI0/",
        "RyzVQpTcIUOeIqRjw8BFg+GPLgo548UXTy7Sq5rpUdpwkae4",
        "Sutgkgvd7CM7B7JRQOMipFVvRPSdjK1wE+ziwGaTmOpebDwl",
        "90RIJl8mRA==",
    ].joined()
    private let traversalVector = [
        "U0FMTTAwMDEAAQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRob",
        "cFbZJ9GPT1oVSFrF8jPvc1ly2WWDPBzdyO29i+043NFbLAB6",
        "kf8V+tOgGKCVQjyTX3ypaDUJn34o9n/vfE+INat8jCMV4YLn",
        "Jr7nYuH8pQANHQsnzTzTLjVEtQix5KBkTyBMRivz/7tqOPR0",
        "2px87r71R25ghBmsM9qEWBlsBfyyQEWBQSGN1nUCu85/GIYu",
        "OjGtp06UgYUSF1ExKWTp7JJbf1rTjpl/ii/adZVuXCjyId3y",
        "05LvHBiv+FtUQCwpZ5wLxzDj7hVJYaYwGvioorq06FcG9OLA",
        "lhuD9kUZ7QxgkcO06Ol2p3cmGGhweb6HXyr0uNY/",
    ].joined()

    private let documentJSON = Data("""
        {"schema":1,"profiles":[{"id":"p","year":2026}],"activeProfileId":"p",\
        "subjects":[{"id":"s","profileId":"p","name":"Mathematik"}],\
        "assessments":[{"id":"a","subjectId":"s","title":"Probe","date":"2026-10-05"}],\
        "officials":[],"timetables":[],"daysOff":[],"exceptions":[],"studies":[],\
        "documents":[{"id":"d","assessmentId":"a","name":"Probe.pdf",\
        "mime":"application/pdf","file":"0123-abcd.pdf","size":6}]}
        """.utf8)

    func testSchemaTwoRoundTripPreservesAndroidFlashcards() throws {
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: documentJSON) as? [String: Any])
        root["schema"] = 2
        root["flashcardSets"] = [["id": "f", "profileId": "p", "name": "Biologie"]]
        root["flashcards"] = [["id": "c", "setId": "f", "front": "Frage", "back": "Antwort"]]
        let json = try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
        let backup = try BackupCodec.export(data: json, password: unicodePassword,
                                             documents: ["0123-abcd.pdf": Data("%PDF-1".utf8)])
        let restored = try BackupCodec.restore(archive: backup, password: unicodePassword)
        XCTAssertEqual(restored.data, json)
    }

    func testReadsJavaUnicodePasswordAndDeflatedZIP() throws {
        let archive = try XCTUnwrap(Data(base64Encoded: androidVector))
        let restored = try BackupCodec.restore(archive: archive, password: unicodePassword)
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: restored.data) as? [String: Any])
        XCTAssertEqual(root["schema"] as? Int, 1)
        XCTAssertTrue(restored.documents.isEmpty)
    }

    func testStoredZIPRoundTripWithDocument() throws {
        let pdf = Data("%PDF-1".utf8)
        let backup = try BackupCodec.export(
            data: documentJSON,
            password: unicodePassword,
            documents: ["0123-abcd.pdf": pdf]
        )
        XCTAssertEqual(backup.prefix(8), Data("SALM0001".utf8))
        let restored = try BackupCodec.restore(archive: backup, password: unicodePassword)
        XCTAssertEqual(restored.data, documentJSON)
        XCTAssertEqual(restored.documents["0123-abcd.pdf"], pdf)
    }

    func testReadsJavaBackupWithDocumentBytes() throws {
        let archive = try XCTUnwrap(Data(base64Encoded: androidDocumentVector))
        let restored = try BackupCodec.restore(archive: archive, password: unicodePassword)
        XCTAssertEqual(restored.documents["0123-abcd.pdf"], Data("%PDF-1".utf8))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: restored.data) as? [String: Any])
        XCTAssertEqual((root["documents"] as? [[String: Any]])?.count, 1)
    }

    func testRejectsWrongPasswordAndTampering() throws {
        let original = try XCTUnwrap(Data(base64Encoded: androidVector))
        XCTAssertThrowsError(try BackupCodec.restore(archive: original, password: "bad"))
        var changed = original
        changed[changed.count - 1] ^= 1
        XCTAssertThrowsError(try BackupCodec.restore(archive: changed, password: unicodePassword))
        changed = original
        changed[0] ^= 1
        XCTAssertThrowsError(try BackupCodec.restore(archive: changed, password: unicodePassword))
    }

    func testRejectsInvalidReferencesAndDocumentPaths() throws {
        let invalidReference = Data(String(decoding: documentJSON, as: UTF8.self)
            .replacingOccurrences(of: "\"assessmentId\":\"a\"", with: "\"assessmentId\":\"missing\"").utf8)
        XCTAssertThrowsError(try BackupCodec.export(data: invalidReference, password: unicodePassword,
                                                  documents: ["0123-abcd.pdf": Data("%PDF-1".utf8)]))
        let invalidPath = Data(String(decoding: documentJSON, as: UTF8.self)
            .replacingOccurrences(of: "0123-abcd.pdf", with: "../evil.pdf").utf8)
        XCTAssertThrowsError(try BackupCodec.export(data: invalidPath, password: unicodePassword,
                                                  documents: ["../evil.pdf": Data("%PDF-1".utf8)]))
        let traversal = try XCTUnwrap(Data(base64Encoded: traversalVector))
        XCTAssertThrowsError(try BackupCodec.restore(archive: traversal, password: unicodePassword))
    }

    func testRejectsDocumentSizeMismatchAndShortPassword() {
        XCTAssertThrowsError(try BackupCodec.export(data: documentJSON, password: unicodePassword,
                                                  documents: ["0123-abcd.pdf": Data("short".utf8)]))
        XCTAssertThrowsError(try BackupCodec.export(data: documentJSON, password: "1234567",
                                                  documents: ["0123-abcd.pdf": Data("%PDF-1".utf8)]))
    }

    @MainActor func testStoreImportAndExportWithDocument() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SchoolStore(fileURL: folder.appendingPathComponent("SchoolData.json"))
        let incoming = try XCTUnwrap(Data(base64Encoded: androidDocumentVector))
        try store.importBackup(archive: incoming, password: unicodePassword)
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: store.dataJSON) as? [String: Any])
        let documents = try XCTUnwrap(root["documents"] as? [[String: Any]])
        let name = try XCTUnwrap(documents.first?["file"] as? String)
        XCTAssertNotEqual(name, "0123-abcd.pdf")
        let file = store.documentsDirectory.appendingPathComponent(name)
        XCTAssertEqual(try Data(contentsOf: file), Data("%PDF-1".utf8))

        let outgoing = try store.exportBackup(password: unicodePassword)
        let roundTrip = try BackupCodec.restore(archive: outgoing, password: unicodePassword)
        XCTAssertEqual(roundTrip.documents[name], Data("%PDF-1".utf8))

        let before = store.dataJSON
        XCTAssertThrowsError(try store.importBackup(archive: incoming, password: "bad"))
        XCTAssertEqual(store.dataJSON, before)
        XCTAssertEqual(try Data(contentsOf: file), Data("%PDF-1".utf8))
    }

    @MainActor func testInvalidSchoolModelLeavesStoreUntouched() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SchoolStore(fileURL: folder.appendingPathComponent("SchoolData.json"))
        let before = store.dataJSON
        let invalidModel = Data("""
            {"schema":1,"profiles":[{"id":"p","grade":999}],"activeProfileId":"p",\
            "subjects":[],"assessments":[],"officials":[],"timetables":[],\
            "daysOff":[],"exceptions":[],"studies":[],"documents":[]}
            """.utf8)
        let archive = try BackupCodec.export(data: invalidModel, password: unicodePassword)
        XCTAssertThrowsError(try store.importBackup(archive: archive, password: unicodePassword))
        XCTAssertEqual(store.dataJSON, before)
    }

    @MainActor func testUnreadableSavedDataCannotBeOverwrittenByOnboarding() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent("SchoolData.json")
        let invalid = Data([0xff, 0xfe, 0x00])
        try invalid.write(to: file)

        let store = SchoolStore(fileURL: file)
        XCTAssertNotNil(store.errorMessage)
        XCTAssertFalse(store.createProfile(state: "Bayern", school: "GRUNDSCHULE", grade: 3,
                                           track: "Grundschule", year: 2026, term: "Ganzes Schuljahr"))
        XCTAssertEqual(try Data(contentsOf: file), invalid)

        let backup = try XCTUnwrap(Data(base64Encoded: androidVector))
        try store.importBackup(archive: backup, password: unicodePassword)
        XCTAssertNotEqual(try Data(contentsOf: file), invalid)
        XCTAssertNil(store.errorMessage)
    }

    @MainActor func testMalformedSavedJSONCanRecoverWithoutDeletingUnknownDocuments() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent("SchoolData.json")
        try Data("{\"profiles\":".utf8).write(to: file)
        let store = SchoolStore(fileURL: file)
        XCTAssertTrue(store.needsRecovery)
        let oldDocument = store.documentsDirectory.appendingPathComponent("unknown.pdf")
        try FileManager.default.createDirectory(at: store.documentsDirectory,
                                                withIntermediateDirectories: true)
        try Data("%PDF-old".utf8).write(to: oldDocument)

        let backup = try XCTUnwrap(Data(base64Encoded: androidVector))
        try store.importBackup(archive: backup, password: unicodePassword)
        XCTAssertFalse(store.needsRecovery)
        XCTAssertNil(store.errorMessage)
        XCTAssertEqual(try Data(contentsOf: oldDocument), Data("%PDF-old".utf8))
        XCTAssertNotEqual(try Data(contentsOf: file), Data("{\"profiles\":".utf8))
    }

    @MainActor func testRestoreStopsRunningStudyAndMarksDurationForCorrection() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SchoolStore(fileURL: folder.appendingPathComponent("SchoolData.json"))
        XCTAssertTrue(store.createProfile(state: "Bayern", school: "GRUNDSCHULE", grade: 3,
                                          track: "Grundschule", year: 2026, term: "Ganzes Schuljahr"))
        let subject = try XCTUnwrap(store.snapshot.activeSubjects.first)
        store.saveAssessment(id: "", subjectId: subject.id, title: "Probe", date: "2026-10-05",
                             kind: "TEST", stage: "PLANNED", notes: "", actual: nil,
                             estimate: nil, weight: 1)
        let assessment = try XCTUnwrap(store.snapshot.activeAssessments.first)
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: store.dataJSON) as? [String: Any])
        root["studies"] = [["id": "timer", "assessmentId": assessment.id, "start": 1000,
                            "end": NSNull(), "elapsedStart": 500, "boot": 7,
                            "correctedMinutes": NSNull(), "correction": ""]]
        let backup = try BackupCodec.export(data: JSONSerialization.data(withJSONObject: root),
                                            password: unicodePassword)
        try store.importBackup(archive: backup, password: unicodePassword)

        let restored = try XCTUnwrap(store.snapshot.studies.first)
        XCTAssertEqual(restored.end, restored.start)
        XCTAssertEqual(restored.seconds(at: Date()), 0)
        XCTAssertTrue(restored.correction.contains("prüfen"))
        let restoredRoot = try XCTUnwrap(JSONSerialization.jsonObject(with: store.dataJSON) as? [String: Any])
        let studies = try XCTUnwrap(restoredRoot["studies"] as? [[String: Any]])
        XCTAssertEqual(studies.first?["elapsedStart"] as? Int, 0)
        XCTAssertEqual(studies.first?["boot"] as? Int, 0)
    }
}

@MainActor final class DemoWorkspaceTests: XCTestCase {
    func testDemoRoundTripKeepsRealBytesProfileAndDocuments() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let realURL = folder.appendingPathComponent("SchoolData.json")
        let store = SchoolStore(fileURL: realURL)
        XCTAssertTrue(store.createProfile(state: "Bayern", school: "GRUNDSCHULE", grade: 3,
                                          track: "Grundschule", year: 2026, term: "Ganzes Schuljahr"))
        let realProfile = try XCTUnwrap(store.snapshot.activeProfileId)
        let subject = try XCTUnwrap(store.snapshot.activeSubjects.first)
        store.saveAssessment(id: "", subjectId: subject.id, title: "Echte Probe", date: "2026-10-06",
                             kind: "TEST", stage: "GRADED", notes: "", actual: 3, estimate: nil, weight: 1)
        let assessment = try XCTUnwrap(store.snapshot.activeAssessments.first)
        let realToken = try XCTUnwrap(store.documentToken(for: assessment.id))
        store.importDocument(data: Data("%PDF-1 real sentinel".utf8), name: "echt.pdf",
                             mime: "application/pdf", assessmentId: assessment.id, token: realToken)
        let document = try XCTUnwrap(store.snapshot.documents.first)
        let realData = try Data(contentsOf: realURL)
        let realDocument = try Data(contentsOf: store.documentURL(document))

        try store.loadDemo()
        XCTAssertTrue(store.isDemo)
        XCTAssertEqual(store.notificationJSON, realData)
        XCTAssertEqual(store.snapshot.activeProfile?.grade, 7)
        XCTAssertEqual(store.snapshot.documents.count, 2)
        XCTAssertEqual(store.snapshot.activeAssessments.filter { $0.stage == "GRADED" }.count, 6)
        XCTAssertEqual(store.snapshot.latestTimetable?.blocks.count, 28)
        for item in store.snapshot.documents {
            let bytes = try Data(contentsOf: store.documentURL(item))
            XCTAssertEqual(bytes.count, Int(item.size))
            XCTAssertTrue(bytes.starts(with: [0x89, 0x50, 0x4e, 0x47]))
        }
        let demoData = store.dataJSON
        try store.loadDemo()
        XCTAssertEqual(store.dataJSON, demoData)
        XCTAssertThrowsError(try store.exportBackup(password: "password1"))
        XCTAssertThrowsError(try store.importBackup(archive: Data(), password: "password1"))

        let relaunched = SchoolStore(fileURL: realURL)
        XCTAssertTrue(relaunched.isDemo)
        XCTAssertEqual(relaunched.dataJSON, demoData)
        try relaunched.returnToReal()
        XCTAssertEqual(relaunched.snapshot.activeProfileId, realProfile)
        XCTAssertEqual(try Data(contentsOf: realURL), realData)
        XCTAssertEqual(try Data(contentsOf: relaunched.documentURL(document)), realDocument)
        try relaunched.deleteDemo()
        XCTAssertFalse(FileManager.default.fileExists(atPath: folder.appendingPathComponent("Demo").path))
        XCTAssertEqual(try Data(contentsOf: realURL), realData)
    }

    func testLateDocumentResultCannotCrossWorkspaceOrResurrectDeletedDemo() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = SchoolStore(fileURL: folder.appendingPathComponent("SchoolData.json"))
        try store.loadDemo()
        let assessment = try XCTUnwrap(store.snapshot.activeAssessments.first)
        let oldToken = try XCTUnwrap(store.documentToken(for: assessment.id))
        try store.deleteDemo()
        store.importDocument(data: Data("%PDF-1 late".utf8), name: "late.pdf",
                             mime: "application/pdf", assessmentId: assessment.id, token: oldToken)
        XCTAssertTrue(store.snapshot.documents.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: folder.appendingPathComponent("Demo").path))
        try store.loadDemo()
        let before = store.dataJSON
        store.importDocument(data: Data("%PDF-1 late".utf8), name: "late.pdf",
                             mime: "application/pdf", assessmentId: assessment.id, token: oldToken)
        XCTAssertEqual(store.dataJSON, before)
        XCTAssertEqual(store.snapshot.documents.count, 2)
    }

    func testDemoStudyTimerWritesOnlyDemoWorkspace() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let realURL = folder.appendingPathComponent("SchoolData.json")
        let store = SchoolStore(fileURL: realURL)
        XCTAssertTrue(store.createProfile(state: "Bayern", school: "GRUNDSCHULE", grade: 3,
                                          track: "Grundschule", year: 2026, term: "Ganzes Schuljahr"))
        let realBytes = try Data(contentsOf: realURL)
        try store.loadDemo()
        let planned = try XCTUnwrap(store.snapshot.activeAssessments.first { $0.stage == "PLANNED" })
        store.startStudy(for: planned.id)
        XCTAssertNil(store.errorMessage)
        XCTAssertEqual(store.snapshot.studies.filter { $0.end == nil }.count, 1)
        try store.returnToReal()
        XCTAssertTrue(store.snapshot.studies.isEmpty)
        XCTAssertEqual(try Data(contentsOf: realURL), realBytes)
        try store.loadDemo()
        XCTAssertEqual(store.snapshot.studies.filter { $0.end == nil }.count, 1)
    }
}
