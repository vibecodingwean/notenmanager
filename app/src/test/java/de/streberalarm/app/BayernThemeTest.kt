package de.streberalarm.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.*
import org.junit.Test

class BayernThemeTest {
    private fun contrast(a: Color, b: Color): Double =
        (maxOf(a.luminance(), b.luminance()) + .05) / (minOf(a.luminance(), b.luminance()) + .05)

    @Test fun bayernControlsUseRedWhiteBlueWithoutInheritedPurple() {
        val c = lookScheme(AppLook.BAYERN)
        assertEquals("Filled buttons have white text", Color.White, c.onPrimary)
        assertTrue(c.primary.red > c.primary.blue * 2)
        assertTrue(c.secondary.blue > c.secondary.red)
        assertEquals(Color.White, c.primaryContainer)
        assertTrue(c.onPrimaryContainer.red > c.onPrimaryContainer.blue * 2)
        val pairs = listOf(c.primary to c.onPrimary, c.primaryContainer to c.onPrimaryContainer,
            c.secondary to c.onSecondary, c.secondaryContainer to c.onSecondaryContainer,
            c.tertiary to c.onTertiary, c.tertiaryContainer to c.onTertiaryContainer,
            c.surface to c.onSurface, c.surfaceVariant to c.onSurfaceVariant,
            c.inverseSurface to c.inverseOnSurface, c.error to c.onError,
            c.errorContainer to c.onErrorContainer)
        pairs.forEach { (bg, fg) -> assertTrue("Contrast ${contrast(bg, fg)}", contrast(bg, fg) >= 4.5) }
        // Include roles that Material components otherwise silently inherit from baseline purple.
        val roles = listOf(c.surfaceTint, c.tertiary, c.secondaryContainer, c.tertiaryContainer,
            c.surfaceDim, c.surfaceBright, c.surfaceContainerLowest, c.surfaceContainerLow,
            c.surfaceContainer, c.surfaceContainerHigh, c.surfaceContainerHighest, c.inversePrimary)
        roles.forEach { color ->
            assertFalse("Purple role: $color", color.blue > color.green * 1.15f &&
                color.red > color.green * 1.15f && color.red < color.blue * 1.8f)
        }
    }
    @Test fun bayernSubjectCategoriesAvoidPurpleAndKeepActionContrast() {
        val subject = de.streberalarm.core.Subject(profileId = "p", name = "Math", core = true)
        val c = lookScheme(AppLook.BAYERN)
        for (s in listOf(subject, subject.copy(core = false, promotion = true), subject.copy(core = false, promotion = false))) {
            val bg = categoryTint(s, AppLook.BAYERN)
            assertTrue("Action contrast ${contrast(c.primary, bg)}", contrast(c.primary, bg) >= 4.5)
            assertFalse(bg.red > bg.green * 1.15f && bg.blue > bg.green * 1.15f)
        }
    }
    @Test fun bayernSelectionFocusAndDisabledStatesStayLegible() {
        val c = lookScheme(AppLook.BAYERN)
        // Material navigation selected indicator and unselected labels/icons.
        assertTrue(contrast(c.onSecondaryContainer, c.secondaryContainer) >= 4.5)
        assertTrue(contrast(c.onSurfaceVariant, c.surface) >= 4.5)
        // Focused outlined fields/radio controls use primary; unselected borders use outline.
        assertTrue(contrast(c.primary, c.surface) >= 3.0)
        assertTrue(contrast(c.outline, c.surface) >= 3.0)
        // Material disabled controls use neutral onSurface alpha, not inherited purple.
        val disabledBg = c.onSurface.copy(alpha = .12f).compositeOver(c.surface)
        val disabledFg = c.onSurface.copy(alpha = .38f).compositeOver(disabledBg)
        assertTrue(contrast(disabledFg, disabledBg) >= 3.0)
        // Focus state layer on navigation retains readable selected text.
        val focused = c.onSecondaryContainer.copy(alpha = .12f).compositeOver(c.secondaryContainer)
        assertTrue(contrast(c.onSecondaryContainer, focused) >= 4.5)
        println("Bayern contrast: button=${contrast(c.onPrimary, c.primary)}, card=${contrast(c.onPrimaryContainer, c.primaryContainer)}, selected=${contrast(c.onSecondaryContainer, c.secondaryContainer)}, disabled=${contrast(disabledFg, disabledBg)}")
    }
    @Test fun overviewWiresWhiteFillRedContentAndRedOutline() {
        // Wiring check complements palette tests when no offline rendering runtime is available.
        val source = java.io.File("src/main/java/de/streberalarm/app/GradeOverviewPanel.kt").readText()
        assertTrue(source.contains("border = if (bayern) BorderStroke(1.dp, scheme.onPrimaryContainer)"))
        assertTrue(source.contains("containerColor = if (bayern) scheme.primaryContainer"))
        assertTrue(source.contains("contentColor = if (bayern) scheme.onPrimaryContainer"))
    }
    @Test fun vividPageHeadingWiresWhiteFillRedTitleOutlineAndBlueBadge() {
        val heading = java.io.File("src/main/java/de/streberalarm/app/VividThemes.kt")
            .readText().substringAfter("internal fun VividPageHeading(")
        val beforePanel = heading.substringBefore("shape = RoundedCornerShape(12.dp)")
        val surface = beforePanel.substringAfterLast("Surface(")
        val panel = heading.substringAfter("shape = RoundedCornerShape(12.dp)")
        assertTrue(surface.contains("color = scheme.primaryContainer"))
        assertTrue(surface.contains("contentColor = scheme.onPrimaryContainer"))
        assertTrue(surface.contains("border = BorderStroke(1.dp, scheme.onPrimaryContainer)"))
        assertTrue(panel.contains("color = scheme.onPrimaryContainer"))
        assertTrue(panel.contains("color = scheme.onPrimaryContainer"))
        assertTrue(beforePanel.contains("color = scheme.onSecondary"))
        assertTrue(beforePanel.contains("color = Color.White"))
        val c = lookScheme(AppLook.BAYERN)
        assertTrue(c.onSecondary.blue > c.onSecondary.red)
        assertTrue(contrast(Color.White, c.onSecondary) >= 4.5)
    }

}
