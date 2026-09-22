package de.streberalarm.app

import androidx.compose.material3.ButtonColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class YouthThemeTest {
    private val ids = listOf("streetart", "bubblegum", "speed")
    private fun readable(a: Color, b: Color) {
        val x = a.luminance(); val y = b.luminance()
        assertTrue("Contrast $a / $b", (maxOf(x,y)+.05f)/(minOf(x,y)+.05f) >= 4.5f)
    }
    @Test fun stableYouthIdsAndMode() {
        ids.forEach { id ->
            val look = AppLook.fromId(id)
            assertEquals(id, look.id)
            assertEquals(id == "speed", look.dark)
            assertTrue(look.hint.isNotBlank())
        }
    }
    @Test fun staticEligibilityAndLegacyActions() {
        ids.forEach { assertFalse(themeAnimationEligible(AppLook.fromId(it))) }
        assertFalse(themeAnimationEligible(AppLook.CLASSIC))
        listOf(AppLook.HACKER, AppLook.SOCIAL, AppLook.STREAMER, AppLook.ORBIT,
            AppLook.PIXEL, AppLook.BAYERN).forEach { assertTrue(themeAnimationEligible(it)) }
        val defaults = ButtonColors(Color.Transparent, Color.White, Color.Gray, Color.LightGray)
        AppLook.entries.filter { !it.isYouth }.forEach {
            assertSame(defaults, actionButtonColors(it, defaults))
        }
        assertEquals(AppLook.CLASSIC, AppLook.fromId("iridescent"))
    }
    @Test fun paletteAndEveryButtonStyleIncludingDisabled() {
        ids.forEach { id ->
            val look = AppLook.fromId(id)
            assertEquals(id, look.id)
            val c = lookScheme(look)
            listOf(c.primary to c.onPrimary, c.surface to c.onSurface,
                c.surfaceVariant to c.onSurfaceVariant, c.primaryContainer to c.onPrimaryContainer,
                c.secondaryContainer to c.onSecondaryContainer, c.background to c.onBackground,
                c.errorContainer to c.onErrorContainer).forEach { readable(it.first, it.second) }
            readable(c.error, c.primaryContainer) // Explicit delete labels inside helper buttons.
            readable(c.error, c.surface)
            readable(c.onSurfaceVariant, c.surfaceContainerHigh) // Dialog supporting labels.
            val action = actionButtonColors(look, ButtonColors(Color.Transparent, Color.White, Color.Gray, Color.LightGray))
            readable(action.containerColor, action.contentColor)
            readable(action.disabledContainerColor, action.disabledContentColor)
            // Filled, tonal, outlined and text defaults; helper must repair both enabled and disabled.
            listOf(c.primary, c.secondaryContainer, Color.Transparent, Color.White).forEach { fill ->
                val b = buttonColorsForLook(look, ButtonColors(fill, Color.White, Color.Gray, Color.LightGray))
                readable(b.containerColor, b.contentColor)
                readable(b.disabledContainerColor, b.disabledContentColor)
            }
        }
    }
}
