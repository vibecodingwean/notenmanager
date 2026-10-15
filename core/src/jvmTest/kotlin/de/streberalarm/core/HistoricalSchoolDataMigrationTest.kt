package de.streberalarm.core

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

class HistoricalSchoolDataMigrationTest {
    @Test
    fun fullHistoricalJsonRetainsEveryExistingRecordAndAttachmentThroughBackup() {
        val p = Profile(id = "history-profile")
        val s = Subject(id = "history-subject", profileId = p.id, name = "Mathematik")
        val e =
            Assessment(
                id = "history-exam",
                subjectId = s.id,
                title = "Alter eigener Titel",
                date = "2026-10-10",
                time = "08:30",
                material = "Brüche",
                notes = "Alte Notiz",
                kind = Kind.SCHOOLWORK,
                stage = Stage.GRADED,
                actual = 3,
                estimate = 2,
                weight = 2.5,
            )
        val document =
            Document(
                id = "history-document",
                assessmentId = e.id,
                name = "Probe.pdf",
                mime = "application/pdf",
                file = "aabb.pdf",
                size = 6,
            )
        val table =
            Timetable(
                id = "history-table",
                profileId = p.id,
                validFrom = "2026-09-01",
                anchorMonday = "2026-09-07",
                blocks =
                    listOf(
                        Lesson(
                            id = "history-lesson",
                            subjectId = s.id,
                            day = 1,
                            week = 2,
                            slot = 1,
                            start = "08:00",
                            end = "08:45",
                        )
                    ),
            )
        val data =
            SchoolData(
                profiles = listOf(p),
                activeProfileId = p.id,
                subjects = listOf(s),
                assessments = listOf(e),
                documents = listOf(document),
                timetables = listOf(table),
                officials =
                    listOf(
                        Official(
                            id = "history-official",
                            subjectId = s.id,
                            period = "Jahreszeugnis",
                            value = 2,
                        )
                    ),
                daysOff =
                    listOf(
                        DayOff(
                            id = "history-free",
                            profileId = p.id,
                            from = "2026-10-15",
                            to = "2026-10-16",
                            reason = "Schulfrei",
                        )
                    ),
                exceptions =
                    listOf(
                        ExceptionLesson(
                            id = "history-extra",
                            subjectId = s.id,
                            date = "2026-10-17",
                            start = "10:00",
                            end = "10:45",
                            cancelled = false,
                        )
                    ),
                studies =
                    listOf(
                        Study(id = "history-study", assessmentId = e.id, start = 1000, end = 61000)
                    ),
            )
        val old =
            dataJson.parseToJsonElement(dataJson.encodeToString(data)).jsonObject.toMutableMap()
        listOf(
                "flashcardSets",
                "flashcards",
                "flashcardProgress",
                "flashcardSessions",
                "vocabularyCollections",
                "vocabularyCards",
                "vocabularyProgress",
                "vocabularySessions",
            )
            .forEach(old::remove)
        old["schema"] = JsonPrimitive(1)
        val migrated = decodeSchoolData(JsonObject(old).toString())
        assertEquals(data, migrated)
        val password = CharArray(16) { index -> ('a'.code + index).toChar() }
        val restored =
            Backup.restore(Backup.export(migrated, password) { "%PDF-1".toByteArray() }, password)
        assertEquals(data, restored.data)
        assertContentEquals("%PDF-1".toByteArray(), restored.files.getValue("aabb.pdf"))
    }

    @Test
    fun corruptOrUnknownJsonNeverReturnsAnEmptySchoolData() {
        listOf("{", "null", "[]", "{\"schema\":99}", "{\"schema\":1,\"privateUnknown\":true}")
            .forEach { assertFails { decodeSchoolData(it) } }
    }
}
