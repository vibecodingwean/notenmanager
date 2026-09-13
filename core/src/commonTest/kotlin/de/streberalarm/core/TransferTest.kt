package de.streberalarm.core

import kotlin.test.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class TransferTest {
    private val p = Profile(school = School.GRUNDSCHULE, grade = 4, track = "Grundschule")

    private fun data(vararg grades: Int): SchoolData {
        val subjects = Defaults.subjects(p)
        val required = Transfer.subjects(p, SchoolData(subjects = subjects))
        return SchoolData(
            profiles = listOf(p),
            activeProfileId = p.id,
            subjects = subjects,
            officials =
                required.zip(grades.toList()).map { (s, n) ->
                    Official(subjectId = s!!.id, period = Transfer.PERIOD, value = n)
                },
            catalogVersion = 2,
        )
    }

    @Test
    fun all216OfficialCombinationsUseIntegerThresholdsAndAllThreeRequiredSubjects() {
        for (a in 1..6) for (b in 1..6) for (c in 1..6) {
            val d = data(a, b, c)
            val r = Transfer.calculate(p, d)
            assertEquals(a + b + c <= 7, r.gymnasium == TransferRoute.DIRECT)
            assertEquals(a + b + c <= 8, r.realschule == TransferRoute.DIRECT)
            assertTrue(r.official)
            d.validate()
        }
    }

    @Test
    fun missingAndOmittedOfficialGradesDoNotFallBackToOptimisticForecasts() {
        val d = data(2, 2)
        assertEquals(TransferRoute.UNKNOWN, Transfer.calculate(p, d).gymnasium)
        assertEquals(
            TransferRoute.UNKNOWN,
            Transfer.calculate(
                    p,
                    data(2, 2, 2).let {
                        it.copy(
                            officials =
                                it.officials.map { o -> o.copy(value = null, omitted = true) }
                        )
                    },
                )
                .realschule,
        )
    }

    @Test
    fun estimatesAndOtherProfilesDoNotChangeTransferAndForecastNeverBecomesOfficial() {
        val d = data(2, 2, 3)
        val rows =
            d.officials.map {
                Assessment(
                    subjectId = it.subjectId,
                    title = "Probe",
                    date = "2026-10-01",
                    kind = Kind.TEST,
                    stage = Stage.GRADED,
                    actual = it.value,
                )
            }
        val forecast =
            d.copy(
                officials = emptyList(),
                assessments =
                    rows +
                        rows
                            .first()
                            .copy(id = id(), actual = null, estimate = 6, stage = Stage.PLANNED),
            )
        val r = Transfer.calculate(p, forecast)
        assertFalse(r.official)
        assertEquals(TransferRoute.FORECAST, r.gymnasium)
        assertEquals(7.0 / 3, r.average)
        assertEquals(
            TransferRoute.UNKNOWN,
            Transfer.calculate(p, forecast.copy(assessments = emptyList())).gymnasium,
        )
        assertEquals(
            TransferRoute.UNKNOWN,
            Transfer.calculate(p.copy(state = "Hessen"), d).gymnasium,
        )
        assertEquals(TransferRoute.UNKNOWN, Transfer.calculate(p.copy(year = 2025), d).gymnasium)
        assertFalse(Transfer.calculate(p.copy(grade = 3), d).official)
        assertEquals(TrafficLight.UNKNOWN, GradeOverviewCalculator.calculate(p, d).light)
        assertEquals(Verdict.UNSUPPORTED, Rules.promotion(p, d.subjects, d.officials, true).verdict)
    }

    @Test
    fun probeHasIndependentMinimumGradesAndParentsChoiceIsConditional() {
        for (a in 1..6) for (b in 1..6) {
            val expected =
                when {
                    a <= 3 && b <= 4 || b <= 3 && a <= 4 -> TransferRoute.PROBE_PASSED
                    a == 4 && b == 4 -> TransferRoute.PARENTS
                    else -> TransferRoute.PROBE_FAILED
                }
            assertEquals(expected, Transfer.probe(a, b))
        }
        assertEquals(TransferRoute.UNKNOWN, Transfer.probe(3, null))
        assertEquals(TransferRoute.UNKNOWN, Transfer.probe(0, 4))
        val d =
            data(4, 4, 4)
                .copy(
                    graduation =
                        mapOf(
                            "${p.id}:uebertritt" to
                                GraduationInput(
                                    procedure = "uebertritt",
                                    numbers = mapOf("gymDeutsch" to 3, "gymMathematik" to 4),
                                )
                        )
                )
        assertEquals(TransferRoute.PROBE_PASSED, Transfer.calculate(p, d).gymnasium)
        assertEquals(TransferRoute.PROBE_PASSED, Transfer.calculate(p, d).realschule)
        assertEquals(d, dataJson.decodeFromString<SchoolData>(dataJson.encodeToString(d)))
    }

    @Test
    fun primaryCoachNeverUsesTeenagePassingSlogan() {
        val advice = PassingCoach.advice(p, data(2, 2, 3), false)
        assertFalse(advice.canRelax)
        assertFalse(advice.message.contains("Einser"))
        assertEquals("Dein nächster Schulweg", advice.title)
    }
}
