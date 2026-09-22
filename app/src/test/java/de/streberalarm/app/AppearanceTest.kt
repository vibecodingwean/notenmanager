package de.streberalarm.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import de.streberalarm.core.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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
    fun vividLooksKeepControlsAndSelectionPanelsReadable() {
        for (look in listOf(AppLook.BAYERN)) {
            assertEquals(look == AppLook.BAYERN, look.dark)
            val c = lookScheme(look)
            assertTrue(contrast(c.onPrimaryContainer, c.primaryContainer) >= 4.5)
            assertTrue(contrast(c.onSurfaceVariant, c.surfaceVariant) >= 4.5)
        }
    }

    @Test
    fun retiredStoredIdFallsBackWithoutChangingLoadedOrImportedSchoolData() {
        val profile = Profile()
        val subject = Subject(profileId = profile.id, name = "Mathematik", core = true)
        val grade = Assessment(subjectId = subject.id, title = "Test", date = "2026-09-19",
            actual = 2, stage = Stage.GRADED)
        val data = SchoolData(profiles = listOf(profile), activeProfileId = profile.id,
            subjects = listOf(subject), assessments = listOf(grade))
        val json = dataJson.encodeToString(data)
        val loaded = dataJson.decodeFromString<SchoolData>(json)
        // AppearancePreferences uses this resolver; appearance is device-local, outside SchoolData.
        val appearance = Appearance(AppLook.fromId("iridescent"), animations = false)
        assertEquals(AppLook.CLASSIC, appearance.look)
        assertFalse(appearance.animations)
        assertEquals(data, loaded)
        assertEquals(json, dataJson.encodeToString(loaded))
        val password = java.util.UUID.randomUUID().toString().toCharArray()
        val backup = Backup.export(loaded, password) { error("No attachments") }
        val imported = Backup.restore(backup, password).data
        assertEquals(data, imported)
        assertEquals(profile, imported.active())
        assertEquals(listOf(grade), imported.assessments)
        assertEquals(AppLook.CLASSIC, AppLook.fromId("iridescent"))
    }

    @Test
    fun storedIdsStayStableAndUnknownValuesUseClassic() {
        assertEquals(
            listOf(
                "classic",
                "hacker",
                "social",
                "streamer",
                "orbit",
                "pixel",
                "bayern",
                "streetart",
                "bubblegum",
                "speed",
            ),
            AppLook.entries.map { it.id },
        )
        AppLook.entries.forEach { assertEquals(it, AppLook.fromId(it.id)) }
        assertEquals(AppLook.CLASSIC, AppLook.fromId("iridescent"))
        assertEquals(AppLook.CLASSIC, AppLook.fromId(null))
        assertEquals(AppLook.CLASSIC, AppLook.fromId("unknown"))
    }
}
