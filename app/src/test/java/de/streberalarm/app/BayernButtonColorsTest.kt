package de.streberalarm.app

import androidx.compose.material3.ButtonColors
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class BayernButtonColorsTest {
    private val defaults = ButtonColors(Color.White, Color.Black, Color.Gray, Color.DarkGray)

    @Test fun bayernActionsHaveRedFillWhiteTextAndPreserveDisabledColors() {
        val actual = buttonColorsForLook(AppLook.BAYERN, defaults)
        assertEquals(Color(0xFFB51225), actual.containerColor)
        assertEquals(Color.White, actual.contentColor)
        assertEquals(defaults.disabledContainerColor, actual.disabledContainerColor)
        assertEquals(defaults.disabledContentColor, actual.disabledContentColor)
        assertEquals(Color.White, lookScheme(AppLook.BAYERN).primaryContainer)
    }

    @Test fun everyOtherLookRetainsExactButtonDefaults() {
        AppLook.entries.filter { it != AppLook.BAYERN && !it.isYouth }.forEach {
            assertSame(defaults, buttonColorsForLook(it, defaults))
        }
    }
}
