import CommonCrypto
import Compression
import CryptoKit
import Foundation
import Security
import zlib

enum BackupCodec {
    static let limit = 160 * 1024 * 1024
    static let documentLimit = 40 * 1024 * 1024
    private static let documentTotalLimit = 80 * 1024 * 1024
    private static let magic = Data("SALM0001".utf8)
    private static let iterations: UInt32 = 600_000

    struct Restored {
        let data: Data
        let documents: [String: Data]
    }

    enum CodecError: LocalizedError {
        case invalidFormat
        case invalidPassword
        case invalidData(String)
        case tooLarge

        var errorDescription: String? {
            switch self {
            case .invalidFormat: "Ungültiges Backup-Format."
            case .invalidPassword: "Passwort falsch oder Backup beschädigt."
            case .invalidData(let detail): "Ungültige Backup-Daten: \(detail)"
            case .tooLarge: "Backup überschreitet das Größenlimit."
            }
        }
    }

    static func export(data: Data, password: String, documents: [String: Data] = [:]) throws -> Data {
        guard password.utf16.count >= 8 else {
            throw CodecError.invalidData("Das Passwort benötigt mindestens acht Zeichen.")
        }
        let metadata = try validateJSON(data)
        try validateDocuments(metadata, documents)
        var zip = Data()
        let entries = [("data.json", data)] + metadata.map { ("documents/\($0.file)", documents[$0.file]!) }
        var central = Data()
        for (name, contents) in entries {
            guard contents.count <= documentLimit, zip.count + contents.count < limit else {
                throw CodecError.tooLarge
            }
            let nameBytes = Data(name.utf8)
            let offset = zip.count
            let checksum = contents.withUnsafeBytes {
                UInt32(crc32(0, $0.bindMemory(to: Bytef.self).baseAddress, uInt(contents.count)))
            }
            zip.add32(0x04034b50)
            zip.add16(20)
            zip.add16(0x0800) // UTF-8 names
            zip.add16(0) // Stored entries are understood by Java ZipInputStream.
            zip.add16(0)
            zip.add16(0)
            zip.add32(checksum)
            zip.add32(UInt32(contents.count))
            zip.add32(UInt32(contents.count))
            zip.add16(UInt16(nameBytes.count))
            zip.add16(0)
            zip.append(nameBytes)
            zip.append(contents)

            central.add32(0x02014b50)
            central.add16(20)
            central.add16(20)
            central.add16(0x0800)
            central.add16(0)
            central.add16(0)
            central.add16(0)
            central.add32(checksum)
            central.add32(UInt32(contents.count))
            central.add32(UInt32(contents.count))
            central.add16(UInt16(nameBytes.count))
            central.add16(0)
            central.add16(0)
            central.add16(0)
            central.add16(0)
            central.add32(0)
            central.add32(UInt32(offset))
            central.append(nameBytes)
        }
        guard zip.count + central.count + 22 <= limit else { throw CodecError.tooLarge }
        let centralOffset = zip.count
        zip.append(central)
        zip.add32(0x06054b50)
        zip.add16(0)
        zip.add16(0)
        zip.add16(UInt16(entries.count))
        zip.add16(UInt16(entries.count))
        zip.add32(UInt32(central.count))
        zip.add32(UInt32(centralOffset))
        zip.add16(0)

        let salt = try randomBytes(count: 16)
        let nonce = try randomBytes(count: 12)
        var key = try deriveKey(password: password, salt: salt)
        defer { key.resetBytes(in: 0..<key.count) }
        let box = try AES.GCM.seal(zip, using: SymmetricKey(data: key), nonce: AES.GCM.Nonce(data: nonce), authenticating: magic)
        var result = magic
        result.append(salt)
        result.append(nonce)
        result.append(box.ciphertext)
        result.append(box.tag)
        guard result.count <= limit else { throw CodecError.tooLarge }
        return result
    }

    static func restore(archive: Data, password: String) throws -> Restored {
        guard (52...limit).contains(archive.count), archive.prefix(8) == magic else {
            throw CodecError.invalidFormat
        }
        var key = try deriveKey(password: password, salt: Data(archive[8..<24]))
        defer { key.resetBytes(in: 0..<key.count) }
        let plain: Data
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: archive[24..<36]),
                ciphertext: archive[36..<(archive.count - 16)],
                tag: archive.suffix(16)
            )
            plain = try AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: magic)
        } catch {
            throw CodecError.invalidPassword
        }
        guard plain.count <= limit else { throw CodecError.tooLarge }
        var entries = try readZIP(plain)
        guard let json = entries.removeValue(forKey: "data.json") else {
            throw CodecError.invalidData("data.json fehlt.")
        }
        let metadata = try validateJSON(json)
        var documents: [String: Data] = [:]
        for item in metadata {
            let path = "documents/\(item.file)"
            guard let contents = entries.removeValue(forKey: path), contents.count == item.size else {
                throw CodecError.invalidData("Dokumentzuordnung unvollständig.")
            }
            documents[item.file] = contents
        }
        guard entries.isEmpty else { throw CodecError.invalidData("Nicht referenzierte ZIP-Datei.") }
        return Restored(data: json, documents: documents)
    }

    private struct DocumentInfo {
        let file: String
        let size: Int
    }

    private static func validateJSON(_ json: Data) throws -> [DocumentInfo] {
        guard json.count <= documentLimit,
              let root = try JSONSerialization.jsonObject(with: json) as? [String: Any],
              [1, 2].contains(root["schema"] as? Int ?? -1) else {
            throw CodecError.invalidData("Schema oder JSON ungültig.")
        }
        func objects(_ key: String, max: Int = 100_000) throws -> [[String: Any]] {
            guard let value = root[key] as? [[String: Any]], value.count <= max else {
                throw CodecError.invalidData("\(key) ungültig.")
            }
            return value
        }
        func ids(_ rows: [[String: Any]], _ key: String) throws -> Set<String> {
            let values = rows.compactMap { $0[key] as? String }
            guard values.count == rows.count, !values.contains(where: \.isEmpty),
                  Set(values).count == values.count else {
                throw CodecError.invalidData("Doppelte oder fehlende \(key)-Werte.")
            }
            return Set(values)
        }
        func refs(_ rows: [[String: Any]], _ key: String, _ targets: Set<String>) throws {
            guard rows.allSatisfy({ ($0[key] as? String).map(targets.contains) == true }) else {
                throw CodecError.invalidData("Ungültiger \(key)-Verweis.")
            }
        }
        let profiles = try objects("profiles", max: 200)
        let subjects = try objects("subjects", max: 10_000)
        let assessments = try objects("assessments")
        let officials = try objects("officials")
        let timetables = try objects("timetables")
        let daysOff = try objects("daysOff")
        let exceptions = try objects("exceptions")
        let studies = try objects("studies")
        let documents = try objects("documents", max: 2_000)
        let profileIDs = try ids(profiles, "id")
        let subjectIDs = try ids(subjects, "id")
        let assessmentIDs = try ids(assessments, "id")
        for rows in [officials, timetables, studies, documents] { _ = try ids(rows, "id") }
        if let active = root["activeProfileId"] as? String, !profileIDs.contains(active) {
            throw CodecError.invalidData("Aktives Profil fehlt.")
        }
        try refs(subjects, "profileId", profileIDs)
        try refs(assessments, "subjectId", subjectIDs)
        try refs(officials, "subjectId", subjectIDs)
        try refs(timetables, "profileId", profileIDs)
        try refs(daysOff, "profileId", profileIDs)
        try refs(exceptions, "subjectId", subjectIDs)
        try refs(studies, "assessmentId", assessmentIDs)
        try refs(documents, "assessmentId", assessmentIDs)
        for timetable in timetables {
            guard let blocks = timetable["blocks"] as? [[String: Any]] else {
                throw CodecError.invalidData("Stundenplan ungültig.")
            }
            try refs(blocks.filter { ($0["isBreak"] as? Bool) != true }, "subjectId", subjectIDs)
            guard blocks.filter({ ($0["isBreak"] as? Bool) == true }).allSatisfy({ ($0["subjectId"] as? String) == "" }) else {
                throw CodecError.invalidData("Pauseneintrag ungültig.")
            }
        }
        var files = Set<String>()
        var total = 0
        var result: [DocumentInfo] = []
        for doc in documents {
            guard let file = doc["file"] as? String,
                  file.range(of: #"^[a-f0-9-]+\.(pdf|jpg|png)$"#, options: .regularExpression) != nil,
                  let size = doc["size"] as? Int, (1...documentLimit).contains(size),
                  let mime = doc["mime"] as? String,
                  ["application/pdf", "image/jpeg", "image/png"].contains(mime),
                  files.insert(file).inserted else {
                throw CodecError.invalidData("Dokumentmetadaten ungültig.")
            }
            total += size
            guard total < documentTotalLimit else { throw CodecError.tooLarge }
            result.append(DocumentInfo(file: file, size: size))
        }
        return result
    }

    private static func validateDocuments(_ metadata: [DocumentInfo], _ files: [String: Data]) throws {
        guard files.count == metadata.count else { throw CodecError.invalidData("Dokumentzuordnung unvollständig.") }
        for item in metadata where files[item.file]?.count != item.size {
            throw CodecError.invalidData("Dokumentgröße stimmt nicht.")
        }
    }

    private static func deriveKey(password: String, salt: Data) throws -> Data {
        var output = Data(count: 32)
        let passwordBytes = Array(password.utf8)
        let passwordStorage = passwordBytes + [0]
        let outputLength = output.count
        let status = output.withUnsafeMutableBytes { outputBuffer in
            salt.withUnsafeBytes { saltBuffer in
                passwordStorage.withUnsafeBufferPointer { passwordBuffer in
                    CCKeyDerivationPBKDF(
                        CCPBKDFAlgorithm(kCCPBKDF2),
                        UnsafeRawPointer(passwordBuffer.baseAddress!).assumingMemoryBound(to: CChar.self),
                        passwordBytes.count,
                        saltBuffer.bindMemory(to: UInt8.self).baseAddress,
                        salt.count,
                        CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
                        iterations,
                        outputBuffer.bindMemory(to: UInt8.self).baseAddress,
                        outputLength
                    )
                }
            }
        }
        guard status == kCCSuccess else { throw CodecError.invalidFormat }
        return output
    }

    private static func randomBytes(count: Int) throws -> Data {
        var result = Data(count: count)
        let status = result.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        guard status == errSecSuccess else { throw CodecError.invalidFormat }
        return result
    }

    private static func readZIP(_ zip: Data) throws -> [String: Data] {
        let bytes = Array(zip)
        guard bytes.count >= 22 else { throw CodecError.invalidData("ZIP fehlt.") }
        let lower = max(0, bytes.count - 22 - 65_535)
        guard let end = stride(from: bytes.count - 22, through: lower, by: -1).first(where: {
            bytes.u32($0) == 0x06054b50 && $0 + 22 + Int(bytes.u16($0 + 20)) == bytes.count
        }) else { throw CodecError.invalidData("ZIP-Verzeichnis fehlt.") }
        let count = Int(bytes.u16(end + 10))
        let centralSize = Int(bytes.u32(end + 12))
        let centralOffset = Int(bytes.u32(end + 16))
        guard bytes.u16(end + 4) == 0, bytes.u16(end + 6) == 0,
              bytes.u16(end + 8) == count, count <= 2_001,
              centralOffset <= end, centralSize == end - centralOffset else {
            throw CodecError.invalidData("ZIP-Verzeichnis ungültig.")
        }
        var cursor = centralOffset
        var total = 0
        var result: [String: Data] = [:]
        var ranges: [Range<Int>] = []
        for _ in 0..<count {
            guard cursor + 46 <= end, bytes.u32(cursor) == 0x02014b50 else {
                throw CodecError.invalidData("ZIP-Eintrag ungültig.")
            }
            let flags = bytes.u16(cursor + 8)
            let method = bytes.u16(cursor + 10)
            let checksum = bytes.u32(cursor + 16)
            let compressed = Int(bytes.u32(cursor + 20))
            let uncompressed = Int(bytes.u32(cursor + 24))
            let nameLength = Int(bytes.u16(cursor + 28))
            let extraLength = Int(bytes.u16(cursor + 30))
            let commentLength = Int(bytes.u16(cursor + 32))
            let local = Int(bytes.u32(cursor + 42))
            let next = cursor + 46 + nameLength + extraLength + commentLength
            guard next <= end, nameLength > 0, (flags & 1) == 0,
                  (flags & 0x0040) == 0, method == 0 || method == 8,
                  uncompressed <= documentLimit, local + 30 <= centralOffset,
                  bytes.u32(local) == 0x04034b50,
                  bytes.u16(local + 6) == flags, bytes.u16(local + 8) == method else {
                throw CodecError.invalidData("ZIP-Eintrag nicht unterstützt.")
            }
            let nameBytes = bytes[(cursor + 46)..<(cursor + 46 + nameLength)]
            guard let name = String(bytes: nameBytes, encoding: .utf8),
                  name == "data.json" || name.range(of: #"^documents/[a-f0-9-]+\.(pdf|jpg|png)$"#, options: .regularExpression) != nil,
                  result[name] == nil,
                  Int(bytes.u16(local + 26)) == nameLength,
                  local + 30 + nameLength <= centralOffset,
                  bytes[(local + 30)..<(local + 30 + nameLength)].elementsEqual(nameBytes) else {
                throw CodecError.invalidData("Unzulässiger Backup-Pfad.")
            }
            let start = local + 30 + nameLength + Int(bytes.u16(local + 28))
            let finish = start + compressed
            guard finish <= centralOffset, compressed <= limit,
                  !ranges.contains(where: { $0.overlaps(local..<finish) }) else {
                throw CodecError.invalidData("ZIP-Datenbereich ungültig.")
            }
            ranges.append(local..<finish)
            let contents: Data
            if method == 0 {
                guard compressed == uncompressed else { throw CodecError.invalidData("ZIP-Größe ungültig.") }
                contents = Data(bytes[start..<finish])
            } else {
                var decoded = [UInt8](repeating: 0, count: uncompressed + 1)
                let length = bytes.withUnsafeBufferPointer { source in
                    decoded.withUnsafeMutableBufferPointer { destination in
                        compression_decode_buffer(
                            destination.baseAddress!, destination.count,
                            source.baseAddress!.advanced(by: start), compressed,
                            nil, COMPRESSION_ZLIB
                        )
                    }
                }
                guard length == uncompressed else { throw CodecError.invalidData("ZIP-Größe ungültig.") }
                contents = Data(decoded.prefix(length))
            }
            let actualCRC = contents.withUnsafeBytes {
                UInt32(crc32(0, $0.bindMemory(to: Bytef.self).baseAddress, uInt(contents.count)))
            }
            guard actualCRC == checksum else { throw CodecError.invalidData("ZIP-Prüfsumme ungültig.") }
            total += contents.count
            guard total <= limit else { throw CodecError.tooLarge }
            result[name] = contents
            cursor = next
        }
        guard cursor == end else { throw CodecError.invalidData("ZIP-Verzeichnis ungültig.") }
        return result
    }
}

private extension Data {
    mutating func add16(_ number: UInt16) {
        append(UInt8(truncatingIfNeeded: number))
        append(UInt8(truncatingIfNeeded: number >> 8))
    }

    mutating func add32(_ number: UInt32) {
        add16(UInt16(truncatingIfNeeded: number))
        add16(UInt16(truncatingIfNeeded: number >> 16))
    }
}

private extension Array where Element == UInt8 {
    func u16(_ offset: Int) -> UInt16 {
        UInt16(self[offset]) | UInt16(self[offset + 1]) << 8
    }

    func u32(_ offset: Int) -> UInt32 {
        UInt32(u16(offset)) | UInt32(u16(offset + 2)) << 16
    }
}
