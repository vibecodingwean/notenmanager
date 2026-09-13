package de.streberalarm.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import de.streberalarm.core.Subject
import org.junit.Assert.*
import org.junit.Test

class AppearanceTest {
    private fun contrast(a: Color, b: Color): Double {
        val x = a.luminance().toDouble()
        val y = b.luminance().toDouble()
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test
    fun everyLookKeepsTextReadableOnSurfaceAndSubjectCategories() {
        val main = Subject(profileId = "p", name = "Mathematik", core = true, promotion = true)
        val promotion = main.copy(core = false)
        val other = main.copy(core = false, promotion = false)
        for (look in AppLook.entries) {
            val c = lookScheme(look)
            val backgrounds =
                listOf(
                    c.surface,
                    c.background,
                    categoryTint(main, look),
                    categoryTint(promotion, look),
                    categoryTint(other, look),
                )
            for (bg in backgrounds) {
                assertTrue("${look.id}: body text contrast", contrast(c.onSurface, bg) >= 4.5)
                assertTrue(
                    "${look.id}: secondary text contrast",
                    contrast(c.onSurfaceVariant, bg) >= 4.5,
                )
                assertTrue("${look.id}: action text contrast", contrast(c.primary, bg) >= 4.5)
            }
            assertTrue("${look.id}: filled button text", contrast(c.onPrimary, c.primary) >= 4.5)
            assertEquals(
                3,
                listOf(main, promotion, other).map { categoryTint(it, look) }.toSet().size,
            )
            assertEquals(
                categoryTint(main, look),
                categoryTint(main.copy(promotion = false, name = "Deutsch"), look),
            )
        }
    }

    @Test
    fun storedIdsStayStableAndUnknownValuesUseClassic() {
        assertEquals(
            listOf("classic", "hacker", "social", "streamer", "orbit", "pixel"),
            AppLook.entries.map { it.id },
        )
        AppLook.entries.forEach { assertEquals(it, AppLook.fromId(it.id)) }
        assertEquals(AppLook.CLASSIC, AppLook.fromId(null))
        assertEquals(AppLook.CLASSIC, AppLook.fromId("unknown"))
    }
}
