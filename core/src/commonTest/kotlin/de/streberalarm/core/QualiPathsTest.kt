package de.streberalarm.core

import kotlin.test.*
import kotlin.test.Test

class QualiPathsTest {
    private val p = Profile(school = School.M_ZUG, grade = 9)

    private fun input(flags: Map<String, Boolean>): GraduationInput {
        val initial =
            GraduationInput(procedure = "Quali", confirmations = flags + ("eligibility" to true))
        return initial.copy(numbers = Rules.qualiFields(p, initial).mapValues { 3 })
    }

    @Test
    fun m9TwoSubjectsAndProjectAlternativeHaveSameDenominator() {
        listOf(false, true).forEach { project ->
            val i = input(mapOf("projectInsteadOfSecondChoice" to project))
            assertEquals(18, Rules.qualiFields(p, i).values.sum())
            assertEquals("3,0", Rules.quali(p, i).value)
        }
    }

    @Test
    fun englishEitherChoiceAndDazSplitCorrectly() {
        val i = input(mapOf("languageOral" to true, "germanOral" to true))
        assertEquals(18, Rules.qualiFields(p, i).values.sum())
        assertEquals("3,0", Rules.quali(p, i).value)
        val second = input(mapOf("secondLanguageOral" to true))
        assertEquals(18, Rules.qualiFields(p, second).values.sum())
    }

    @Test
    fun supplementaryMathKeepsFractionUntilFinalTruncation() {
        val i = input(mapOf("mathExtra" to true))
        val changed =
            i.copy(
                numbers =
                    i.numbers +
                        ("Mathematik Prüfungsnote" to 4) +
                        ("Mathematik zusätzliche mündliche Prüfung" to 1)
            )
        assertEquals("3,0", Rules.quali(p, changed).value)
    }

    @Test
    fun missingSupplementaryResultDoesNotBecomeZero() {
        val i = input(mapOf("germanExtra" to true))
        assertEquals(
            Verdict.INCOMPLETE,
            Rules.quali(p, i.copy(numbers = i.numbers - ("Deutsch zusätzliche mündliche Prüfung")))
                .verdict,
        )
    }
}
