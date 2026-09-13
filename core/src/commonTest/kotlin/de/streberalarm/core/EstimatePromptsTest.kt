package de.streberalarm.core

import kotlin.test.*

class EstimatePromptsTest {
    private val p = Profile(school = School.REALSCHULE)
    private val s = Subject(profileId = p.id, name = "Mathematik")
    private val e = Assessment(subjectId = s.id, title = "Schulaufgabe", date = "2026-10-25")

    private fun data(exam: Assessment = e) =
        SchoolData(
            profiles = listOf(p),
            activeProfileId = p.id,
            subjects = listOf(s),
            assessments = listOf(exam),
        )

    private fun at(time: String, zone: String = "Europe/Berlin") =
        SchoolDateTime.parse(time).atZone(SchoolZone(zone))

    @Test
    fun asksAtSixteenIncludingDstAndTimezoneChanges() {
        for (zone in listOf("Europe/Berlin", "America/New_York")) {
            val before = at("2026-10-25T15:59:59", zone)
            assertTrue(EstimatePrompts.due(data(), before).isEmpty())
            assertEquals(at("2026-10-25T16:00", zone), EstimatePrompts.nextAt(data(), before))
            assertEquals(listOf(e), EstimatePrompts.due(data(), at("2026-10-25T16:00", zone)))
        }
    }

    @Test
    fun gradingAsksImmediatelyAndAnswersOrSkipsSuppressQuestions() {
        val now = at("2026-10-20T09:00")
        val graded = e.copy(stage = Stage.GRADED, actual = 2)
        assertEquals(listOf(graded), EstimatePrompts.due(data(graded), now))
        assertTrue(EstimatePrompts.due(data(graded.copy(estimate = 3)), now).isEmpty())
        assertTrue(
            EstimatePrompts.due(
                    data(graded).copy(delivered = setOf(EstimatePrompts.skipKey(graded))),
                    now,
                )
                .isEmpty()
        )
        assertTrue(EstimatePrompts.due(data(e.copy(stage = Stage.CANCELLED)), now).isEmpty())
    }

    @Test
    fun deliveryPersistsButDeniedNotificationsKeepInAppFallbackWithoutAlarmLoop() {
        val now = at("2026-10-26T10:00")
        val sent = data().copy(delivered = setOf(EstimatePrompts.key(e)))
        assertNull(EstimatePrompts.nextAt(sent, now))
        assertEquals(listOf(e), EstimatePrompts.due(sent, now))
        assertNull(EstimatePrompts.nextAt(data(), now, includeOverdue = false))
        assertTrue(EstimatePrompts.nextAt(data(), now)!! > now)
        assertTrue(EstimatePrompts.due(data().copy(activeProfileId = null), now).isEmpty())
    }

    @Test
    fun reschedulingGetsNewKeyAndRemovedOrCancelledTestsHaveNoAlarm() {
        val now = at("2026-10-26T10:00")
        val moved = e.copy(date = "2026-10-28")
        val d = data(moved).copy(delivered = setOf(EstimatePrompts.key(e)))
        assertTrue(EstimatePrompts.due(d, now).isEmpty())
        assertEquals(at("2026-10-28T16:00"), EstimatePrompts.nextAt(d, now))
        assertNull(EstimatePrompts.nextAt(d.copy(assessments = emptyList()), now))
        assertNull(EstimatePrompts.nextAt(data(e.copy(stage = Stage.CANCELLED)), now))
    }

    @Test
    fun catalogUpdateKeepsIdsAndDoesNotDuplicateOrResurrectIt() {
        val old = s.copy(name = "Ethik")
        val updated = Defaults.updateCatalog(data().copy(subjects = listOf(old)))
        assertEquals("Ethik/Religion", updated.subjects.first { it.id == old.id }.name)
        assertEquals(1, updated.subjects.count { it.name == "IT · Informationstechnologie" })
        assertEquals(updated, Defaults.updateCatalog(updated))
        val deleted = updated.copy(subjects = listOf(updated.subjects.first { it.id == old.id }))
        assertEquals(deleted, Defaults.updateCatalog(deleted))
        val customIt = s.copy(name = "IT")
        assertEquals(
            listOf(customIt),
            Defaults.updateCatalog(data().copy(subjects = listOf(customIt))).subjects,
        )
    }
}
