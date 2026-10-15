import Foundation

@MainActor extension SchoolStore {
    func exportBackup(password: String) throws -> Data {
        guard !isDemo else { throw StoreError.demoBackupDisabled }
        let current = dataJSON
        let names = try backupDocumentNames(in: current)
        var files: [String: Data] = [:]
        for name in names {
            let url = documentsDirectory.appendingPathComponent(name, isDirectory: false)
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            guard let size = attributes[.size] as? NSNumber,
                  size.intValue <= BackupCodec.documentLimit else {
                throw BackupTransferError.documentTooLarge
            }
            files[name] = try Data(contentsOf: url, options: .mappedIfSafe)
        }
        return try BackupCodec.export(data: current, password: password, documents: files)
    }

    func importBackup(archive: Data, password: String) throws {
        guard !isDemo else { throw StoreError.demoBackupDisabled }
        let restored = try BackupCodec.restore(archive: archive, password: password)
        // Corrupt local JSON has no trustworthy document inventory. Leave its files
        // untouched; a validated backup can still restore the school data.
        let oldNames = needsRecovery ? [] : try backupDocumentNames(in: dataJSON)
        guard var root = try JSONSerialization.jsonObject(with: restored.data) as? [String: Any],
              var metadata = root["documents"] as? [[String: Any]] else {
            throw BackupTransferError.invalidJSON
        }
        var renamed: [String: Data] = [:]
        for index in metadata.indices {
            guard let oldName = metadata[index]["file"] as? String,
                  let contents = restored.documents[oldName] else {
                throw BackupTransferError.invalidJSON
            }
            let suffix = (oldName as NSString).pathExtension
            let newName = UUID().uuidString.lowercased() + "." + suffix
            metadata[index]["file"] = newName
            renamed[newName] = contents
        }
        root["documents"] = metadata
        guard var studies = root["studies"] as? [[String: Any]] else {
            throw BackupTransferError.invalidJSON
        }
        for index in studies.indices where studies[index]["end"] == nil || studies[index]["end"] is NSNull {
            guard let start = studies[index]["start"] as? NSNumber else {
                throw BackupTransferError.invalidJSON
            }
            // A timer from another device cannot keep running on this device's clock.
            // Zero is conservative until the user corrects the actual duration.
            studies[index]["end"] = start
            studies[index]["elapsedStart"] = 0
            studies[index]["boot"] = 0
            studies[index]["correction"] = "Aus Backup wiederhergestellt: Sitzungsdauer bitte prüfen."
        }
        root["studies"] = studies
        let rewritten = try JSONSerialization.data(withJSONObject: root)
        // Kotlin checks the full SchoolData schema and every reference before files or JSON change.
        let normalized = try validateRestoreJSON(rewritten)

        let manager = FileManager.default
        let directory = documentsDirectory
        try manager.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableDirectory = directory
        try mutableDirectory.setResourceValues(values)
        let staging = directory.appendingPathComponent(".restore-\(UUID().uuidString)", isDirectory: true)
        try manager.createDirectory(at: staging, withIntermediateDirectories: false)
        var installed: [URL] = []
        defer { try? manager.removeItem(at: staging) }
        do {
            for (name, contents) in renamed {
                guard contents.count <= BackupCodec.documentLimit else {
                    throw BackupTransferError.documentTooLarge
                }
                try contents.write(to: staging.appendingPathComponent(name), options: .atomic)
            }
            for name in renamed.keys {
                let target = directory.appendingPathComponent(name)
                guard !manager.fileExists(atPath: target.path) else {
                    throw BackupTransferError.fileCollision
                }
                try manager.moveItem(at: staging.appendingPathComponent(name), to: target)
                installed.append(target)
            }
            try restoreValidatedJSON(normalized)
        } catch {
            for url in installed { try? manager.removeItem(at: url) }
            throw error
        }
        for name in oldNames {
            try? manager.removeItem(at: directory.appendingPathComponent(name))
        }
    }
}

private enum BackupTransferError: LocalizedError {
    case invalidJSON
    case documentTooLarge
    case fileCollision

    var errorDescription: String? {
        switch self {
        case .invalidJSON: "Dokumentmetadaten im Backup sind ungültig."
        case .documentTooLarge: "Ein Dokument überschreitet 40 MiB."
        case .fileCollision: "Eine Dokumentdatei existiert bereits."
        }
    }
}

private func backupDocumentNames(in data: Data) throws -> [String] {
    guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
          let documents = root["documents"] as? [[String: Any]] else {
        throw BackupTransferError.invalidJSON
    }
    var names: [String] = []
    for document in documents {
        guard let name = document["file"] as? String,
              name.range(of: #"^[a-f0-9-]+\.(pdf|jpg|png)$"#, options: .regularExpression) != nil else {
            throw BackupTransferError.invalidJSON
        }
        names.append(name)
    }
    return names
}
