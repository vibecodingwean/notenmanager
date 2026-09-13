package de.streberalarm.core

import kotlin.test.*

class GradeOverviewTest {
    private val p = Profile(school = School.REALSCHULE, grade = 8, track = "I")

    private fun subject(name: String, core: Boolean = false, promotion: Boolean = true) =
        Subject(
            profileId = p.id,
            name = name,
            core = core,
            promotion = promotion,
            annualSchoolworks = 0,
        )

    private fun data(vararg entries: Pair<Subject, Int>): SchoolData =
        SchoolData(
            profiles = listOf(p),
            activeProfileId = p.id,
            subjects = entries.map { it.first },
            assessments =
                entries.map { (s, n) ->
                    Assessment(
                        subjectId = s.id,
                        title = "Note",
                        date = "2026-10-01",
                        kind = Kind.ORAL,
                        stage = Stage.GRADED,
                        actual = n,
                    )
                },
            catalogVersion = 2,
        )

    private fun annual(d: SchoolData): SchoolData =
        d.copy(
            officials =
                d.assessments.map {
                    Official(subjectId = it.subjectId, period = "Jahreszeugnis", value = it.actual)
                }
        )

    private fun result(d: SchoolData, profile: Profile = p) =
        GradeOverviewCalculator.calculate(profile, d)

    @Test
    fun averageCountsEachKnownSubjectOnceAndNeverEstimatesOrAnotherProfile() {
        val math = subject("Mathematik", true)
        val sport = subject("Sport")
        val d = data(math to 2, sport to 6)
        val estimated =
            Assessment(subjectId = math.id, title = "Noch offen", date = "2026-10-02", estimate = 1)
        val extra = d.assessments.first().copy(id = id())
        val other = subject("Fremdes Profil").copy(profileId = "other")
        val report =
            result(
                d.copy(
                    subjects = d.subjects + other,
                    assessments =
                        d.assessments +
                            extra +
                            estimated +
                            extra.copy(id = id(), subjectId = other.id, actual = 6) +
                            extra.copy(id = id(), stage = Stage.CANCELLED, actual = null),
                )
            )
        assertEquals(4.0, report.average)
        assertEquals(2, report.averageCount)
        assertEquals(2, report.subjectCount)
        assertEquals(TrafficLight.GREEN, report.light)
        assertEquals(listOf(math.id), report.standings.map { it.subject.id })
    }

    @Test
    fun smallOverallMeanCannotHideARealschuleSixAndGoodGradesDoNotCompensate() {
        val d =
            annual(
                data(
                    subject("Mathematik", true) to 6,
                    subject("Deutsch", true) to 1,
                    subject("Englisch", true) to 1,
                    subject("Physik", true) to 1,
                )
            )
        val report = result(d)
        assertEquals(2.25, report.average)
        assertEquals(TrafficLight.RED, report.light)
        assertTrue(report.compensation.isEmpty())
        assertEquals(5, report.improvements.first().goals.single().grade)
        assertTrue(report.advice.contains("nicht automatisch"))
    }

    @Test
    fun realschuleThreeFivesNeedTwoImprovementsAndEveryShownPlanPasses() {
        val d =
            annual(
                data(
                    subject("Mathematik", true) to 5,
                    subject("Englisch", true) to 5,
                    subject("Physik", true) to 5,
                    subject("Deutsch", true) to 2,
                )
            )
        val report = result(d)
        assertEquals(TrafficLight.RED, report.light)
        assertEquals(3, report.improvements.size)
        report.improvements.forEach { plan ->
            assertEquals(2, plan.goals.size)
            assertTrue(plan.goals.all { it.grade == 4 })
            val changed =
                d.officials.map { o ->
                    o.copy(
                        value = plan.goals.find { it.subjectId == o.subjectId }?.grade ?: o.value
                    )
                }
            assertEquals(Verdict.SATISFIED, Rules.promotion(p, d.subjects, changed, true).verdict)
        }
        assertFalse(report.notes.any { it.startsWith("Nachprüfung:") })
    }

    @Test
    fun unknownGradesStayUnknownAndAVisibleRiskIsNotHiddenByMissingOnes() {
        val math = subject("Mathematik", true)
        val english = subject("Englisch", true)
        val d = data(math to 2).copy(subjects = listOf(math, english))
        assertEquals(TrafficLight.UNKNOWN, result(d).light)
        assertEquals(2.0, result(d).average)
        assertEquals(1, result(d).averageCount)
        assertEquals(
            TrafficLight.RED,
            result(d.copy(assessments = d.assessments.map { it.copy(actual = 6) })).light,
        )
        assertNull(result(d.copy(assessments = emptyList())).average)
    }

    @Test
    fun annualGradesOverrideOnlyTheForecastAndAnOmissionActsAsSix() {
        val math = subject("Mathematik", true)
        val d = data(math to 2)
        val override =
            d.copy(
                officials =
                    listOf(Official(subjectId = math.id, period = "Jahreszeugnis", value = 6))
            )
        val report = result(override)
        assertEquals(2.0, report.average)
        assertEquals(TrafficLight.RED, report.light)
        assertTrue(report.standings.single().annual)
        assertEquals(
            TrafficLight.RED,
            result(
                    override.copy(
                        officials = override.officials.map { it.copy(value = null, omitted = true) }
                    )
                )
                .light,
        )
        assertEquals(
            TrafficLight.GREEN,
            result(
                    override.copy(
                        officials = override.officials.map { it.copy(period = "Zwischenzeugnis") }
                    )
                )
                .light,
        )
        assertEquals(2, d.assessments.single().actual)
    }

    @Test
    fun forecastRoundsOnlyForSimulationAndFlagsBorderlineOutcomes() {
        val a = subject("Mathematik", true)
        val b = subject("Deutsch", true)
        val base = data(a to 4, b to 4)
        val extra = base.assessments.map { it.copy(id = id(), actual = 5, weight = 0.25) }
        val forecast = result(base.copy(assessments = base.assessments + extra))
        assertEquals(4.2, forecast.average!!, 1e-10)
        assertTrue(forecast.standings.all { it.grade == 4 && !it.annual })
        assertEquals(TrafficLight.AMBER, forecast.light)
        val atBoundary =
            result(
                base.copy(
                    assessments =
                        base.assessments + base.assessments.map { it.copy(id = id(), actual = 5) }
                )
            )
        assertEquals(TrafficLight.RED, atBoundary.light)
        assertTrue(atBoundary.standings.all { it.grade == 5 })
        assertTrue(base.officials.isEmpty())
    }

    @Test
    fun schoolSpecificExclusionsAndCurrentYearMigrationPreserveEverythingElse() {
        val art = subject("Kunst")
        val music = subject("Musik")
        val sport = subject("Sport")
        val math = subject("Mathematik", true)
        val d = annual(data(art to 6, music to 6, sport to 6, math to 2)).copy(catalogVersion = 1)
        val normalized = Defaults.updateCatalog(d)
        assertEquals(TrafficLight.GREEN, result(normalized).light)
        assertEquals(d.assessments, normalized.assessments)
        assertEquals(d.officials, normalized.officials)
        assertEquals(d.subjects.map { it.id }, normalized.subjects.map { it.id })
        assertEquals(normalized, Defaults.updateCatalog(normalized))
        val artTrack = p.copy(track = "IIIb · Kunst")
        assertTrue(Defaults.subjects(artTrack).first { it.name == "Kunst" }.promotion)
        assertEquals(TrafficLight.RED, result(d, artTrack).light)
        val historical = d.copy(profiles = listOf(p.copy(year = 2025)))
        assertEquals(historical.subjects, Defaults.updateCatalog(historical).subjects)
        assertEquals(
            listOf(math),
            Defaults.updateCatalog(normalized.copy(subjects = listOf(math))).subjects,
        )
    }

    @Test
    fun gymnasiumCompensationRequiresRightYearAndCoreSubjectsAndNeverTurnsGreen() {
        val profile = p.copy(school = School.GYMNASIUM, grade = 10, track = "NTG")
        val d =
            annual(
                data(
                    subject("Mathematik", true) to 5,
                    subject("Physik", true) to 5,
                    subject("Deutsch", true) to 3,
                    subject("Englisch", true) to 3,
                    subject("Chemie", true) to 3,
                    subject("Kunst") to 1,
                )
            )
        val report = result(d, profile)
        assertEquals(TrafficLight.AMBER, report.light)
        val matched = report.compensation.first { it.alreadyMet }
        assertEquals(3, matched.goals.size)
        assertTrue(matched.goals.all { goal -> d.subjects.first { it.id == goal.subjectId }.core })
        assertTrue(report.compensation.all { plan -> plan.goals.none { it.name == "Kunst" } })
        assertEquals(TrafficLight.RED, result(d, profile.copy(grade = 9)).light)
        assertTrue(result(d, profile.copy(grade = 9)).compensation.isEmpty())
        val oneBetter =
            annual(data(subject("Mathematik", true) to 6, subject("Deutsch", true) to 1))
        assertEquals(TrafficLight.AMBER, result(oneBetter, profile).light)
        assertEquals(
            Verdict.RISK,
            Rules.promotion(
                    profile,
                    oneBetter.subjects,
                    oneBetter.officials,
                    true,
                    previousCompensation = true,
                )
                .verdict,
        )
        assertTrue(result(oneBetter, profile).notes.any { it.contains("Kein erneuter Ausgleich") })
    }

    @Test
    fun mClassCompensationAndGermanSixExclusionAreDistinctFromRegularClass() {
        val profile = p.copy(school = School.M_ZUG, track = "Technik")
        val d =
            annual(
                data(
                    subject("Mathematik", true) to 6,
                    subject("Kunst") to 1,
                    subject("Deutsch", true) to 3,
                )
            )
        assertEquals(TrafficLight.AMBER, result(d, profile).light)
        val german =
            d.copy(
                subjects =
                    d.subjects.map {
                        if (it.name == "Mathematik") it.copy(name = "Deutsch")
                        else if (it.name == "Deutsch") it.copy(name = "Mathematik") else it
                    }
            )
        assertEquals(TrafficLight.RED, result(german, profile).light)
        assertTrue(result(german, profile).compensation.isEmpty())
        val regular = p.copy(school = School.MITTELSCHULE)
        val boundary =
            annual(
                data(
                    subject("Deutsch", true) to 6,
                    subject("Mathematik", true) to 5,
                    subject("Englisch", true) to 4,
                    subject("Natur und Technik") to 1,
                )
            )
        assertNotEquals(TrafficLight.RED, result(boundary, regular).light) // mean4, exactly3 units
        val above =
            boundary.copy(
                officials = boundary.officials.map { if (it.value == 1) it.copy(value = 2) else it }
            )
        assertEquals(TrafficLight.RED, result(above, regular).light)
        val fourUnits =
            annual(
                data(
                    subject("Deutsch") to 6,
                    subject("Mathematik") to 6,
                    subject("Englisch") to 1,
                    subject("Kunst") to 1,
                )
            )
        assertEquals(
            TrafficLight.RED,
            result(fourUnits, regular).light,
        ) // good mean does not erase4 units
        result(fourUnits, regular).improvements.forEach { plan ->
            val changed =
                fourUnits.officials.map { o ->
                    o.copy(
                        value = plan.goals.find { it.subjectId == o.subjectId }?.grade ?: o.value
                    )
                }
            assertEquals(
                Verdict.SCHOOL_DECISION,
                Rules.promotion(regular, fourUnits.subjects, changed, true).verdict,
            )
        }
    }

    @Test
    fun unsupportedYearsStatesFinalYearsAndPointsNeverUseOrdinaryPromotion() {
        val d = data(subject("Mathematik", true) to 2)
        for (profile in
            listOf(
                p.copy(state = "Berlin"),
                p.copy(year = 2025),
                p.copy(grade = 10),
                p.copy(school = School.MITTELSCHULE, grade = 9),
                p.copy(school = School.GYMNASIUM, grade = 12, term = "12/1"),
            )) {
            assertEquals(TrafficLight.UNKNOWN, result(d, profile).light)
            assertTrue(result(d, profile).improvements.isEmpty())
        }
    }

    @Test
    fun allSmallGradePatternsProduceOnlyValidImprovementPlans() {
        for (school in
            listOf(School.REALSCHULE, School.GYMNASIUM, School.MITTELSCHULE, School.M_ZUG)) {
            val profile = p.copy(school = school, grade = 8)
            for (a in 1..6) for (b in 1..6) for (c in 1..6) {
                val d =
                    annual(
                        data(
                            subject("Deutsch", true) to a,
                            subject("Mathematik", true) to b,
                            subject("Englisch", true) to c,
                        )
                    )
                result(d, profile).improvements.forEach { plan ->
                    val changed =
                        d.officials.map { o ->
                            o.copy(
                                value =
                                    plan.goals.find { it.subjectId == o.subjectId }?.grade
                                        ?: o.value
                            )
                        }
                    val verdict = Rules.promotion(profile, d.subjects, changed, true).verdict
                    assertTrue(
                        verdict == Verdict.SATISFIED ||
                            school == School.MITTELSCHULE && verdict == Verdict.SCHOOL_DECISION,
                        "$school/$a/$b/$c -> $plan",
                    )
                }
            }
        }
    }
}
