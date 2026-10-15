package de.streberalarm.app

import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import de.streberalarm.core.*
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val repo
        get() = ui.activity.repo

    private lateinit var profile: Profile
    private lateinit var subject: Subject

    @Before
    fun prepare() {
        ui.activity.appearance.select(
            AppLook.fromId(InstrumentationRegistry.getArguments().getString("look"))
        )
        ui.activity.appearance.animate(false)
        profile = Profile()
        subject =
            Subject(
                profileId = profile.id,
                name = "Mathematik",
                annualSchoolworks = 2,
                weightsConfirmed = true,
                core = true,
            )
        runBlocking {
            repo.update {
                SchoolData(
                    profiles = listOf(profile),
                    activeProfileId = profile.id,
                    subjects = listOf(subject),
                )
            }
        }
        ui.waitForIdle()
    }

    @Test
    fun primaryTransferUsesOfficialGradesAndKeepsThemThroughRecreation() {
        val p = profile.copy(school = School.GRUNDSCHULE, grade = 4, track = "Grundschule")
        val subjects = Defaults.subjects(p)
        runBlocking { repo.update { it.copy(profiles = listOf(p), subjects = subjects) } }
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        tap("Offizielle Ergebnisse")
        tap("Übertrittsergebnis")
        choose("Deutsch", "2")
        choose("Mathematik", "3")
        choose("Heimat- und Sachunterricht", "3")
        ui.activityRule.scenario.recreate()
        visible(ui.onNodeWithText("Übertrittsnoten speichern")).performClick()
        ui.waitUntil(5000) { repo.state.value!!.officials.size == 3 }
        val saved = repo.state.value!!
        assertEquals(listOf(2, 3, 3), Transfer.subjects(p, saved).map { subject -> saved.officials.single { it.subjectId == subject!!.id }.value })
        ui.activityRule.scenario.recreate()
        assertEquals(saved, repo.state.value)
    }

    @Test
    fun todayShowsSameAverageAsGradesWithoutCoachMessage() {
        val p = profile.copy(school = School.REALSCHULE, grade = 8, track = "I")
        val subjects = (1..4).map { Subject(profileId = p.id, name = "Fach $it", promotion = true) }
        val marks =
            subjects.zip(listOf(1, 1, 6, 6)).map { (s, n) ->
                Assessment(
                    subjectId = s.id,
                    title = "Probe",
                    date = LocalDate.now().toString(),
                    kind = Kind.TEST,
                    stage = Stage.GRADED,
                    actual = n,
                )
            }
        runBlocking {
            repo.update { it.copy(profiles = listOf(p), subjects = subjects, assessments = marks) }
        }
        ui.onNodeWithTag("overall-average").assertTextEquals("Ø 3,50")
        ui.onNodeWithTag("passing-advice").assertDoesNotExist()
        ui.onNodeWithText("Läuft bei dir").assertDoesNotExist()
        ui.onNodeWithTag("nav-Meine Noten").performClick()
        ui.onNodeWithTag("overall-average").assertDoesNotExist()
        ui.onNodeWithTag("nav-Heute").performClick()
        runBlocking { repo.update { it.copy(assessments = emptyList()) } }
        ui.onNodeWithTag("overall-average").assertTextEquals("Ø –")
    }

    @Test
    fun gradesShowOverallAverageAndPromotionWithoutAnExtraProcedure() {
        val p = profile.copy(school = School.REALSCHULE, grade = 8, track = "I")
        val math = subject.copy(annualSchoolworks = 0)
        val english = math.copy(id = id(), name = "Englisch")
        val art = math.copy(id = id(), name = "Kunst", core = false)
        val marks =
            listOf(math to 5, english to 5, art to 1).map { (s, n) ->
                Assessment(
                    subjectId = s.id,
                    title = "Note",
                    date = LocalDate.now().toString(),
                    kind = Kind.ORAL,
                    stage = Stage.GRADED,
                    actual = n,
                )
            }
        runBlocking {
            repo.update {
                it.copy(
                    profiles = listOf(p),
                    subjects = listOf(math, english, art),
                    assessments = marks,
                )
            }
        }
        ui.onNodeWithTag("grade-overview").assertExists()
        ui.onNodeWithText("Offizielle Ergebnisse prüfen").assertDoesNotExist()
        ui.onNodeWithTag("overall-average").assertTextEquals("Ø 3,67")
        ui.onNodeWithTag("promotion-red").assertExists()
        ui.onNodeWithText("Einschätzung aus bisherigen Noten").assertExists()
        val before = repo.state.value!!
        capture("overview-risk")
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("promotion-red").assertExists()
        assertEquals(before, repo.state.value)
        runBlocking {
            repo.update {
                it.copy(
                    officials =
                        listOf(
                            Official(subjectId = math.id, period = "Jahreszeugnis", value = 3),
                            Official(subjectId = english.id, period = "Jahreszeugnis", value = 3),
                            Official(subjectId = art.id, period = "Jahreszeugnis", value = 6),
                        )
                )
            }
        }
        ui.onNodeWithTag("promotion-green").assertExists()
        ui.onNodeWithTag("overall-average").assertTextEquals("Ø 3,67")
        runBlocking { repo.update { it.copy(officials = emptyList(), assessments = emptyList()) } }
        ui.onNodeWithTag("promotion-unknown").assertExists()
        ui.onNodeWithTag("overall-average").assertTextEquals("Ø –")
    }

    @After
    fun resetAppearance() {
        ui.activity.appearance.select(AppLook.CLASSIC)
        ui.activity.appearance.animate(false)
    }

    @Test
    fun settingsPrioritizeTimetableAndLookWithCompactSupport() {
        val before = repo.state.value!!
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        ui.onNodeWithTag("settings-timetable").assertIsDisplayed()
        ui.onNodeWithTag("settings-look").assertIsDisplayed()
        ui.onNodeWithText("Backup exportieren").assertDoesNotExist()
        ui.onNodeWithText("Benachrichtigungen einstellen").assertDoesNotExist()
        ui.onNodeWithTag("theme-classic").assertDoesNotExist()
        capture("settings-child-friendly")
        ui.onNodeWithTag("settings-timetable").performClick()
        tap("Stundenplan bearbeiten")
        ui.onNodeWithTag("picker-Fach für Stunde 1").assertExists()
        ui.activityRule.scenario.recreate()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        ui.waitForIdle()
        ui.onNodeWithText("Dein Stundenplan").assertExists()
        ui.onNodeWithContentDescription("Zurück").performClick()
        ui.onNodeWithTag("settings-look").performClick()
        ui.onNodeWithTag("theme-classic").assertExists()
        ui.activityRule.scenario.recreate()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        ui.waitForIdle()
        ui.onNodeWithTag("settings-timetable").assertExists()
        tap("Erinnerungen")
        ui.onNodeWithText("Benachrichtigungen einstellen").assertExists()
        tap("Erinnerungen")
        ui.onNodeWithText("Benachrichtigungen einstellen").assertDoesNotExist()
        tap("Datensicherung")
        tap("Backup wiederherstellen")
        ui.onNodeWithText("Datei auswählen").assertIsNotEnabled()
        tap("Abbrechen")
        tap("Datensicherung")
        tap("Datenschutz & Rechtsgrundlagen")
        ui.onNodeWithText("Datenschutz").assertExists()
        ui.onNodeWithText("Wer kann meine Daten sehen?").assertExists()
        ui.onNodeWithText("Rechtsgrundlagen").performScrollTo().assertExists()
        capture("settings-privacy-and-rules")
        ui.onNodeWithText("Zeugnis & Abschluss prüfen").assertDoesNotExist()
        ui.activityRule.scenario.recreate()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        ui.waitForIdle()
        ui.onNodeWithTag("settings-timetable").assertExists()
        tap("Schuljahr")
        tap("Neue Klasse / neues Schuljahr")
        ui.activityRule.scenario.recreate()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        ui.waitForIdle()
        ui.onNodeWithText("Neue Klasse / neues Schuljahr").assertExists()
        assertEquals(before, repo.state.value)
    }

    @Test
    fun settingsKeepExpandedSupportAndScrollAfterRecreationOnAChildPage() {
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        tap("Erinnerungen")
        val link = visible(ui.onNodeWithText("Datenschutz & Rechtsgrundlagen"))
        val scrollRange = ui.onNode(hasScrollAction()).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        val beforeScroll = scrollRange.value()
        if (scrollRange.maxValue() > 0f) assertTrue(beforeScroll > 0f)
        link.performClick()
        ui.activityRule.scenario.recreate()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        ui.waitForIdle()
        ui.onNodeWithText("Benachrichtigungen einstellen").assertExists()
        val afterScroll = ui.onNode(hasScrollAction()).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(beforeScroll, afterScroll, 1f)
    }

    @Test
    fun settingsTimetableHolidayAndExceptionFormsReturnToTheirParent() {
        val before = repo.state.value!!
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        listOf("Ferien / freien Tag hinzufügen", "Ausfall / Zusatzunterricht").forEachIndexed { index, action ->
            ui.onNodeWithTag("settings-timetable").performScrollTo().performClick()
            tap("Ferien, Ausfälle und ältere Pläne")
            tap(action)
            ui.activityRule.scenario.recreate()
            if (index == 0) {
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
                ui.waitForIdle()
            } else ui.onNodeWithContentDescription("Zurück").performClick()
            ui.onNodeWithText("Dein Stundenplan").assertExists()
            if (index == 0) ui.onNodeWithContentDescription("Zurück").performClick()
            else {
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
                ui.waitForIdle()
            }
            ui.onNodeWithTag("settings-look").assertExists()
        }
        assertEquals(before, repo.state.value)
    }

    @Test
    fun appearanceCanBeSelectedInSettings() {
        val exam =
            Assessment(
                subjectId = subject.id,
                title = "Schulaufgabe · Mathematik",
                date = LocalDate.now().plusDays(3).toString(),
                material = "Brüche und Dezimalzahlen",
            )
        runBlocking { repo.update { it.copy(assessments = listOf(exam)) } }
        val before = repo.state.value!!
        ui.waitForIdle()
        val navBounds =
            listOf("Heute", "Lernen", "Meine Noten").map {
                ui.onNodeWithTag("nav-$it").fetchSemanticsNode().boundsInWindow
            }
        for (look in AppLook.entries) {
            ui.onNodeWithContentDescription("Einstellungen").performClick()
            ui.onNodeWithTag("settings-look").performClick()
            ui.onNodeWithTag("theme-${look.id}").performScrollTo().performClick()
            ui.onNodeWithTag("theme-${look.id}").assertIsSelected()
            ui.onNodeWithTag("appearance-${look.id}").assertExists()
            ui.activityRule.scenario.recreate()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle()
            ui.onNodeWithTag("theme-${look.id}").assertIsSelected()
            assertEquals(look, AppearancePreferences(ui.activity).state.value.look)
            capture("theme-${look.id}-settings")
            ui.onNodeWithContentDescription("Zurück").performClick()
            ui.onNodeWithContentDescription("Zurück").performClick()
            listOf("Heute", "Lernen", "Meine Noten").forEachIndexed { i, label ->
                assertEquals(
                    navBounds[i],
                    ui.onNodeWithTag("nav-$label").fetchSemanticsNode().boundsInWindow,
                )
            }
            ui.onNodeWithTag("nav-Heute").performClick()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            ui.onNodeWithText("Nächste Prüfung").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
            ) {
                it(layouts)
            }
            assertEquals(lookScheme(look).onBackground, layouts.single().layoutInput.style.color)
            capture("theme-${look.id}-today")
            ui.onNodeWithTag("nav-Lernen").performClick()
            ui.onNodeWithTag("exam-${exam.id}").assertExists()
            ui.onNodeWithContentDescription("Lernen starten").assertDoesNotExist()
            capture("theme-${look.id}-learning")
            ui.onNodeWithTag("nav-Meine Noten").performClick()
            ui.onNodeWithText("Mathematik").assertExists()
            capture("theme-${look.id}-grades")
            assertEquals(before, repo.state.value!!.copy(studies = before.studies))
        }
        assertEquals(AppLook.CLASSIC, AppLook.fromId("a-future-look"))
    }

    @Test
    fun decorativeMotionCanBeDisabledWithoutChangingTheLookOrClock() {
        val look =
            AppLook.fromId(InstrumentationRegistry.getArguments().getString("look") ?: "orbit")
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val original =
            device.executeShellCommand("settings get global animator_duration_scale").trim()
        try {
            device.executeShellCommand("settings put global animator_duration_scale 1")
            ui.onNodeWithContentDescription("Einstellungen").performClick()
            ui.onNodeWithTag("settings-look").performClick()
            ui.onNodeWithTag("theme-${look.id}").performScrollTo().performClick()
            if (look == AppLook.HACKER) {
                ui.onNodeWithTag("crt-overlay", useUnmergedTree = true).assertExists()
                assertTrue(
                    ui.onAllNodesWithTag("crt-monitor", useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                )
            }
            ui.onNodeWithTag("theme-animations").performScrollTo().performClick()
            ui.onNodeWithTag("theme-animations").assertIsOn()
            ui.onNodeWithContentDescription("Zurück").performClick()
            ui.onNodeWithContentDescription("Zurück").performClick()
            ui.waitUntil(5000) {
                ui.onAllNodesWithTag("look-moving", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            ui.mainClock.autoAdvance = false
            // Infinite animations are cancelled by the Compose test clock in auto mode.
            // Re-enter them with manual frames to verify actual drawing.
            ui.runOnUiThread { ui.activity.appearance.animate(false) }
            ui.mainClock.advanceTimeBy(64)
            ui.runOnUiThread { ui.activity.appearance.animate(true) }
            ui.mainClock.advanceTimeBy(64)
            val canvas = ui.onNodeWithTag("look-moving", useUnmergedTree = true)
            val first = canvas.captureToImage().toPixelMap()
            ui.mainClock.advanceTimeBy(2000)
            val next = canvas.captureToImage().toPixelMap()
            assertTrue(
                "Decorative background must move",
                (0 until first.width step 6).any { x ->
                    (0 until first.height step 6).any { y -> first[x, y] != next[x, y] }
                },
            )
            ui.runOnUiThread { ui.activity.appearance.animate(false) }
            ui.mainClock.advanceTimeBy(64)
            val still = ui.onNodeWithTag("look-still", useUnmergedTree = true)
            val frozen = still.captureToImage().toPixelMap()
            ui.mainClock.advanceTimeBy(2000)
            val frozenLater = still.captureToImage().toPixelMap()
            assertTrue(
                (0 until frozen.width step 6).all { x ->
                    (0 until frozen.height step 6).all { y -> frozen[x, y] == frozenLater[x, y] }
                }
            )
            ui.mainClock.autoAdvance = true
            ui.runOnUiThread { ui.activity.appearance.animate(true) }
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            ui.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            ui.onNodeWithTag("look-moving", useUnmergedTree = true).assertExists()
            ui.onNodeWithContentDescription("Einstellungen").performClick()
            ui.onNodeWithTag("settings-look").performClick()
            ui.onNodeWithTag("theme-animations").performScrollTo().performClick()
            ui.onNodeWithTag("theme-animations").assertIsOff()
            ui.onNodeWithTag("appearance-${look.id}").assertExists()
            ui.onNodeWithTag("look-still", useUnmergedTree = true).assertExists()
            ui.activityRule.scenario.recreate()
            assertFalse(AppearancePreferences(ui.activity).state.value.animations)
            ui.onNodeWithTag("theme-animations").performScrollTo().performClick()
            device.executeShellCommand("settings put global animator_duration_scale 0")
            ui.waitUntil(5000) {
                ui.onAllNodesWithTag("look-still", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            assertEquals(look, ui.activity.appearance.state.value.look)
            // The system and app switches affect only the decorative layer.
            assertTrue(ui.activity.appearance.state.value.animations)
        } finally {
            ui.mainClock.autoAdvance = true
            device.executeShellCommand("settings put global animator_duration_scale $original")
        }
    }

    private fun visible(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
        var parent = node.fetchSemanticsNode().parent
        var scrollable = false
        while (parent != null) {
            if (parent.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy))
                scrollable = true
            parent = parent.parent
        }
        if (scrollable) node.performScrollTo()
        return node
    }

    private fun capture(name: String) {
        ui.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(File(ui.activity.cacheDir, "$name.png"))
    }

    private fun tap(label: String) {
        visible(ui.onNodeWithText(label)).performClick()
        ui.waitForIdle()
    }

    private fun openArchive() {
        ui.onNodeWithTag("nav-Lernen").performClick()
        if (ui.onAllNodesWithText("Alle Proben").fetchSemanticsNodes().isNotEmpty())
            tap("Alle Proben")
    }

    private fun openTimetable() {
        ui.onNodeWithTag("nav-Heute").performClick()
        tap("Stundenplan in Einstellungen")
    }

    private fun field(label: String, value: String) {
        visible(ui.onNodeWithText(label)).performTextReplacement(value)
        ui.waitForIdle()
        ui.runOnUiThread {
            ui.activity
                .getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(ui.activity.window.decorView.windowToken, 0)
        }
        ui.waitForIdle()
        ui.onNodeWithText(label).assertTextContains(value)
    }

    private fun openChoices(label: String) {
        ui.onNodeWithTag("picker-$label").performScrollTo().performClick()
        ui.waitForIdle()
    }

    private fun choose(label: String, value: String) {
        openChoices(label)
        ui.onNodeWithTag("choices-$label").performScrollToNode(hasText(value))
        ui.onNode(hasText(value) and hasAnyAncestor(hasTestTag("choices-$label"))).performClick()
        ui.waitForIdle()
        tap("Übernehmen")
    }

    private fun date(label: String, value: String) {
        ui.onNodeWithTag("date-$label").performScrollTo().performClick()
        val date = LocalDate.parse(value)
        androidx.test.espresso.Espresso.onView(
                androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(
                    android.widget.DatePicker::class.java
                )
            )
            .perform(
                object : androidx.test.espresso.ViewAction {
                    override fun getConstraints() =
                        androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(
                            android.widget.DatePicker::class.java
                        )

                    override fun getDescription() = "Select a date in the opened calendar"

                    override fun perform(
                        controller: androidx.test.espresso.UiController,
                        view: android.view.View,
                    ) {
                        (view as android.widget.DatePicker).updateDate(
                            date.year,
                            date.monthValue - 1,
                            date.dayOfMonth,
                        )
                        controller.loopMainThreadUntilIdle()
                    }
                }
            )
        androidx.test.espresso.Espresso.onView(
                androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1)
            )
            .perform(androidx.test.espresso.action.ViewActions.click())
        ui.waitForIdle()
    }

    private fun clock(label: String, hour: Int, minute: Int) {
        visible(ui.onNodeWithText("$label:", substring = true)).performClick()
        androidx.test.espresso.Espresso.onView(
                androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(
                    android.widget.TimePicker::class.java
                )
            )
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(
                object : androidx.test.espresso.ViewAction {
                    override fun getConstraints() =
                        androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(
                            android.widget.TimePicker::class.java
                        )

                    override fun getDescription() = "Set timetable time"

                    override fun perform(
                        controller: androidx.test.espresso.UiController,
                        view: android.view.View,
                    ) {
                        (view as android.widget.TimePicker).apply {
                            this.hour = hour
                            this.minute = minute
                        }
                        controller.loopMainThreadUntilIdle()
                    }
                }
            )
        androidx.test.espresso.Espresso.onView(
                androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1)
            )
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.click())
        ui.waitForIdle()
    }

    @Test
    fun standardTimesAndEditedGapsAppearInEditorAndSavedTimetable() {
        openTimetable()
        tap("Stundenplan bearbeiten")
        ui.onNodeWithTag("gap-09:30-09:50").performScrollTo().assertExists()
        ui.onNodeWithTag("gap-11:20-11:40").performScrollTo().assertExists()
        tap("Doppelstunde")
        tap("Stunde")
        val times = listOf("13:30–14:15", "14:30–15:15", "15:15–16:00")
        for (i in 7..9) {
            ui.onNodeWithTag("picker-Fach für Stunde $i")
                .performScrollTo()
                .assertTextContains("$i. Stunde · ${times[i - 7]}")
        }
        ui.onNodeWithTag("gap-14:15-14:30").performScrollTo().assertExists()
        for (i in listOf(1, 2, 3, 7, 8, 9)) choose("Fach für Stunde $i", "Mathematik")
        visible(ui.onNodeWithContentDescription("Uhrzeit für Stunde 1")).performClick()
        clock("Ende", 8, 30)
        ui.onNodeWithTag("gap-08:30-08:45").performScrollTo().assertExists()
        clock("Ende", 8, 45)
        ui.onNodeWithTag("gap-08:30-08:45").assertDoesNotExist()
        clock("Ende", 8, 30)
        capture("automatic-break-editor")
        tap("Stundenplan speichern")
        ui.waitUntil(5000) { runBlocking { repo.current().timetables.isNotEmpty() } }
        ui.onNodeWithTag("gap-08:30-08:45").performScrollTo().assertExists()
        ui.onNodeWithTag("gap-09:30-09:50").performScrollTo().assertExists()
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        ui.onNodeWithTag("gap-08:30-08:45").performScrollTo().assertExists()
        capture("automatic-break-overview")
        val rows = runBlocking { repo.current().timetables.single().blocks }
        assertEquals(6, rows.size)
        assertTrue(rows.none { it.isBreak })
        assertEquals("08:30", rows.single { it.slot == 1 }.end)
        assertEquals("16:00", rows.single { it.slot == 9 }.end)
    }

    @Test
    fun timetableShowsOnlyActualSubjectAverageAndUpdatesLive() {
        val rs = profile.copy(school = School.REALSCHULE, track = "I")
        val unknown = Subject(profileId = profile.id, name = "Geschichte")
        val grades =
            listOf(
                Assessment(
                    subjectId = subject.id,
                    title = "Arbeit",
                    date = "2026-09-01",
                    kind = Kind.SCHOOLWORK,
                    stage = Stage.GRADED,
                    actual = 2,
                ),
                Assessment(
                    subjectId = subject.id,
                    title = "Mündlich",
                    date = "2026-09-02",
                    kind = Kind.ORAL,
                    stage = Stage.GRADED,
                    actual = 4,
                ),
                Assessment(
                    subjectId = subject.id,
                    title = "Schätzung",
                    date = "2026-09-03",
                    estimate = 6,
                ),
                Assessment(
                    subjectId = unknown.id,
                    title = "Unbekannt",
                    date = "2026-09-03",
                    estimate = 1,
                ),
            )
        val table =
            Timetable(
                profileId = profile.id,
                validFrom = "2026-09-01",
                anchorMonday = "2026-08-31",
                blocks =
                    listOf(
                        Lesson(
                            subjectId = subject.id,
                            day = 1,
                            slot = 1,
                            start = "08:00",
                            end = "08:45",
                        ),
                        Lesson(
                            subjectId = unknown.id,
                            day = 1,
                            slot = 2,
                            start = "08:45",
                            end = "09:30",
                        ),
                        Lesson(
                            subjectId = "",
                            day = 1,
                            slot = 3,
                            start = "09:50",
                            end = "10:35",
                            isBreak = true,
                        ),
                    ),
            )
        runBlocking {
            repo.update {
                it.copy(
                    profiles = listOf(rs),
                    subjects = listOf(subject, unknown),
                    assessments = grades,
                    timetables = listOf(table),
                )
            }
        }
        openTimetable()
        ui.onNodeWithTag("lesson-1-1").assertTextContains("Schnitt 2,67")
        ui.onAllNodes(hasText("Schnitt", substring = true)).assertCountEquals(1)
        capture("timetable-average")
        runBlocking {
            repo.update {
                it.copy(
                    assessments =
                        it.assessments.map { a ->
                            if (a.id == grades[1].id) a.copy(actual = 1) else a
                        }
                )
            }
        }
        ui.waitForIdle()
        ui.onNodeWithTag("lesson-1-1").assertTextContains("Schnitt 1,67")
        tap("Stundenplan bearbeiten")
        ui.onNodeWithText("Schnitt 1,67").assertExists()
        choose("Fach für Stunde 1", "Geschichte")
        ui.onAllNodes(hasText("Schnitt", substring = true)).assertCountEquals(0)
    }

    @Test
    fun subjectCategoryColorsMatchAcrossCardsAndReactToClassification() {
        val main = subject.copy(core = true, promotion = true)
        val promotion = Subject(profileId = profile.id, name = "Geschichte", promotion = true)
        val other = Subject(profileId = profile.id, name = "Kunst", core = false, promotion = false)
        val date = LocalDate.now()
        val exam =
            Assessment(
                subjectId = main.id,
                title = "Farbprüfung",
                date = date.plusDays(1).toString(),
            )
        val timetable =
            Timetable(
                profileId = profile.id,
                validFrom = date.toString(),
                anchorMonday = date.with(java.time.DayOfWeek.MONDAY).toString(),
                blocks =
                    listOf(main, promotion, other).mapIndexed { index, s ->
                        val times = periodTimes(index + 1, java.time.LocalTime.of(8, 0))
                        Lesson(
                            subjectId = s.id,
                            day = 1,
                            slot = index + 1,
                            start = times.first,
                            end = times.second,
                        )
                    },
            )
        runBlocking {
            repo.update {
                it.copy(
                    subjects = listOf(main, promotion, other),
                    assessments = listOf(exam),
                    timetables = listOf(timetable),
                )
            }
        }
        fun assertFill(tag: String, expected: androidx.compose.ui.graphics.Color) {
            val node = visible(ui.onNodeWithTag(tag))
            val pixels = node.captureToImage().toPixelMap()
            assertEquals("Background for $tag", expected, pixels[pixels.width / 2, 12])
        }
        val violet = androidx.compose.ui.graphics.Color(0xFFE5DAFF)
        val blue = androidx.compose.ui.graphics.Color(0xFFCFEAFB)
        val gray = androidx.compose.ui.graphics.Color(0xFFE8EBF0)
        ui.onNodeWithTag("nav-Meine Noten").performClick()
        assertFill("subject-${main.id}", violet)
        assertFill("subject-${promotion.id}", blue)
        assertFill("subject-${other.id}", gray)
        capture("category-subjects")
        runBlocking {
            repo.update {
                it.copy(
                    subjects =
                        it.subjects.map { s -> if (s.id == main.id) s.copy(core = false) else s }
                )
            }
        }
        ui.waitForIdle()
        assertFill("subject-${main.id}", blue)
        openArchive()
        assertFill("exam-${exam.id}", blue)
        openTimetable()
        assertFill("lesson-1-1", blue)
        assertFill("lesson-1-2", blue)
        assertFill("lesson-1-3", gray)
        capture("category-timetable")
        tap("Stundenplan bearbeiten")
        assertFill("picker-Fach für Stunde 1", blue)
        assertFill("picker-Fach für Stunde 2", blue)
        assertFill("picker-Fach für Stunde 3", gray)
    }

    @Test
    fun subjectCatalogWheelsAndLeanFormsAreAlphabetical() {
        val rs = profile.copy(school = School.REALSCHULE, track = "I")
        val ethics = Subject(profileId = rs.id, name = "Ethik")
        val german = Subject(profileId = rs.id, name = "Deutsch")
        runBlocking {
            repo.update {
                SchoolData(
                    profiles = listOf(rs),
                    activeProfileId = rs.id,
                    subjects = listOf(subject, ethics, german),
                )
            }
        }
        val expected =
            listOf("Deutsch", "Ethik/Religion", "IT · Informationstechnologie", "Mathematik")
        ui.onNodeWithTag("nav-Meine Noten").performClick()
        val positions = expected.map { ui.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertEquals(positions.sorted(), positions)
        capture("sorted-subject-colors")
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        tap("Fächer & Gewichtungen")
        tap("Mathematik")
        tap("Schulregeln für dieses Fach")
        tap("Gewichtung anpassen (optional)")
        ui.onNodeWithText("Diese Gewichtungen wurden von der Schule bestätigt").assertDoesNotExist()
        tap("Fach speichern")
        ui.onNodeWithContentDescription("Zurück").performClick()
        openArchive()
        tap("Prüfung planen")
        ui.onNodeWithText("Testname (freiwillig)").assertDoesNotExist()
        ui.onNodeWithText("Welche Note glaubst du?").assertDoesNotExist()
        openChoices("Fach")
        for ((index, name) in expected.withIndex()) {
            ui.onNodeWithTag("choices-Fach").performScrollToIndex(index)
            ui.onNodeWithTag("choice-option-$index").assertTextContains(name)
        }
        tap("Abbrechen")
        choose("Fach", "IT · Informationstechnologie")
        capture("lean-test-form")
        tap("Speichern")
        ui.waitUntil(5000) { repo.state.value!!.assessments.isNotEmpty() }
        openTimetable()
        tap("Stundenplan bearbeiten")
        openChoices("Fach für Stunde 1")
        for ((index, name) in (listOf("Noch frei") + expected + "Pause").withIndex()) {
            ui.onNodeWithTag("choices-Fach für Stunde 1").performScrollToIndex(index)
            ui.onNodeWithTag("choice-option-$index").assertTextContains(name)
        }
        tap("Abbrechen")
        choose("Fach für Stunde 1", "Deutsch")
        choose("Fach für Stunde 2", "Mathematik")
        capture("matching-timetable-colors")
    }

    @Test
    fun onboardingUnsupportedStateAndSettings() {
        assertEquals("com.streberalarm.app", ui.activity.packageName)
        val appInfo = ui.activity.applicationInfo
        assertEquals("NotenManager", appInfo.loadLabel(ui.activity.packageManager).toString())
        val launcherIcon = appInfo.loadIcon(ui.activity.packageManager)
        assertTrue(launcherIcon is android.graphics.drawable.AdaptiveIconDrawable)
        // Capture the actual packaged icon for inspection of Android's mask and safe area.
        for (size in listOf(48, 512)) {
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    size,
                    size,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            launcherIcon.setBounds(0, 0, size, size)
            launcherIcon.draw(android.graphics.Canvas(bitmap))
            File(ui.activity.cacheDir, "launcher-$size.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        runBlocking { repo.update { SchoolData() } }
        ui.waitForIdle()
        ui.onNodeWithText("Willkommen bei NotenManager").assertExists()
        capture("notenmanager-onboarding")
        choose("Bundesland", "Berlin")
        ui.onNodeWithText("Noch nicht unterstützt").assertExists()
        ui.onNodeWithText("Weiter").performScrollTo().assertIsNotEnabled()
        choose("Bundesland", "Bayern")
        tap("Weiter")
        tap("Los geht’s")
        ui.waitUntil(5000) { runBlocking { repo.current().active() != null } }
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        tap("Datensicherung")
        ui.onNodeWithText("Backup exportieren").performScrollTo().assertExists()
        tap("Backup wiederherstellen")
        ui.onNodeWithText("Vorhandene Daten vollständig durch das Backup ersetzen").assertExists()
        ui.onNodeWithText("Datei auswählen").assertIsNotEnabled()
        ui.onNodeWithText("Abbrechen").performClick()
    }

    @Test
    fun timetableSixSlotsPauseNewSubjectAndAfternoonBlock() {
        openTimetable()
        tap("Stundenplan bearbeiten")
        for (n in 1..6) ui.onNodeWithTag("picker-Fach für Stunde $n")
            .performScrollTo()
            .assertExists()
        choose("Fach für Stunde 1", "Mathematik")
        choose("Fach für Stunde 2", "Pause")
        openChoices("Fach für Stunde 3")
        tap("Fach hinzufügen")
        field("Wie heißt das Fach?", "Sachkunde")
        tap("Fach hinzufügen")
        ui.waitUntil(5000) {
            runBlocking { repo.current().subjects.any { it.name == "Sachkunde" } }
        }
        ui.onNodeWithTag("picker-Fach für Stunde 3").assertTextContains("Sachkunde")
        tap("Doppelstunde")
        choose("Fach für Stunde 7", "Mathematik")
        choose("Fach für Stunde 8", "Mathematik")
        ui.onNodeWithTag("day-2").performScrollTo().performClick()
        ui.onNodeWithTag("picker-Fach für Stunde 1")
            .performScrollTo()
            .assertTextContains("Noch frei")
        choose("Fach für Stunde 1", "Sachkunde")
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        ui.onNodeWithTag("picker-Fach für Stunde 1")
            .performScrollTo()
            .assertTextContains("Sachkunde")
        capture("timetable-editor")
        tap("Stundenplan speichern")
        ui.waitUntil(5000) { runBlocking { repo.current().timetables.isNotEmpty() } }
        val data = runBlocking { repo.current() }
        val rows = data.timetables.single().blocks
        assertEquals(6, rows.size)
        assertTrue(rows.single { it.isBreak }.subjectId.isEmpty())
        assertFalse(data.subjects.any { it.name == "Pause" })
        assertEquals(listOf(1, 2, 3, 7, 8), rows.filter { it.day == 1 }.map { it.slot }.sorted())
        tap("Ferien, Ausfälle und ältere Pläne")
        tap("Ferien / freien Tag hinzufügen")
        date("Von", "2026-09-21")
        date("Bis einschließlich", "2026-09-21")
        field("Grund / Feiertag", "Pädagogischer Tag")
        tap("Freie Zeit speichern")
        ui.waitUntil(5000) {
            runBlocking { repo.current().daysOff.any { it.reason == "Pädagogischer Tag" } }
        }
        tap("Ferien, Ausfälle und ältere Pläne")
        ui.onNodeWithText("Pädagogischer Tag", substring = true).performScrollTo().assertExists()
    }

    @Test
    fun selectionMenusExposeRadioChoicesWithoutScrollSelection() {
        runBlocking { repo.update { SchoolData() } }
        ui.waitForIdle()
        openChoices("Deine Klasse")
        ui.onNodeWithTag("choices-Deine Klasse").assertExists()
        val chosen = hasText("8. Klasse") and hasAnyAncestor(hasTestTag("choices-Deine Klasse"))
        ui.onNode(chosen)
            .assertIsSelected()
            .assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.Role,
                    androidx.compose.ui.semantics.Role.RadioButton,
                )
            )
        ui.onNodeWithTag("choices-Deine Klasse").performScrollToNode(hasText("10. Klasse"))
        ui.onNode(hasText("10. Klasse") and hasAnyAncestor(hasTestTag("choices-Deine Klasse")))
            .assertIsNotSelected()
            .performClick()
            .assertIsSelected()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("choices-Deine Klasse").performScrollToNode(hasText("10. Klasse"))
        ui.onNode(hasText("10. Klasse") and hasAnyAncestor(hasTestTag("choices-Deine Klasse")))
            .assertIsSelected()
        tap("Abbrechen")
        ui.onNodeWithTag("picker-Deine Klasse").assertTextContains("8. Klasse")
        choose("Deine Klasse", "10. Klasse")
        ui.onNodeWithTag("picker-Deine Klasse").assertTextContains("10. Klasse")
    }

    @Test
    fun radioScrollCancelAndLabelledRealschuleProfilePersist() {
        runBlocking { repo.update { SchoolData() } }
        ui.waitForIdle()
        choose("Schulart", "Realschule")
        openChoices("Deine Klasse")
        capture("class-choices")
        ui.onNodeWithTag("choices-Deine Klasse").performTouchInput { swipeUp(durationMillis = 500) }
        ui.waitForIdle()
        tap("Abbrechen")
        ui.onNodeWithTag("picker-Deine Klasse").assertTextContains("8. Klasse")
        choose("Deine Klasse", "10. Klasse")
        openChoices("Deine Klasse")
        ui.onNodeWithTag("choices-Deine Klasse")
            .assert(
                androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                    "10. Klasse",
                )
            )
        tap("Übernehmen")
        choose("Schulzweig", "II · Wirtschaft und Rechnungswesen")
        tap("Weiter")
        tap("Los geht’s")
        ui.waitUntil(5000) { runBlocking { repo.current().active()?.grade == 10 } }
        val data = runBlocking { repo.current() }
        capture("game-home")
        assertEquals("II", data.active()!!.track)
        assertTrue(
            data.subjects.single { it.name == "Betriebswirtschaftslehre/Rechnungswesen" }.core
        )
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        ui.onNodeWithTag("nav-Meine Noten").performClick()
        ui.onNodeWithText("Betriebswirtschaftslehre/Rechnungswesen")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun realschuleFifthGradeHasNoTrack() {
        runBlocking { repo.update { SchoolData() } }
        ui.waitForIdle()
        choose("Schulart", "Realschule")
        choose("Deine Klasse", "5. Klasse")
        ui.onNodeWithText("In Klasse 5 und 6 gibt es noch keinen Schulzweig.").assertExists()
        tap("Weiter")
        tap("Los geht’s")
        ui.waitUntil(5000) { runBlocking { repo.current().active() != null } }

        val data = runBlocking { repo.current() }
        assertEquals(Defaults.NO_TRACK, data.active()!!.track)
        assertFalse(data.subjects.any { it.name == "Betriebswirtschaftslehre/Rechnungswesen" })
        assertEquals(
            setOf("Deutsch", "Mathematik", "Englisch"),
            data.subjects.filter { it.core }.map { it.name }.toSet(),
        )
    }

    @Test
    fun longPressingSubjectOffersConfirmedDeletion() {
        val other = subject.copy(id = id(), name = "Deutsch")
        val exams =
            listOf(subject, other).map {
                Assessment(subjectId = it.id, title = "Probe", date = "2026-10-01")
            }
        val studies = exams.map { Study(assessmentId = it.id, start = 1000, end = 61000) }
        val officials =
            listOf(subject, other).map { Official(subjectId = it.id, period = "Jahr", value = 2) }
        val lessons =
            listOf(subject, other).mapIndexed { index, s ->
                Lesson(
                    subjectId = s.id,
                    day = 1,
                    start = if (index == 0) "08:00" else "09:00",
                    end = if (index == 0) "08:45" else "09:45",
                    slot = index,
                )
            }
        val exceptions =
            listOf(subject, other).map {
                ExceptionLesson(
                    subjectId = it.id,
                    date = "2026-10-02",
                    start = "08:00",
                    end = "08:45",
                    cancelled = true,
                )
            }
        val documents =
            exams.map { exam ->
                val file = File(repo.documentsDir, "${id()}.pdf")
                val pdf = PdfDocument()
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(100, 100, 1).create())
                pdf.finishPage(page)
                file.outputStream().use { pdf.writeTo(it) }
                pdf.close()
                Document(
                    assessmentId = exam.id,
                    name = "Probe.pdf",
                    mime = "application/pdf",
                    file = file.name,
                    size = file.length(),
                )
            }
        runBlocking {
            repo.update {
                it.copy(
                    subjects = listOf(subject, other),
                    assessments = exams,
                    studies = studies,
                    officials = officials,
                    documents = documents,
                    exceptions = exceptions,
                    timetables =
                        listOf(
                            Timetable(
                                profileId = profile.id,
                                validFrom = "2026-09-21",
                                anchorMonday = "2026-09-21",
                                blocks = lessons,
                            )
                        ),
                )
            }
        }
        val before = repo.state.value!!
        ui.onNodeWithTag("nav-Meine Noten").performClick()
        ui.onNodeWithTag("subject-${subject.id}").performScrollTo().performTouchInput {
            longClick()
        }
        ui.onNodeWithText("Löschen").assertExists().performClick()
        ui.onNodeWithText("Fach vollständig löschen?").assertExists()
        ui.onNodeWithText("Abbrechen").performClick()
        assertEquals(before, runBlocking { repo.current() })

        ui.onNodeWithTag("subject-${subject.id}").performScrollTo().performTouchInput {
            longClick()
        }
        ui.onNodeWithText("Löschen").assertExists().performClick()
        ui.onNodeWithText("Fach löschen").performClick()
        ui.waitUntil(5000) { runBlocking { repo.current().subjects.none { it.id == subject.id } } }
        ui.onNodeWithTag("subject-${subject.id}").assertDoesNotExist()
        val after = runBlocking { repo.current() }
        assertEquals(listOf(other), after.subjects)
        assertEquals(listOf(exams.last()), after.assessments)
        assertEquals(listOf(studies.last()), after.studies)
        assertEquals(listOf(officials.last()), after.officials)
        assertEquals(listOf(documents.last()), after.documents)
        assertEquals(listOf(exceptions.last()), after.exceptions)
        assertEquals(listOf(lessons.last()), after.timetables.single().blocks)
        assertFalse(File(repo.documentsDir, documents.first().file).exists())
        assertTrue(File(repo.documentsDir, documents.last().file).exists())
        ui.activityRule.scenario.recreate()
        assertEquals(after, runBlocking { repo.current() })
    }

    @Test
    fun settingsChoicesDistinguishProfilesAndKeepTheirData() {
        val first = Profile(school = School.REALSCHULE, track = "I")
        val second = first.copy(id = id(), track = "II")
        val third = second.copy(id = id())
        runBlocking {
            repo.update {
                SchoolData(profiles = listOf(first, second, third), activeProfileId = first.id)
            }
        }
        ui.waitForIdle()
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        tap("Schuljahr")
        choose("Dein Schuljahr", "Realschule · 8. Klasse · 2026 · II · Ganzes Schuljahr · Profil 3")
        ui.waitUntil(5000) { runBlocking { repo.current().activeProfileId == third.id } }
        assertEquals(3, runBlocking { repo.current().profiles.size })
        ui.onNodeWithText("Dein Schulzweig: II · Wirtschaft und Rechnungswesen")
            .performScrollTo()
            .assertExists()
    }

}
