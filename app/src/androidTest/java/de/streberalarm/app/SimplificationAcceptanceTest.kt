package de.streberalarm.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.streberalarm.core.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fictional emulator-only journeys for the simplified Android surfaces. */
@RunWith(AndroidJUnit4::class)
class SimplificationAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    private fun demo(): SchoolData {
        runBlocking {
            ui.activity.repo.deleteDemo()
            ui.activity.repo.loadDemo()
        }
        ui.waitForIdle()
        return ui.activity.repo.state.value!!
    }

    @Test
    fun fixedTabsAndDayCalendarSurviveRecreation() {
        demo()
        val today = ui.onNodeWithTag("nav-Heute").fetchSemanticsNode().boundsInRoot
        val grades = ui.onNodeWithTag("nav-Meine Noten").fetchSemanticsNode().boundsInRoot
        val learn = ui.onNodeWithTag("nav-Lernen").fetchSemanticsNode().boundsInRoot
        assertTrue(today.left < grades.left && grades.left < learn.left)
        ui.onNodeWithTag("overall-average").assertExists()
        ui.onNodeWithTag("today-add-grade").assertExists()
        ui.onNodeWithText("Jetzt lernen").assertDoesNotExist()
        ui.onNodeWithText("Gefühlte Note eintragen").assertDoesNotExist()
        ui.onNodeWithTag("day-next").performScrollTo().performClick()
        val tomorrow = LocalDate.now().plusDays(1).toString()
        ui.onNodeWithTag("day-date").assertTextEquals(dateLabel(tomorrow))
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("day-date").performScrollTo().assertTextEquals(dateLabel(tomorrow))
        ui.onNodeWithTag("day-timetable").performTouchInput { swipeLeft() }
        ui.onNodeWithTag("day-date")
            .assertTextEquals(dateLabel(LocalDate.now().plusDays(2).toString()))
        ui.onNodeWithText("Zurück zu heute").performScrollTo().assertIsDisplayed().performClick()
        ui.onNodeWithTag("day-date").assertTextEquals(dateLabel(LocalDate.now().toString()))
    }

    @Test
    fun subjectGradesHaveThumbnailsAndVisibleEditDeleteMenu() {
        val d = demo()
        val english = d.subjects.first { it.name == "Englisch" }
        val grade = d.assessments.first { it.subjectId == english.id && it.actual != null }
        val document = d.documents.first { it.assessmentId == grade.id }
        ui.onNodeWithTag("nav-Meine Noten").performClick()
        ui.onNodeWithTag("subject-${english.id}").performScrollTo().performClick()
        ui.onNodeWithTag("document-thumb-${document.id}")
            .performScrollTo()
            .assertExists()
            .performClick()
        ui.onNodeWithText(document.name).assertExists()
        ui.onNodeWithContentDescription("Zurück").performClick()
        ui.onNodeWithTag("grade-menu-${grade.id}").performScrollTo().performClick()
        ui.onNodeWithText("Bearbeiten").performClick()
        ui.onNodeWithText("Note speichern").performScrollTo().assertIsEnabled()
        ui.onNodeWithContentDescription("Zurück").performClick()
        assertEquals(grade, ui.activity.repo.state.value!!.assessments.first { it.id == grade.id })
    }

    @Test
    fun pastUngradedExamKeepsDocumentsAndGoesToCompactGrade() {
        val d = demo()
        val e = d.assessments.first { old -> d.documents.any { it.assessmentId == old.id } }
        runBlocking {
            ui.activity.repo.update { old ->
                old.copy(
                    assessments =
                        old.assessments.map {
                            if (it.id == e.id) it.copy(actual = null, stage = Stage.WRITTEN) else it
                        }
                )
            }
        }
        ui.waitForIdle()
        ui.onNodeWithTag("nav-Lernen").performClick()
        ui.onNodeWithText("Note noch offen").performScrollTo().assertExists()
        ui.onNodeWithTag("open-grade-${e.id}").performScrollTo().performClick()
        ui.onNodeWithTag("document-thumb-${d.documents.first { it.assessmentId == e.id }.id}")
            .assertExists()
        ui.onNodeWithText("Note eintragen").performScrollTo().performClick()
        ui.onNodeWithTag("grade-3").performScrollTo().performClick()
        ui.onNodeWithText("Note speichern").performScrollTo().performClick()
        ui.waitUntil(10000) {
            ui.activity.repo.state.value!!.assessments.first { it.id == e.id }.actual == 3
        }
        val after = ui.activity.repo.state.value!!
        assertEquals(d.documents, after.documents)
        assertEquals(e.id, after.assessments.first { it.id == e.id }.id)
    }

    @Test
    fun everyThemeKeepsSameThreeTabStructureAndSettingsFunctions() {
        demo()
        AppLook.entries.forEach { look ->
            ui.runOnIdle { ui.activity.appearance.select(look) }
            ui.onNodeWithTag("nav-Heute").assertExists()
            ui.onNodeWithTag("nav-Meine Noten").performClick()
            ui.onNodeWithText("Fach hinzufügen").assertExists()
            ui.onNodeWithTag("nav-Lernen").performClick()
            ui.onNodeWithTag("learn-plan").assertExists()
            ui.onNodeWithTag("learn-vocabulary").assertExists()
            ui.onNodeWithTag("learn-flashcards").assertExists()
            ui.onNodeWithTag("nav-Heute").performClick()
        }
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        ui.onNodeWithText("Fächer & Gewichtungen").performScrollTo().performClick()
        ui.onNodeWithText("Offizielle Ergebnisse").performScrollTo().assertExists()
        ui.onNodeWithText("Datensicherung").performScrollTo().assertExists()
    }

    @Test
    fun staleEstimateNotificationIntentCannotOpenRemovedRoute() {
        demo()
        var original: Intent? = null
        try {
            ui.activityRule.scenario.onActivity { activity ->
                original = Intent(activity.intent)
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(
                    activity, Intent(activity.intent)
                        .setData(android.net.Uri.parse("streberalarm://estimate/old"))
                        .putExtra(EstimateNotifications.EXTRA_EXAM, "old"))
            }
            ui.onNodeWithTag("today-add-grade").assertExists()
            ui.onNodeWithText("Gefühlte Note eintragen").assertDoesNotExist()
            assertNull(ui.activity.estimateRequest.value)
        } finally {
            // ActivityScenario identifies lifecycle callbacks by the launch Intent's filter.
            // Restore only the harness identity after exercising the actual notification callback.
            ui.activityRule.scenario.onActivity { activity -> original?.let { activity.intent = it } }
        }
    }

    @Test
    fun legacyEstimateChannelIsRetiredWithoutChangingStoredEstimates() {
        val d = demo()
        val manager = ui.activity.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                "estimates",
                "Legacy",
                android.app.NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        runBlocking { ui.activity.repo.refreshEstimates() }
        assertNull(manager.getNotificationChannel("estimates"))
        assertEquals(d.assessments, ui.activity.repo.state.value!!.assessments)
        assertTrue(
            de.streberalarm.core.Reminders.plan(d, java.time.ZonedDateTime.now()).isNotEmpty()
        )
    }
    @Test fun globalGradeSelectionRecoversDraftAfterRecreation() {
        val data = demo()
        val subject = data.subjects.alphabetical().first { it.name == "Englisch" }
        ui.onNodeWithTag("today-add-grade").performScrollTo().performClick()
        ui.onNodeWithTag("picker-Fach").performClick()
        val index = data.subjects.alphabetical().indexOfFirst { it.id == subject.id }
        ui.onNodeWithTag("choices-Fach").performScrollToIndex(index)
        ui.onNodeWithTag("choice-option-$index").performClick()
        ui.onNodeWithText("Übernehmen").performClick()
        ui.onNodeWithTag("grade-2").performScrollTo().performClick()
        ui.onNodeWithTag("picker-kind").performScrollTo().performClick()
        ui.onNodeWithTag("choice-option-0").performClick()
        ui.onNodeWithText("Übernehmen").performClick()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("grade-2").performScrollTo().assertIsSelected()
        ui.onNodeWithText("Note speichern").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(10000) { ui.activity.repo.state.value!!.assessments.size == data.assessments.size + 1 }
        val saved = ui.activity.repo.state.value!!.assessments.single { it.id !in data.assessments.map { old -> old.id } }
        assertEquals(subject.id, saved.subjectId)
        assertEquals(2, saved.actual)
    }
    @Test fun compactPointsSaveZeroAndFifteenWithoutLosingLegacyExamFields() {
        val profile = Profile(grade = 12, term = "12/1")
        val subject = Subject(profileId = profile.id, name = "Mathematik")
        val exam = Assessment(subjectId = subject.id, title = "Bestehende Klausur", date = LocalDate.now().toString(),
            time = "08:30", kind = Kind.SCHOOLWORK, stage = Stage.WRITTEN, notes = "Alte Notiz", weight = 2.5)
        runBlocking { ui.activity.repo.deleteDemo(); ui.activity.repo.update {
            SchoolData(profiles = listOf(profile), activeProfileId = profile.id, subjects = listOf(subject), assessments = listOf(exam))
        } }
        ui.onNodeWithTag("nav-Lernen").performClick()
        ui.onNodeWithTag("open-grade-${exam.id}").performScrollTo().performClick()
        ui.onNodeWithText("Note eintragen").performClick()
        ui.onNodeWithTag("grade-0").performScrollTo().performClick()
        ui.onNodeWithText("Note speichern").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(10000) { ui.activity.repo.state.value!!.assessments.single().actual == 0 }
        ui.onNodeWithTag("grade-menu-${exam.id}").performScrollTo().performClick()
        ui.onNodeWithText("Bearbeiten").performClick()
        ui.onNodeWithTag("grade-15").performScrollTo().performClick()
        ui.onNodeWithText("Note speichern").performScrollTo().assertIsEnabled().performClick()
        ui.waitUntil(10000) { ui.activity.repo.state.value!!.assessments.single().actual == 15 }
        assertEquals(exam.copy(stage = Stage.GRADED, actual = 15), ui.activity.repo.state.value!!.assessments.single())
    }
}
