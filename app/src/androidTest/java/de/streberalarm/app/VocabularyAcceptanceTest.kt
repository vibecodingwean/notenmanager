package de.streberalarm.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import de.streberalarm.core.*
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Fictional data only. Compiling these journeys is not evidence of executing them. */
@RunWith(AndroidJUnit4::class)
class VocabularyAcceptanceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val repo
        get() = ui.activity.repo

    private lateinit var owner: Profile

    @Before
    fun prepare() {
        owner = Profile(id = "vocabulary-owner")
        ui.activity.appearance.select(
            AppLook.fromId(InstrumentationRegistry.getArguments().getString("look"))
        )
        ui.activity.appearance.animate(false)
        runBlocking {
            repo.deleteDemo()
            repo.update { SchoolData(profiles = listOf(owner), activeProfileId = owner.id) }
        }
        ui.waitForIdle()
    }

    @After
    fun resetAppearance() {
        repo.beforeVocabularyCommit = null
        ui.activity.appearance.select(AppLook.CLASSIC)
    }

    private fun node(tag: String) = ui.onNodeWithTag("vocab-$tag")

    private fun visible(n: SemanticsNodeInteraction): SemanticsNodeInteraction {
        runCatching { n.performScrollTo() }
        return n
    }

    private fun visibleTag(tag: String): SemanticsNodeInteraction {
        if (
            ui.onAllNodesWithTag("vocab-$tag").fetchSemanticsNodes().isEmpty() &&
                ui.onAllNodesWithTag("vocab-list").fetchSemanticsNodes().isNotEmpty()
        ) {
            ui.onNodeWithTag("vocab-list").performScrollToNode(hasTestTag("vocab-$tag"))
        }
        return visible(node(tag))
    }

    private fun tap(tag: String) {
        if (tag == "rename") {
            ui.onNodeWithTag("vocab-list").performScrollToNode(
                hasTestTag("vocab-rename") and
                    SemanticsMatcher("placed touch target") { it.layoutInfo.isPlaced }
            )
            node(tag).assertIsDisplayed().assertIsEnabled()
        }
        visibleTag(tag).performClick()
        ui.waitForIdle()
        ui.waitUntil(10000) { ui.onAllNodesWithTag("vocab-saving").fetchSemanticsNodes().isEmpty() }
        ui.waitForIdle()
    }

    private fun text(tag: String, value: String) {
        visibleTag(tag).performTextReplacement(value)
    }

    private fun enter() {
        ui.onNodeWithTag("nav-Lernen").performClick()
        visible(ui.onNodeWithTag("learn-vocabulary")).performClick()
        ui.waitForIdle()
    }

    @Test
    fun vocabularyEntryReturnsToChronologicalLearningPage() {
        enter()
        tap("exit")
        ui.onNodeWithTag("learn-plan").assertExists()
        ui.onNodeWithTag("learn-vocabulary").assertExists()
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("learn-plan").assertExists()
        ui.onNodeWithText("Alle Proben").assertDoesNotExist()
    }

    private fun fixture(words: Int = 1) {
        runBlocking {
            repo.updateVocabulary(repo.vocabularyGeneration.value) { source ->
                var d = Vocabulary.createCollection(source, owner.id, "animals", "Tiere")
                listOf("Hund" to "dog", "Katze" to "cat", "Vogel" to "bird")
                    .take(words)
                    .forEachIndexed { i, w ->
                        d =
                            Vocabulary.saveCard(
                                d,
                                owner.id,
                                "animals",
                                "word-$i",
                                null,
                                w.first,
                                w.second,
                            )
                    }
                d
            }
        }
    }

    private fun start() {
        enter()
        tap("collection-animals")
        tap("start")
    }

    private fun session() =
        repo.state.value!!.vocabularySessions.single { it.profileId == owner.id }

    @Test
    fun flipRoundPersistsRevealAndSelfGradeAcrossRecreation() {
        fixture(1)
        enter()
        tap("collection-animals")
        tap("style-FLIP")
        tap("start")
        node("prompt").assertTextContains("Hund")
        tap("flip")
        assertEquals(VocabularyPhase.REVEALED, session().phase)
        ui.activityRule.scenario.recreate()
        ui.waitForIdle()
        node("flip-answer").assertTextContains("dog")
        tap("practice")
        assertEquals(1L, repo.state.value!!.vocabularyProgress.single().wrongAttempts)
        tap("next")
        tap("flip")
        tap("known")
        tap("next")
        node("summary").assertExists()
        assertEquals(1L, repo.state.value!!.vocabularyProgress.single().correctAttempts)
    }

    private fun answer(value: String, ime: Boolean = false) {
        val turn = session().turn
        text("answer", value)
        if (ime) visible(node("answer")).performImeAction() else tap("check")
        ui.waitUntil(5000) { session().turn == turn + 1 }
        ui.waitForIdle()
        node("feedback").assertExists()
        node("answer").assertDoesNotExist()
    }

    private fun answerCurrent() {
        val word = repo.state.value!!.vocabularyCards.single { it.id == session().currentCardId }
        answer(word.english)
        tap("next")
    }

    @Test
    fun collectionCardCrudValidationAlternativesSaveNextAndDiscard() {
        enter()
        tap("add-collection")
        node("save-collection").assertIsNotEnabled()
        text("collection-name", "x".repeat(81))
        node("save-collection").assertIsNotEnabled()
        text("collection-name", "Tiere")
        ui.activityRule.scenario.recreate()
        node("collection-name").assertTextContains("Tiere")
        tap("save-collection")
        tap("add-card")
        node("save-card").assertIsNotEnabled()
        text("german", "x".repeat(161))
        text("english", "dog")
        node("save-card").assertIsNotEnabled()
        text("german", "Hund")
        text("english", "x".repeat(121))
        node("save-card").assertIsNotEnabled()
        text("english", "dog")
        tap("add-alternative")
        text("alternative-0", " DOG ")
        node("save-card").assertIsNotEnabled()
        text("alternative-0", "hound")
        tap("save-next")
        ui.waitUntil(5000) { repo.state.value!!.vocabularyCards.size == 1 }
        assertEquals(
            "",
            node("german")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText]
                .text,
        )
        text("german", " hund ")
        text("english", "canine")
        node("validation").assertExists()
        node("save-card").assertIsNotEnabled()
        text("german", "Katze")
        text("english", "cat")
        tap("save-card")
        val cards = repo.state.value!!.vocabularyCards
        assertEquals(2, cards.size)
        assertEquals(listOf("hound"), cards.single { it.german == "Hund" }.alternatives)
        val dog = cards.single { it.german == "Hund" }
        tap("edit-${dog.id}")
        text("english", "doggy")
        ui.onNodeWithContentDescription("Zurück").performClick()
        tap("cancel-confirm")
        node("english").assertTextContains("doggy")
        tap("cancel")
        tap("confirm")
        assertEquals("dog", repo.state.value!!.vocabularyCards.single { it.id == dog.id }.english)
        tap("delete-${dog.id}")
        tap("cancel-confirm")
        assertTrue(repo.state.value!!.vocabularyCards.any { it.id == dog.id })
        tap("delete-${dog.id}")
        tap("confirm")
        ui.waitUntil(5000) { repo.state.value!!.vocabularyCards.none { it.id == dog.id } }
        tap("rename")
        text("collection-name", "Tierwörter")
        tap("save-collection")
        assertEquals("Tierwörter", repo.state.value!!.vocabularyCollections.single().name)
        tap("delete-collection")
        tap("cancel-confirm")
        tap("delete-collection")
        tap("confirm")
        ui.waitUntil(5000) { repo.state.value!!.vocabularyCollections.isEmpty() }
        assertTrue(repo.state.value!!.vocabularyCards.isEmpty())
        tap("exit")
        ui.onNodeWithTag("learn-plan").assertExists()
    }

    @Test
    fun wrongFeedbackTwoInterveningWordsRetryCorrectAndSummaryReplay() {
        fixture(3)
        start()
        node("prompt").assertTextEquals("Hund")
        answer("dgo", ime = true)
        node("feedback").assertTextEquals("Englisch: dog")
        node("attempts").assertTextEquals("Versuche: 0 richtig · 1 falsch")
        node("words").assertTextEquals("Wörter: 0 von 3 geschafft · 3 offen")
        tap("next")
        node("prompt").assertTextEquals("Katze")
        answerCurrent()
        node("prompt").assertTextEquals("Vogel")
        answerCurrent()
        node("prompt").assertTextEquals("Hund")
        answer(" DOG ")
        node("words").assertTextEquals("Wörter: 3 von 3 geschafft · 0 offen")
        ui.activityRule.scenario.recreate()
        node("feedback").assertExists()
        tap("next")
        node("summary").assertExists()
        node("attempts").assertTextEquals("Versuche: 3 richtig · 1 falsch")
        val original = session().selected
        tap("repeat-failed")
        assertEquals(listOf("word-0"), session().selected.map { it.cardId })
        node("attempts").assertTextEquals("Versuche: 0 richtig · 0 falsch")
        answerCurrent()
        node("summary").assertExists()
        node("repeat-failed").assertIsNotEnabled()
        tap("new-round")
        tap("start")
        while (session().phase != VocabularyPhase.COMPLETE) answerCurrent()
        val completed = session().selected
        tap("repeat-all")
        assertEquals(completed, session().selected)
        assertEquals(original.toSet(), session().selected.toSet())
    }

    @Test
    fun pauseDraftFeedbackRecreationSettingsAndActualRepositoryReopen() {
        fixture()
        start()
        text("answer", "draft")
        ui.activityRule.scenario.recreate()
        node("answer").assertTextContains("draft")
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        val replacedId = session().id
        runBlocking {
            repo.updateVocabulary(repo.vocabularyGeneration.value) { latest ->
                Vocabulary.startRound(
                    latest,
                    owner.id,
                    "animals",
                    "replacement-round",
                    VocabularyMode.ALL,
                    SchoolDate.parse(LocalDate.now().toString()),
                    replacedId,
                    replaceUnfinished = true,
                )
            }
        }
        ui.onNodeWithContentDescription("Zurück").performClick()
        ui.waitForIdle()
        assertEquals(
            "",
            node("answer")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText]
                .text,
        )
        tap("pause")
        tap("resume")
        // Pause preserves accepted state; an unsubmitted draft need not survive leaving the round.
        answer("wrong")
        val saved = session()
        tap("pause")
        ui.activityRule.scenario.recreate()
        tap("resume")
        assertEquals(saved, session())
        node("feedback").assertExists()
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        ui.onNodeWithContentDescription("Zurück").performClick()
        node("feedback").assertExists()
        ui.activityRule.scenario.recreate()
        node("feedback").assertExists()
        runBlocking {
            Repository(ui.activity.applicationContext).use { reopened ->
                assertEquals(saved, reopened.current().vocabularySessions.single())
            }
        }
        tap("next")
        answer("dog")
        tap("next")
        assertEquals(1L, session().wrongAttempts)
        assertEquals(1L, session().correctAttempts)
    }

    @Test
    fun blankDontKnowSingleCardRetryAndEmptyModesWithoutErrors() {
        fixture()
        start()
        node("check").assertIsNotEnabled()
        val before = session()
        node("answer").performImeAction()
        assertEquals(before, session())
        tap("dont-know")
        node("response").assertTextEquals("Deine Antwort: Weiß ich noch nicht")
        tap("next")
        node("prompt").assertTextEquals("Hund")
        answer("dog")
        tap("next")
        node("repeat-failed").assertIsEnabled()
        tap("repeat-all")
        answerCurrent()
        node("repeat-failed").assertIsNotEnabled()
        // Replace progress with a learned, future-due fixture; UI filters use domain dates.
        runBlocking {
            repo.updateVocabulary(repo.vocabularyGeneration.value) {
                it.copy(
                    vocabularyProgress =
                        listOf(
                            VocabularyProgress(
                                "word-0",
                                1,
                                1,
                                LocalDate.now().plusDays(1).toString(),
                                LocalDate.now().toString(),
                                2,
                                1,
                            )
                        )
                )
            }
        }
        tap("new-round")
        tap("mode-UNSURE")
        node("empty-mode").assertExists()
        node("start").assertIsNotEnabled()
        tap("mode-DUE")
        node("empty-mode").assertExists()
        node("start").assertIsNotEnabled()
        tap("mode-ALL")
        node("start").assertIsEnabled()
    }

    @Test
    fun pausedRoundReplacementConfirmationAndContentEditsInvalidateOnlyAffectedState() {
        fixture(2)
        start()
        answer("wrong")
        tap("pause")
        val paused = session()
        tap("start")
        tap("cancel-confirm")
        assertEquals(paused, session())
        tap("start")
        tap("confirm")
        assertNotEquals(paused.id, session().id)
        tap("pause")
        tap("edit-word-0")
        tap("save-card")
        assertTrue(repo.state.value!!.vocabularySessions.isNotEmpty()) // canonical no-op
        tap("edit-word-0")
        text("english", "hound")
        tap("save-next")
        node("german").assertExists()
        assertEquals(
            "",
            node("german")
                .fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText]
                .text,
        )
        tap("cancel")
        ui.waitUntil(5000) { repo.state.value!!.vocabularySessions.isEmpty() }
        assertTrue(repo.state.value!!.vocabularyProgress.none { it.cardId == "word-0" })
        assertEquals(2, repo.state.value!!.vocabularyCards.single { it.id == "word-0" }.revision)
        assertEquals(1, repo.state.value!!.vocabularyCards.single { it.id == "word-1" }.revision)
        node("resume").assertDoesNotExist()
    }

    @Test
    fun profileChangeReturnsToOwnedOverviewAndKeepsOtherPausedFeedback() {
        fixture()
        start()
        answer("wrong")
        val saved = session()
        val second = owner.copy(id = "other-owner")
        runBlocking {
            repo.update { it.copy(profiles = it.profiles + second, activeProfileId = second.id) }
        }
        ui.waitForIdle()
        node("add-collection").assertExists()
        node("resume").assertDoesNotExist()
        assertEquals(saved, repo.state.value!!.vocabularySessions.single())
        tap("add-collection")
        text("collection-name", "Andere Wörter")
        tap("save-collection")
        assertEquals(second.id, repo.state.value!!.vocabularyCollections.last().profileId)
        runBlocking { repo.update { it.copy(activeProfileId = owner.id) } }
        ui.waitForIdle()
        tap("resume")
        node("feedback").assertExists()
        assertEquals(saved, session())
    }

    @Test
    fun systemBackPausesRoundAndEditorDraftNeedsConfirmation() {
        fixture()
        start()
        val saved = session()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        ui.waitForIdle()
        node("resume").assertExists()
        assertEquals(saved, session())
        tap("add-card")
        text("german", "Elefant")
        ui.waitForIdle()
        node("german").assertTextContains("Elefant")
        // Hide IME first; the next system Back reaches the dirty editor handler.
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        ui.waitForIdle()
        if (ui.onAllNodesWithTag("vocab-confirm").fetchSemanticsNodes().isEmpty()) {
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            ui.waitForIdle()
        }
        tap("cancel-confirm")
        node("german").assertTextContains("Elefant")
        tap("cancel")
        tap("confirm")
        assertEquals(1, repo.state.value!!.vocabularyCards.size)
    }

    @Test
    fun storageFailureKeepsEditorAndAnswerAndSlowSaveLocksDuplicateInputs() {
        fixture(2)
        enter()
        tap("collection-animals")
        tap("add-card")
        text("german", "Fisch")
        text("english", "fish")
        repo.beforeVocabularyCommit = { throw java.io.IOException("Test: Speichern nicht möglich") }
        tap("save-card")
        node("error").assertTextContains("Test: Speichern nicht möglich")
        node("english").assertTextContains("fish")
        assertEquals(2, repo.state.value!!.vocabularyCards.size)
        repo.beforeVocabularyCommit = null
        tap("save-card")
        assertEquals(1, repo.state.value!!.vocabularyCards.count { it.german == "Fisch" })
        tap("start")
        val initial = session()
        val currentEnglish =
            repo.state.value!!.vocabularyCards.single { it.id == initial.currentCardId }.english
        val nextGerman =
            repo.state.value!!.vocabularyCards.single { it.id == initial.pending[1] }.german
        repo.beforeVocabularyCommit = {
            throw java.io.IOException("Test: Antwort nicht gespeichert")
        }
        text("answer", currentEnglish)
        tap("check")
        node("answer").assertTextContains(currentEnglish)
        assertEquals(initial, session())
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        repo.beforeVocabularyCommit = {
            started.complete(Unit)
            kotlinx.coroutines.withTimeout(20000) { release.await() }
        }
        visible(node("check")).performClick()
        runBlocking { kotlinx.coroutines.withTimeout(10000) { started.await() } }
        ui.waitForIdle()
        node("saving").assertExists()
        node("check").assertIsNotEnabled()
        node("answer").assertIsNotEnabled()
        node("check").performClick()
        val disabledField = node("answer").fetchSemanticsNode()
        if (
            disabledField.config.contains(
                androidx.compose.ui.semantics.SemanticsActions.OnImeAction
            )
        )
            // The high-level IME helper requires an enabled field. Invoke the retained
            // callback on the UI thread to exercise duplicate input while saving.
            node("answer").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.OnImeAction
            ) { it() }
        assertEquals(initial, session())
        release.complete(Unit)
        ui.waitUntil(10000) { session().turn == initial.turn + 1 }
        ui.waitForIdle()
        assertEquals(1L, session().correctAttempts)
        val feedback = session()
        val nextStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val nextRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
        repo.beforeVocabularyCommit = {
            nextStarted.complete(Unit)
            kotlinx.coroutines.withTimeout(20000) { nextRelease.await() }
        }
        visible(node("next")).performClick()
        runBlocking { kotlinx.coroutines.withTimeout(10000) { nextStarted.await() } }
        ui.waitForIdle()
        node("next").assertIsNotEnabled()
        node("next").performClick()
        assertEquals(feedback, session())
        nextRelease.complete(Unit)
        ui.waitUntil(10000) { session().phase == VocabularyPhase.ASKING }
        repo.beforeVocabularyCommit = null
        ui.waitForIdle()
        assertEquals(feedback.turn, session().turn)
        node("prompt").assertTextEquals(nextGerman)
    }

    @Test
    fun encryptedVocabularyFeedbackRestoreKeepsStateAndRejectsCorruption() {
        fixture()
        start()
        answer("wrong")
        val subject =
            Subject(id = "backup-subject", profileId = owner.id, name = "Englisch")
        val assessmentId = "backup-assessment"
        val sourceFile = java.io.File(ui.activity.cacheDir, "vocabulary-fixture.pdf")
        val document = android.graphics.pdf.PdfDocument()
        val page =
            document.startPage(
                android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 300, 1).create()
            )
        page.canvas.drawText("Fiktive Vokabelprobe", 20f, 30f, android.graphics.Paint())
        document.finishPage(page)
        sourceFile.outputStream().use { document.writeTo(it) }
        document.close()
        runBlocking {
            repo.update { current ->
                current.copy(
                    subjects = current.subjects + subject,
                    assessments =
                        current.assessments +
                            Assessment(
                                id = assessmentId,
                                subjectId = subject.id,
                                title = "Fiktive Probe",
                                date = LocalDate.now().toString(),
                                kind = Kind.TEST,
                                stage = Stage.GRADED,
                                actual = 2,
                            )
                )
            }
            repo.addLocalPdf(assessmentId, sourceFile)
        }
        sourceFile.delete()
        val saved = repo.state.value!!
        val savedDocumentBytes =
            java.io.File(repo.documentsDir, saved.documents.single().file).readBytes()
        val password = java.util.UUID.randomUUID().toString().toCharArray()
        runBlocking {
            val bytes = repo.export(password)
            try {
                repo.restore(
                    bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
                    password,
                )
                fail("Corrupt backup")
            } catch (_: Exception) {}
            assertEquals(saved, repo.state.value)
            val oldEpoch = repo.vocabularyGeneration.value
            repo.restore(bytes, password)
            assertTrue(repo.vocabularyGeneration.value > oldEpoch)
            val restored = repo.state.value!!
            assertEquals(
                saved.copy(documents = emptyList()),
                restored.copy(documents = emptyList()),
            )
            assertEquals(2, restored.assessments.single().actual)
            assertEquals(
                saved.documents.single().copy(file = restored.documents.single().file),
                restored.documents.single(),
            )
            assertArrayEquals(
                savedDocumentBytes,
                java.io.File(repo.documentsDir, restored.documents.single().file).readBytes(),
            )
        }
        password.fill('\u0000')
        ui.waitForIdle()
        node("resume").assertExists() // Restore returns to overview, even with identical IDs/turn.
        tap("resume")
        node("feedback").assertExists()
    }
}
