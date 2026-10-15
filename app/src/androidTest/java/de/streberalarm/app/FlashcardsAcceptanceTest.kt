package de.streberalarm.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.streberalarm.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlashcardsAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val repo
        get() = ui.activity.repo

    private val owner = Profile(id = "flash-owner")

    @Before
    fun prepare() {
        ui.activity.appearance.select(AppLook.CLASSIC)
        ui.activity.appearance.animate(false)
        runBlocking {
            repo.update { SchoolData(profiles = listOf(owner), activeProfileId = owner.id) }
        }
        ui.waitForIdle()
    }

    @After
    fun reset() {
        ui.activity.appearance.animate(true)
    }

    private fun tap(tag: String) {
        val matcher = hasTestTag("flash-$tag")
        if (ui.onAllNodesWithTag("flash-set").fetchSemanticsNodes().isNotEmpty())
            runCatching { ui.onNodeWithTag("flash-set").performScrollToNode(matcher) }
        if (ui.onAllNodesWithTag("flash-overview").fetchSemanticsNodes().isNotEmpty())
            runCatching { ui.onNodeWithTag("flash-overview").performScrollToNode(matcher) }
        runCatching { ui.onNode(matcher).performScrollTo() }
        ui.onNode(matcher).assertIsDisplayed().assertIsEnabled().performClick()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("flash-saving").fetchSemanticsNodes().isEmpty() }
        ui.waitForIdle()
    }

    @Test
    fun createFlipPracticeResumeAndResultPersist() {
        ui.onNodeWithTag("nav-Lernen").performClick()
        ui.onNodeWithTag("learn-flashcards").performClick()
        tap("add-set")
        ui.onNodeWithTag("flash-set-name").performTextInput("Biologie")
        tap("save-set")
        tap("add-card")
        ui.onNodeWithTag("flash-front").performTextInput("Was ist eine Zelle?")
        ui.onNodeWithTag("flash-back").performTextInput("Baustein des Lebens")
        tap("save-card")
        tap("start")
        ui.onNodeWithTag("flash-question").assertTextContains("Was ist eine Zelle?")
        tap("flip")
        assertEquals(FlashcardPhase.REVEALED, repo.state.value!!.flashcardSessions.single().phase)
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        ui.onNodeWithTag("flash-answer").assertTextContains("Baustein des Lebens")
        tap("practice")
        assertEquals(1L, repo.state.value!!.flashcardProgress.single().practice)
        ui.onNodeWithTag("flash-progress").assertTextContains("1 offen", substring = true)
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        assertEquals(1L, repo.state.value!!.flashcardProgress.single().practice)
        tap("next")
        tap("flip")
        tap("known")
        assertEquals(1L, repo.state.value!!.flashcardProgress.single().known)
        tap("next")
        ui.onNodeWithTag("flash-result").assertExists()
        assertEquals(FlashcardPhase.COMPLETE, repo.state.value!!.flashcardSessions.single().phase)
        tap("repeat-open")
        assertEquals(1, repo.state.value!!.flashcardSessions.single().selected.size)
    }

    @Test fun mutePersistsAndAppearanceToggleDisablesFlipMotion() {
        ui.runOnIdle {
            PracticeEffects(ui.activity).use { first ->
                first.changeMuted(true)
                PracticeEffects(ui.activity).use { assertTrue(it.muted) }
                ui.activity.appearance.animate(false)
                assertFalse(first.animationEnabled())
                first.changeMuted(false)
            }
        }
    }
}
