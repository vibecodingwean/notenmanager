package de.streberalarm.core

import kotlin.test.*

class PortablePlanningTest {
    @Test
    fun sharedCalendarAndDstHaveNoJavaDependency() {
        val p = Profile()
        val s = Subject(profileId = p.id, name = "Mathematik")
        val e = Assessment(subjectId = s.id, title = "Schulaufgabe", date = "2026-11-01")
        val d =
            SchoolData(
                profiles = listOf(p),
                activeProfileId = p.id,
                subjects = listOf(s),
                assessments = listOf(e),
            )
        val now = SchoolDateTime.parse("2026-10-01T12:00").atZone(SchoolZone("Europe/Berlin"))
        val reminders = Reminders.plan(d, now)
        assertEquals(9, reminders.size)
        assertTrue(reminders.all { it.at.toLocalDateTime().toString().endsWith("T16:00") })
        val before = SchoolDateTime.parse("2026-10-24T16:00").atZone(SchoolZone("Europe/Berlin"))
        val after = SchoolDateTime.parse("2026-10-25T16:00").atZone(SchoolZone("Europe/Berlin"))
        assertEquals(25 * 3600 * 1000L, after.epochMillis - before.epochMillis)
    }

    @Test
    fun nativeSerializableModelRoundTripAndId() {
        val p = Profile()
        val encoded = dataJson.encodeToString(Profile.serializer(), p)
        assertEquals(p, dataJson.decodeFromString(Profile.serializer(), encoded))
        assertTrue(p.id.matches(Regex("[a-f0-9-]{36}")))
    }

    @Test
    fun stableRuleSelectionDoesNotFallBackAcrossStates() {
        assertNotNull(RulePackages.resolve(Profile()))
        assertNull(RulePackages.resolve(Profile(state = "Sachsen")))
        assertNull(RulePackages.resolve(Profile(ruleVersion = "BY-future")))
        assertNull(RulePackages.resolve(Profile(year = 2025)))
    }
}
