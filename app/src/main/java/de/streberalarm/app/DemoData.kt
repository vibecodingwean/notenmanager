package de.streberalarm.app

import de.streberalarm.core.*
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Fictional data lives only in the separate demo database. No real profile IDs are reused. */
internal object DemoData {
    const val MATH_IMAGE = "demo/math-demo.png"
    const val ENGLISH_IMAGE = "demo/english-demo.png"

    fun create(today: LocalDate = LocalDate.now()): SchoolData {
        val year = if (today.monthValue >= 8) today.year else today.year - 1
        val profile = Profile(school = School.REALSCHULE, year = year, grade = 7, track = "I")
        val subjects = Defaults.subjects(profile)
        fun subject(name: String) = subjects.first { it.name == name }
        val schoolStart = LocalDate.of(year, 9, 15)
        val schoolEnd = LocalDate.of(year + 1, 7, 31)
        val displayWeek = today.coerceIn(schoolStart.plusWeeks(3), schoolEnd.minusWeeks(5))
        val monday = displayWeek.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))

        val matrix = listOf(
            listOf("Mathematik", "Mathematik", "Deutsch", "Englisch", "Geschichte", "Sport"),
            listOf("Englisch", "Biologie", "Biologie", "Deutsch", "Kunst", "Kunst"),
            listOf("Physik", "Physik", "Mathematik", "Geschichte", "Sport"),
            listOf("Deutsch", "Englisch", "Mathematik", "IT · Informationstechnologie", "IT · Informationstechnologie", "Musik"),
            listOf("Geographie", "Geographie", "Deutsch", "Englisch", "Ethik/Religion"),
        )
        val times = listOf("08:00" to "08:45", "08:45" to "09:30", "09:50" to "10:35",
            "10:35" to "11:20", "11:35" to "12:20", "12:20" to "13:05")
        val lessons = matrix.flatMapIndexed { day, names ->
            names.mapIndexed { slot, name ->
                Lesson(subjectId = subject(name).id, day = day + 1,
                    start = times[slot].first, end = times[slot].second, slot = slot + 1)
            }
        }
        val timetable = Timetable(profileId = profile.id, validFrom = schoolStart.toString(),
            anchorMonday = monday.toString(), blocks = lessons)
        fun exam(name: String, title: String, days: Long, kind: Kind, grade: Int? = null,
                 material: String = "", notes: String = "", date: LocalDate? = null) = Assessment(
            subjectId = subject(name).id, title = title,
            date = (date ?: displayWeek.plusDays(days)).coerceIn(schoolStart, schoolEnd).toString(),
            kind = kind, stage = if (grade == null) Stage.PLANNED else Stage.GRADED,
            actual = grade, material = material, notes = notes)
        val math = exam("Mathematik", "Brüche und Gleichungen", -15, Kind.SCHOOLWORK, 2,
            "Brüche, Gleichungen und Prozentrechnung", "Fiktive Beispielarbeit: 18 von 20 Punkten.",
            date = if (year == 2026) LocalDate.of(2026, 10, 9) else null)
        val english = exam("Englisch", "Simple Past", -8, Kind.ANNOUNCED_TEST, 1,
            "Simple Past", "Fiktiver Beispieltest: 12 von 12 Punkten.",
            date = if (year == 2026) LocalDate.of(2026, 10, 8) else null)
        val assessments = listOf(math, english,
            exam("Deutsch", "Erörterung", -25, Kind.SCHOOLWORK, 3),
            exam("Biologie", "Zellen und Organe", -20, Kind.EX, 2),
            exam("Geschichte", "Industrialisierung", -11, Kind.ORAL, 2),
            exam("Physik", "Kräfte", -4, Kind.EX, 3),
            exam("Mathematik", "Lineare Funktionen", 11, Kind.SCHOOLWORK,
                material = "Geraden zeichnen, Steigung berechnen, Textaufgaben lösen.",
                notes = "Lernidee: Montag 20 Minuten Aufgaben, Mittwoch die Fehler wiederholen."),
            exam("Englisch", "Unit 3 vocabulary", 19, Kind.ANNOUNCED_TEST,
                material = "Vokabeln aus Unit 3 und simple past wiederholen."))
        val collections = listOf(VocabularyCollection(id(), profile.id, "Englisch · Unit 3"))
        val words = listOf("die Schule" to "school", "die Hausaufgabe" to "homework",
            "die Pause" to "break", "lernen" to "to study", "die Frage" to "question",
            "die Antwort" to "answer", "morgen" to "tomorrow", "gestern" to "yesterday")
        val cards = words.map { (de, en) -> VocabularyCard(id(), collections.single().id, de, en) }
        val studyStart = displayWeek.minusDays(2).atTime(16, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        return SchoolData(profiles = listOf(profile), activeProfileId = profile.id,
            subjects = subjects, assessments = assessments, timetables = listOf(timetable),
            daysOff = Defaults.holidays(profile),
            studies = listOf(Study(assessmentId = assessments[6].id, start = studyStart,
                end = studyStart + 25 * 60_000L)),
            documents = listOf(
                Document(assessmentId = math.id, name = "Mathe · fiktiver Scan · 18/20.png",
                    mime = "image/png", file = "00000000-0000-0000-0000-000000000101.png", size = 1),
                Document(assessmentId = english.id, name = "Englisch · fiktiver Scan · 12/12.png",
                    mime = "image/png", file = "00000000-0000-0000-0000-000000000102.png", size = 1)),
            vocabularyCollections = collections, vocabularyCards = cards,
            vocabularyProgress = listOf(
                VocabularyProgress(cardId = cards[0].id, revision = 1, stage = 1,
                    dueDate = displayWeek.minusDays(1).toString(),
                    lastScheduleDate = displayWeek.minusDays(4).toString(), correctAttempts = 1),
                VocabularyProgress(cardId = cards[1].id, revision = 1, stage = 0,
                    dueDate = displayWeek.toString(),
                    lastScheduleDate = displayWeek.minusDays(1).toString(), wrongAttempts = 1),
            )).let(Defaults::updateCatalog)
    }
}
