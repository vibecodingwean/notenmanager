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
class CompactGradePersistenceTest {
    @Test
    fun draftSurvivesRestartAndCommitsOnlyWithGrade() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = Profile(id = id(), year = 2026, grade = 8)
        val subject = Subject(id = id(), profileId = profile.id, name = "Entwurfsfach")
        val pdf = File(context.cacheDir, "grade-${id()}.pdf")
        pdf.writeText("%PDF-1.7\nGrade draft test\n")
        var initial: SchoolData? = null
        var draftId: String? = null
        try {
            Repository(context).use { repo ->
                initial = repo.current()
                repo.update {
                    it.copy(
                        profiles = it.profiles + profile,
                        activeProfileId = profile.id,
                        subjects = it.subjects + subject,
                    )
                }
                val generation = repo.vocabularyGeneration.value
                val draft =
                    repo.openGradeDraft(profile.id, subject.id, expectedGeneration = generation)
                draftId = draft.id
                repo.addLocalPdf(draft.id, pdf, generation)
                repo.updateGradeDraftForm(
                    draft.id,
                    "2026-10-14",
                    Kind.TEST,
                    "Brüche",
                    2,
                    "1",
                    generation,
                )
                assertFalse(repo.current().assessments.any { it.id == draft.id })
                assertFalse(repo.current().documents.any { it.assessmentId == draft.id })
            }
            Repository(context).use { reopened ->
                val generation = reopened.vocabularyGeneration.value
                val draft =
                    reopened.openGradeDraft(profile.id, subject.id, expectedGeneration = generation)
                assertEquals(draftId, draft.id)
                assertEquals("Brüche", draft.topic)
                assertEquals(2, draft.actual)
                assertEquals(1, reopened.gradeDraftDocuments(draft.id).size)
                try {
                    reopened.saveCompactGrade(
                        draft.id,
                        "2026-10-14",
                        Kind.TEST,
                        "Brüche",
                        9,
                        1.0,
                        generation,
                    )
                    fail("An invalid grade must not commit or discard the attachment")
                } catch (_: IllegalArgumentException) {}
                assertFalse(reopened.current().assessments.any { it.id == draft.id })
                assertEquals(1, reopened.gradeDraftDocuments(draft.id).size)
                reopened.beforeGradeDraftCleanup = { error("Simulated process interruption") }
                try {
                    reopened.saveCompactGrade(
                        draft.id,
                        "2026-10-14",
                        Kind.TEST,
                        "Brüche",
                        2,
                        1.0,
                        generation,
                    )
                    fail("The draft cleanup seam must interrupt after the durable grade commit")
                } catch (_: IllegalStateException) {}
                assertEquals(
                    1,
                    reopened.current().documents.count { it.id == draft.documents.single().id },
                )
                reopened.beforeGradeDraftCleanup = null
                val savedId =
                    reopened.saveCompactGrade(
                        draft.id,
                        "2026-10-14",
                        Kind.TEST,
                        "Brüche",
                        2,
                        1.0,
                        generation,
                    )
                assertEquals(draft.resultId, savedId)
                val saved = reopened.current()
                assertEquals(2, saved.assessments.single { it.id == savedId }.actual)
                val document = saved.documents.single { it.assessmentId == savedId }
                assertTrue(File(reopened.documentsDir, document.file).isFile)
                assertTrue(reopened.gradeDraftDocuments(draft.id).isEmpty())
                val cancelled =
                    reopened.openGradeDraft(profile.id, subject.id, expectedGeneration = generation)
                reopened.addLocalPdf(cancelled.id, pdf, generation)
                reopened.cancelGradeDraft(cancelled.id, generation)
                assertTrue(reopened.gradeDraftDocuments(cancelled.id).isEmpty())
                assertTrue(File(reopened.documentsDir, document.file).isFile)
                assertEquals(1, reopened.current().documents.count { it.assessmentId == savedId })
            }
        } finally {
            Repository(context).use { repo ->
                draftId?.let { repo.cancelGradeDraft(it, repo.vocabularyGeneration.value) }
                initial?.let { original -> repo.update { original } }
            }
            pdf.delete()
        }
    }

    @Test
    fun existingGradeEditKeepsHiddenFieldsAndNeverAssociatesWithGradedExam() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = Profile(id = id(), year = 2026, grade = 8)
        val subject = Subject(id = id(), profileId = profile.id, name = "Bestandsfach")
        val existing =
            Assessment(
                id = id(),
                subjectId = subject.id,
                title = "Eigener Titel",
                date = "2026-10-10",
                time = "08:30",
                kind = Kind.SCHOOLWORK,
                stage = Stage.GRADED,
                material = "",
                notes = "Alte Notiz",
                estimate = 3,
                actual = 3,
                weight = 2.5,
            )
        var initial: SchoolData? = null
        try {
            Repository(context).use { repo ->
                initial = repo.current()
                repo.update {
                    it.copy(
                        profiles = it.profiles + profile,
                        activeProfileId = profile.id,
                        subjects = it.subjects + subject,
                        assessments = it.assessments + existing,
                    )
                }
                val generation = repo.vocabularyGeneration.value
                val newDraft =
                    repo.openGradeDraft(profile.id, subject.id, expectedGeneration = generation)
                try {
                    repo.changeGradeDraft(newDraft.id, subject.id, existing.id, generation)
                    fail("A new note must not overwrite a graded exam")
                } catch (_: IllegalArgumentException) {}
                val edit = repo.openGradeDraft(profile.id, subject.id, existing.id, generation)
                repo.saveCompactGrade(
                    edit.id,
                    existing.date,
                    existing.kind,
                    existing.notes,
                    2,
                    existing.weight,
                    generation,
                )
                val sameDate = repo.current().assessments.single { it.id == existing.id }
                assertEquals(existing.title, sameDate.title)
                assertEquals(existing.time, sameDate.time)
                assertEquals(existing.notes, sameDate.notes)
                assertEquals("", sameDate.material)
                assertEquals(existing.estimate, sameDate.estimate)
                assertEquals(existing.weight, sameDate.weight, 0.0)
                val second = repo.openGradeDraft(profile.id, subject.id, existing.id, generation)
                repo.saveCompactGrade(
                    second.id,
                    "2026-10-11",
                    existing.kind,
                    existing.notes,
                    1,
                    existing.weight,
                    generation,
                )
                assertNull(repo.current().assessments.single { it.id == existing.id }.time)
                repo.cancelGradeDraft(newDraft.id, generation)
            }
        } finally {
            Repository(context).use { repo ->
                initial?.let { original -> repo.update { original } }
            }
        }
    }
}
