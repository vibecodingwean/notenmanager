package de.streberalarm.core

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private val p = Profile()
    private val s =
        Subject(
            profileId = p.id,
            name = "Mathematik",
            annualSchoolworks = 3,
            core = true,
            weightsConfirmed = true,
        )

    private fun exam(
        kind: Kind = Kind.SCHOOLWORK,
        actual: Int? = null,
        estimate: Int? = null,
        date: String = "2026-10-01",
    ) =
        Assessment(
            subjectId = s.id,
            title = "Schulaufgabe",
            date = date,
            kind = kind,
            actual = actual,
            estimate = estimate,
            stage = if (actual == null) Stage.PLANNED else Stage.GRADED,
        )

    @Test
    fun estimatesNeverAlterActualGrades() {
        val a = listOf(exam(actual = 2), exam(Kind.ORAL, 4), exam(estimate = 1))
        assertEquals(8.0 / 3, Grades.calculate(p, s, a).value!!, 1e-10)
        assertEquals(7.0 / 3, Grades.calculate(p, s, a, true).value!!, 1e-10)
    }

    @Test
    fun configuredAnnualCountNotRecordedCountControlsRatio() {
        val a = listOf(exam(actual = 2), exam(Kind.ORAL, 4))
        assertEquals(3.0, Grades.calculate(p, s.copy(annualSchoolworks = 2), a).value!!, 0.0)
        assertEquals(8.0 / 3, Grades.calculate(p, s, a).value!!, 1e-10)
        assertNull(Grades.calculate(p, s.copy(annualSchoolworks = null), a).value)
        assertNull(Grades.calculate(p, s, listOf(a[0])).value)
    }

    @Test
    fun realschuleWeightsEachLargeResultTwice() {
        val a = listOf(exam(actual = 2), exam(actual = 4), exam(Kind.ORAL, 1))
        assertEquals(2.6, Grades.calculate(p.copy(school = School.REALSCHULE), s, a).value!!, 1e-10)
    }

    @Test
    fun unsupportedStateAndHistoricalYearNeverUseBavarianPackage() {
        assertNull(Grades.calculate(p.copy(state = "Berlin"), s, listOf(exam(actual = 1))).value)
        assertNull(Grades.calculate(p.copy(year = 2025), s, listOf(exam(actual = 1))).value)
        assertEquals(
            Verdict.UNSUPPORTED,
            Rules.promotion(p.copy(state = "Berlin"), listOf(s), emptyList(), true).verdict,
        )
    }

    private fun data(e: Assessment = exam()): SchoolData {
        return SchoolData(
            profiles = listOf(p),
            activeProfileId = p.id,
            subjects = listOf(s),
            assessments = listOf(e),
            timetables =
                listOf(
                    Timetable(
                        profileId = p.id,
                        validFrom = "2026-09-01",
                        anchorMonday = "2026-08-31",
                        blocks =
                            listOf(
                                Lesson(subjectId = s.id, day = 1, start = "08:00", end = "08:45"),
                                Lesson(subjectId = s.id, day = 1, start = "08:45", end = "09:30"),
                                Lesson(subjectId = s.id, day = 4, start = "08:00", end = "09:30"),
                            ),
                    )
                ),
        )
    }

    @Test
    fun doublePeriodsHolidaysCancellationsAndUnknownExamTime() {
        val e = exam(date = "2026-09-17")
        val d = data(e)
        assertEquals(1, Lessons.remaining(d, e, LocalDateTime.parse("2026-09-14T07:00")).size)
        assertEquals(
            2,
            Lessons.remaining(d, e.copy(time = "10:00"), LocalDateTime.parse("2026-09-14T07:00"))
                .size,
        )
        assertEquals(
            1,
            Lessons.remaining(d, e.copy(time = "09:30"), LocalDateTime.parse("2026-09-14T07:00"))
                .size,
        )
        val off =
            d.copy(
                daysOff =
                    listOf(
                        DayOff(
                            profileId = p.id,
                            from = "2026-09-14",
                            to = "2026-09-14",
                            reason = "frei",
                        )
                    )
            )
        assertEquals(0, Lessons.remaining(off, e, LocalDateTime.parse("2026-09-14T07:00")).size)
        val extra =
            off.copy(
                exceptions =
                    listOf(
                        ExceptionLesson(
                            subjectId = s.id,
                            date = "2026-09-14",
                            start = "10:00",
                            end = "11:00",
                            cancelled = false,
                        )
                    )
            )
        assertEquals(1, Lessons.remaining(extra, e, LocalDateTime.parse("2026-09-14T07:00")).size)
        val cancelled =
            d.copy(
                exceptions =
                    listOf(
                        ExceptionLesson(
                            subjectId = s.id,
                            date = "2026-09-14",
                            start = "08:00",
                            end = "09:30",
                            cancelled = true,
                        )
                    )
            )
        assertEquals(
            0,
            Lessons.remaining(cancelled, e, LocalDateTime.parse("2026-09-14T07:00")).size,
        )
    }

    @Test
    fun weekAlternationAndDatedVersion() {
        val e = exam()
        val d = data(e)
        val t = d.timetables[0]
        val a =
            t.copy(
                blocks =
                    listOf(
                        Lesson(subjectId = s.id, day = 1, start = "08:00", end = "09:00", week = 1)
                    )
            )
        assertEquals(
            listOf(LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-28")),
            Lessons.remaining(
                    d.copy(timetables = listOf(a)),
                    e,
                    LocalDateTime.parse("2026-09-14T07:00"),
                )
                .map { it.date },
        )
        val b = a.copy(id = id(), validFrom = "2026-09-21", blocks = emptyList())
        assertEquals(
            1,
            Lessons.remaining(
                    d.copy(timetables = listOf(a, b)),
                    e,
                    LocalDateTime.parse("2026-09-14T07:00"),
                )
                .size,
        )
    }

    @Test
    fun remindersLocalTimeDstBundlingAndEdit() {
        val e = exam(date = "2026-11-01")
        val d = data(e).copy(timetables = emptyList())
        val now = ZonedDateTime.parse("2026-10-01T18:00:00+02:00[Europe/Berlin]")
        val plan = Reminders.plan(d, now)
        assertEquals(9, plan.size)
        assertTrue(plan.all { it.at.hour == 16 })
        assertEquals(2, plan.map { it.at.offset }.distinct().size)
        assertEquals(
            9,
            Reminders.plan(d.copy(assessments = listOf(e, e.copy(id = id()))), now).size,
        )
        assertTrue(
            Reminders.plan(d.copy(assessments = listOf(e.copy(stage = Stage.CANCELLED))), now)
                .isEmpty()
        )
        val shifted = Reminders.plan(d.copy(assessments = listOf(e.copy(date = "2026-11-05"))), now)
        assertTrue(shifted.flatMap { it.notices }.all { "2026-11-05" in it.key })
        assertTrue(Reminders.plan(d, now.plusDays(27)).all { it.at > now.plusDays(27) })
    }

    @Test
    fun thresholdNoticeOnceAndLateCapture() {
        val e = exam(date = "2026-09-17")
        val d = data(e)
        val now = ZonedDateTime.parse("2026-09-13T12:00:00+02:00[Europe/Berlin]")
        val notices =
            Reminders.plan(d, now).flatMap { it.notices }.filter { it.key.startsWith("lessons:") }
        assertEquals(1, notices.size)
        assertTrue(notices[0].text.contains("1×"))
        assertTrue(
            Reminders.plan(d.copy(delivered = setOf(notices[0].key)), now)
                .flatMap { it.notices }
                .none { it.key.startsWith("lessons:") }
        )
    }

    @Test
    fun timerPersistsMonotonicDurationAndPreventsSecondTimer() {
        val e = exam()
        val d = Timers.start(data(e), e.id, 100000, 500, 1)
        assertThrows(IllegalArgumentException::class.java) { Timers.start(d, e.id, 100000, 500, 1) }
        assertEquals(120, Timers.stop(d, 1, 120500, 1).studies.single().seconds(0))
        val reboot = Timers.stop(d, 300000, 10, 2).studies.single()
        assertTrue(reboot.correction.contains("neu gestartet"))
        assertEquals(200, reboot.seconds(0))
    }

    @Test
    fun encryptedBackupRoundTripAndTamperDoesNotProduceData() {
        val e = exam()
        val file = "${id()}.pdf"
        val doc =
            Document(
                assessmentId = e.id,
                name = "Zwei Seiten.pdf",
                mime = "application/pdf",
                file = file,
                size = 6,
            )
        val d = data(e).copy(documents = listOf(doc))
        val password = id().toCharArray()
        val bytes = Backup.export(d, password) { "%PDF-1".toByteArray() }
        val restored = Backup.restore(bytes, password)
        assertEquals(d, restored.data)
        assertArrayEquals("%PDF-1".toByteArray(), restored.files[file])
        assertThrows(IllegalArgumentException::class.java) {
            Backup.restore(bytes, "falschesPW".toCharArray())
        }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { Backup.restore(bytes, password) }
    }

    @Test
    fun danglingAttachmentAndMultipleTimersFailValidation() {
        val d = data()
        assertThrows(IllegalArgumentException::class.java) {
            d.copy(
                    documents =
                        listOf(
                            Document(
                                assessmentId = "missing",
                                name = "a",
                                mime = "application/pdf",
                                file = "${id()}.pdf",
                                size = 3,
                            )
                        )
                )
                .validate()
        }
    }

    @Test
    fun promotionMissingNotZeroAndCompensationIsSchoolDecision() {
        val profile = p.copy(grade = 10)
        val subjects =
            (1..5).map { Subject(profileId = profile.id, name = "Fach $it", core = true) }
        fun notes(v: List<Int>) =
            subjects.zip(v).map { (s, n) ->
                Official(subjectId = s.id, period = "Jahreszeugnis", value = n)
            }
        assertEquals(
            Verdict.INCOMPLETE,
            Rules.promotion(profile, subjects, emptyList(), true).verdict,
        )
        assertEquals(
            Verdict.SCHOOL_DECISION,
            Rules.promotion(profile, subjects, notes(listOf(5, 5, 3, 3, 3)), true, false).verdict,
        )
        assertEquals(
            Verdict.RISK,
            Rules.promotion(profile, subjects, notes(listOf(5, 5, 3, 3, 3)), true, true).verdict,
        )
        assertEquals(
            Verdict.SATISFIED,
            Rules.promotion(profile, subjects, notes(listOf(5, 4, 4, 4, 4)), true).verdict,
        )
    }

    @Test
    fun qualiTruncatesAndRequiresInputs() {
        val profile = p.copy(school = School.MITTELSCHULE, grade = 9)
        val fields = Rules.qualiFields(profile, false).mapValues { 3 as Int? }
        val good =
            GraduationInput(
                procedure = "Quali",
                numbers = fields,
                confirmations = mapOf("eligibility" to true),
            )
        assertEquals("3,0", Rules.certificate(profile, good).value)
        assertEquals(
            Verdict.SATISFIED,
            Rules.certificate(
                    profile,
                    good.copy(numbers = fields + ("Wirtschaft und Beruf Jahresnote" to 4)),
                )
                .verdict,
        )
        assertEquals(
            Verdict.RISK,
            Rules.certificate(profile, good.copy(numbers = fields + ("Projektprüfung" to 4)))
                .verdict,
        )
        assertEquals(
            Verdict.INCOMPLETE,
            Rules.certificate(profile, good.copy(numbers = emptyMap())).verdict,
        )
    }

    @Test
    fun middleCertificateGoodAverageCannotHideGermanSixOrProjectSix() {
        val i =
            GraduationInput(
                procedure = "Mittlerer Abschluss",
                numbers = mapOf("Deutsch" to 6, "Mathematik" to 1, "Englisch" to 1, "Physik" to 1),
                confirmations = mapOf("allOfficial" to true),
            )
        assertEquals(
            Verdict.RISK,
            Rules.certificate(p.copy(school = School.REALSCHULE, grade = 10), i).verdict,
        )
        assertEquals(
            Verdict.RISK,
            Rules.certificate(
                    p.copy(school = School.M_ZUG, grade = 10),
                    i.copy(
                        numbers =
                            i.numbers +
                                ("Deutsch" to 1) +
                                ("Projekt" to 1) +
                                ("Projektprüfung" to 6)
                    ),
                )
                .verdict,
        )
        assertEquals(
            Verdict.SATISFIED,
            Rules.certificate(
                    p.copy(school = School.REALSCHULE, grade = 10),
                    i.copy(numbers = i.numbers + ("Deutsch" to 1) + ("Physik" to 6)),
                )
                .verdict,
        )
    }

    @Test
    fun abiturGradeTableAndAdditionalExamRounding() {
        assertEquals(1.0, Rules.abiturGrade(823), 0.0)
        assertEquals(1.1, Rules.abiturGrade(822), 0.0)
        assertEquals(3.9, Rules.abiturGrade(301), 0.0)
        assertEquals(4.0, Rules.abiturGrade(300), 0.0)
        assertEquals(5, Rules.examPoints(AbiturExam("Deutsch", points = 1, oralAddition = 2)))
        assertEquals(
            Verdict.INCOMPLETE,
            Rules.certificate(
                    p.copy(grade = 13, term = "13/2"),
                    GraduationInput(procedure = "Abitur"),
                )
                .verdict,
        )
    }
}
