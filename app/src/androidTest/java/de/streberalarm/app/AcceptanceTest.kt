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
        val grades =
            subjects
                .filter { it.name in Transfer.NAMES }
                .zip(listOf(2, 2, 3))
                .map { (s, n) ->
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
            repo.update { it.copy(profiles = listOf(p), subjects = subjects, assessments = grades) }
        }
        ui.onNodeWithText("Dein nächster Schulweg").assertExists()
        ui.onNodeWithTag("nav-Meine Noten").performClick()
        ui.onNodeWithTag("transfer-overview").assertExists()
        ui.onNodeWithTag("grade-overview").assertDoesNotExist()
        visible(ui.onNodeWithText("Übertrittsnoten eintragen")).performClick()
        choose("Deutsch", "2")
        choose("Mathematik", "3")
        choose("Heimat- und Sachunterricht", "3")
        ui.activityRule.scenario.recreate()
        visible(ui.onNodeWithText("Übertrittsnoten speichern")).performClick()
        ui.onNodeWithText("Noten aus dem Übertrittszeugnis").assertExists()
        visible(ui.onNodeWithTag("transfer-Realschule-DIRECT")).assertExists()
        visible(ui.onNodeWithTag("transfer-Gymnasium-PROBE_NEEDED")).assertExists()
        val saved = repo.state.value!!
        assertEquals(
            listOf(2, 3, 3),
            Transfer.subjects(p, saved).map { s ->
                saved.officials.single { it.subjectId == s!!.id }.value
            },
        )
        capture("primary-transfer-official")
        ui.activityRule.scenario.recreate()
        assertEquals(saved, repo.state.value)
        visible(ui.onNodeWithText("Übertrittsnoten eintragen")).performClick()
        choose("Noten aus", "Probeunterricht Gymnasium")
        choose("Deutsch", "4")
        choose("Mathematik", "4")
        visible(ui.onNodeWithText("Übertrittsnoten speichern")).performClick()
        visible(ui.onNodeWithTag("transfer-Gymnasium-PARENTS")).assertExists()
        assertEquals(saved.officials, repo.state.value!!.officials)
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
        ui.onNodeWithTag("overall-average").assertTextEquals("Ø 3,50")
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
        ui.onNodeWithTag("nav-Meine Noten").performClick()
        ui.onNodeWithTag("grade-overview").assertExists()
        ui.onNodeWithText("Offizielle Ergebnisse prüfen").assertDoesNotExist()
        ui.onNodeWithTag("overall-average").assertTextEquals("Ø 3,67")
        ui.onNodeWithTag("promotion-red").assertExists()
        visible(ui.onNodeWithTag("promotion-goal")).assertTextContains(" 4", substring = true)
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
            ui.onNodeWithText("Dein Look").assertExists()
            ui.onNodeWithTag("theme-${look.id}").performScrollTo().performClick()
            ui.onNodeWithTag("theme-${look.id}").assertIsSelected()
            ui.onNodeWithTag("appearance-${look.id}").assertExists()
            ui.activityRule.scenario.recreate()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle()
            ui.onNodeWithTag("theme-${look.id}").assertIsSelected()
            assertEquals(look, AppearancePreferences(ui.activity).state.value.look)
            capture("theme-${look.id}-settings")
            ui.onNodeWithContentDescription("Zurück").performClick()
            listOf("Heute", "Lernen", "Meine Noten").forEachIndexed { i, label ->
                assertEquals(
                    navBounds[i],
                    ui.onNodeWithTag("nav-$label").fetchSemanticsNode().boundsInWindow,
                )
            }
            ui.onNodeWithTag("nav-Heute").performClick()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            ui.onNodeWithText("Deine nächste Probe").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult
            ) {
                it(layouts)
            }
            assertEquals(lookScheme(look).onBackground, layouts.single().layoutInput.style.color)
            capture("theme-${look.id}-today")
            ui.onNodeWithTag("nav-Lernen").performClick()
            ui.onNodeWithTag("probe-${exam.id}").assertExists()
            visible(ui.onNodeWithContentDescription("Lernen starten"))
                .assertIsEnabled()
                .performClick()
            ui.waitUntil(5000) { repo.state.value!!.studies.any { it.end == null } }
            ui.onNodeWithContentDescription("Lernen beenden").performClick()
            ui.waitUntil(5000) { repo.state.value!!.studies.none { it.end == null } }
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
        tap("Stundenplan")
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

    private fun openWheel(label: String) {
        ui.onNodeWithTag("picker-$label").performScrollTo().performClick()
        ui.waitForIdle()
    }

    private fun choose(label: String, value: String) {
        openWheel(label)
        ui.onNodeWithTag("wheel-$label").performScrollToNode(hasText(value))
        ui.onNode(hasText(value) and hasAnyAncestor(hasTestTag("wheel-$label"))).performClick()
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
    fun learningDeckAndTodayScheduleStayFocusedOnCurrentProfile() {
        val rs = profile.copy(school = School.REALSCHULE, track = "I")
        val first =
            Assessment(
                subjectId = subject.id,
                title = "Bald",
                date = LocalDate.now().plusDays(2).toString(),
            )
        val later =
            first.copy(
                id = "later",
                title = "Später",
                date = LocalDate.now().plusDays(8).toString(),
            )
        val date = LocalDate.now()
        val lesson =
            Lesson(
                subjectId = subject.id,
                day = date.dayOfWeek.value,
                start = "08:00",
                end = "08:45",
                slot = 1,
            )
        val table =
            Timetable(
                profileId = profile.id,
                validFrom = date.toString(),
                anchorMonday = date.with(java.time.DayOfWeek.MONDAY).toString(),
                blocks = listOf(lesson),
            )
        val actual =
            listOf(
                first.copy(id = "grade1", stage = Stage.GRADED, actual = 2),
                first.copy(id = "grade2", kind = Kind.ORAL, stage = Stage.GRADED, actual = 4),
            )
        runBlocking {
            repo.update {
                it.copy(
                    profiles = listOf(rs),
                    assessments = listOf(later, first) + actual,
                    timetables = listOf(table),
                )
            }
        }
        ui.onNodeWithTag("today-lesson-${lesson.id}").performScrollTo()
        ui.onNode(
                hasText("Ø 2,67") and hasAnyAncestor(hasTestTag("today-lesson-${lesson.id}")),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        capture("three-today")
        ui.onNodeWithTag("nav-Lernen").performClick()
        ui.onNodeWithTag("probe-${first.id}").assertExists()
        capture("three-learning")
        ui.onNodeWithTag("learning-deck").performTouchInput { swipeLeft() }
        ui.waitForIdle()
        ui.onNodeWithTag("probe-${later.id}").assertExists()
        ui.onNodeWithTag("learning-deck").performTouchInput { swipeUp() }
        ui.waitForIdle()
        capture("three-learning-next")
        ui.onNodeWithTag("mark-written-${later.id}").assertIsDisplayed().performClick()
        ui.waitUntil(5000) {
            runBlocking {
                repo.current().assessments.find { it.id == later.id }?.stage == Stage.WRITTEN
            }
        }
        ui.onNodeWithTag("learn-stage-WRITTEN").performScrollTo().performClick()
        ui.onNodeWithTag("probe-${later.id}").assertExists()
        ui.onNodeWithTag("study-clock").assertDoesNotExist()
    }

    @Test
    fun threeAreasQuickPlanningAndContextualExamActions() {
        runBlocking {
            repo.update {
                it.copy(profiles = listOf(profile.copy(school = School.REALSCHULE, track = "I")))
            }
        }
        ui.onNodeWithTag("nav-Heute").assertExists()
        ui.onNodeWithTag("nav-Lernen").assertExists()
        ui.onNodeWithTag("nav-Meine Noten").assertExists()
        ui.onNodeWithText("Probe planen").performScrollTo().performClick()
        ui.onNodeWithTag("plan-subject-${subject.id}").performScrollTo().performClick()
        ui.onNodeWithText("Was kommt / kam dran?").assertDoesNotExist()
        ui.onNodeWithTag("stage-PLANNED").assertDoesNotExist()
        date("Datum", "2026-10-01")
        tap("Probe planen")
        ui.waitUntil(5000) { runBlocking { repo.current().assessments.size == 1 } }
        tap("Lernstoff ergänzen")
        field("Was kommt / kam dran?", "Brüche und Prozentrechnung")
        tap("Lernstoff speichern")
        ui.waitUntil(5000) {
            runBlocking {
                repo.current().assessments.single().material == "Brüche und Prozentrechnung"
            }
        }
        ui.onNodeWithTag("study-clock").performScrollTo().assertIsDisplayed().performClick()
        ui.waitUntil(5000) { runBlocking { repo.current().studies.any { it.end == null } } }
        ui.onNodeWithTag("study-clock").performClick()
        ui.waitUntil(5000) { runBlocking { repo.current().studies.all { it.end != null } } }
        ui.onNodeWithText("Lernrunde geschafft!", substring = true).assertExists()
        tap("Geschrieben")
        ui.waitUntil(5000) {
            runBlocking { repo.current().assessments.single().stage == Stage.WRITTEN }
        }
        ui.onNodeWithTag("study-clock").assertDoesNotExist()
        tap("Gefühlte Note eintragen")
        tap("Weiß ich nicht")
        tap("Note eintragen")
        ui.onNodeWithTag("grade-5").performClick()
        tap("Test speichern")
        ui.waitUntil(5000) { runBlocking { repo.current().assessments.single().actual == 5 } }
        ui.onNodeWithTag("study-clock").assertDoesNotExist()
        capture("three-areas-graded")
        ui.onNodeWithContentDescription("Zurück").performClick()
        ui.onNodeWithTag("nav-Meine Noten").performClick()
        ui.onNodeWithText("Hier brauchst du Unterstützung").assertExists()
    }

    @Test
    fun learningClockIsDirectAndPlanningStatusNeedsNoFoldout() {
        openArchive()
        tap("Test hinzufügen")
        capture("study-plan")
        field("Was kommt / kam dran?", "Brüche kürzen und erweitern")
        tap("Test speichern")
        ui.waitUntil(5000) { runBlocking { repo.current().assessments.size == 1 } }
        capture("study-detail")
        ui.onNodeWithTag("study-clock").assertIsDisplayed().performClick()
        ui.waitUntil(5000) { runBlocking { repo.current().studies.any { it.end == null } } }
        ui.waitUntil(5000) {
            runBlocking { repo.current().studies.single().seconds(System.currentTimeMillis()) >= 2 }
        }
        capture("study-clock-running")
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        ui.onNodeWithTag("study-clock").assertIsDisplayed().performClick()
        ui.waitUntil(5000) { runBlocking { repo.current().studies.single().end != null } }
        tap("Geschrieben")
        ui.waitUntil(5000) {
            runBlocking { repo.current().assessments.single().stage == Stage.WRITTEN }
        }
        tap("Note eintragen")
        ui.onNodeWithTag("grade-3").assertIsDisplayed().performClick()
        ui.onNodeWithText("Was kommt / kam dran?").assertDoesNotExist()
        capture("study-quick-grade")
        tap("Test speichern")
        ui.waitUntil(5000) { runBlocking { repo.current().assessments.single().actual == 3 } }
        tap("Test bearbeiten")
        ui.onNodeWithText("Schon geschrieben oder benotet?").assertDoesNotExist()
        ui.onNodeWithTag("stage-PLANNED").assertExists().performClick()
        tap("Test speichern")
        ui.waitUntil(5000) {
            runBlocking { repo.current().assessments.single().stage == Stage.PLANNED }
        }
        assertNull(runBlocking { repo.current().assessments.single().actual })
    }

    @Test
    fun homeClockStopsTheRightExamAndOtherClockIsDisabled() {
        val first = Assessment(subjectId = subject.id, title = "Bruchrechnung", date = "2026-10-01")
        val second = Assessment(subjectId = subject.id, title = "Geometrie", date = "2026-10-02")
        runBlocking {
            repo.update { it.copy(assessments = listOf(first, second)) }
            repo.start(first.id)
        }
        ui.onNodeWithTag("nav-Heute").performClick()
        ui.onNodeWithTag("study-clock")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Lernen beenden")
        capture("study-home-running")
        openArchive()
        tap("Geometrie")
        ui.onNodeWithTag("study-clock").assertIsNotEnabled()
        assertEquals(first.id, runBlocking { repo.current().studies.single().assessmentId })
        ui.onNodeWithContentDescription("Zurück").performClick()
        ui.onNodeWithTag("nav-Heute").performClick()
        ui.onNodeWithTag("study-clock").performClick()
        ui.waitUntil(5000) { runBlocking { repo.current().studies.single().end != null } }
        runBlocking {
            repo.update {
                it.copy(
                    assessments =
                        it.assessments.map { e ->
                            if (e.id == second.id) e.copy(stage = Stage.CANCELLED) else e
                        }
                )
            }
        }
        openArchive()
        tap("Geometrie")
        ui.onNodeWithTag("study-clock").assertDoesNotExist()
        ui.onNodeWithText("Note eintragen").assertDoesNotExist()
    }

    @Test
    fun quickPointsAcceptZeroAndFifteenWithoutChangingExamDetails() {
        val upper = profile.copy(grade = 12, term = "12/1")
        val exam =
            Assessment(
                subjectId = subject.id,
                title = "Klausur",
                date = "2026-10-01",
                time = "09:15",
                material = "Funktionen",
                estimate = 8,
                weight = 1.5,
            )
        runBlocking {
            repo.update { it.copy(profiles = listOf(upper), assessments = listOf(exam)) }
        }
        openArchive()
        tap("Klausur")
        tap("Note eintragen")
        ui.onNodeWithTag("grade-0").performClick()
        tap("Test speichern")
        ui.waitUntil(5000) { runBlocking { repo.current().assessments.single().actual == 0 } }
        tap("Note ändern")
        visible(ui.onNodeWithTag("grade-15")).performClick()
        tap("Test speichern")
        ui.waitUntil(5000) { runBlocking { repo.current().assessments.single().actual == 15 } }
        assertEquals(
            exam.copy(stage = Stage.GRADED, actual = 15),
            runBlocking { repo.current().assessments.single() },
        )
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
        ui.onNodeWithText("Haupt-/Kernfach").assertExists()
        ui.onNodeWithText("Vorrückungsfach").assertExists()
        ui.onNodeWithText("Weiteres Fach").assertExists()
        capture("category-subjects")
        tap("Was bedeuten die Farben?")
        ui.onNodeWithText("Violett: Haupt-/Kernfach").assertExists()
        tap("Was bedeuten die Farben?")
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
        tap("Mathematik")
        tap("Fach und Gewichtungen bearbeiten")
        tap("Schulregeln für dieses Fach")
        tap("Gewichtung anpassen (optional)")
        ui.onNodeWithText("Diese Gewichtungen wurden von der Schule bestätigt").assertDoesNotExist()
        tap("Fach speichern")
        ui.onNodeWithContentDescription("Zurück").performClick()
        openArchive()
        tap("Test hinzufügen")
        ui.onNodeWithText("Testname (freiwillig)").assertDoesNotExist()
        ui.onNodeWithText("Welche Note glaubst du?").assertDoesNotExist()
        openWheel("Fach")
        for ((index, name) in expected.withIndex()) {
            ui.onNodeWithTag("wheel-Fach").performScrollToIndex(index)
            ui.onNodeWithTag("wheel-option-$index").assertTextContains(name)
        }
        tap("Abbrechen")
        choose("Fach", "IT · Informationstechnologie")
        capture("lean-test-form")
        tap("Test speichern")
        ui.onNodeWithContentDescription("Zurück").performClick()
        openTimetable()
        tap("Stundenplan bearbeiten")
        openWheel("Fach für Stunde 1")
        for ((index, name) in (listOf("Noch frei") + expected + "Pause").withIndex()) {
            ui.onNodeWithTag("wheel-Fach für Stunde 1").performScrollToIndex(index)
            ui.onNodeWithTag("wheel-option-$index").assertTextContains(name)
        }
        tap("Abbrechen")
        choose("Fach für Stunde 1", "Deutsch")
        choose("Fach für Stunde 2", "Mathematik")
        capture("matching-timetable-colors")
    }

    @Test
    fun timedEstimateNotificationIsPersistentAndReschedulingCancelsIt() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand(
            "pm grant com.streberalarm.app android.permission.POST_NOTIFICATIONS"
        )
        val manager = ui.activity.getSystemService(android.app.NotificationManager::class.java)
        manager.cancelAll()
        val date = LocalDate.now().plusDays(1)
        val exam =
            Assessment(subjectId = subject.id, title = "Test am Nachmittag", date = date.toString())
        runBlocking { repo.update { it.copy(assessments = listOf(exam)) } }
        val before = date.atTime(15, 59).atZone(java.time.ZoneId.systemDefault())
        runBlocking { repo.refreshEstimates(before) }
        assertTrue(manager.activeNotifications.none { it.tag == "estimate:${exam.id}" })
        val due = before.withHour(16).withMinute(0)
        runBlocking { repo.refreshEstimates(due) }
        val sent = manager.activeNotifications.single { it.tag == "estimate:${exam.id}" }
        runBlocking { repo.refreshEstimates(due.plusSeconds(1)) }
        assertEquals(
            sent.postTime,
            manager.activeNotifications.single { it.tag == sent.tag }.postTime,
        )
        assertTrue(
            EstimatePrompts.key(exam) in runBlocking { Repository(ui.activity).current().delivered }
        )
        runBlocking {
            repo.update {
                it.copy(assessments = listOf(exam.copy(date = date.plusDays(1).toString())))
            }
        }
        assertTrue(manager.activeNotifications.none { it.tag == sent.tag })
    }

    @Test
    fun gradeEntryPostsEstimateQuestionAndNotificationOpensSingleQuestion() {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand(
                "pm grant com.streberalarm.app android.permission.POST_NOTIFICATIONS"
            )
        val manager = ui.activity.getSystemService(android.app.NotificationManager::class.java)
        manager.cancelAll()
        val exam =
            Assessment(
                subjectId = subject.id,
                title = "Meine Schulaufgabe",
                date = LocalDate.now().plusDays(1).toString(),
            )
        runBlocking { repo.update { it.copy(assessments = listOf(exam)) } }
        openArchive()
        tap("Meine Schulaufgabe")
        tap("Note eintragen")
        ui.onNodeWithTag("grade-3").performClick()
        tap("Test speichern")
        ui.waitUntil(5000) { manager.activeNotifications.any { it.tag == "estimate:${exam.id}" } }
        val posted = manager.activeNotifications.single { it.tag == "estimate:${exam.id}" }
        val host = ui.activity
        val originalIntent = host.intent
        try {
            posted.notification.contentIntent.send()
            ui.waitUntil(5000) {
                ui.onAllNodesWithText("Welche Note hattest du erwartet?")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            choose("Deine gefühlte Note", "2")
            tap("Einschätzung speichern")
            ui.waitUntil(5000) { runBlocking { repo.current().assessments.single().estimate == 2 } }
            assertEquals(3, runBlocking { repo.current().assessments.single().actual })
            assertTrue(manager.activeNotifications.none { it.tag == posted.tag })
        } finally {
            // ActivityScenario tracks its launcher intent; restore it after exercising a real
            // notification deep link.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                host.intent = originalIntent
            }
        }
    }

    @Test
    fun deniedNotificationsKeepQuestionInAppAndUnknownAnswerStopsIt() {
        // Android 13+ revoking this runtime permission kills the app. The external check revokes it
        // before starting this isolated instrumentation run, then restores it afterwards.
        org.junit.Assume.assumeFalse(
            "Run separately after adb pm revoke POST_NOTIFICATIONS",
            EstimateNotifications.allowed(ui.activity),
        )
        val manager = ui.activity.getSystemService(android.app.NotificationManager::class.java)
        manager.cancelAll()
        val exam =
            Assessment(
                subjectId = subject.id,
                title = "Test ohne Push",
                date = LocalDate.now().toString(),
                stage = Stage.GRADED,
                actual = 2,
            )
        runBlocking { repo.update { it.copy(assessments = listOf(exam)) } }
        assertFalse(EstimateNotifications.allowed(ui.activity))
        assertTrue(manager.activeNotifications.none { it.tag == "estimate:${exam.id}" })
        ui.waitUntil(5000) {
            ui.onAllNodesWithText("Gefühlte Note eintragen").fetchSemanticsNodes().isNotEmpty()
        }
        tap("Gefühlte Note eintragen")
        tap("Weiß ich nicht")
        ui.waitUntil(5000) {
            runBlocking { EstimatePrompts.skipKey(exam) in repo.current().delivered }
        }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand(
                "pm grant com.streberalarm.app android.permission.POST_NOTIFICATIONS"
            )
        runBlocking { repo.refreshEstimates() }
        assertTrue(manager.activeNotifications.none { it.tag == "estimate:${exam.id}" })
    }

    @Test
    fun unnamedTestNeedsNoClockAndMergesOldNotesWithoutLoss() {
        openArchive()
        tap("Test hinzufügen")
        ui.onNodeWithText("Testname (freiwillig)").assertDoesNotExist()
        ui.onNodeWithText("Welche Note glaubst du?").assertDoesNotExist()
        ui.onNodeWithText("Uhrzeit (freiwillig)", substring = true).assertDoesNotExist()
        ui.onNodeWithText("Notizen", substring = false).assertDoesNotExist()
        field("Was kommt / kam dran?", "Brüche und eigene Notizen")
        tap("Test speichern")
        ui.waitUntil(5000) { runBlocking { repo.current().assessments.size == 1 } }
        val exam = runBlocking { repo.current().assessments.single() }
        assertEquals("Schulaufgabe · Mathematik", exam.title)
        assertNull(exam.time)
        runBlocking {
            repo.update {
                it.copy(assessments = listOf(exam.copy(notes = "Buch Seite 42", time = "10:15")))
            }
        }
        tap("Test bearbeiten")
        ui.onNodeWithText("Was kommt / kam dran?")
            .performScrollTo()
            .assertTextContains("Brüche und eigene Notizen\n\nBuch Seite 42")
        tap("Test speichern")
        ui.waitUntil(5000) { runBlocking { repo.current().assessments.single().notes.isEmpty() } }
        val saved = runBlocking { repo.current().assessments.single() }
        assertEquals("Brüche und eigene Notizen\n\nBuch Seite 42", saved.material)
        assertEquals("10:15", saved.time)
        tap("Test bearbeiten")
        date("Datum", "2026-10-10")
        tap("Test speichern")
        ui.waitUntil(5000) {
            runBlocking { repo.current().assessments.single().date == "2026-10-10" }
        }
        assertNull(runBlocking { repo.current().assessments.single().time })
    }

    @Test
    fun offlineExamLearningEstimateGradeDocumentAndBackupJourney() {
        openArchive()
        tap("Test hinzufügen")
        date("Datum", "2026-10-01")
        field("Was kommt / kam dran?", "Brüche kürzen und erweitern")
        tap("Test speichern")
        ui.waitUntil(5000) { runBlocking { repo.current().assessments.size == 1 } }
        ui.onNodeWithTag("study-clock").assertIsDisplayed()
        ui.onNodeWithTag("study-clock").performClick()
        ui.waitUntil(5000) { runBlocking { repo.current().studies.any { it.end == null } } }
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        ui.onNodeWithTag("study-clock").performClick()
        ui.waitUntil(5000) { runBlocking { repo.current().studies.all { it.end != null } } }
        tap("Note eintragen")
        ui.onNodeWithTag("grade-3").performClick()
        tap("Test speichern")
        ui.waitUntil(5000) { runBlocking { repo.current().assessments.single().actual == 3 } }
        tap("Gefühlte Note eintragen")
        choose("Deine gefühlte Note", "2")
        tap("Einschätzung speichern")
        ui.waitUntil(5000) { runBlocking { repo.current().assessments.single().estimate == 2 } }
        val exam = runBlocking { repo.current().assessments.single() }
        assertEquals(2, exam.estimate)
        assertEquals("Brüche kürzen und erweitern", exam.material)
        val oral =
            Assessment(
                subjectId = subject.id,
                title = "Mündlich",
                date = "2026-09-20",
                kind = Kind.ORAL,
                stage = Stage.GRADED,
                actual = 1,
            )
        runBlocking { repo.update { it.copy(assessments = it.assessments + oral) } }
        val source = File(repo.documentsDir, "test-source.pdf")
        val pdf = PdfDocument()
        try {
            repeat(2) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, index + 1).create())
                page.canvas.drawColor(android.graphics.Color.WHITE)
                page.canvas.drawText(
                    "Aufgabe ${index+1}",
                    40f,
                    80f,
                    android.graphics.Paint().apply { textSize = 24f },
                )
                pdf.finishPage(page)
            }
            source.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        val uri = FileProvider.getUriForFile(ui.activity, "com.streberalarm.app.files", source)
        runBlocking { repo.importDocuments(exam.id, listOf(uri)) }
        tap("Unterlagen")
        tap("Dokument 1.pdf")
        ui.waitUntil(5000) {
            ui.onAllNodesWithText("Seite 1 von 2").fetchSemanticsNodes().isNotEmpty()
        }
        tap("Nächste")
        ui.onNodeWithText("Seite 2 von 2").assertExists()
        val before = runBlocking { repo.current() }
        val password = id().toCharArray()
        val backup = runBlocking { repo.export(password) }
        try {
            runBlocking { repo.restore(backup, "falschfalsch".toCharArray()) }
            fail("Wrong password accepted")
        } catch (_: IllegalArgumentException) {}
        assertEquals(before, runBlocking { repo.current() })
        val corrupt = backup.clone()
        corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
        try {
            runBlocking { repo.restore(corrupt, password) }
            fail("Corrupt archive accepted")
        } catch (_: IllegalArgumentException) {}
        assertEquals(before, runBlocking { repo.current() })
        runBlocking { repo.restore(backup, password) }
        val restored = runBlocking { repo.current() }
        assertEquals(before.assessments, restored.assessments)
        assertEquals(before.studies, restored.studies)
        assertEquals(exam.id, restored.documents.single().assessmentId)
        android.graphics.pdf
            .PdfRenderer(
                android.os.ParcelFileDescriptor.open(
                    File(repo.documentsDir, restored.documents.single().file),
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                )
            )
            .use { assertEquals(2, it.pageCount) }
        ui.onNodeWithContentDescription("Zurück").performClick()
        ui.onNodeWithTag("nav-Meine Noten").performClick()
        tap("Mathematik")
        ui.onNodeWithText("2,00").assertExists()
    }

    @Test
    fun onboardingUnsupportedStateAndSettings() {
        assertEquals("com.streberalarm.app", ui.activity.packageName)
        val appInfo = ui.activity.applicationInfo
        assertEquals("StreberAlarm", appInfo.loadLabel(ui.activity.packageManager).toString())
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
        ui.onNodeWithText("Willkommen bei StreberAlarm").assertExists()
        choose("Bundesland", "Berlin")
        ui.onNodeWithText("Noch nicht unterstützt").assertExists()
        ui.onNodeWithText("Weiter").performScrollTo().assertIsNotEnabled()
        choose("Bundesland", "Bayern")
        tap("Weiter")
        tap("Los geht’s")
        ui.waitUntil(5000) { runBlocking { repo.current().active() != null } }
        ui.onNodeWithContentDescription("Einstellungen").performClick()
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
        openWheel("Fach für Stunde 3")
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
    fun wheelScrollCancelAndLabelledRealschuleProfilePersist() {
        runBlocking { repo.update { SchoolData() } }
        ui.waitForIdle()
        choose("Schulart", "Realschule")
        choose("Schulzweig", "II · Wirtschaft und Rechnungswesen")
        tap("Weiter")
        openWheel("Deine Klasse")
        capture("class-wheel")
        ui.onNodeWithTag("wheel-Deine Klasse").performTouchInput { swipeUp(durationMillis = 500) }
        ui.waitForIdle()
        tap("Abbrechen")
        ui.onNodeWithTag("picker-Deine Klasse").assertTextContains("8. Klasse")
        choose("Deine Klasse", "10. Klasse")
        openWheel("Deine Klasse")
        ui.onNodeWithTag("wheel-Deine Klasse")
            .assert(
                androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                    "10. Klasse",
                )
            )
        tap("Übernehmen")
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
        choose("Schulzweig", "II · Wirtschaft und Rechnungswesen")
        tap("Weiter")
        choose("Deine Klasse", "5. Klasse")
        ui.onNodeWithText("In Klasse 5 und 6 gibt es noch keinen Schulzweig.").assertExists()
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
    fun settingsWheelDistinguishesProfilesAndKeepsTheirData() {
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
        choose("Dein Schuljahr", "Realschule · 8. Klasse · 2026 · II · Ganzes Schuljahr · Profil 3")
        ui.waitUntil(5000) { runBlocking { repo.current().activeProfileId == third.id } }
        assertEquals(3, runBlocking { repo.current().profiles.size })
        ui.onNodeWithText("Dein Schulzweig: II · Wirtschaft und Rechnungswesen")
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun gradeEntryCanPhotographTwoPagesWithoutLosingTheChosenGrade() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("pm grant com.streberalarm.app android.permission.CAMERA")
        val exam =
            Assessment(
                subjectId = subject.id,
                title = "Note mit Foto",
                date = "2026-10-01",
                stage = Stage.WRITTEN,
                estimate = 3,
            )
        runBlocking { repo.update { it.copy(assessments = listOf(exam)) } }
        openArchive()
        tap("Note mit Foto")
        tap("Note eintragen")
        ui.onNodeWithTag("grade-4").performClick()
        tap("Probe fotografieren")
        tap("Google ML Kit")
        ui.onNodeWithText("Google-Scanner: Datenschutz").assertExists()
        tap("Zur Scanner-Auswahl")
        tap("Abbrechen")
        ui.onNodeWithTag("grade-4").assertIsSelected()
        tap("Probe fotografieren")
        tap("Lokaler Scanner")
        repeat(2) {
            ui.waitUntil(15000) {
                runCatching {
                        ui.onNodeWithText("Seite fotografieren").assertIsEnabled()
                        true
                    }
                    .getOrDefault(false)
            }
            tap("Seite fotografieren")
            ui.waitUntil(15000) {
                ui.onAllNodesWithText("90° drehen").fetchSemanticsNodes().isNotEmpty()
            }
            tap("Seite übernehmen")
        }
        tap("2 Seiten als PDF speichern")
        ui.waitUntil(10000) { repo.state.value!!.documents.size == 1 }
        ui.onNodeWithTag("grade-4").assertIsSelected()
        ui.onNodeWithText("1 Unterlage gespeichert").assertExists()
        assertNull(repo.state.value!!.assessments.single().actual)
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("grade-4").assertIsSelected()
        capture("grade-with-photo")
        tap("Test speichern")
        ui.waitUntil(5000) { repo.state.value!!.assessments.single().actual == 4 }
        val document = repo.state.value!!.documents.single()
        assertEquals(exam.id, document.assessmentId)
        android.graphics.pdf
            .PdfRenderer(
                android.os.ParcelFileDescriptor.open(
                    File(repo.documentsDir, document.file),
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                )
            )
            .use { assertEquals(2, it.pageCount) }
    }

    @Test
    fun cameraTwoPagesRotateAndLocalPdf() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("pm grant com.streberalarm.app android.permission.CAMERA")
        val e = Assessment(subjectId = subject.id, title = "Scanablauf", date = "2026-10-01")
        runBlocking { repo.update { it.copy(assessments = listOf(e)) } }
        openArchive()
        tap("Scanablauf")
        tap("Unterlagen")
        tap("Arbeit scannen")
        ui.onNodeWithText("Lokaler Scanner").performClick()
        repeat(2) {
            ui.waitUntil(15000) {
                ui.onAllNodesWithText("Seite fotografieren").fetchSemanticsNodes().isNotEmpty() &&
                    runCatching {
                            ui.onNodeWithText("Seite fotografieren").assertIsEnabled()
                            true
                        }
                        .getOrDefault(false)
            }
            tap("Seite fotografieren")
            ui.waitUntil(15000) {
                ui.onAllNodesWithText("90° drehen").fetchSemanticsNodes().isNotEmpty()
            }
            tap("90° drehen")
            tap("Seite übernehmen")
        }
        ui.waitUntil(5000) {
            ui.onAllNodesWithText("2 Seiten als PDF speichern").fetchSemanticsNodes().isNotEmpty()
        }
        tap("2 Seiten als PDF speichern")
        ui.waitUntil(10000) { runBlocking { repo.current().documents.isNotEmpty() } }
        val document = runBlocking { repo.current().documents.single() }
        assertEquals(e.id, document.assessmentId)
        android.graphics.pdf
            .PdfRenderer(
                android.os.ParcelFileDescriptor.open(
                    File(repo.documentsDir, document.file),
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                )
            )
            .use { assertEquals(2, it.pageCount) }
        tap("Unterlagen")
        tap("Scan 1.pdf")
        ui.waitUntil(5000) {
            ui.onAllNodesWithText("Seite 1 von 2").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun persistentTimerAndNotificationScheduleSurviveRepositoryReopen() {
        val exam =
            Assessment(
                subjectId = subject.id,
                title = "Persistenzprüfung",
                date = LocalDate.now().plusDays(7).toString(),
            )
        runBlocking {
            repo.update { it.copy(assessments = listOf(exam)) }
            repo.start(exam.id)
        }
        val second = Repository(ui.activity.applicationContext)
        assertEquals(exam.id, runBlocking { second.current().studies.single().assessmentId })
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        ui.onNodeWithText("Lerntimer läuft").assertExists()
        val alarms =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
                .executeShellCommand("dumpsys alarm")
        assertTrue(alarms.contains("com.streberalarm.app"))
        runBlocking { repo.stop() }
    }

    @Test
    fun googleScannerRequiresDisclosureBeforeInitialization() {
        val e = Assessment(subjectId = subject.id, title = "Scanner-Auswahl", date = "2026-10-01")
        runBlocking { repo.update { it.copy(assessments = listOf(e)) } }
        openArchive()
        tap("Scanner-Auswahl")
        tap("Unterlagen")
        tap("Arbeit scannen")
        ui.onNodeWithText("Google ML Kit").performClick()
        ui.onNodeWithText("Google-Scanner: Datenschutz").assertExists()
        ui.onNodeWithText("Google-Scanner starten").assertExists()
        val providers =
            ui.activity.packageManager
                .getPackageInfo(
                    "com.streberalarm.app",
                    android.content.pm.PackageManager.GET_PROVIDERS,
                )
                .providers
                .orEmpty()
        assertFalse(providers.any { it.name.contains("MlKitInitProvider") })
        val getter =
            Class.forName("com.google.mlkit.common.sdkinternal.MlKitContext")
                .getMethod("getInstance")
        try {
            getter.invoke(null)
            fail("ML Kit initialized before confirmation")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is IllegalStateException)
        }
        ui.onNodeWithText("Zur Scanner-Auswahl").performClick()
        ui.onNodeWithText("Lokaler Scanner").assertExists()
        ui.onNodeWithText("Abbrechen").performClick()
        assertTrue(runBlocking { repo.current().documents.isEmpty() })
    }

    @Test
    fun zPersistentFixtureForProcessDeathAndReboot() {
        val e =
            Assessment(
                subjectId = subject.id,
                title = "Lernen für Bruchrechnung",
                date = LocalDate.now().plusDays(7).toString(),
                material = "Brüche kürzen, erweitern und addieren",
                estimate = 2,
            )
        runBlocking {
            repo.update { it.copy(assessments = listOf(e)) }
            repo.start(e.id)
        }
        ui.waitUntil(5000) {
            ui.onAllNodesWithText("Lerntimer läuft").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
