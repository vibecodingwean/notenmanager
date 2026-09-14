package de.streberalarm.core

import kotlin.test.*

class DefaultsTest {
    @Test
    fun realschuleHasNoTrackBeforeSeventhGrade() {
        assertEquals(listOf(Defaults.NO_TRACK), Defaults.tracks(School.REALSCHULE, 5))
        assertEquals(listOf(Defaults.NO_TRACK), Defaults.tracks(School.REALSCHULE, 6))
        assertEquals(
            listOf(
                "I",
                "II",
                "IIIa",
                "IIIb · Kunst",
                "IIIb · Werken",
                "IIIb · Ernährung und Gesundheit",
                "IIIb · Sozialwesen",
            ),
            Defaults.tracks(School.REALSCHULE, 7),
        )
    }

    @Test
    fun noTrackAddsNoRealschuleTrackSubject() {
        val profile = Profile(school = School.REALSCHULE, grade = 5, track = Defaults.NO_TRACK)
        val subjects = Defaults.subjects(profile)

        assertFalse(subjects.any { it.name == "Betriebswirtschaftslehre/Rechnungswesen" })
        assertFalse(subjects.any { it.name == "Französisch" })
        assertEquals(
            setOf("Deutsch", "Mathematik", "Englisch"),
            subjects.filter { it.core }.map { it.name }.toSet(),
        )
    }
}
