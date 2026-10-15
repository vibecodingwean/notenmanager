package de.streberalarm.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class IosBridgeTest {
    @Test fun editsDefaultSubjectRulesAndKeepsItsIdentity() {
        val bridge = IosBridge()
        val initial = bridge.createProfile(bridge.emptyData(), "Bayern", "GYMNASIUM", 8, "NTG", 2026,
            "Ganzes Schuljahr")
        val german = dataJson.decodeFromString<SchoolData>(initial).subjects.first { it.name == "Deutsch" }
        assertEquals(null, german.annualSchoolworks)
        val saved = bridge.saveSubject(initial, german.id, german.name, german.core, german.promotion,
            4, false, false, 1.2, 1.0, 1.0, true)
        val updated = dataJson.decodeFromString<SchoolData>(saved).subjects.first { it.id == german.id }
        assertEquals(4, updated.annualSchoolworks)
        assertEquals(1.2, updated.smallWrittenWeight)
        assertEquals(true, updated.weightsConfirmed)
        var graded = bridge.saveAssessment(saved, "", german.id, "Schulaufgabe", "2026-10-15",
            "SCHOOLWORK", "GRADED", "", 2, -1)
        graded = bridge.saveAssessment(graded, "", german.id, "Mündlich", "2026-10-16",
            "ORAL", "GRADED", "", 3, -1)
        val data = dataJson.decodeFromString<SchoolData>(graded)
        assertEquals(true, Grades.calculate(data.active()!!, updated, data.assessments).value != null)
    }

    @Test fun importedLearningMaterialSurvivesAssessmentEdit() {
        val bridge = IosBridge()
        var json = bridge.createProfile(bridge.emptyData(), "Bayern", "REALSCHULE", 7, "I", 2026,
            "Ganzes Schuljahr")
        val subject = dataJson.decodeFromString<SchoolData>(json).subjects.first()
        json = bridge.saveAssessment(json, "", subject.id, "Probe", "2026-10-15", "SCHOOLWORK",
            "PLANNED", "Algebra", -1, -1)
        val old = dataJson.decodeFromString<SchoolData>(json).assessments.single()
        assertEquals("Algebra", old.learningNotes)
        json = bridge.saveAssessment(json, old.id, subject.id, old.title, old.date,
            "SCHOOLWORK", "PLANNED", "Algebra und Geometrie", -1, -1)
        val edited = dataJson.decodeFromString<SchoolData>(json).assessments.single()
        assertEquals("Algebra und Geometrie", edited.learningNotes)
    }

    @Test fun deletesOnlyActiveProfilesDayOffAndException() {
        val bridge = IosBridge()
        var json = bridge.createProfile(bridge.emptyData(), "Bayern", "REALSCHULE", 7, "I", 2026,
            "Ganzes Schuljahr")
        val subject = dataJson.decodeFromString<SchoolData>(json).subjects.first()
        json = bridge.saveDayOff(json, "", "2026-10-05", "2026-10-05", "frei")
        json = bridge.saveException(json, "", subject.id, "2026-10-05", "08:00", "08:45", true)
        val current = dataJson.decodeFromString<SchoolData>(json)
        json = bridge.deleteDayOff(json, current.daysOff.single().id)
        json = bridge.deleteException(json, current.exceptions.single().id)
        val cleared = dataJson.decodeFromString<SchoolData>(json)
        assertEquals(0, cleared.daysOff.size)
        assertEquals(0, cleared.exceptions.size)
    }

    @Test fun plansAllFutureEstimatePrompts() {
        val bridge = IosBridge()
        var json = bridge.createProfile(bridge.emptyData(), "Bayern", "REALSCHULE", 7, "I", 2026,
            "Ganzes Schuljahr")
        val subject = dataJson.decodeFromString<SchoolData>(json).subjects.first()
        for (date in listOf("2026-10-05", "2026-10-07"))
            json = bridge.saveAssessment(json, "", subject.id, "Probe $date", date, "SCHOOLWORK",
                "PLANNED", "", -1, -1)
        val now = SchoolDate.parse("2026-10-01").atTime(12, 0).atZone(SchoolZone("Europe/Berlin"))
        val plan = bridge.estimatePromptPlan(json, now.epochMillis, "Europe/Berlin")
        assertContains(plan, "2026-10-05")
        assertContains(plan, "2026-10-07")
    }
    @Test fun createsPersistsAndCalculates() {
        val bridge = IosBridge()
        var json = bridge.createProfile(bridge.emptyData(), "Bayern", "REALSCHULE", 7, "I", 2026,
            "Ganzes Schuljahr")
        val data = dataJson.decodeFromString<SchoolData>(json)
        assertEquals("Deutsch", data.subjects.first { it.name == "Deutsch" }.name)
        val subject = data.subjects.first { it.name == "Deutsch" }
        json = bridge.saveAssessment(json, "", subject.id, "Probe", "2026-11-10", "ORAL", "GRADED", "",
            2, -1)
        assertEquals(2, dataJson.decodeFromString<SchoolData>(json).assessments.single().actual)
        assertContains(bridge.overview(json), "Deutsch")
        assertContains(bridge.normalizeData(json), "Probe")
    }

    @Test fun rejectsInvalidMutations() {
        val bridge = IosBridge()
        val json = bridge.createProfile(bridge.emptyData(), "Bayern", "GYMNASIUM", 8, "NTG", 2026,
            "Ganzes Schuljahr")
        assertFailsWith<IllegalArgumentException> {
            bridge.saveAssessment(json, "", "missing", "Test", "2026-11-10", "EX", "GRADED", "", 2, -1)
        }
        assertFailsWith<IllegalArgumentException> {
            bridge.addSubject(json, "Deutsch", false, true, -1)
        }
    }

    @Test fun datedTimetableAndChangesDriveDayAndRemainingLessons() {
        val bridge = IosBridge()
        var json = bridge.createProfile(bridge.emptyData(), "Bayern", "REALSCHULE", 7, "I", 2026,
            "Ganzes Schuljahr")
        val subject = dataJson.decodeFromString<SchoolData>(json).subjects.first { it.name == "Deutsch" }
        val monday = Lesson(subjectId = subject.id, day = 1, start = "08:00", end = "08:45", slot = 1)
        json = bridge.saveTimetable(json, "", "2026-08-01", "2026-08-03", dataJson.encodeToString(listOf(monday)))
        assertContains(bridge.dayLessons(json, "2026-10-05"), "Deutsch")
        json = bridge.saveAssessment(json, "", subject.id, "Probe", "2026-10-15", "SCHOOLWORK",
            "PLANNED", "", -1, -1)
        val exam = dataJson.decodeFromString<SchoolData>(json).assessments.single()
        assertEquals(2, bridge.remainingLessons(json, exam.id, "2026-10-04T12:00:00"))
        json = bridge.saveDayOff(json, "", "2026-10-05", "2026-10-05", "frei")
        assertEquals("[]", bridge.dayLessons(json, "2026-10-05"))
        json = bridge.saveException(json, "", subject.id, "2026-10-05", "10:00", "10:45", false)
        assertContains(bridge.dayLessons(json, "2026-10-05"), "10:00")
        assertFailsWith<IllegalArgumentException> {
            bridge.saveTimetable(json, "", "2026-08-01", "2026-08-04", "[]")
        }
    }

    @Test fun datedPlanSelectsABWeekAndHonorsCancellation() {
        val bridge = IosBridge()
        var json = bridge.createProfile(bridge.emptyData(), "Bayern", "REALSCHULE", 7, "I", 2026,
            "Ganzes Schuljahr")
        val subjects = dataJson.decodeFromString<SchoolData>(json).subjects
        val german = subjects.first { it.name == "Deutsch" }
        val math = subjects.first { it.name == "Mathematik" }
        json = bridge.saveTimetable(json, "", "2026-08-01", "2026-08-03",
            dataJson.encodeToString(listOf(Lesson(subjectId = german.id, day = 1, start = "08:00",
                end = "08:45", slot = 1))))
        json = bridge.saveTimetable(json, "", "2026-10-01", "2026-09-28",
            dataJson.encodeToString(listOf(
                Lesson(subjectId = math.id, day = 1, start = "08:00", end = "08:45", week = 1, slot = 1),
                Lesson(subjectId = german.id, day = 1, start = "08:00", end = "08:45", week = 2, slot = 1),
            )))
        assertContains(bridge.dayLessons(json, "2026-09-28"), "Deutsch")
        assertContains(bridge.dayLessons(json, "2026-10-05"), "Deutsch")
        assertContains(bridge.dayLessons(json, "2026-10-12"), "Mathematik")
        json = bridge.saveException(json, "", german.id, "2026-10-05", "08:00", "08:45", true)
        assertEquals("[]", bridge.dayLessons(json, "2026-10-05"))
    }

    @Test fun studyTimerUsesSharedCoreAndPreventsDoubleStart() {
        val bridge = IosBridge()
        var json = bridge.createProfile(bridge.emptyData(), "Bayern", "GYMNASIUM", 8, "NTG", 2026,
            "Ganzes Schuljahr")
        val subject = dataJson.decodeFromString<SchoolData>(json).subjects.first()
        json = bridge.saveAssessment(json, "", subject.id, "Probe", "2026-10-15", "EX", "PLANNED", "", -1, -1)
        val exam = dataJson.decodeFromString<SchoolData>(json).assessments.single()
        json = bridge.startStudy(json, exam.id, 100_000L, 10_000L, 42)
        assertFailsWith<IllegalArgumentException> { bridge.startStudy(json, exam.id, 101_000L, 11_000L, 42) }
        json = bridge.stopStudy(json, 160_000L, 70_000L, 42)
        val study = dataJson.decodeFromString<SchoolData>(json).studies.single()
        assertEquals(160_000L, study.end)
        json = bridge.correctStudy(json, study.id, 45, "Pause abziehen")
        assertEquals(2700L, dataJson.decodeFromString<SchoolData>(json).studies.single().seconds(999_000L))
    }

    @Test fun documentMetadataIsValidatedAndFollowsAssessmentDeletion() {
        val bridge = IosBridge()
        var json = bridge.createProfile(bridge.emptyData(), "Bayern", "REALSCHULE", 7, "I", 2026,
            "Ganzes Schuljahr")
        val subject = dataJson.decodeFromString<SchoolData>(json).subjects.first()
        json = bridge.saveAssessment(json, "", subject.id, "Probe", "2026-10-15", "SCHOOLWORK", "PLANNED", "", -1, -1)
        val exam = dataJson.decodeFromString<SchoolData>(json).assessments.single()
        assertFailsWith<IllegalArgumentException> {
            bridge.addDocument(json, exam.id, "unsicher", "application/pdf", "../leak.pdf", 6)
        }
        json = bridge.addDocument(json, exam.id, "Probe.pdf", "application/pdf", "0123-abcd.pdf", 6)
        assertEquals(1, dataJson.decodeFromString<SchoolData>(json).documents.size)
        json = bridge.deleteAssessment(json, exam.id)
        assertEquals(0, dataJson.decodeFromString<SchoolData>(json).documents.size)
    }

    @Test fun transferUsesOfficialGradesAndCertificateHonorsRulePackage() {
        val bridge = IosBridge()
        var json = bridge.createProfile(bridge.emptyData(), "Bayern", "GRUNDSCHULE", 4,
            "Grundschule", 2026, "Ganzes Schuljahr")
        val subjects = dataJson.decodeFromString<SchoolData>(json).subjects
        for ((name, grade) in listOf("Deutsch" to 2, "Mathematik" to 2,
            "Heimat- und Sachunterricht" to 3)) {
            json = bridge.saveOfficial(json, subjects.first { it.name == name }.id,
                "Übertrittszeugnis", grade, false)
        }
        assertContains(bridge.transferOverview(json), "DIRECT")
        val unsupported = bridge.createProfile(bridge.emptyData(), "Bayern", "GYMNASIUM", 13,
            "NTG", 2025, "13/2")
        assertContains(bridge.certificateOverview(unsupported, "Abitur"), "UNSUPPORTED")
    }

    @Test fun dueEstimateRemainsWhenNotificationNotDeliveredAndSkipIsPersisted() {
        val bridge = IosBridge()
        var json = bridge.createProfile(bridge.emptyData(), "Bayern", "REALSCHULE", 7,
            "I", 2026, "Ganzes Schuljahr")
        val subject = dataJson.decodeFromString<SchoolData>(json).subjects.first()
        json = bridge.saveAssessment(json, "", subject.id, "Probe", "2026-10-05", "SCHOOLWORK",
            "WRITTEN", "", -1, -1)
        val exam = dataJson.decodeFromString<SchoolData>(json).assessments.single()
        val now = SchoolDate.parse("2026-10-05").atTime(16, 1).atZone(SchoolZone("Europe/Berlin"))
        assertContains(bridge.estimatePromptsDue(json, now.epochMillis, "Europe/Berlin"), exam.id)
        json = bridge.skipEstimate(json, exam.id)
        assertEquals("[]", bridge.estimatePromptsDue(json, now.epochMillis, "Europe/Berlin"))
    }

    @Test fun deletingProfileRemovesOwnedRecordsAndKeepsOtherProfile() {
        val bridge = IosBridge()
        val first = bridge.createProfile(bridge.emptyData(), "Bayern", "GYMNASIUM", 8,
            "NTG", 2026, "Ganzes Schuljahr")
        val both = bridge.createProfile(first, "Bayern", "REALSCHULE", 7,
            "I", 2026, "Ganzes Schuljahr")
        val base = dataJson.decodeFromString<SchoolData>(both)
        val oldProfile = base.profiles.first()
        val newProfile = base.profiles.last()
        val oldSubject = base.subjects.first { it.profileId == oldProfile.id }
        val newSubject = base.subjects.first { it.profileId == newProfile.id }
        val oldExam = Assessment(subjectId = oldSubject.id, title = "Alt", date = "2026-10-15")
        val newExam = Assessment(subjectId = newSubject.id, title = "Neu", date = "2026-10-16")
        val populated = base.copy(
            assessments = listOf(oldExam, newExam),
            officials = listOf(Official(subjectId = oldSubject.id, period = "Jahr", value = 2)),
            timetables = listOf(Timetable(profileId = oldProfile.id, validFrom = "2026-08-01",
                anchorMonday = "2026-08-03")),
            daysOff = listOf(DayOff(profileId = oldProfile.id, from = "2026-10-05",
                to = "2026-10-05", reason = "frei")),
            exceptions = listOf(ExceptionLesson(subjectId = oldSubject.id, date = "2026-10-05",
                start = "08:00", end = "08:45", cancelled = true)),
            studies = listOf(Study(assessmentId = oldExam.id, start = 1000, end = 2000)),
            documents = listOf(Document(assessmentId = oldExam.id, name = "Alt.pdf",
                mime = "application/pdf", file = "0123-abcd.pdf", size = 6)),
            delivered = setOf("date:${oldExam.id}:2026-10-15:1", "estimate:${oldExam.id}:2026-10-15",
                "date:${newExam.id}:2026-10-16:1"),
            graduation = mapOf("${oldProfile.id}:Abitur" to GraduationInput(procedure = "Abitur"),
                "${newProfile.id}:Quali" to GraduationInput(procedure = "Quali")),
        )
        val remaining = dataJson.decodeFromString<SchoolData>(
            bridge.deleteProfile(dataJson.encodeToString(populated), oldProfile.id))
        assertEquals(listOf(newProfile.id), remaining.profiles.map { it.id })
        assertEquals(newProfile.id, remaining.activeProfileId)
        assertEquals(true, remaining.subjects.all { it.profileId == newProfile.id })
        assertEquals(listOf(newExam.id), remaining.assessments.map { it.id })
        assertEquals(emptyList(), remaining.officials)
        assertEquals(emptyList(), remaining.timetables)
        assertEquals(emptyList(), remaining.daysOff)
        assertEquals(emptyList(), remaining.exceptions)
        assertEquals(emptyList(), remaining.studies)
        assertEquals(emptyList(), remaining.documents)
        assertEquals(setOf("date:${newExam.id}:2026-10-16:1"), remaining.delivered)
        assertEquals(setOf("${newProfile.id}:Quali"), remaining.graduation.keys)

        val fallback = dataJson.decodeFromString<SchoolData>(
            bridge.deleteProfile(dataJson.encodeToString(populated), newProfile.id))
        assertEquals(oldProfile.id, fallback.activeProfileId)
        val empty = dataJson.decodeFromString<SchoolData>(
            bridge.deleteProfile(dataJson.encodeToString(remaining), newProfile.id))
        assertEquals(emptyList(), empty.profiles)
        assertEquals(null, empty.activeProfileId)
        assertFailsWith<IllegalArgumentException> {
            bridge.deleteProfile(dataJson.encodeToString(remaining), oldProfile.id)
        }
    }
}
