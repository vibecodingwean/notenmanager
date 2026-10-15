package de.streberalarm.core

import kotlin.test.*
import kotlinx.serialization.encodeToString

class FlashcardsTest {
    private fun fixture(): SchoolData {
        var d = SchoolData(profiles = listOf(Profile(id = "p")), activeProfileId = "p")
        d = Flashcards.createSet(d, "p", "s", "Biologie")
        d = Flashcards.saveCard(d, "p", "s", "a", null, "Zelle?", "Baustein")
        return Flashcards.saveCard(d, "p", "s", "b", null, "Kern?", "Steuerung")
    }

    @Test
    fun flipGradeRepeatAndRestoreDoNotDoubleCount() {
        var d = Flashcards.start(fixture(), "p", "s", "round")
        assertEquals(2, d.flashcardSessions.single().openCards)
        d = Flashcards.reveal(d, "p", "round", 0, "a")
        assertFails { Flashcards.reveal(d, "p", "round", 0, "a") }
        d = Flashcards.grade(d, "p", "round", 0, "a", false)
        assertEquals(listOf("b", "a"), d.flashcardSessions.single().pending)
        assertEquals(1L, d.flashcardProgress.single().practice)
        val restored = decodeSchoolData(dataJson.encodeToString(d))
        assertEquals(d, restored)
        assertFails { Flashcards.grade(restored, "p", "round", 0, "a", false) }
        d = Flashcards.continueRound(restored, "p", "round", 1)
        d = Flashcards.reveal(d, "p", "round", 1, "b")
        d = Flashcards.grade(d, "p", "round", 1, "b", true)
        d = Flashcards.continueRound(d, "p", "round", 2)
        d = Flashcards.reveal(d, "p", "round", 2, "a")
        d = Flashcards.grade(d, "p", "round", 2, "a", true)
        d = Flashcards.continueRound(d, "p", "round", 3)
        assertEquals(FlashcardPhase.COMPLETE, d.flashcardSessions.single().phase)
        assertEquals(2, d.flashcardSessions.single().completed.size)
        val replay = Flashcards.start(d, "p", "s", "retry", "round", onlyOpen = true)
        assertEquals(listOf("a"), replay.flashcardSessions.single().selected)
    }

    @Test
    fun editsInvalidateAffectedSessionAndProgressButPreserveOtherSet() {
        var d = fixture()
        d = Flashcards.createSet(d, "p", "other", "Chemie")
        d = Flashcards.saveCard(d, "p", "other", "c", null, "Atom?", "Teilchen")
        d = Flashcards.start(d, "p", "s", "round")
        d = Flashcards.reveal(d, "p", "round", 0, "a")
        d = Flashcards.grade(d, "p", "round", 0, "a", true)
        d = Flashcards.saveCard(d, "p", "s", "a", 1, "Zelle?", "Grundbaustein")
        assertTrue(d.flashcardSessions.isEmpty())
        assertTrue(d.flashcardProgress.isEmpty())
        assertNotNull(d.flashcards.find { it.id == "c" })
        assertFails { Flashcards.grade(d, "p", "round", 0, "a", true) }
        assertFails { Flashcards.saveCard(d, "p", "s", "a", 1, "x", "y") }
    }

    @Test
    fun schemaOneMigratesAndMalformedNewDataIsRejected() {
        val old = decodeSchoolData("""{"schema":1,"profiles":[],"activeProfileId":null}""")
        assertEquals(2, old.schema)
        assertTrue(old.flashcardSets.isEmpty())
        assertFails { decodeSchoolData("""{"schema":3}""") }
        assertFails {
            decodeSchoolData(
                """{"schema":2,"flashcards":[{"id":"x","setId":"missing","front":"a","back":"b"}]}"""
            )
        }
        assertFails { decodeSchoolData("""{"schema":1,"unexpected":true}""") }
    }
}
