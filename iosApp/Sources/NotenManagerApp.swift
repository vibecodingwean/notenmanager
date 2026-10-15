import SwiftUI
import UserNotifications

@main struct NotenManagerApp: App {
    @StateObject private var store = SchoolStore()
    @StateObject private var notifications = NotificationScheduler()
    @StateObject private var appearance = AppearanceStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(notifications)
                .environmentObject(appearance)
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var store: SchoolStore
    @EnvironmentObject private var notifications: NotificationScheduler
    @EnvironmentObject private var appearance: AppearanceStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var showProfiles = false

    var body: some View {
        VStack(spacing: 0) {
            if store.isDemo {
                HStack {
                    Text("DEMO").font(.headline.bold()).accessibilityIdentifier("demoBadge")
                    Spacer()
                    Button("Zu echten Daten") {
                        do { try store.returnToReal() }
                        catch { store.errorMessage = error.localizedDescription }
                    }.accessibilityIdentifier("returnToReal")
                }
                .padding(.horizontal, 16).padding(.vertical, 9)
                .foregroundStyle(.white).background(Color.orange)
            }
            Group {
                if store.needsRecovery {
                    RecoveryView()
                } else if store.snapshot.activeProfile == nil {
                    OnboardingView()
                } else {
                    TabView {
                        NavigationStack { DayView() }
                            .tabItem { Label("Dein Tag", systemImage: "calendar") }
                        NavigationStack { LearningView() }
                            .tabItem { Label("Lernen", systemImage: "book") }
                        NavigationStack { GradesView() }
                            .tabItem { Label("Meine Noten", systemImage: "chart.bar") }
                    }
                }
            }
            .id(store.isDemo ? "demoWorkspace" : "realWorkspace")
        }
        .tint(appearance.accent)
        .preferredColorScheme(appearance.dark ? .dark : .light)
        .foregroundStyle(appearance.ink)
        .lookFontDesign(appearance.selected.fontDesign)
        .alert("Hinweis", isPresented: Binding(get: { store.errorMessage != nil },
                                                 set: { if !$0 { store.errorMessage = nil } })) {
            Button("OK") { store.errorMessage = nil }
        } message: { Text(store.errorMessage ?? "") }
        .task { await notifications.refresh(dataJSON: store.notificationJSON); store.refreshDueEstimates() }
        .onChange(of: store.revision) { _ in
            Task { await notifications.refresh(dataJSON: store.notificationJSON) }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                store.refreshDueEstimates()
                Task { await notifications.refresh(dataJSON: store.notificationJSON) }
            }
        }
        .onReceive(Timer.publish(every: 60, on: .main, in: .common).autoconnect()) { _ in
            store.refreshDueEstimates()
        }
    }
}

private struct RecoveryView: View {
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 20) {
                Text("Gespeicherte Daten konnten nicht geladen werden.")
                    .font(.title2.bold())
                Text("Die vorhandene Datei bleibt erhalten. Stelle ein gültiges Backup wieder her, bevor du weiterarbeitest.")
                NavigationLink {
                    BackupView()
                } label: {
                    Label("Backup aus Datei wiederherstellen", systemImage: "square.and.arrow.down")
                }.accessibilityIdentifier("openRecoveryBackup")
                Spacer()
            }
            .padding()
            .navigationTitle("Daten wiederherstellen")
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var store: SchoolStore
    @State private var confirmDelete = false

    var body: some View {
        Form {
            Section("Testdaten") {
                Text("Eine fiktive 7. Klasse mit Stundenplan, Noten und zwei Beispielscans. Deine echten Daten bleiben getrennt.")
                    .font(.footnote).lookMuted()
                Button("Testdaten laden") {
                    do { try store.loadDemo() }
                    catch { store.errorMessage = error.localizedDescription }
                }.accessibilityIdentifier("loadDemo")
                if store.isDemo {
                    Button("Zu echten Daten") {
                        do { try store.returnToReal() }
                        catch { store.errorMessage = error.localizedDescription }
                    }
                }
                Button("Testdaten löschen", role: .destructive) { confirmDelete = true }
                    .accessibilityIdentifier("deleteDemo")
            }.listRowBackground(LookCardBackground())
        }
        .lookScreen()
        .navigationTitle("Einstellungen")
        .confirmationDialog("Testdaten löschen?", isPresented: $confirmDelete) {
            Button("Testdaten löschen", role: .destructive) {
                do { try store.deleteDemo() }
                catch { store.errorMessage = error.localizedDescription }
            }.accessibilityIdentifier("confirmDeleteDemo")
        } message: {
            Text("Der fiktive Stundenplan, die Noten und Beispielscans werden entfernt. Deine echten Daten bleiben erhalten.")
        }
    }
}

struct OnboardingView: View {
    @EnvironmentObject private var store: SchoolStore
    var onCreated: (() -> Void)? = nil
    @State private var step = 0
    @State private var state = "Bayern"
    @State private var school = "GYMNASIUM"
    @State private var grade = 8
    @State private var track = "NTG"
    @State private var year = SchoolDates.currentYear()
    @State private var term = "Ganzes Schuljahr"

    private let states = ["Bayern", "Baden-Württemberg", "Berlin", "Brandenburg", "Bremen", "Hamburg",
                          "Hessen", "Mecklenburg-Vorpommern", "Niedersachsen", "Nordrhein-Westfalen",
                          "Rheinland-Pfalz", "Saarland", "Sachsen", "Sachsen-Anhalt", "Schleswig-Holstein", "Thüringen"]
    private let schools = [("GRUNDSCHULE", "Grundschule"), ("GYMNASIUM", "Gymnasium"),
                           ("REALSCHULE", "Realschule"), ("MITTELSCHULE", "Mittelschule"),
                           ("M_ZUG", "Mittelschule · M-Zug")]

    private var grades: [Int] {
        switch school {
        case "GRUNDSCHULE": return Array(3...4)
        case "GYMNASIUM": return Array(5...13)
        case "MITTELSCHULE": return Array(5...9)
        case "M_ZUG": return Array(7...10)
        default: return Array(5...10)
        }
    }

    private var tracks: [String] {
        switch school {
        case "GRUNDSCHULE": return ["Grundschule"]
        case "GYMNASIUM": return ["NTG", "SG", "HG", "MuG", "WWG", "SWG"]
        case "REALSCHULE":
            return grade < 7 ? ["Kein Zweig"] : ["I", "II", "IIIa", "IIIb · Kunst", "IIIb · Werken",
                                                    "IIIb · Ernährung und Gesundheit", "IIIb · Sozialwesen"]
        default: return ["Technik", "Wirtschaft und Kommunikation", "Ernährung und Soziales"]
        }
    }

    private var terms: [String] {
        school == "GYMNASIUM" && grade >= 12
            ? ["12/1", "12/2", "13/1", "13/2"]
            : ["Ganzes Schuljahr", "1. Halbjahr", "2. Halbjahr"]
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section {
                        Image("Brand").resizable().scaledToFit().frame(width: 88, height: 88)
                        Text("Deine Schule, deine Noten").font(.title2.bold())
                        Text("Alles bleibt auf diesem Gerät.").lookMuted()
                    }
                    if step == 0 {
                        Section("Deine Schule") {
                            Picker("Bundesland", selection: $state) { ForEach(states, id: \.self) { Text($0) } }
                            Picker("Schulart", selection: $school) {
                                ForEach(schools, id: \.0) { Text($0.1).tag($0.0) }
                            }.onChange(of: school) { _ in resetSchoolChoices() }
                            Picker("Klasse", selection: $grade) {
                                ForEach(grades, id: \.self) { Text("\($0). Klasse").tag($0) }
                            }.onChange(of: grade) { _ in resetSchoolChoices() }
                            Picker("Zweig", selection: $track) { ForEach(tracks, id: \.self) { Text($0) } }
                            if school == "REALSCHULE" && grade < 7 {
                                Text("In Klasse 5 und 6 gibt es noch keinen Zweig.").font(.footnote)
                            }
                            if state != "Bayern" {
                                Text("Für dieses Bundesland liegt noch kein geprüftes Regelpaket vor.")
                                    .font(.footnote).foregroundStyle(.orange)
                            }
                        }
                        Button("Weiter") { step = 1 }.accessibilityIdentifier("onboardingNext")
                    } else {
                        Section("Dein Schuljahr") {
                            Stepper("Schuljahr \(SchoolDates.yearLabel(year))", value: $year, in: 2000...2099)
                            Picker("Zeitraum", selection: $term) { ForEach(terms, id: \.self) { Text($0) } }
                        }
                        Button("Profil anlegen") {
                            if store.createProfile(state: state, school: school, grade: grade, track: track,
                                                   year: year, term: term) {
                                onCreated?()
                            }
                        }.accessibilityIdentifier("createProfile")
                        Button("Zurück") { step = 0 }
                    }
                }.listRowBackground(LookCardBackground())
            }
            .lookScreen()
            .navigationTitle("NotenManager")
            .toolbar {
                NavigationLink { SettingsView() } label: {
                    Label("Einstellungen", systemImage: "gearshape")
                }.accessibilityIdentifier("openSettingsOnboarding")
            }
        }
    }

    private func resetSchoolChoices() {
        if !grades.contains(grade) { grade = grades.first ?? 8 }
        if !tracks.contains(track) { track = tracks.first ?? "NTG" }
        if !terms.contains(term) { term = terms.first ?? "Ganzes Schuljahr" }
    }
}

private struct NoteCard: View {
    @EnvironmentObject private var appearance: AppearanceStore
    let title: String
    let detail: String
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if appearance.selected.isYouth { LookRibbon(look: appearance.selected) }
            Text(title).font(.system(.headline, design: appearance.selected.fontDesign))
                .fontWeight(appearance.selected.isYouth ? .black : .semibold)
                .italic(appearance.lookID == "speed")
                .foregroundStyle(appearance.ink)
            Text(detail).font(.subheadline).foregroundStyle(appearance.selected.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background { LookCardBackground() }
    }
}

struct DayView: View {
    @EnvironmentObject private var store: SchoolStore
    @EnvironmentObject private var appearance: AppearanceStore
    @State private var editing = false
    @State private var estimatePrompt: SchoolEstimatePrompt?
    private var upcoming: [SchoolAssessment] {
        store.snapshot.activeAssessments.filter { $0.stage == "PLANNED" || $0.stage == "WRITTEN" }
            .sorted { $0.date < $1.date }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let summary = store.summary {
                    NoteCard(title: "Dein Schnitt", detail: summary.average.map(germanAverage) ?? "Noch keine Noten")
                    NoteCard(title: summary.title, detail: summary.reason)
                }
                if let running = store.snapshot.studies.first(where: { $0.end == nil }) {
                    TimelineView(.periodic(from: .now, by: 1)) { timeline in
                        NoteCard(title: "Lerntimer läuft", detail: "\(studyTitle(running.assessmentId)) · \(duration(running.seconds(at: timeline.date)))")
                    }
                    Button("Lernen beenden") { store.stopStudy() }
                }
                if let prompt = store.dueEstimates.first {
                    NoteCard(title: "Wie lief deine Probe?", detail: prompt.title)
                    Button("Gefühlte Note eintragen") { estimatePrompt = prompt }
                }
                let lessons = store.dayLessons(on: Date())
                if !lessons.isEmpty {
                    Text("Heute in der Schule").font(.title2.bold())
                    ForEach(lessons) { lesson in
                        NoteCard(title: lesson.subjectName, detail: "\(lesson.start)–\(lesson.end)")
                    }
                }
                Text("Deine nächsten Proben").font(.title2.bold())
                if upcoming.isEmpty { Text("Noch nichts geplant.").lookMuted() }
                ForEach(upcoming) { exam in
                    NavigationLink {
                        AssessmentDetailView(assessment: exam)
                    } label: {
                        NoteCard(title: exam.title, detail: "\(subjectName(exam.subjectId)) · \(exam.date)")
                    }.buttonStyle(.plain)
                }
            }.padding()
        }
        .lookScreen()
        .navigationTitle("Dein Tag")
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                NavigationLink { TimetableView() } label: { Image(systemName: "calendar.day.timeline.left") }
                    .accessibilityIdentifier("openTimetable")
                NavigationLink { AppearanceView() } label: { Image(systemName: "paintbrush") }
                    .accessibilityLabel("Looks auswählen")
                    .accessibilityIdentifier("openAppearanceHome")
                Button("Probe planen", systemImage: "plus") { editing = true }
            }
        }
        .sheet(isPresented: $editing) { AssessmentEditor() }
        .sheet(item: $estimatePrompt) { EstimateQuestionView(prompt: $0) }
    }

    private func subjectName(_ id: String) -> String {
        store.snapshot.subjects.first { $0.id == id }?.name ?? "Fach"
    }

    private func studyTitle(_ id: String) -> String {
        store.snapshot.assessments.first { $0.id == id }?.title ?? "Probe"
    }
}

private func duration(_ seconds: Int64) -> String {
    let minutes = seconds / 60
    return "\(minutes / 60):\(String(format: "%02lld", minutes % 60)):\(String(format: "%02lld", seconds % 60))"
}

func germanAverage(_ value: Double) -> String {
    String(format: "%.2f", locale: Locale(identifier: "de_DE"), arguments: [value])
}

struct EstimateQuestionView: View {
    @EnvironmentObject private var store: SchoolStore
    @Environment(\.dismiss) private var dismiss
    let prompt: SchoolEstimatePrompt
    @State private var grade = -1

    private var grades: [Int] {
        store.snapshot.activeProfile?.school == "GYMNASIUM" &&
        (store.snapshot.activeProfile?.grade ?? 0) >= 12 ? Array(0...15) : Array(1...6)
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section {
                        Text(prompt.title).font(.headline)
                        Text("Was glaubst du: Welche Note hast du geschrieben?")
                        Text("Dein Gefühl zählt nicht zum Notenschnitt.").lookMuted()
                        Picker("Deine gefühlte Note", selection: $grade) {
                            Text("Bitte wählen").tag(-1)
                            ForEach(grades, id: \.self) { Text("\($0)").tag($0) }
                        }
                    }
                    Button("Einschätzung speichern") {
                        store.setEstimate(id: prompt.id, grade: grade)
                        if store.errorMessage == nil { dismiss() }
                    }.disabled(grade < 0)
                    Button("Weiß ich nicht") {
                        store.skipEstimate(id: prompt.id)
                        if store.errorMessage == nil { dismiss() }
                    }
                }.listRowBackground(LookCardBackground())
            }
            .lookScreen()
            .navigationTitle("Wie lief deine Probe?")
            .toolbar { Button("Schließen") { dismiss() } }
        }
    }
}

struct LearningView: View {
    @EnvironmentObject private var store: SchoolStore
    var body: some View {
        List {
            Group {
                if store.snapshot.activeAssessments.isEmpty {
                    Text("Plane zuerst eine Probe in „Dein Tag“. ")
                }
                ForEach(store.snapshot.activeAssessments.sorted { $0.date < $1.date }) { exam in
                    NavigationLink {
                        AssessmentDetailView(assessment: exam)
                    } label: {
                        VStack(alignment: .leading) {
                            Text(exam.title).font(.headline)
                            Text("\(exam.date) · \(exam.learningNotes.isEmpty ? "Lernstoff ergänzen" : exam.learningNotes)")
                                .lineLimit(2).lookMuted()
                        }
                    }
                }
            }.listRowBackground(LookCardBackground())
        }
        .lookScreen()
        .navigationTitle("Lernen")
    }
}

struct GradesView: View {
    @EnvironmentObject private var store: SchoolStore
    @EnvironmentObject private var notifications: NotificationScheduler
    @State private var addingSubject = false
    @State private var addingProfile = false
    @State private var profileToDelete: SchoolProfile?

    var body: some View {
        List {
            Group {
                if let summary = store.summary {
                    Section("Orientierung") {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(summary.average.map { "Gesamtschnitt \(germanAverage($0))" } ?? "Noch kein Gesamtschnitt")
                                .font(.title2.bold())
                            Text(summary.basis).lookMuted()
                        }
                        VStack(alignment: .leading, spacing: 8) {
                            Text(summary.title).font(.headline)
                            Text(summary.reason)
                            Text(summary.advice).font(.footnote).lookMuted()
                        }
                    }
                }
                Section("Fächer") {
                    ForEach(store.snapshot.activeSubjects.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }) { subject in
                        NavigationLink {
                            SubjectDetailView(subject: subject)
                        } label: {
                            HStack {
                                Text(subject.name)
                                Spacer()
                                if let average = store.summary?.standings.first(where: { $0.subjectId == subject.id })?.average {
                                    Text(germanAverage(average)).lookMuted()
                                } else { Text("–").lookMuted() }
                            }
                        }
                    }
                    Button("Fach hinzufügen", systemImage: "plus") { addingSubject = true }
                }
                profilesSection
                Section("Daten") {
                    NavigationLink("Einstellungen") { SettingsView() }
                    NavigationLink("Verschlüsseltes Backup") { BackupView() }
                    NavigationLink("Zeugnis & Abschluss") { OfficialResultsView() }
                }
                Section("Darstellung") {
                    NavigationLink {
                        AppearanceView()
                    } label: {
                        Label("Look auswählen", systemImage: "paintbrush")
                    }
                }
                Section("Erinnerungen") {
                    Button("Lokale Mitteilungen erlauben") {
                        Task {
                            if await notifications.requestAuthorization() {
                                await notifications.refresh(dataJSON: store.notificationJSON)
                            }
                        }
                    }
                    if notifications.authorizationStatus == .denied {
                        Text("Mitteilungen sind aus. Fällige Einschätzungsfragen bleiben in „Dein Tag“ sichtbar.")
                            .font(.footnote)
                    }
                    if let error = notifications.schedulingError {
                        Text(error).font(.footnote).foregroundStyle(.orange)
                    }
                }
            }.listRowBackground(LookCardBackground())
        }
        .lookScreen()
        .navigationTitle("Meine Noten")
        .toolbar {
            NavigationLink {
                AppearanceView()
            } label: {
                Label("Looks", systemImage: "paintbrush")
            }
            .accessibilityIdentifier("openAppearance")
            NavigationLink("Zeugnis") { OfficialResultsView() }
                .accessibilityIdentifier("openOfficial")
        }
        .sheet(isPresented: $addingSubject) { SubjectEditor() }
        .sheet(isPresented: $addingProfile) { OnboardingView(onCreated: { addingProfile = false }) }
        .alert(profileDeleteTitle, isPresented: Binding(
            get: { profileToDelete != nil },
            set: { if !$0 { profileToDelete = nil } }
        )) {
            Button("Profil löschen", role: .destructive) {
                if let profileToDelete { store.deleteProfile(profileToDelete) }
                profileToDelete = nil
            }
            Button("Abbrechen", role: .cancel) { profileToDelete = nil }
        } message: {
            Text("Alle Fächer, Noten, Lernzeiten und Dokumente dieses Profils werden dauerhaft gelöscht.")
        }
    }

    private var profileDeleteTitle: String {
        guard let profileToDelete else { return "Schulprofil löschen?" }
        return "\(profileToDelete.schoolName) \(profileToDelete.grade). Klasse löschen?"
    }

    private var profilesSection: some View {
        Section {
            ForEach(store.snapshot.profiles) { profile in
                HStack {
                    Button {
                        store.selectProfile(profile.id)
                    } label: {
                        Text("\(profile.schoolName) · \(profile.grade). Klasse · \(SchoolDates.yearLabel(profile.year))")
                        if profile.id == store.snapshot.activeProfileId {
                            Spacer()
                            Image(systemName: "checkmark")
                        }
                    }
                    .buttonStyle(.borderless)
                    .accessibilityIdentifier("profileSelect_\(profile.id)")
                    .accessibilityValue(profile.id == store.snapshot.activeProfileId ? "Aktiv" : "Inaktiv")
                    Button(role: .destructive) {
                        profileToDelete = profile
                    } label: {
                        Image(systemName: "trash")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("\(profile.schoolName) \(profile.grade). Klasse löschen")
                }
            }
            Button("Weiteres Profil") { addingProfile = true }
        } header: {
            Text("Schulprofile")
        } footer: {
            Text("Falsche Schulart? Lege zuerst das richtige Profil an und lösche danach das alte.")
        }
    }
}

extension SchoolProfile {
    var schoolName: String {
        switch school {
        case "GRUNDSCHULE": return "Grundschule"
        case "GYMNASIUM": return "Gymnasium"
        case "REALSCHULE": return "Realschule"
        case "M_ZUG": return "Mittelschule · M-Zug"
        default: return "Mittelschule"
        }
    }
}

struct SubjectDetailView: View {
    @EnvironmentObject private var store: SchoolStore
    let subject: SchoolSubject
    @State private var addingAssessment = false
    @State private var editingSubject = false

    private var currentSubject: SchoolSubject {
        store.snapshot.subjects.first { $0.id == subject.id } ?? subject
    }

    var body: some View {
        List {
            Group {
                if let row = store.summary?.standings.first(where: { $0.subjectId == subject.id }) {
                Section("Leistungsstand") {
                        Text(row.average.map { "Schnitt \(germanAverage($0))" } ?? "Noch keine tatsächliche Note")
                        if let grade = row.grade { Text("\(row.annual ? "Jahreszeugnisnote" : "Prognose"): \(grade)") }
                    }
                }
                Section("Fachregeln") {
                    Button("Fach und Gewichtungen bearbeiten", systemImage: "slider.horizontal.3") {
                        editingSubject = true
                    }
                    if store.snapshot.activeProfile?.school == "GYMNASIUM" &&
                        currentSubject.annualSchoolworks == nil {
                        Text("Schulaufgabenanzahl fehlt. Der rechnerische Fachschnitt bleibt bis zur Eingabe offen.")
                            .font(.footnote).lookMuted()
                    }
                }
                Section("Leistungsnachweise") {
                    ForEach(store.snapshot.assessments.filter { $0.subjectId == subject.id }.sorted { $0.date < $1.date }) { exam in
                        NavigationLink {
                            AssessmentDetailView(assessment: exam)
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(exam.title)
                                    Text(exam.date).font(.footnote).lookMuted()
                                }
                                Spacer()
                                Text(exam.actual.map(String.init) ?? "–")
                            }
                        }
                    }
                    Button("Leistungsnachweis hinzufügen", systemImage: "plus") { addingAssessment = true }
                }
            }.listRowBackground(LookCardBackground())
        }
        .lookScreen()
        .navigationTitle(currentSubject.name)
        .sheet(isPresented: $addingAssessment) { AssessmentEditor(subjectId: subject.id) }
        .sheet(isPresented: $editingSubject) { SubjectEditor(existing: currentSubject) }
    }
}

struct SubjectEditor: View {
    @EnvironmentObject private var store: SchoolStore
    @Environment(\.dismiss) private var dismiss
    let existing: SchoolSubject?
    @State private var name: String
    @State private var core: Bool
    @State private var promotion: Bool
    @State private var schoolworks: String
    @State private var advanced: Bool
    @State private var examSubject: Bool
    @State private var smallWeight: String
    @State private var oralWeight: String
    @State private var practicalWeight: String
    @State private var weightsConfirmed: Bool

    init(existing: SchoolSubject? = nil) {
        self.existing = existing
        _name = State(initialValue: existing?.name ?? "")
        _core = State(initialValue: existing?.core ?? false)
        _promotion = State(initialValue: existing?.promotion ?? true)
        _schoolworks = State(initialValue: existing?.annualSchoolworks.map(String.init) ?? "")
        _advanced = State(initialValue: existing?.advanced ?? false)
        _examSubject = State(initialValue: existing?.examSubject ?? false)
        _smallWeight = State(initialValue: existing.map { String($0.smallWrittenWeight) } ?? "1")
        _oralWeight = State(initialValue: existing.map { String($0.oralWeight) } ?? "1")
        _practicalWeight = State(initialValue: existing.map { String($0.practicalWeight) } ?? "1")
        _weightsConfirmed = State(initialValue: existing?.weightsConfirmed ?? false)
    }

    private func parsedWeight(_ value: String) -> Double? {
        guard let number = Double(value.replacingOccurrences(of: ",", with: ".")),
              number.isFinite, number > 0, number <= 1000 else { return nil }
        return number
    }

    private var valid: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        (schoolworks.isEmpty || schoolworks.count <= 2 && (Int(schoolworks) ?? -1) <= 12 &&
            (Int(schoolworks) ?? -1) >= 0) &&
        parsedWeight(smallWeight) != nil && parsedWeight(oralWeight) != nil &&
        parsedWeight(practicalWeight) != nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    TextField("Fachname", text: $name).accessibilityIdentifier("subjectName")
                    Toggle("Haupt-/Kernfach", isOn: $core)
                    Toggle("Vorrückungsfach", isOn: $promotion)
                    if store.snapshot.activeProfile?.school == "GYMNASIUM" &&
                        (store.snapshot.activeProfile?.grade ?? 0) < 12 {
                        TextField("Schulaufgaben im Schuljahr (0–12)", text: $schoolworks)
                            .keyboardType(.numberPad)
                        Text("Für Gymnasium-Fachschnitte ist die Schulaufgabenanzahl nötig.")
                            .font(.footnote).lookMuted()
                    }
                    if store.snapshot.activeProfile?.school == "GYMNASIUM" &&
                        (store.snapshot.activeProfile?.grade ?? 0) >= 12 {
                        Toggle("Leistungsfach", isOn: $advanced)
                        Toggle("Abiturprüfungsfach", isOn: $examSubject)
                    }
                    Section("Gewichtungen") {
                        TextField("Kleine schriftliche Leistungen", text: $smallWeight)
                            .keyboardType(.decimalPad)
                        TextField("Mündliche Leistungen", text: $oralWeight)
                            .keyboardType(.decimalPad)
                        TextField("Praktische Leistungen", text: $practicalWeight)
                            .keyboardType(.decimalPad)
                        Toggle("Schulische Gewichtungen bestätigt", isOn: $weightsConfirmed)
                        Text("1 bedeutet keine zusätzliche Änderung. Nur bestätigte Schulgewichte eintragen.")
                            .font(.footnote).lookMuted()
                    }
                }.listRowBackground(LookCardBackground())
            }
            .lookScreen()
            .navigationTitle(existing == nil ? "Fach hinzufügen" : "Fach bearbeiten")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Speichern") {
                        guard let small = parsedWeight(smallWeight),
                              let oral = parsedWeight(oralWeight),
                              let practical = parsedWeight(practicalWeight) else { return }
                        store.saveSubject(id: existing?.id ?? "", name: name, core: core,
                            promotion: promotion, annualSchoolworks: Int(schoolworks),
                            advanced: advanced, examSubject: examSubject,
                            smallWrittenWeight: small, oralWeight: oral,
                            practicalWeight: practical, weightsConfirmed: weightsConfirmed)
                        if store.errorMessage == nil { dismiss() }
                    }.disabled(!valid)
                }
            }
        }
    }
}

struct AssessmentDetailView: View {
    @EnvironmentObject private var store: SchoolStore
    @Environment(\.dismiss) private var dismiss
    let assessment: SchoolAssessment
    @State private var editing = false
    @State private var deleting = false
    @State private var correcting: SchoolStudy?

    var body: some View {
        List {
            Group {
                Section("Leistungsnachweis") {
                    LabeledContent("Termin", value: assessment.date)
                    LabeledContent("Status", value: assessment.stage)
                    if let actual = assessment.actual { LabeledContent("Note", value: "\(actual)") }
                    if let estimate = assessment.estimate { LabeledContent("Einschätzung", value: "\(estimate)") }
                }
                Section("Lernstoff / Unterlagen") {
                    Text(assessment.learningNotes.isEmpty ? "Noch keine Notizen" : assessment.learningNotes)
                }
                DocumentSection(assessmentId: assessment.id)
                Section("Lernzeiten") {
                    if let running = store.snapshot.studies.first(where: { $0.end == nil }) {
                        if running.assessmentId == assessment.id {
                            TimelineView(.periodic(from: .now, by: 1)) { timeline in
                                Text("Läuft: \(duration(running.seconds(at: timeline.date)))")
                            }
                            Button("Lernen beenden") { store.stopStudy() }
                        } else {
                            Text("Für eine andere Probe läuft schon ein Lerntimer.")
                                .lookMuted()
                        }
                    } else {
                        Button("Lernen starten", systemImage: "play.fill") { store.startStudy(for: assessment.id) }
                    }
                    ForEach(store.snapshot.studies.filter { $0.assessmentId == assessment.id && $0.end != nil }) { study in
                        Button("\(duration(study.seconds(at: Date()))) · Dauer berichtigen") { correcting = study }
                        if !study.correction.isEmpty { Text(study.correction).font(.footnote) }
                    }
                    if let remaining = store.remainingLessons(for: assessment.id, at: Date()),
                        assessment.stage == "PLANNED" {
                        Text("Noch \(remaining) Unterrichtsblöcke bis zur Probe")
                            .font(.footnote).lookMuted()
                    }
                }
                Button("Bearbeiten", systemImage: "pencil") { editing = true }
                Button("Löschen", role: .destructive) { deleting = true }
            }.listRowBackground(LookCardBackground())
        }
        .lookScreen()
        .navigationTitle(assessment.title)
        .sheet(isPresented: $editing) { AssessmentEditor(assessment: assessment) }
        .sheet(item: $correcting) { StudyCorrectionView(study: $0) }
        .confirmationDialog("Leistungsnachweis löschen?", isPresented: $deleting) {
            Button("Löschen", role: .destructive) { store.deleteAssessment(assessment.id); dismiss() }
        }
    }
}

struct StudyCorrectionView: View {
    @EnvironmentObject private var store: SchoolStore
    @Environment(\.dismiss) private var dismiss
    let study: SchoolStudy
    @State private var minutes = ""
    @State private var reason = ""

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    TextField("Tatsächliche Minuten", text: $minutes).keyboardType(.numberPad)
                    TextField("Warum geändert?", text: $reason)
                    Button("Dauer speichern") {
                        guard let value = Int(minutes), value >= 0, value <= 10080 else { return }
                        store.correctStudy(id: study.id, minutes: value, reason: reason)
                        if store.errorMessage == nil { dismiss() }
                    }.disabled((Int(minutes) ?? -1) < 0 || (Int(minutes) ?? 10081) > 10080 || reason.isEmpty)
                }.listRowBackground(LookCardBackground())
            }
            .lookScreen()
            .navigationTitle("Lernzeit berichtigen")
            .toolbar { Button("Abbrechen") { dismiss() } }
        }
    }
}

struct AssessmentEditor: View {
    @EnvironmentObject private var store: SchoolStore
    @Environment(\.dismiss) private var dismiss
    let assessment: SchoolAssessment?
    @State private var subjectId: String
    @State private var title: String
    @State private var date: Date
    @State private var kind: String
    @State private var stage: String
    @State private var notes: String
    @State private var weight: String
    @State private var actual: Int
    @State private var estimate: Int

    init(subjectId: String = "", assessment: SchoolAssessment? = nil) {
        self.assessment = assessment
        _subjectId = State(initialValue: assessment?.subjectId ?? subjectId)
        _title = State(initialValue: assessment?.title ?? "")
        let formatter = SchoolDates.formatter("yyyy-MM-dd")
        _date = State(initialValue: assessment.flatMap { formatter.date(from: $0.date) } ?? Date())
        _kind = State(initialValue: assessment?.kind ?? "SCHOOLWORK")
        _stage = State(initialValue: assessment?.stage ?? "PLANNED")
        _notes = State(initialValue: assessment?.learningNotes ?? "")
        _weight = State(initialValue: assessment.map { String($0.weight) } ?? "1")
        _actual = State(initialValue: assessment?.actual ?? -1)
        _estimate = State(initialValue: assessment?.estimate ?? -1)
    }

    private var kindChoices: [(String, String)] {
        switch store.snapshot.activeProfile?.school {
        case "GRUNDSCHULE": return [("TEST", "Probe"), ("ORAL", "Mündliche Note"), ("PRACTICAL", "Praktische Leistung")]
        case "REALSCHULE": return [("SCHOOLWORK", "Schulaufgabe"), ("ANNOUNCED_TEST", "Angesagter Test"),
                                     ("EX", "Stegreifaufgabe"), ("ORAL", "Mündliche Note"),
                                     ("PRACTICAL", "Praktische Leistung"), ("SHORT_TEST", "Kurzarbeit"),
                                     ("PERFORMANCE_TEST", "Fachlicher Leistungstest")]
        case "GYMNASIUM": return [("SCHOOLWORK", "Schulaufgabe"), ("EX", "Stegreifaufgabe"),
                                    ("ORAL", "Mündliche Note"), ("PRACTICAL", "Praktische Leistung"),
                                    ("SEMINAR", "Seminarleistung"), ("INSTRUMENT", "Instrument / Gesang"),
                                    ("ART_PROJECT", "Künstlerisches Projekt"),
                                    ("PRACTICAL_EXAM", "Praktische Fachprüfung"),
                                    ("THEORY_LARGE", "Sporttheorie · Schulaufgabe"),
                                    ("THEORY_SMALL", "Sporttheorie · kleine Leistung"),
                                    ("SHORT_TEST", "Kurzarbeit"), ("PERFORMANCE_TEST", "Fachlicher Leistungstest")]
        default: return [("TEST", "Probe"), ("ORAL", "Mündliche Note"),
                         ("PRACTICAL", "Praktische Leistung"), ("PROJECT", "Projektprüfung")]
        }
    }

    private var grades: [Int] { store.snapshot.activeProfile?.school == "GYMNASIUM" &&
        (store.snapshot.activeProfile?.grade ?? 0) >= 12 ? Array(0...15) : Array(1...6) }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section("Leistungsnachweis") {
                        Picker("Fach", selection: $subjectId) {
                            Text("Bitte wählen").tag("")
                            ForEach(store.snapshot.activeSubjects.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }) {
                                Text($0.name).tag($0.id)
                            }
                        }.accessibilityIdentifier("assessmentSubject")
                        TextField("Titel", text: $title).accessibilityIdentifier("assessmentTitle")
                        DatePicker("Datum", selection: $date, displayedComponents: .date)
                        Picker("Art", selection: $kind) {
                            ForEach(kindChoices.sorted { $0.1.localizedStandardCompare($1.1) == .orderedAscending }, id: \.0) {
                                Text($0.1).tag($0.0)
                            }
                        }.accessibilityIdentifier("assessmentKind")
                        Picker("Status", selection: $stage) {
                            Text("Geplant").tag("PLANNED")
                            Text("Geschrieben").tag("WRITTEN")
                            Text("Benotet").tag("GRADED")
                            Text("Abgesagt").tag("CANCELLED")
                        }
                    }
                    if stage == "GRADED" {
                        Picker("Tatsächliche Note", selection: $actual) {
                            Text("Bitte wählen").tag(-1)
                            ForEach(grades, id: \.self) { Text("\($0)").tag($0) }
                        }.accessibilityIdentifier("actualGrade")
                    } else if stage == "PLANNED" || stage == "WRITTEN" {
                        Picker("Einschätzung", selection: $estimate) {
                            Text("Noch keine").tag(-1)
                            ForEach(grades, id: \.self) { Text("\($0)").tag($0) }
                        }
                    }
                    Section("Lernstoff / Unterlagen") {
                        TextField("Worum geht es?", text: $notes, axis: .vertical)
                            .lineLimit(3...8)
                    }
                    Section("Gewichtung") {
                        TextField("Einzelgewicht", text: $weight).keyboardType(.decimalPad)
                        Text("1 bedeutet normales Gewicht. Nur von der Schule mitgeteilte Abweichungen eintragen.")
                            .font(.footnote).lookMuted()
                    }
                }.listRowBackground(LookCardBackground())
            }
            .lookScreen()
            .navigationTitle(assessment == nil ? "Probe planen" : "Bearbeiten")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Speichern") {
                        let formatter = SchoolDates.formatter("yyyy-MM-dd")
                        guard let parsedWeight = Double(weight.replacingOccurrences(of: ",", with: ".")),
                              parsedWeight.isFinite, parsedWeight > 0, parsedWeight <= 1000 else { return }
                        let selectedKind = assessment == nil && !kindChoices.contains(where: { $0.0 == kind })
                            ? kindChoices[0].0 : kind
                        store.saveAssessment(id: assessment?.id ?? "", subjectId: subjectId,
                                             title: title, date: formatter.string(from: date),
                                             kind: selectedKind, stage: stage, notes: notes,
                                             actual: stage == "GRADED" ? actual : nil,
                                             estimate: estimate < 0 ? nil : estimate, weight: parsedWeight)
                        if store.errorMessage == nil { dismiss() }
                    }
                    .disabled(subjectId.isEmpty || title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                              (stage == "GRADED" && actual < 0) ||
                              !(Double(weight.replacingOccurrences(of: ",", with: "."))
                                .map { $0.isFinite && $0 > 0 && $0 <= 1000 } ?? false))
                    .accessibilityIdentifier("saveAssessment")
                }
            }
        }
        .onAppear {
            if assessment == nil && !kindChoices.contains(where: { $0.0 == kind }) {
                kind = kindChoices[0].0
            }
        }
    }
}
