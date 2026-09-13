package de.streberalarm.app

import de.streberalarm.core.Subject
import org.junit.Assert.*
import org.junit.Test

class SubjectPresentationTest {
    @Test
    fun subjectsUseGermanAlphabeticalOrderAndCategoryColors() {
        val names =
            listOf(
                "Mathematik",
                "IT · Informationstechnologie",
                "Ethik/Religion",
                "Deutsch",
                "Ägyptisch",
            )
        assertEquals(
            listOf(
                "Ägyptisch",
                "Deutsch",
                "Ethik/Religion",
                "IT · Informationstechnologie",
                "Mathematik",
            ),
            names.map { Subject(profileId = "p", name = it) }.alphabetical().map { it.name },
        )
        val main = Subject(profileId = "p", name = "Mathematik", core = true, promotion = true)
        val promotion = main.copy(core = false)
        val other = promotion.copy(promotion = false)
        assertEquals(androidx.compose.ui.graphics.Color(0xFFE5DAFF), subjectTint(main))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFCFEAFB), subjectTint(promotion))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFE8EBF0), subjectTint(other))
        assertEquals(subjectTint(main), subjectTint(main.copy(promotion = false)))
        assertEquals(subjectTint(main), subjectTint(main.copy(name = "Deutsch")))
        assertEquals(subjectTint(other), subjectTint(other.copy(name = "Musik")))
        assertEquals("Haupt-/Kernfach", subjectCategory(main))
        assertEquals("Vorrückungsfach", subjectCategory(promotion))
        assertEquals("Weiteres Fach", subjectCategory(other))
    }
}
