import PDFKit
import QuickLook
import SwiftUI
import UniformTypeIdentifiers
import VisionKit

struct DocumentSection: View {
    @EnvironmentObject private var store: SchoolStore
    let assessmentId: String
    @State private var importing = false
    @State private var scanning = false
    @State private var importToken: SchoolStore.DocumentImportToken?
    @State private var scanToken: SchoolStore.DocumentImportToken?

    var body: some View {
        Section("Dokumente") {
            ForEach(store.snapshot.documents.filter { $0.assessmentId == assessmentId }) { document in
                NavigationLink {
                    DocumentPreview(url: store.documentURL(document), mime: document.mime)
                        .lookScreen()
                        .navigationTitle(document.name)
                } label: {
                    Label(document.name, systemImage: document.mime == "application/pdf" ? "doc.richtext" : "photo")
                }
                .swipeActions {
                    Button("Löschen", role: .destructive) { store.deleteDocument(document) }
                }
            }
            Button("Datei importieren", systemImage: "square.and.arrow.down") {
                importToken = store.documentToken(for: assessmentId)
                importing = importToken != nil
            }
            Button("Probe fotografieren", systemImage: "camera.viewfinder") {
                scanToken = store.documentToken(for: assessmentId)
                scanning = scanToken != nil
            }
                .disabled(!VNDocumentCameraViewController.isSupported)
            if !VNDocumentCameraViewController.isSupported {
                Text("Der Dokumentenscanner benötigt eine iPhone- oder iPad-Kamera.")
                    .font(.footnote).lookMuted()
            }
        }.listRowBackground(LookCardBackground())
        .fileImporter(isPresented: $importing, allowedContentTypes: [.pdf, .jpeg, .png]) { result in
            guard let token = importToken, store.acceptsDocumentToken(token) else { return }
            importToken = nil
            do {
                let url = try result.get()
                guard url.startAccessingSecurityScopedResource() else {
                    throw CocoaError(.fileReadNoPermission)
                }
                defer { url.stopAccessingSecurityScopedResource() }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                guard size > 0 && size <= 40 * 1024 * 1024 else { throw SchoolStore.StoreError.documentTooLarge }
                let type = try url.resourceValues(forKeys: [.contentTypeKey]).contentType
                let mime: String
                switch type {
                case .pdf: mime = "application/pdf"
                case .jpeg: mime = "image/jpeg"
                case .png: mime = "image/png"
                default: throw SchoolStore.StoreError.unsupportedDocument
                }
                store.importDocument(data: try Data(contentsOf: url), name: url.lastPathComponent,
                                     mime: mime, assessmentId: assessmentId, token: token)
            } catch {
                if store.acceptsDocumentToken(token) { store.errorMessage = error.localizedDescription }
            }
        }
        .sheet(isPresented: $scanning) {
            if let token = scanToken {
                DocumentCamera { result in
                    scanning = false
                    scanToken = nil
                    guard store.acceptsDocumentToken(token) else { return }
                    switch result {
                    case .success(let data):
                        store.importDocument(data: data, name: "Scan.pdf", mime: "application/pdf",
                                             assessmentId: assessmentId, token: token)
                    case .failure(let error): store.errorMessage = error.localizedDescription
                    }
                }
            }
        }
    }
}

private struct DocumentPreview: View {
    let url: URL
    let mime: String
    @State private var zoom = 1.0

    var body: some View {
        if mime == "image/png" || mime == "image/jpeg" {
            if let image = UIImage(contentsOfFile: url.path) {
                GeometryReader { geometry in
                    ScrollView([.horizontal, .vertical]) {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(width: geometry.size.width * zoom)
                            .accessibilityLabel("Dokumentbild")
                            .accessibilityIdentifier("scanImagePreview")
                    }
                }
                .toolbar {
                    Button("Verkleinern", systemImage: "minus.magnifyingglass") {
                        zoom = max(1, zoom / 1.5)
                    }.disabled(zoom <= 1)
                    Button("Vergrößern", systemImage: "plus.magnifyingglass") {
                        zoom = min(4, zoom * 1.5)
                    }.disabled(zoom >= 4)
                }
            } else {
                Text("Bild nicht verfügbar")
            }
        } else {
            PDFQuickLookPreview(url: url)
        }
    }
}

private struct PDFQuickLookPreview: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: QLPreviewController, context: Context) {
        context.coordinator.url = url
        controller.reloadData()
    }

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        var url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController,
                               previewItemAt index: Int) -> QLPreviewItem { url as NSURL }
    }
}

private struct DocumentCamera: UIViewControllerRepresentable {
    let onCompletion: (Result<Data, Error>) -> Void

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ controller: VNDocumentCameraViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onCompletion: onCompletion) }

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let onCompletion: (Result<Data, Error>) -> Void
        init(onCompletion: @escaping (Result<Data, Error>) -> Void) { self.onCompletion = onCompletion }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController,
                                          didFinishWith scan: VNDocumentCameraScan) {
            guard scan.pageCount > 0 && scan.pageCount <= 30 else {
                onCompletion(.failure(ScanError.pageLimit)); return
            }
            let pdf = PDFDocument()
            for index in 0..<scan.pageCount {
                guard let page = PDFPage(image: scan.imageOfPage(at: index)) else {
                    onCompletion(.failure(ScanError.invalidImage)); return
                }
                pdf.insert(page, at: index)
            }
            guard let data = pdf.dataRepresentation(), data.count <= 40 * 1024 * 1024 else {
                onCompletion(.failure(ScanError.sizeLimit)); return
            }
            onCompletion(.success(data))
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            controller.dismiss(animated: true)
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController,
                                          didFailWithError error: Error) {
            onCompletion(.failure(error))
        }
    }

    private enum ScanError: LocalizedError {
        case pageLimit, invalidImage, sizeLimit
        var errorDescription: String? {
            switch self {
            case .pageLimit: "Der Scan darf höchstens 30 Seiten enthalten."
            case .invalidImage: "Eine Scan-Seite konnte nicht verarbeitet werden."
            case .sizeLimit: "Der Scan ist größer als 40 MiB."
            }
        }
    }
}
