import SwiftUI

struct OfficialResultsView: View {
    @EnvironmentObject private var store: SchoolStore
    @State private var rosterComplete = false
    @State private var previousCompensation = -1
    @State private var socialInternship = false

    var body: some View {
        List {
            Group {
                if let profile = store.snapshot.activeProfile {
                    Section {
                        Text("Rechnerische Schnitte und gefühlte Noten werden nicht als Zeugnisnoten übernommen. Trage nur Ergebnisse ein, die deine Schule mitgeteilt hat.")
                            .lookMuted()
                    }
                    if profile.school == "GRUNDSCHULE" {
                        transferSections(profile: profile)
                    } else {
                        annualGrades(profile: profile)
                        promotionSection(profile: profile)
                        GraduationEditor(profile: profile)
                    }
                    Section("Geltungsbereich") {
                        Text("Die geprüften Regeln betreffen reguläre Schülerlaufbahnen in Bayern im Schuljahr 2026/27. Andere Bundesländer, historische Schuljahre und Sonderlaufbahnen benötigen eine Prüfung durch die Schule.")
                            .font(.footnote).lookMuted()
                    }
                } else {
                    Text("Bitte zuerst ein Schulprofil auswählen.")
                }
            }.listRowBackground(LookCardBackground())
        }
        .lookScreen()
        .navigationTitle("Zeugnis & Abschluss")
    }

    @ViewBuilder private func annualGrades(profile: SchoolProfile) -> some View {
        Section("Offizielle Jahreszeugnisnoten") {
            Text("Diese Einträge stammen aus dem Zeugnis. Der Schnitt unter „Meine Noten“ bleibt eine Orientierung.")
                .font(.footnote).lookMuted()
            ForEach(store.snapshot.activeSubjects.sorted { $0.name < $1.name }) { subject in
                OfficialGradeRow(subject: subject, period: "Jahreszeugnis",
                                 points: profile.school == "GYMNASIUM" && profile.grade >= 12,
                                 allowOmission: true)
            }
        }
    }

    @ViewBuilder private func transferSections(profile: SchoolProfile) -> some View {
        Section("Dein nächster Schulweg") {
            if profile.grade == 3 {
                Text("Klasse 3: Der Übertritt folgt nach Klasse 4. Die angezeigten Noten sind eine Vorschau.")
                    .lookMuted()
            }
            if let overview = store.transferOverview() {
                Text(overview.official ? "Noten aus dem Übertrittszeugnis" : "Vorschau aus bisherigen Noten")
                    .font(.headline)
                Text(overview.average.map { "Schnitt \(germanAverage($0))" } ?? "Noch kein vollständiger Schnitt")
                    .font(.title2.bold())
                ForEach(Array(["Deutsch", "Mathematik", "Heimat- und Sachunterricht"].enumerated()), id: \.offset) { index, name in
                    LabeledContent(name, value: index < overview.values.count
                                   ? overview.values[index].map(germanAverage) ?? "–" : "–")
                }
                if overview.duplicateSubjects {
                    Text("Ein Übertrittsfach ist mehrfach angelegt. Bitte die Fächerliste klären.")
                        .foregroundStyle(.orange)
                }
                if !overview.supported {
                    Text("Für dieses Bundesland oder Schuljahr liegt kein geprüftes Übertrittsregelpaket vor.")
                        .foregroundStyle(.orange)
                }
                Text(overview.official
                     ? "Alle drei Zeugnisnoten müssen vorliegen. Das Ergebnis nutzt ihre ungerundete Summe; der angezeigte Schnitt ist gerundet. Fehlende Werte werden nicht geschätzt."
                     : "Die Vorschau ist keine Zeugnisnote und keine verbindliche Eignung.")
                    .font(.footnote).lookMuted()
                TransferRouteRow(title: "Gymnasium", route: overview.gymnasium, supported: overview.supported)
                TransferRouteRow(title: "Realschule", route: overview.realschule, supported: overview.supported)
            } else {
                Text("Übertrittsergebnis derzeit nicht verfügbar.").lookMuted()
            }
        }
        if profile.grade == 4 {
            Section("Übertrittszeugnis · offizielle Noten") {
                ForEach(store.snapshot.activeSubjects.filter { isTransferSubject($0.name) }) { subject in
                    OfficialGradeRow(subject: subject, period: "Übertrittszeugnis",
                                     points: false, allowOmission: false)
                }
                Text("Deutsch, Mathematik und Heimat- und Sachunterricht müssen jeweils genau einmal vorhanden sein.")
                    .font(.footnote).lookMuted()
            }
            Section("Probeunterricht") {
                TransferProbeEditor(route: "gym", title: "Gymnasium")
                TransferProbeEditor(route: "rs", title: "Realschule")
                Text("Nur offiziell mitgeteilte Probeunterrichtsnoten eintragen. Eine Aufnahme und Ausnahmen entscheidet die Schule.")
                    .font(.footnote).lookMuted()
            }
        }
    }

    @ViewBuilder private func promotionSection(profile: SchoolProfile) -> some View {
        Section("Vorrückungsbedingungen") {
            Toggle("Alle tatsächlichen Vorrückungsfächer sind angelegt", isOn: $rosterComplete)
            if profile.school == "GYMNASIUM" && (10...11).contains(profile.grade) {
                Picker("Vorjahr nur durch Notenausgleich bestanden", selection: $previousCompensation) {
                    Text("Unbekannt").tag(-1)
                    Text("Nein").tag(0)
                    Text("Ja").tag(1)
                }
            }
            if profile.school == "GYMNASIUM" && profile.grade == 11 && profile.track == "SWG" {
                Toggle("Sozialpraktikum abgeschlossen", isOn: $socialInternship)
            }
            if let result = store.promotionOverview(
                rosterComplete: rosterComplete,
                previousCompensation: previousCompensation < 0 ? nil : previousCompensation == 1,
                socialInternship: profile.school == "GYMNASIUM" && profile.grade == 11 && profile.track == "SWG"
                    ? socialInternship : nil
            ) {
                RuleResultCard(result: result)
            } else {
                Text("Prüfung derzeit nicht verfügbar.").lookMuted()
            }
        }
    }

    private func isTransferSubject(_ name: String) -> Bool {
        ["deutsch", "mathematik", "mathe", "heimat- und sachunterricht", "hsu"]
            .contains(name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
    }
}

private struct OfficialGradeRow: View {
    @EnvironmentObject private var store: SchoolStore
    let subject: SchoolSubject
    let period: String
    let points: Bool
    let allowOmission: Bool

    private var current: SchoolOfficial? {
        store.snapshot.officials.first { $0.subjectId == subject.id && $0.period == period }
    }

    private var selection: Binding<Int> {
        Binding(
            get: { current?.omitted == true ? -2 : current?.value ?? -1 },
            set: { choice in
                store.saveOfficial(subjectId: subject.id, period: period,
                                   value: choice >= 0 ? choice : nil, omitted: choice == -2)
            }
        )
    }

    var body: some View {
        Picker(subject.name, selection: selection) {
            Text("Fehlt").tag(-1)
            if allowOmission { Text("Ausgelassen").tag(-2) }
            ForEach(points ? Array(0...15) : Array(1...6), id: \.self) { value in
                Text(points ? "\(value) Punkte" : "Note \(value)").tag(value)
            }
        }
        .accessibilityIdentifier("officialGrade_\(subject.id)_\(period)")
    }
}

private struct TransferRouteRow: View {
    let title: String
    let route: String
    let supported: Bool

    private var label: String {
        guard supported else { return "Nicht unterstützt" }
        switch route {
        case "DIRECT": return "Notenbedingung erfüllt"
        case "FORECAST": return "Bisher rechnerisch im Bereich"
        case "PROBE_NEEDED": return "Probeunterricht als weiterer Weg"
        case "PROBE_PASSED": return "Probeunterricht bestanden"
        case "PARENTS": return "4 und 4: Eltern können Aufnahme beantragen"
        case "PROBE_FAILED": return "Probeunterricht: Noten reichen nicht"
        default: return "Noch keine vollständige Grundlage"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.headline)
            Text(label).foregroundStyle(supported && route == "DIRECT" || supported && route == "PROBE_PASSED"
                                        ? .green : .secondary)
        }
        .accessibilityIdentifier("transfer_\(title)_\(route)")
    }
}

private struct TransferProbeEditor: View {
    @EnvironmentObject private var store: SchoolStore
    let route: String
    let title: String
    @State private var german = -1
    @State private var math = -1

    var body: some View {
        DisclosureGroup("Probeunterricht \(title)") {
            gradePicker("Deutsch", selection: $german)
            gradePicker("Mathematik", selection: $math)
            Button("Probeunterrichtsnoten speichern") {
                store.saveTransferProbe(route: route, german: german < 0 ? nil : german,
                                        math: math < 0 ? nil : math)
            }
        }
        .onAppear {
            let stored = store.transferProbeGrades(route: route)
            german = stored?.german ?? -1
            math = stored?.math ?? -1
        }
    }

    private func gradePicker(_ name: String, selection: Binding<Int>) -> some View {
        Picker(name, selection: selection) {
            Text("Fehlt").tag(-1)
            ForEach(1...6, id: \.self) { Text("Note \($0)").tag($0) }
        }
    }
}

private struct RuleResultCard: View {
    let result: SchoolRuleResult

    private var color: Color {
        switch result.verdict {
        case "SATISFIED": return .green
        case "RISK": return .red
        case "SCHOOL_DECISION": return .orange
        default: return .secondary
        }
    }

    private var status: String {
        switch result.verdict {
        case "SATISFIED": return "Erfasste Bedingungen erfüllt"
        case "RISK": return "Bedingungen nicht erfüllt"
        case "SCHOOL_DECISION": return "Entscheidung der Schule erforderlich"
        case "UNSUPPORTED": return "Nicht unterstützt"
        default: return "Angaben unvollständig"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(status).font(.subheadline.bold()).foregroundStyle(color)
            Text(result.title).font(.headline)
            if let value = result.value { Text(value).font(.title2.bold()) }
            ForEach(result.details, id: \.self) { Text($0).font(.subheadline) }
            ForEach(result.sources, id: \.self) { source in
                if let url = URL(string: source) { Link("Rechtsquelle", destination: url).font(.footnote) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("ruleResult_\(result.verdict)")
    }
}

private struct GraduationEditor: View {
    @EnvironmentObject private var store: SchoolStore
    let profile: SchoolProfile
    @State private var procedure = ""
    @State private var input: SchoolGraduationInput?
    @State private var result: SchoolRuleResult?
    @State private var editingCourseName: String?
    @State private var courseDraft = SchoolAbiturCourse(
        name: "", area: 1, group: "Sonstiges", points: [nil, nil, nil, nil],
        included: [true, true, true, true], requiredTerms: 4, advanced: false
    )
    @State private var showCourseEditor = false

    private var procedures: [String] {
        switch profile.school {
        case "GYMNASIUM": return ["Abitur"]
        case "REALSCHULE": return ["Mittlerer Abschluss"]
        case "MITTELSCHULE": return ["Quali"]
        case "M_ZUG": return ["Quali", "Mittlerer Abschluss"]
        default: return []
        }
    }

    var body: some View {
        Section("Abschluss") {
            if procedures.isEmpty {
                Text("Für dieses Profil ist kein Abschlussverfahren vorgesehen.")
                    .lookMuted()
            } else {
                Picker("Verfahren", selection: $procedure) {
                    ForEach(procedures, id: \.self) { Text($0).tag($0) }
                }
                Text("Verwende nur offiziell festgestellte Noten, Punkte und Bestätigungen. Der Rechner entscheidet nicht über Zulassung oder Sonderfälle.")
                    .font(.footnote).lookMuted()
                if let current = input {
                    switch procedure {
                    case "Quali": qualiFields(current)
                    case "Mittlerer Abschluss": middleFields(current)
                    case "Abitur": abiturFields(current)
                    default: EmptyView()
                    }
                    Button("Angaben speichern und prüfen") {
                        var saved = current
                        saved.procedure = procedure
                        store.saveGraduation(saved)
                        if store.errorMessage == nil {
                            input = saved
                            result = store.certificateOverview(procedure: procedure)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                }
                if let result {
                    RuleResultCard(result: result)
                } else {
                    Text("Bitte Angaben speichern und prüfen.")
                        .font(.footnote).lookMuted()
                }
            }
        }
        .onAppear { load(procedures.first ?? "") }
        .onChange(of: procedure) { selected in load(selected) }
        .onChange(of: profile.id) { _ in load(procedures.first ?? "") }
        .sheet(isPresented: $showCourseEditor) {
            AbiturCourseEditor(initial: courseDraft,
                               usedNames: Set(input?.courses.map(\.name) ?? []),
                               editingName: editingCourseName) { course in
                saveCourse(course)
            }
        }
    }

    private func load(_ selected: String) {
        guard !selected.isEmpty else { input = nil; result = nil; return }
        if procedure != selected { procedure = selected }
        input = store.graduationInput(procedure: selected)
        result = store.certificateOverview(procedure: selected)
    }

    @ViewBuilder private func qualiFields(_ current: SchoolGraduationInput) -> some View {
        Text("Quali: Wahlfächer und genehmigte Ersatzprüfungen mit der Schule klären.")
            .font(.footnote).lookMuted()
        confirmation("Wahlfach 1 ist Englisch mit schriftlicher und mündlicher Prüfung", key: "languageOral")
        confirmation("Deutsch durch genehmigtes Deutsch als Zweitsprache ersetzt", key: "germanOral")
        if profile.school == "M_ZUG" {
            confirmation("Zusätzliches Wahlfach durch genehmigte Projektprüfung ersetzt",
                         key: "projectInsteadOfSecondChoice")
            if current.confirmations["projectInsteadOfSecondChoice"] != true {
                confirmation("Zusätzliches Wahlfach mit schriftlicher und mündlicher Prüfung",
                             key: "secondLanguageOral")
            }
        }
        if current.confirmations["germanOral"] != true {
            confirmation("Zusätzliche mündliche Prüfung in Deutsch", key: "germanExtra")
        }
        confirmation("Zusätzliche mündliche Prüfung in Mathematik", key: "mathExtra")
        ForEach(store.qualiFieldNames(input: current), id: \.key) { field in
            numberPicker(field.weight == 0 ? field.key : "\(field.key) · Gewicht \(field.weight)",
                         key: field.key, range: 1...6)
        }
        confirmation("Teilnahme und Fachwahl von der Schule bestätigt", key: "eligibility")
    }

    @ViewBuilder private func middleFields(_ current: SchoolGraduationInput) -> some View {
        Text("Offizielle Gesamtnoten aus dem Abschlusszeugnis übernehmen. Prüfungsnoten nicht durch selbst gerundete Mittelwerte ersetzen.")
            .font(.footnote).lookMuted()
        ForEach(middleSubjectNames, id: \.self) { name in
            numberPicker("\(name) · offizielle Gesamtnote", key: name, range: 1...6)
        }
        confirmation("Alle Abschlussfächer vollständig und offiziell festgestellt", key: "allOfficial")
    }

    private var middleSubjectNames: [String] {
        var names = store.snapshot.activeSubjects
            .filter { $0.promotion && $0.name != "Sport" }
            .map(\.name)
        if profile.school == "M_ZUG" {
            names.removeAll { ["Wirtschaft und Beruf", profile.track].contains($0) }
            names += ["Projekt", "Projektprüfung"]
        }
        return Array(NSOrderedSet(array: names)) as? [String] ?? names
    }

    @ViewBuilder private func abiturFields(_ current: SchoolGraduationInput) -> some View {
        Text("Vier offizielle Halbjahresleistungen je Kurs erfassen. Die Einbringung wählst du selbst; es findet keine automatische Kursoptimierung statt.")
            .font(.footnote).lookMuted()
        ForEach(current.courses, id: \.name) { course in
            VStack(alignment: .leading, spacing: 6) {
                Text(course.name).font(.headline)
                Text(course.points.map { $0.map(String.init) ?? "–" }.joined(separator: " / "))
                    .lookMuted()
                HStack {
                    Button("Bearbeiten") {
                        editingCourseName = course.name
                        courseDraft = course
                        showCourseEditor = true
                    }
                    Button("Entfernen", role: .destructive) { removeCourse(course.name) }
                }
            }
        }
        Button("Kurs hinzufügen", systemImage: "plus") {
            editingCourseName = nil
            courseDraft = SchoolAbiturCourse(name: "", area: 1, group: "Sonstiges",
                points: [nil, nil, nil, nil], included: [true, true, true, true],
                requiredTerms: 4, advanced: false)
            showCourseEditor = true
        }
        Text("Fünf Abiturprüfungsfächer").font(.headline)
        ForEach(current.courses, id: \.name) { course in
            examFields(course.name)
        }
        numberPicker("Seminararbeit · Punkte", key: "Seminararbeit", range: 0...15)
        numberPicker("Seminargespräch · Punkte", key: "Seminargespräch", range: 0...15)
        confirmation("Fachwahl und Einbringung von der Schule bestätigt", key: "subjectsApproved")
        confirmation("Belegung, Stunden und zweite Fremdsprache nachgewiesen",
                     key: "hoursAndSecondLanguage")
        confirmation("Seminararbeit abgegeben; beide Seminarhalbjahre eingebracht",
                     key: "seminarSubmitted")
        confirmation("Alle vorgeschriebenen Prüfungen abgelegt", key: "allExamsTaken")
    }

    @ViewBuilder private func examFields(_ name: String) -> some View {
        Toggle("\(name) als Prüfungsfach", isOn: Binding(
            get: { input?.exams.contains { $0.name == name } == true },
            set: { selected in
                guard var current = input else { return }
                if selected && !current.exams.contains(where: { $0.name == name }) {
                    current.exams.append(SchoolAbiturExam(name: name, written: true,
                                                          points: nil, oralAddition: nil, practical: nil))
                } else if !selected {
                    current.exams.removeAll { $0.name == name }
                }
                input = current; result = nil
            }
        ))
        if let exam = input?.exams.first(where: { $0.name == name }) {
            Toggle("\(name) · schriftliche Prüfung", isOn: Binding(
                get: { input?.exams.first(where: { $0.name == name })?.written ?? true },
                set: { value in modifyExam(name) { $0.written = value; if !value { $0.oralAddition = nil } } }
            ))
            examNumberPicker("\(name) · Prüfungspunkte", name: name, field: .points)
            if exam.written { examNumberPicker("\(name) · mündliche Zusatzprüfung", name: name, field: .oral) }
            if name == "Sport" || name == "Musik" && exam.written {
                examNumberPicker("\(name) · praktische Prüfung", name: name, field: .practical)
            }
        }
    }

    private enum ExamField { case points, oral, practical }

    private func examNumberPicker(_ title: String, name: String, field: ExamField) -> some View {
        Picker(title, selection: Binding<Int>(
            get: {
                guard let exam = input?.exams.first(where: { $0.name == name }) else { return -1 }
                switch field {
                case .points: return exam.points ?? -1
                case .oral: return exam.oralAddition ?? -1
                case .practical: return exam.practical ?? -1
                }
            },
            set: { value in
                modifyExam(name) { exam in
                    let number = value < 0 ? nil : value
                    switch field {
                    case .points: exam.points = number
                    case .oral: exam.oralAddition = number
                    case .practical: exam.practical = number
                    }
                }
            }
        )) {
            Text("Fehlt").tag(-1)
            ForEach(0...15, id: \.self) { Text("\($0) Punkte").tag($0) }
        }
    }

    private func modifyExam(_ name: String, _ edit: (inout SchoolAbiturExam) -> Void) {
        guard var current = input, let index = current.exams.firstIndex(where: { $0.name == name }) else { return }
        edit(&current.exams[index])
        input = current; result = nil
    }

    private func saveCourse(_ course: SchoolAbiturCourse) {
        guard var current = input else { return }
        if let oldName = editingCourseName {
            current.courses.removeAll { $0.name == oldName }
            if oldName != course.name {
                for index in current.exams.indices where current.exams[index].name == oldName {
                    current.exams[index].name = course.name
                }
            }
        }
        current.courses.append(course)
        input = current; result = nil
    }

    private func removeCourse(_ name: String) {
        guard var current = input else { return }
        current.courses.removeAll { $0.name == name }
        current.exams.removeAll { $0.name == name }
        input = current; result = nil
    }

    private func confirmation(_ title: String, key: String) -> some View {
        Toggle(title, isOn: Binding(
            get: { input?.confirmations[key] == true },
            set: { value in
                guard var current = input else { return }
                current.confirmations[key] = value
                if key == "germanOral" && value { current.confirmations["germanExtra"] = false }
                input = current; result = nil
            }
        ))
    }

    private func numberPicker(_ title: String, key: String, range: ClosedRange<Int>) -> some View {
        Picker(title, selection: Binding<Int>(
            get: {
                guard let values = input?.numbers, let value = values[key] ?? nil else { return -1 }
                return value
            },
            set: { choice in
                guard var current = input else { return }
                current.numbers[key] = choice < 0 ? nil : choice
                input = current; result = nil
            }
        )) {
            Text("Fehlt").tag(-1)
            ForEach(Array(range), id: \.self) { value in Text("\(value)").tag(value) }
        }
    }
}

private struct AbiturCourseEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State private var course: SchoolAbiturCourse
    let usedNames: Set<String>
    let editingName: String?
    let onSave: (SchoolAbiturCourse) -> Void

    init(initial: SchoolAbiturCourse, usedNames: Set<String>, editingName: String?,
         onSave: @escaping (SchoolAbiturCourse) -> Void) {
        var prepared = initial
        prepared.points = Array(prepared.points.prefix(4)) + Array(repeating: nil, count: max(0, 4 - prepared.points.count))
        prepared.included = Array(prepared.included.prefix(4)) + Array(repeating: true, count: max(0, 4 - prepared.included.count))
        _course = State(initialValue: prepared)
        self.usedNames = usedNames
        self.editingName = editingName
        self.onSave = onSave
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Section("Kurs") {
                        TextField("Fachname, z. B. Deutsch", text: $course.name)
                        Picker("Aufgabenfeld", selection: $course.area) {
                            Text("0 · Sport / Seminar").tag(0)
                            Text("1 · sprachlich-künstlerisch").tag(1)
                            Text("2 · gesellschaftswissenschaftlich").tag(2)
                            Text("3 · mathematisch-naturwissenschaftlich").tag(3)
                        }
                        Picker("Fachgruppe", selection: $course.group) {
                            ForEach(["Sonstiges", "Sprache", "Naturwissenschaft", "Sport", "Seminar"], id: \.self) {
                                Text($0).tag($0)
                            }
                        }
                        Picker("Verpflichtende Halbjahre", selection: $course.requiredTerms) {
                            ForEach([0, 2, 4], id: \.self) { Text("\($0)").tag($0) }
                        }
                        Toggle("Leistungsfach", isOn: $course.advanced)
                    }
                    Section("Offizielle Halbjahresleistungen") {
                        ForEach(Array(["12/1", "12/2", "13/1", "13/2"].enumerated()), id: \.offset) { index, label in
                            Picker("\(label) · Punkte", selection: Binding<Int>(
                                get: { course.points[index] ?? -1 },
                                set: { course.points[index] = $0 < 0 ? nil : $0 }
                            )) {
                                Text("Fehlt").tag(-1)
                                ForEach(0...15, id: \.self) { Text("\($0)").tag($0) }
                            }
                            Toggle("\(label) einbringen", isOn: Binding(
                                get: { course.included[index] },
                                set: { course.included[index] = $0 }
                            ))
                        }
                        Text("Nicht belegte Halbjahre mit 0 Punkten eintragen und nicht einbringen.")
                            .font(.footnote).lookMuted()
                    }
                }.listRowBackground(LookCardBackground())
            }
            .lookScreen()
            .navigationTitle("Abiturkurs")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Übernehmen") {
                        course.name = course.name.trimmingCharacters(in: .whitespacesAndNewlines)
                        onSave(course)
                        dismiss()
                    }
                    .disabled(course.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                              course.points.contains(where: { $0 == nil }) ||
                              (usedNames.contains(course.name.trimmingCharacters(in: .whitespacesAndNewlines)) &&
                               course.name.trimmingCharacters(in: .whitespacesAndNewlines) != editingName))
                }
            }
        }
    }
}
