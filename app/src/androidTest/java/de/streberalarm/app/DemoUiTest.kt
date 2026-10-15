package de.streberalarm.app

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import de.streberalarm.core.*
import java.io.File
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemoUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun settingsCanLoadReturnAndDeleteDemo() {
        val repo = ui.activity.repo
        runBlocking { repo.deleteDemo() }
        ui.waitForIdle()
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        ui.onNodeWithText("Testdaten laden").performClick()
        ui.waitUntil(10_000) { repo.isDemo.value }
        ui.onNodeWithText("NotenManager · DEMO").assertExists()
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        ui.onNodeWithText("Echte Daten anzeigen").performClick()
        ui.waitUntil(10_000) { !repo.isDemo.value }
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        ui.onNodeWithText("Testdaten laden").performClick()
        ui.waitUntil(10_000) { repo.isDemo.value }
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        ui.onNodeWithText("Testdaten löschen").performClick()
        ui.onNodeWithText("Jetzt löschen").performClick()
        ui.waitUntil(10_000) { !repo.isDemo.value }
    }

    @Test fun emptyRealWorkspaceCanOpenSettingsAndReturnFromDemo() {
        val repo = ui.activity.repo
        runBlocking { repo.deleteDemo(); repo.update { SchoolData() } }
        ui.waitForIdle()
        ui.onNodeWithText("Willkommen bei NotenManager").assertExists()
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        ui.onNodeWithText("Datenschutz & Rechtsgrundlagen").performClick()
        ui.onNodeWithText("Wer kann meine Daten sehen?").assertExists()
        ui.onNodeWithContentDescription("Zurück").performClick()
        ui.onNodeWithText("Testdaten laden").performClick()
        ui.waitUntil(10_000) { repo.isDemo.value }
        ui.onNodeWithContentDescription("Einstellungen").performClick()
        ui.onNodeWithText("Echte Daten anzeigen").performClick()
        ui.waitUntil(10_000) { !repo.isDemo.value }
        ui.onNodeWithText("Willkommen bei NotenManager").assertExists()
        assertTrue(runBlocking { repo.current().profiles.isEmpty() })
        runBlocking { repo.deleteDemo() }
    }

    @Test fun obsoleteRealNotificationLeavesDemoAndRealDataUntouched() {
        val repo = ui.activity.repo
        val profile = Profile(id = "notification-real")
        val subject = Subject(id = "notification-subject", profileId = profile.id, name = "Mathematik")
        val exam = Assessment(id = "notification-exam", subjectId = subject.id,
            title = "Echte Benachrichtigung", date = "2026-10-09", stage = Stage.WRITTEN)
        runBlocking {
            repo.deleteDemo()
            repo.update { SchoolData(profiles = listOf(profile), activeProfileId = profile.id,
                subjects = listOf(subject), assessments = listOf(exam)) }
            repo.loadDemo()
        }
        val demo = runBlocking { repo.current() }
        val switched = AtomicBoolean(false)
        repo.beforeRealPublication = { switched.set(true) }
        try {
            ui.activity.estimateRequest.value = exam.id
            ui.waitUntil(10_000) { ui.activity.estimateRequest.value == null }
            assertTrue(repo.isDemo.value)
            assertEquals(demo, runBlocking { repo.current() })
            assertTrue(runBlocking { repo.realCurrent().assessments.any { it.id == exam.id } })
            assertFalse(switched.get())
        } finally {
            repo.beforeRealPublication = null
            runBlocking { repo.deleteDemo() }
        }
    }

    @Test fun demoGradePhotoRotationCropSurvivesRecreationAndCommitsWithGrade() {
        val repo = ui.activity.repo
        val context = ui.activity.applicationContext
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("pm grant com.streberalarm.app android.permission.CAMERA")
        runBlocking { repo.deleteDemo(); repo.loadDemo() }
        val before = runBlocking { repo.current() }
        val exam = before.assessments.single { it.title == "Simple Past" }
        val profile = before.active()!!
        val generation = repo.vocabularyGeneration.value
        try {
            ui.waitForIdle()
            ui.onNodeWithTag("nav-Meine Noten").performClick()
            ui.onNodeWithTag("subject-${exam.subjectId}").performScrollTo().performClick()
            ui.onNodeWithTag("grade-menu-${exam.id}").performScrollTo().performClick()
            ui.onNodeWithText("Bearbeiten").performClick()
            ui.onNodeWithTag("grade-1").assertIsSelected()
            ui.onNodeWithText("Probe fotografieren").performScrollTo().performClick()
            ui.onNodeWithText("Lokaler Scanner").performClick()
            ui.waitUntil(15_000) { runCatching {
                ui.onNodeWithText("Seite fotografieren").assertIsEnabled(); true
            }.getOrDefault(false) }
            ui.onNodeWithText("Seite fotografieren").performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("90° drehen").fetchSemanticsNodes().isNotEmpty() }
            val photo = File(context.cacheDir, "grade-scan-${runBlocking {
                repo.openGradeDraft(profile.id, exam.subjectId, exam.id, generation)
            }.id}").listFiles()!!.single { it.extension == "jpg" }
            val asymmetric = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            for (y in 0 until asymmetric.height) for (x in 0 until asymmetric.width) {
                asymmetric.setPixel(x, y,
                    if (x < 60) android.graphics.Color.RED else android.graphics.Color.BLUE)
            }
            photo.outputStream().use { assertTrue(asymmetric.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            asymmetric.recycle()
            ui.activityRule.scenario.recreate()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("90° drehen").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("90° drehen").performScrollTo().performClick()
            ui.onNode(hasContentDescription("Range start") and
                hasAnyAncestor(hasTestTag("real-crop-vertical")), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.SetProgress) { it(0.65f) }
            ui.activityRule.scenario.recreate()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("Seite übernehmen").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Seite übernehmen").performScrollTo().performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText("1 Seiten als PDF speichern").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Seite fotografieren").performScrollTo().performClick()
            ui.waitUntil(15_000) { ui.onAllNodesWithText("Seite übernehmen").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Seite übernehmen").performScrollTo().performClick()
            ui.waitUntil(10_000) { ui.onAllNodesWithText("2 Seiten als PDF speichern").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("2 Seiten als PDF speichern").performScrollTo().performClick()
            val draft = runBlocking { repo.openGradeDraft(profile.id, exam.subjectId, exam.id, generation) }
            ui.waitUntil(10_000) { runBlocking { repo.gradeDraftDocuments(draft.id).size == 1 } }
            assertEquals(before.documents.size, repo.state.value!!.documents.size)
            val doc = runBlocking { repo.gradeDraftDocuments(draft.id).single() }
            val pdf = File(context.filesDir, "demo-grade-drafts/${draft.id}/${doc.file}")
            PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertEquals(2, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    assertEquals(80, page.width)
                    assertTrue(page.height in 30..55)
                    val rendered = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val center = rendered.getPixel(rendered.width / 2, rendered.height / 2)
                        assertTrue(android.graphics.Color.blue(center) > android.graphics.Color.red(center) * 2)
                    } finally { rendered.recycle() }
                }
            }
            ui.onNodeWithTag("grade-1").assertIsSelected()
            ui.onNodeWithText("Note speichern").performScrollTo().performClick()
            ui.waitUntil(10_000) { repo.state.value!!.documents.size == before.documents.size + 1 }
            assertEquals(exam.id, repo.state.value!!.assessments.single { it.id == exam.id }.id)
            assertEquals(1, repo.state.value!!.assessments.single { it.id == exam.id }.actual)
            assertTrue(before.documents.all { original -> repo.state.value!!.documents.any { it.id == original.id } })
        } finally {
            runBlocking { repo.deleteDemo() }
        }
    }
    @Test fun fullCropCompressionOwnsBitmapAcrossPreviewRecreation() {
        val repo = ui.activity.repo
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("pm grant com.streberalarm.app android.permission.CAMERA")
        runBlocking { repo.deleteDemo(); repo.loadDemo() }
        val before = repo.state.value!!
        val exam = before.assessments.single { it.title == "Simple Past" }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val compressed = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            ui.onNodeWithTag("nav-Meine Noten").performClick()
            ui.onNodeWithTag("subject-${exam.subjectId}").performScrollTo().performClick()
            ui.onNodeWithTag("grade-menu-${exam.id}").performScrollTo().performClick()
            ui.onNodeWithText("Bearbeiten").performClick()
            ui.onNodeWithText("Probe fotografieren").performScrollTo().performClick()
            ui.onNodeWithText("Lokaler Scanner").performClick()
            ui.waitUntil(15000) { runCatching { ui.onNodeWithText("Seite fotografieren").assertIsEnabled(); true }.getOrDefault(false) }
            ui.onNodeWithText("Seite fotografieren").performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithText("Seite übernehmen").fetchSemanticsNodes().isNotEmpty() }
            ScanTiming.beforePageWrite = { entered.countDown(); check(release.await(15, java.util.concurrent.TimeUnit.SECONDS)) }
            ScanTiming.afterPageWrite = { compressed.set(true) }
            ScanTiming.afterPageWorker = { finished.countDown() }
            ui.onNodeWithText("Seite übernehmen").performScrollTo().performClick()
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            ui.onNodeWithText("90° drehen").assertIsNotEnabled()
            ui.onNodeWithText("Aufnahme verwerfen").assertIsNotEnabled()
            ui.activityRule.scenario.recreate()
            release.countDown()
            assertTrue(finished.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("Worker compression survives disposal of its former preview", compressed.get())
            ui.onNodeWithText("Seite übernehmen").performScrollTo().assertIsEnabled()
            assertEquals(before.documents, repo.state.value!!.documents)
        } finally {
            release.countDown()
            ScanTiming.beforePageWrite = null
            ScanTiming.afterPageWrite = null
            ScanTiming.afterPageWorker = null
            runBlocking { repo.deleteDemo() }
        }
    }
    @Test fun draftGoogleScannerStillRequiresDisclosureBeforeInitialization() {
        val repo = ui.activity.repo
        runBlocking { repo.deleteDemo(); repo.loadDemo() }
        val before = repo.state.value!!
        val exam = before.assessments.single { it.title == "Simple Past" }
        try {
            ui.onNodeWithTag("nav-Meine Noten").performClick()
            ui.onNodeWithTag("subject-${exam.subjectId}").performScrollTo().performClick()
            ui.onNodeWithTag("grade-menu-${exam.id}").performScrollTo().performClick()
            ui.onNodeWithText("Bearbeiten").performClick()
            ui.onNodeWithText("Probe fotografieren").performScrollTo().performClick()
            ui.onNodeWithText("Google ML Kit").performClick()
            ui.onNodeWithText("Google-Scanner: Datenschutz").assertExists()
            ui.onNodeWithText("Google-Scanner starten").assertExists()
            val providers = ui.activity.packageManager.getPackageInfo("com.streberalarm.app", android.content.pm.PackageManager.GET_PROVIDERS).providers.orEmpty()
            assertFalse(providers.any { it.name.contains("MlKitInitProvider") })
            val getter = Class.forName("com.google.mlkit.common.sdkinternal.MlKitContext").getMethod("getInstance")
            try { getter.invoke(null); fail("ML Kit initialized before explicit confirmation") }
            catch (e: java.lang.reflect.InvocationTargetException) { assertTrue(e.cause is IllegalStateException) }
            ui.onNodeWithText("Zur Scanner-Auswahl").performClick()
            ui.onNodeWithText("Lokaler Scanner").assertExists()
            ui.onNode(hasText("Abbrechen") and hasAnyAncestor(isDialog())).performClick()
            assertEquals(before.documents, repo.state.value!!.documents)
        } finally { runBlocking { repo.deleteDemo() } }
    }
}
