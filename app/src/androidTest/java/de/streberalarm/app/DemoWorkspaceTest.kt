package de.streberalarm.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.streberalarm.core.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemoWorkspaceTest {
    @Test fun realDataAndFilesSurviveRepeatLoadDemoEditsRestartAndDelete() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Repository(context).use { repo ->
            repo.deleteDemo()
            val profile = Profile(id = "real-sentinel")
            val selectedProfile = Profile(id = "selected-real", grade = 9)
            val subject = Subject(id = "real-subject", profileId = profile.id, name = "Echte Mathematik")
            val assessment = Assessment(id = "real-exam", subjectId = subject.id, title = "Privat", date = "2026-10-15")
            val file = "00000000-0000-0000-0000-000000000001.pdf"
            val bytes = "%PDF-1.7\nprivate sentinel".toByteArray()
            File(repo.documentsDir, file).writeBytes(bytes)
            val original = SchoolData(profiles = listOf(profile, selectedProfile),
                activeProfileId = selectedProfile.id,
                subjects = listOf(subject), assessments = listOf(assessment),
                documents = listOf(Document(assessmentId = assessment.id, name = "Privat.pdf",
                    mime = "application/pdf", file = file, size = bytes.size.toLong())))
            repo.update { original }
            val savedReal = repo.current()
            val realGeneration = repo.vocabularyGeneration.value
            repo.loadDemo()
            assertTrue(repo.isDemo.value)
            val seeded = repo.current()
            assertTrue(seeded.profiles.isNotEmpty())
            assertTrue(seeded.documents.size >= 2)
            assertTrue(seeded.timetables.single().blocks.size >= 20)
            assertTrue(seeded.assessments.any { it.stage == Stage.GRADED })
            val demoRoundtrip = Backup.restore(Backup.export(seeded, "demo-proof-password".toCharArray()) {
                File(repo.documentsDir, it).readBytes()
            }, "demo-proof-password".toCharArray())
            assertEquals(seeded, demoRoundtrip.data)
            seeded.documents.forEach { document ->
                assertArrayEquals(File(repo.documentsDir, document.file).readBytes(), demoRoundtrip.files.getValue(document.file))
            }
            val demoFile = File(repo.documentsDir, seeded.documents.first().file)
            assertTrue(demoFile.exists())
            val demoStaging = File(context.cacheDir, "demo-scans").apply { mkdirs() }
            File(demoStaging, "unsaved.jpg").writeBytes(byteArrayOf(1, 2, 3))
            val realStaging = File(context.cacheDir, "scan-real-sentinel.jpg")
            realStaging.writeBytes(byteArrayOf(9, 8, 7))
            try {
                repo.export("demo-proof-password".toCharArray())
                fail("Demo must not export a manual backup")
            } catch (_: IllegalArgumentException) { }
            try {
                repo.update(realGeneration) { SchoolData() }
                fail("A queued real write must not reach the demo")
            } catch (_: IllegalArgumentException) { }
            repo.update { it.copy(assessments = it.assessments.map { exam ->
                if (exam.id == seeded.assessments.first().id) exam.copy(title = "Meine Demoänderung") else exam
            }) }
            val edited = repo.current()
            repo.loadDemo()
            assertEquals(edited, repo.current())
            Repository(context).use { reopened ->
                reopened.current()
                assertTrue(reopened.isDemo.value)
                assertEquals(edited, reopened.current())
                reopened.returnToReal()
                assertEquals(savedReal, reopened.current())
                assertArrayEquals(bytes, File(reopened.documentsDir, file).readBytes())
                val realBackup = reopened.export("demo-proof-password".toCharArray())
                assertEquals(savedReal, Backup.restore(realBackup, "demo-proof-password".toCharArray()).data)
                reopened.loadDemo()
                assertEquals(edited, reopened.current())
                try {
                    reopened.restore(realBackup, "demo-proof-password".toCharArray())
                    fail("Real backup must not replace the active demo")
                } catch (_: IllegalArgumentException) { }
                assertEquals(edited, reopened.current())
                reopened.deleteDemo()
                assertFalse(reopened.isDemo.value)
                assertEquals(savedReal, reopened.current())
                assertArrayEquals(bytes, File(reopened.documentsDir, file).readBytes())
                assertFalse(demoFile.exists())
                assertFalse(demoStaging.exists())
                assertArrayEquals(byteArrayOf(9, 8, 7), realStaging.readBytes())
                reopened.deleteDemo()
                assertEquals(savedReal, reopened.current())
                realStaging.delete()
                Unit
            }
        }
    }
}
