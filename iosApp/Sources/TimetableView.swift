import SwiftUI

private let weekdays = ["Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag"]
private let defaultStarts = ["08:00", "08:45", "09:50", "10:35", "11:40", "12:25", "13:30", "14:30", "15:15"]

private func schoolDate(_ date: Date) -> String {
    SchoolDates.formatter("yyyy-MM-dd").string(from: date)
}

private func parsedDate(_ text: String) -> Date? {
    SchoolDates.formatter("yyyy-MM-dd").date(from: text)
}

private func monday(for date: Date) -> Date {
    Calendar(identifier: .iso8601).dateInterval(of: .weekOfYear, for: date)?.start ?? date
}

private func defaultTime(slot: Int) -> (String, String) {
    let formatter = DateFormatter(); formatter.dateFormat = "HH:mm"
    let start = slot <= defaultStarts.count ? defaultStarts[slot - 1] : "15:15"
    let base = formatter.date(from: start) ?? Date()
    let adjusted = Calendar.current.date(byAdding: .minute,
        value: slot <= defaultStarts.count ? 0 : (slot - defaultStarts.count) * 45, to: base) ?? base
    let end = Calendar.current.date(byAdding: .minute, value: 45, to: adjusted) ?? adjusted
    return (formatter.string(from: adjusted), formatter.string(from: end))
}

struct TimetableView: View {
    @EnvironmentObject private var store: SchoolStore
    @State private var loaded = false
    @State private var timetableId = ""
    @State private var validFrom = Date()
    @State private var anchorMonday = monday(for: Date())
    @State private var blocks: [SchoolLesson] = []
    @State private var day = 1
    @State private var week = 0
    @State private var ab = false
    @State private var slotCount = 6
    @State private var timeSlot: Int?
    @State private var confirmSameWeek = false
    @State private var addingDayOff = false
    @State private var addingException = false
    @State private var editingDayOff: SchoolDayOff?
    @State private var editingException: SchoolException?

    private var planVersions: [SchoolTimetable] {
        store.snapshot.timetables.filter { $0.profileId == store.snapshot.activeProfileId }
            .sorted { $0.validFrom > $1.validFrom }
    }

    private var subjects: [SchoolSubject] {
        store.snapshot.activeSubjects.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        Form {
            Group {
                Section("Planstand") {
                    if !planVersions.isEmpty {
                        Picker("Planfassung", selection: $timetableId) {
                            Text("Neue Fassung").tag("")
                            ForEach(planVersions) { Text("Ab \($0.validFrom)").tag($0.id) }
                        }
                        .onChange(of: timetableId) { id in
                            if let table = planVersions.first(where: { $0.id == id }) { load(table) }
                        }
                    }
                    DatePicker("Gültig ab", selection: $validFrom, displayedComponents: .date)
                    DatePicker("A-Woche beginnt am Montag", selection: $anchorMonday, displayedComponents: .date)
                    Button("Neue datierte Fassung aus diesem Plan") { timetableId = "" }
                        .disabled(blocks.isEmpty)
                    if timetableId.isEmpty { Text("Neue Fassung wird beim Speichern angelegt.").font(.footnote) }
                }
                Section("Wochentage") {
                    Picker("Tag", selection: $day) {
                        ForEach(1...7, id: \.self) { index in Text(weekdays[index - 1]).tag(index) }
                    }
                    if ab {
                        Picker("Woche", selection: $week) {
                            Text("A-Woche").tag(1)
                            Text("B-Woche").tag(2)
                        }.pickerStyle(.segmented)
                        Button("Gleiche Woche für beide verwenden") { confirmSameWeek = true }
                    } else {
                        Button("A/B-Wochen einschalten") { enableAB() }
                    }
                }
                Section("\(weekdays[day - 1]) · \(ab ? (week == 1 ? "A-Woche" : "B-Woche") : "jede Woche")") {
                    ForEach(1...slotCount, id: \.self) { slot in
                        let row = lesson(slot)
                        let times = row.map { ($0.start, $0.end) } ?? defaultTime(slot: slot)
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text("\(slot). Stunde · \(times.0)–\(times.1)").font(.subheadline.bold())
                                Spacer()
                                if row != nil {
                                    Button("Zeit", systemImage: "clock") { timeSlot = slot }
                                        .labelStyle(.iconOnly)
                                }
                            }
                            Picker("Fach", selection: selection(slot)) {
                                Text("Noch frei").tag("")
                                ForEach(subjects) { Text($0.name).tag($0.id) }
                                Text("Pause").tag("__pause")
                            }
                            .accessibilityIdentifier("lesson-\(day)-\(week)-\(slot)")
                        }
                    }
                    if slotCount < 20 { Button("Stunde anhängen", systemImage: "plus") { slotCount += 1 } }
                }
                Section("Abweichungen") {
                    ForEach(store.snapshot.daysOff.filter { $0.profileId == store.snapshot.activeProfileId }) { free in
                        Button("Frei: \(free.from)–\(free.to) · \(free.reason)") { editingDayOff = free }
                            .swipeActions {
                                Button("Löschen", role: .destructive) { store.deleteDayOff(free.id) }
                            }
                    }
                    Button("Freien Tag eintragen", systemImage: "sun.max") { addingDayOff = true }
                    ForEach(store.snapshot.exceptions.filter { change in
                        store.snapshot.activeSubjects.contains { $0.id == change.subjectId }
                    }) { change in
                        Button("\(change.date): \(change.cancelled ? "Entfall" : "Zusatz") · \(change.start)–\(change.end)") {
                            editingException = change
                        }
                        .swipeActions {
                            Button("Löschen", role: .destructive) { store.deleteException(change.id) }
                        }
                    }
                    Button("Entfall oder Zusatzstunde", systemImage: "calendar.badge.plus") { addingException = true }
                }
            }.listRowBackground(LookCardBackground())
        }
        .lookScreen()
        .navigationTitle("Dein Stundenplan")
        .toolbar {
            Button("Speichern") { save() }
                .accessibilityIdentifier("saveTimetable")
        }
        .onAppear { if !loaded { load(); loaded = true } }
        .sheet(item: Binding(get: { timeSlot.map { SlotID(slot: $0) } },
                             set: { timeSlot = $0?.slot })) { slot in
            if let row = lesson(slot.slot) {
                LessonTimeEditor(row: row) { start, end in
                    replace(row: SchoolLesson(id: row.id, subjectId: row.subjectId, day: row.day,
                        start: start, end: end, week: row.week, isBreak: row.isBreak, slot: row.slot))
                }
            }
        }
        .sheet(isPresented: $addingDayOff) { DayOffEditor() }
        .sheet(isPresented: $addingException) { ExceptionEditor() }
        .sheet(item: $editingDayOff) { DayOffEditor(existing: $0) }
        .sheet(item: $editingException) { ExceptionEditor(existing: $0) }
        .confirmationDialog("B-Woche verwerfen?", isPresented: $confirmSameWeek) {
            Button("A-Woche für alle Wochen übernehmen", role: .destructive) { useSameWeek() }
        }
    }

    private func load() {
        if let table = store.snapshot.latestTimetable {
            load(table)
        }
    }

    private func load(_ table: SchoolTimetable) {
        timetableId = table.id
        validFrom = parsedDate(table.validFrom) ?? Date()
        anchorMonday = parsedDate(table.anchorMonday) ?? monday(for: Date())
        blocks = table.blocks
        ab = blocks.contains { $0.week != 0 }
        week = ab ? 1 : 0
        slotCount = max(6, blocks.map(\.slot).max() ?? 6)
    }

    private func save() {
        let date = schoolDate(validFrom)
        store.saveTimetable(id: timetableId, validFrom: date,
            anchorMonday: schoolDate(monday(for: anchorMonday)), blocks: blocks)
        if store.errorMessage == nil && timetableId.isEmpty {
            timetableId = planVersions.first { $0.validFrom == date }?.id ?? ""
        }
    }

    private func lesson(_ slot: Int) -> SchoolLesson? {
        blocks.first { $0.day == day && $0.week == week && $0.slot == slot }
    }

    private func selection(_ slot: Int) -> Binding<String> {
        Binding(get: {
            guard let row = lesson(slot) else { return "" }
            return row.isBreak ? "__pause" : row.subjectId
        }, set: { value in
            let old = lesson(slot)
            blocks.removeAll { $0.day == day && $0.week == week && $0.slot == slot }
            if value.isEmpty { return }
            let times = old.map { ($0.start, $0.end) } ?? defaultTime(slot: slot)
            blocks.append(SchoolLesson(id: old?.id ?? UUID().uuidString.lowercased(),
                subjectId: value == "__pause" ? "" : value, day: day, start: times.0,
                end: times.1, week: week, isBreak: value == "__pause", slot: slot))
        })
    }

    private func replace(row: SchoolLesson) {
        blocks.removeAll { $0.id == row.id }
        blocks.append(row)
    }

    private func enableAB() {
        blocks = blocks.flatMap { row in
            [SchoolLesson(id: UUID().uuidString.lowercased(), subjectId: row.subjectId,
                day: row.day, start: row.start, end: row.end, week: 1,
                isBreak: row.isBreak, slot: row.slot),
             SchoolLesson(id: UUID().uuidString.lowercased(), subjectId: row.subjectId,
                day: row.day, start: row.start, end: row.end, week: 2,
                isBreak: row.isBreak, slot: row.slot)]
        }
        ab = true
        week = 1
    }

    private func useSameWeek() {
        blocks = blocks.filter { $0.week == 1 }.map { row in
            SchoolLesson(id: row.id, subjectId: row.subjectId, day: row.day,
                start: row.start, end: row.end, week: 0,
                isBreak: row.isBreak, slot: row.slot)
        }
        ab = false
        week = 0
    }

    private struct SlotID: Identifiable { let slot: Int; var id: Int { slot } }
}

private struct LessonTimeEditor: View {
    @Environment(\.dismiss) private var dismiss
    let row: SchoolLesson
    let save: (String, String) -> Void
    @State private var start: String
    @State private var end: String

    init(row: SchoolLesson, save: @escaping (String, String) -> Void) {
        self.row = row
        self.save = save
        _start = State(initialValue: row.start)
        _end = State(initialValue: row.end)
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    TextField("Beginn (HH:mm)", text: $start)
                    TextField("Ende (HH:mm)", text: $end)
                    Text("Bei einer Zeitlücke erscheint im Tagesplan eine Pause.").font(.footnote)
                }.listRowBackground(LookCardBackground())
            }
            .lookScreen()
            .navigationTitle("Zeit der Stunde")
            .toolbar {
                Button("Übernehmen") { save(start, end); dismiss() }
                    .disabled(start.isEmpty || end.isEmpty)
            }
        }
    }
}

private struct DayOffEditor: View {
    @EnvironmentObject private var store: SchoolStore
    @Environment(\.dismiss) private var dismiss
    let existing: SchoolDayOff?
    @State private var from: Date
    @State private var to: Date
    @State private var reason: String

    init(existing: SchoolDayOff? = nil) {
        self.existing = existing
        _from = State(initialValue: existing.flatMap { parsedDate($0.from) } ?? Date())
        _to = State(initialValue: existing.flatMap { parsedDate($0.to) } ?? Date())
        _reason = State(initialValue: existing?.reason ?? "Frei")
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    DatePicker("Von", selection: $from, displayedComponents: .date)
                    DatePicker("Bis", selection: $to, displayedComponents: .date)
                    TextField("Grund", text: $reason)
                }.listRowBackground(LookCardBackground())
            }
            .lookScreen()
            .navigationTitle("Freier Tag")
            .toolbar {
                Button("Speichern") {
                    store.saveDayOff(id: existing?.id ?? "", from: schoolDate(from),
                        to: schoolDate(to), reason: reason)
                    if store.errorMessage == nil { dismiss() }
                }.disabled(to < from || reason.isEmpty)
            }
        }
    }
}

private struct ExceptionEditor: View {
    @EnvironmentObject private var store: SchoolStore
    @Environment(\.dismiss) private var dismiss
    let existing: SchoolException?
    @State private var subjectId: String
    @State private var date: Date
    @State private var start: String
    @State private var end: String
    @State private var cancelled: Bool

    init(existing: SchoolException? = nil) {
        self.existing = existing
        _subjectId = State(initialValue: existing?.subjectId ?? "")
        _date = State(initialValue: existing.flatMap { parsedDate($0.date) } ?? Date())
        _start = State(initialValue: existing?.start ?? "08:00")
        _end = State(initialValue: existing?.end ?? "08:45")
        _cancelled = State(initialValue: existing?.cancelled ?? true)
    }

    var body: some View {
        NavigationStack {
            Form {
                Group {
                    Picker("Fach", selection: $subjectId) {
                        Text("Bitte wählen").tag("")
                        ForEach(store.snapshot.activeSubjects) { Text($0.name).tag($0.id) }
                    }
                    DatePicker("Datum", selection: $date, displayedComponents: .date)
                    TextField("Beginn (HH:mm)", text: $start)
                    TextField("Ende (HH:mm)", text: $end)
                    Toggle("Entfällt", isOn: $cancelled)
                }.listRowBackground(LookCardBackground())
            }
            .lookScreen()
            .navigationTitle("Stundenänderung")
            .toolbar {
                Button("Speichern") {
                    store.saveException(id: existing?.id ?? "", subjectId: subjectId, date: schoolDate(date),
                        start: start, end: end, cancelled: cancelled)
                    if store.errorMessage == nil { dismiss() }
                }.disabled(subjectId.isEmpty)
            }
        }
    }
}
