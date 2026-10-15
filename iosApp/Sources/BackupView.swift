import SwiftUI
import UniformTypeIdentifiers

private struct BackupArchive: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    static var writableContentTypes: [UTType] { [.data] }
    var data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        self.data = data
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

struct BackupView: View {
    @EnvironmentObject private var store: SchoolStore
    @State private var password = ""
    @State private var confirmation = ""
    @State private var archive: BackupArchive?
    @State private var exporting = false
    @State private var importing = false
    @State private var info: String?

    var body: some View {
        Form {
            Group {
                Section {
                    Text("Speichere deine Schulprofile, Noten und Dokumente als verschlüsselte Datei. Bewahre Datei und Passwort sicher auf.")
                    SecureField("Backup-Passwort (mindestens 8 Zeichen)", text: $password)
                        .textContentType(.newPassword)
                    SecureField("Passwort wiederholen", text: $confirmation)
                        .textContentType(.newPassword)
                }
                Section {
                    if store.isDemo {
                        Text("Backups sind bei Testdaten ausgeschaltet. Wechsle zuerst zu deinen echten Daten.")
                            .font(.footnote).lookMuted()
                    }
                    Button("Backup erstellen", systemImage: "square.and.arrow.up") { export() }
                        .disabled(store.isDemo || store.needsRecovery || password.count < 8 || password != confirmation)
                    Button("Backup wiederherstellen", systemImage: "square.and.arrow.down") { importing = true }
                        .disabled(store.isDemo || password.isEmpty)
                }
                Section {
                    Text("Beim Wiederherstellen wird der aktuelle Schulstand ersetzt. Ein vorhandenes Backup vorher zu speichern ist sinnvoll.")
                        .font(.footnote).lookMuted()
                }
            }.listRowBackground(LookCardBackground())
        }
        .lookScreen()
        .navigationTitle("Datensicherung")
        .fileExporter(isPresented: $exporting, document: archive, contentType: .data,
                      defaultFilename: "NotenManager-\(today()).sabackup") { result in
            if case .failure(let error) = result { store.errorMessage = error.localizedDescription }
            archive = nil
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item]) { result in
            do {
                let url = try result.get()
                guard url.startAccessingSecurityScopedResource() else { throw CocoaError(.fileReadNoPermission) }
                defer { url.stopAccessingSecurityScopedResource() }
                let values = try url.resourceValues(forKeys: [.fileSizeKey])
                guard (values.fileSize ?? 0) <= 160 * 1024 * 1024 else { throw CocoaError(.fileReadTooLarge) }
                try store.importBackup(archive: Data(contentsOf: url), password: password)
                info = "Backup vollständig wiederhergestellt."
            } catch { store.errorMessage = error.localizedDescription }
        }
        .alert("Datensicherung", isPresented: Binding(get: { info != nil },
                                                       set: { if !$0 { info = nil } })) {
            Button("OK") { info = nil }
        } message: { Text(info ?? "") }
    }

    private func export() {
        do {
            archive = BackupArchive(data: try store.exportBackup(password: password))
            exporting = true
        } catch { store.errorMessage = error.localizedDescription }
    }

    private func today() -> String {
        SchoolDates.formatter("yyyy-MM-dd").string(from: Date())
    }
}
