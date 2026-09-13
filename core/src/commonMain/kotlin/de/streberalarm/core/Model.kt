package de.streberalarm.core

import de.streberalarm.core.SchoolDate as LocalDate
import de.streberalarm.core.SchoolTime as LocalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.DayOfWeek
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@OptIn(ExperimentalUuidApi::class) fun id(): String = Uuid.random().toString()

val dataJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

@Serializable
enum class School(val label: String) {
    GRUNDSCHULE("Grundschule"),
    GYMNASIUM("Gymnasium"),
    REALSCHULE("Realschule"),
    MITTELSCHULE("Mittelschule"),
    M_ZUG("Mittelschule · M-Zug"),
}

@Serializable
enum class Stage(val label: String) {
    PLANNED("Geplant"),
    WRITTEN("Geschrieben"),
    GRADED("Benotet"),
    CANCELLED("Abgesagt"),
}

@Serializable
enum class Kind(val label: String, val large: Boolean = false) {
    SCHOOLWORK("Schulaufgabe", true),
    ANNOUNCED_TEST("Angesagter Test (§ 18 RSO)", true),
    EX("Stegreifaufgabe"),
    ORAL("Mündliche Note"),
    PRACTICAL("Praktische Leistung"),
    TEST("Probe"),
    PROJECT("Projektprüfung"),
    SEMINAR("Seminarleistung"),
    INSTRUMENT("Instrument / Gesang"),
    ART_PROJECT("Künstlerisches Projekt"),
    PRACTICAL_EXAM("Praktische Fachprüfung"),
    THEORY_LARGE("Sporttheorie · Schulaufgabe", true),
    THEORY_SMALL("Sporttheorie · kleine Leistung"),
    SHORT_TEST("Kurzarbeit"),
    PERFORMANCE_TEST("Fachlicher Leistungstest"),
}

@Serializable
data class Profile(
    val id: String = id(),
    val state: String = "Bayern",
    val school: School = School.GYMNASIUM,
    val year: Int = 2026,
    val grade: Int = 8,
    val track: String = "NTG",
    val term: String = "Ganzes Schuljahr",
    val ruleVersion: String = "BY-2026.08-v1",
    val graduationYear: Int? = null,
) {
    val points: Boolean
        get() = school == School.GYMNASIUM && grade >= 12

    fun validate() {
        require(year in 2000..2100)
        require(grade in Defaults.grades(school))
        require(track.isNotBlank())
        require(
            term in
                listOf(
                    "Ganzes Schuljahr",
                    "1. Halbjahr",
                    "2. Halbjahr",
                    "12/1",
                    "12/2",
                    "13/1",
                    "13/2",
                )
        )
        if (points) require(term in listOf("12/1", "12/2", "13/1", "13/2"))
    }
}

@Serializable
data class Subject(
    val id: String = id(),
    val profileId: String,
    val name: String,
    val annualSchoolworks: Int? = null,
    val weightsConfirmed: Boolean = false,
    val core: Boolean = false,
    val promotion: Boolean = true,
    val advanced: Boolean = false,
    val examSubject: Boolean = false,
    val smallWrittenWeight: Double = 1.0,
    val oralWeight: Double = 1.0,
    val practicalWeight: Double = 1.0,
)

@Serializable
data class Assessment(
    val id: String = id(),
    val subjectId: String,
    val title: String,
    val date: String,
    val time: String? = null,
    val kind: Kind = Kind.SCHOOLWORK,
    val stage: Stage = Stage.PLANNED,
    val material: String = "",
    val notes: String = "",
    val estimate: Int? = null,
    val actual: Int? = null,
    val weight: Double = 1.0,
) {
    val learningNotes: String
        get() = listOf(material, notes).filter { it.isNotBlank() }.joinToString("\n\n")

    fun validate(points: Boolean) {
        require(title.isNotBlank())
        LocalDate.parse(date)
        time?.let(LocalTime::parse)
        require(weight.isFinite() && weight > 0 && weight <= 1000)
        listOfNotNull(actual, estimate).forEach { require(it in (if (points) 0..15 else 1..6)) }
        require((stage == Stage.GRADED) == (actual != null))
    }
}

@Serializable
data class Official(
    val id: String = id(),
    val subjectId: String,
    val period: String,
    val value: Int?,
    val omitted: Boolean = false,
)

@Serializable
data class Timetable(
    val id: String = id(),
    val profileId: String,
    val validFrom: String,
    val anchorMonday: String,
    val blocks: List<Lesson> = emptyList(),
)

@Serializable
data class Lesson(
    val id: String = id(),
    val subjectId: String,
    val day: Int,
    val start: String,
    val end: String,
    val week: Int = 0,
    val isBreak: Boolean = false,
    val slot: Int = 0,
)

@Serializable
data class DayOff(
    val id: String = id(),
    val profileId: String,
    val from: String,
    val to: String,
    val reason: String,
)

@Serializable
data class ExceptionLesson(
    val id: String = id(),
    val subjectId: String,
    val date: String,
    val start: String,
    val end: String,
    val cancelled: Boolean,
)

@Serializable
data class Study(
    val id: String = id(),
    val assessmentId: String,
    val start: Long,
    val end: Long? = null,
    val elapsedStart: Long = 0,
    val boot: Int = 0,
    val correctedMinutes: Int? = null,
    val correction: String = "",
) {
    fun seconds(now: Long): Long =
        correctedMinutes?.toLong()?.times(60) ?: ((end ?: now) - start).coerceAtLeast(0) / 1000
}

@Serializable
data class Document(
    val id: String = id(),
    val assessmentId: String,
    val name: String,
    val mime: String,
    val file: String,
    val size: Long,
)

@Serializable
data class SchoolData(
    val schema: Int = 1,
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String? = null,
    val subjects: List<Subject> = emptyList(),
    val assessments: List<Assessment> = emptyList(),
    val officials: List<Official> = emptyList(),
    val timetables: List<Timetable> = emptyList(),
    val daysOff: List<DayOff> = emptyList(),
    val exceptions: List<ExceptionLesson> = emptyList(),
    val studies: List<Study> = emptyList(),
    val documents: List<Document> = emptyList(),
    val delivered: Set<String> = emptySet(),
    val graduation: Map<String, GraduationInput> = emptyMap(),
    val catalogVersion: Int = 0,
) {
    fun active() = profiles.find { it.id == activeProfileId }

    fun validate() {
        require(
            profiles.size <= 200 &&
                subjects.size <= 10000 &&
                assessments.size <= 100000 &&
                documents.size <= 2000
        ) {
            "Zu viele Einträge im Backup."
        }
        require(schema == 1) { "Unbekannte Backup-Version" }
        fun <T> unique(items: List<T>, key: (T) -> String) {
            require(items.map(key).distinct().size == items.size)
        }
        unique(profiles) { it.id }
        unique(subjects) { it.id }
        unique(assessments) { it.id }
        unique(documents) { it.id }
        unique(officials) { it.id }
        unique(timetables) { it.id }
        unique(studies) { it.id }
        require(
            profiles.isEmpty() && activeProfileId == null ||
                profiles.any { it.id == activeProfileId }
        )
        profiles.forEach { it.validate() }
        require(
            subjects.map { it.profileId to it.name.trim().lowercase() }.distinct().size ==
                subjects.size
        ) {
            "Dieses Fach ist im Profil bereits vorhanden."
        }
        val ps = profiles.associateBy { it.id }
        val ss = subjects.associateBy { it.id }
        val es = assessments.associateBy { it.id }
        subjects.forEach {
            require(ps.containsKey(it.profileId) && it.name.isNotBlank())
            require(it.annualSchoolworks == null || it.annualSchoolworks in 0..12)
            require(
                listOf(it.smallWrittenWeight, it.oralWeight, it.practicalWeight).all { w ->
                    w.isFinite() && w > 0 && w <= 1000
                }
            )
        }
        assessments.forEach { a ->
            val profile = ps.getValue(ss.getValue(a.subjectId).profileId)
            a.validate(profile.points)
            require(
                LocalDate.parse(a.date) in
                    LocalDate.of(profile.year, 8, 1)..LocalDate.of(profile.year + 1, 9, 30)
            ) {
                "Prüfung liegt außerhalb ihres Schuljahres."
            }
        }
        officials.forEach { o ->
            val p = ps.getValue(ss.getValue(o.subjectId).profileId)
            require(!o.omitted || o.value == null)
            require(o.value == null || o.value in (if (p.points) 0..15 else 1..6))
        }
        require(officials.map { it.subjectId to it.period }.distinct().size == officials.size)
        require(studies.count { it.end == null } <= 1)
        studies.forEach {
            require(es.containsKey(it.assessmentId))
            require(it.start >= 0 && (it.end == null || it.end >= it.start))
            require(
                it.correctedMinutes == null ||
                    it.correctedMinutes in 0..10080 && it.correction.isNotBlank()
            )
        }
        documents.forEach {
            require(es.containsKey(it.assessmentId))
            require(it.file.matches(Regex("[a-f0-9-]+\\.(pdf|jpg|png)")))
            require(it.mime in listOf("application/pdf", "image/jpeg", "image/png"))
            require(it.size in 1..MAX_DOCUMENT_BYTES)
        }
        require(documents.map { it.file }.distinct().size == documents.size)
        timetables.forEach { t ->
            require(ps.containsKey(t.profileId))
            LocalDate.parse(t.validFrom)
            require(LocalDate.parse(t.anchorMonday).dayOfWeek == DayOfWeek.MONDAY)
            t.blocks.forEach { b ->
                require(
                    if (b.isBreak) b.subjectId.isEmpty()
                    else ss[b.subjectId]?.profileId == t.profileId
                )
                require(b.day in 1..7 && b.week in 0..2)
                require(b.slot in 0..30)
                require(LocalTime.parse(b.start) < LocalTime.parse(b.end))
            }
            val positioned =
                t.blocks.filter { it.slot > 0 }.map { Triple(it.day, it.week, it.slot) }
            require(positioned.distinct().size == positioned.size)
        }
        require(timetables.map { it.profileId to it.validFrom }.distinct().size == timetables.size)
        daysOff.forEach {
            require(ps.containsKey(it.profileId))
            require(LocalDate.parse(it.from) <= LocalDate.parse(it.to))
        }
        exceptions.forEach {
            require(ss.containsKey(it.subjectId))
            LocalDate.parse(it.date)
            require(LocalTime.parse(it.start) < LocalTime.parse(it.end))
        }
    }

    fun deleteAssessment(examId: String) =
        copy(
            assessments = assessments.filterNot { it.id == examId },
            studies = studies.filterNot { it.assessmentId == examId },
            documents = documents.filterNot { it.assessmentId == examId },
        )
}

const val MAX_DOCUMENT_BYTES = 40L * 1024 * 1024

fun kinds(profile: Profile): List<Kind> =
    when (profile.school) {
        School.GRUNDSCHULE -> listOf(Kind.TEST, Kind.ORAL, Kind.PRACTICAL)
        School.GYMNASIUM ->
            listOf(
                Kind.SCHOOLWORK,
                Kind.EX,
                Kind.ORAL,
                Kind.PRACTICAL,
                Kind.SEMINAR,
                Kind.INSTRUMENT,
                Kind.ART_PROJECT,
                Kind.PRACTICAL_EXAM,
                Kind.THEORY_LARGE,
                Kind.THEORY_SMALL,
                Kind.SHORT_TEST,
                Kind.PERFORMANCE_TEST,
            )
        School.REALSCHULE ->
            listOf(
                Kind.SCHOOLWORK,
                Kind.ANNOUNCED_TEST,
                Kind.EX,
                Kind.ORAL,
                Kind.PRACTICAL,
                Kind.SHORT_TEST,
                Kind.PERFORMANCE_TEST,
            )
        else -> listOf(Kind.TEST, Kind.ORAL, Kind.PRACTICAL, Kind.PROJECT)
    }
