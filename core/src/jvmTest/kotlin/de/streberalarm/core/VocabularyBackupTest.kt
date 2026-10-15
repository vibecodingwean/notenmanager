package de.streberalarm.core

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test

class VocabularyBackupTest {
    private val password = UUID.randomUUID().toString().toCharArray()
    private val today = SchoolDate.parse("2026-10-11")

    @Test
    fun encryptedFlashcardRoundtripAndLegacySchemaMigration() {
        var data = SchoolData(profiles = listOf(Profile(id = "p")), activeProfileId = "p")
        data = Flashcards.createSet(data, "p", "set", "Geschichte")
        data = Flashcards.saveCard(data, "p", "set", "card", null, "Wann?", "Heute")
        data = Flashcards.start(data, "p", "set", "round")
        data = Flashcards.reveal(data, "p", "round", 0, "card")
        data = Flashcards.grade(data, "p", "round", 0, "card", false)
        assertEquals(data, Backup.restore(Backup.export(data, password) { error("No documents") }, password).data)
        val legacy = Backup.restore(encryptedFixture("""{"schema":1,"profiles":[],"activeProfileId":null}"""), password).data
        assertEquals(2, legacy.schema)
        assertTrue(legacy.flashcards.isEmpty())
    }

    private fun twoProfiles(): SchoolData {
        var data =
            SchoolData(
                profiles = listOf(Profile(id = "p"), Profile(id = "q")),
                activeProfileId = "p",
            )
        for (owner in listOf("p", "q")) {
            data = data.copy(activeProfileId = owner)
            data = Vocabulary.createCollection(data, owner, "collection-$owner", "Words")
            data =
                Vocabulary.saveCard(
                    data,
                    owner,
                    "collection-$owner",
                    "word-$owner",
                    null,
                    "Haus",
                    "house",
                    listOf("home"),
                )
            data =
                Vocabulary.startRound(
                    data,
                    owner,
                    "collection-$owner",
                    "session-$owner",
                    VocabularyMode.ALL,
                    today,
                )
        }
        val subject = Subject(id = "subject", profileId = "p", name = "Englisch")
        val exam =
            Assessment(id = "exam", subjectId = subject.id, title = "Probe", date = "2026-10-11")
        val doc =
            Document(
                id = "doc",
                assessmentId = exam.id,
                name = "Sample.pdf",
                mime = "application/pdf",
                file = "a.pdf",
                size = 6,
            )
        return data.copy(
            subjects = listOf(subject),
            assessments = listOf(exam),
            documents = listOf(doc),
        )
    }

    /**
     * External fixture writer: can encode genuinely old shape and authenticated invalid payloads.
     */
    private fun encryptedFixture(json: String): ByteArray {
        val plain =
            ByteArrayOutputStream()
                .also { out ->
                    ZipOutputStream(out).use { zip ->
                        zip.putNextEntry(ZipEntry("data.json"))
                        zip.write(json.toByteArray())
                        zip.closeEntry()
                    }
                }
                .toByteArray()
        val magic = "SALM0001".toByteArray()
        val salt = ByteArray(16) { (it + 10).toByte() }
        val nonce = ByteArray(12) { (it + 20).toByte() }
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        val raw =
            try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(raw, "AES"),
                GCMParameterSpec(128, nonce),
            )
            cipher.updateAAD(magic)
            return magic + salt + nonce + cipher.doFinal(plain)
        } finally {
            raw.fill(0)
            plain.fill(0)
        }
    }

    @Test
    fun encryptedRoundtripBothProfilesAttachmentsAndEveryPersistedPhase() {
        var d = twoProfiles()
        val states = mutableListOf(d)
        d = Vocabulary.submitAnswer(d, "q", "session-q", 0, "word-q", 1, "wrong", today)
        states.add(d)
        d = Vocabulary.continueRound(d, "q", "session-q", 1)
        d = Vocabulary.submitAnswer(d, "q", "session-q", 1, "word-q", 1, "HOME", today)
        states.add(d)
        d = Vocabulary.continueRound(d, "q", "session-q", 2)
        states.add(d)
        for (state in states) {
            val bytes = Backup.export(state, password) { "%PDF-1".toByteArray() }
            val restored = Backup.restore(bytes, password)
            assertEquals(state, restored.data)
            assertContentEquals("%PDF-1".toByteArray(), restored.files.getValue("a.pdf"))
        }
        assertEquals(1L, d.vocabularyProgress.single().correctAttempts)
        assertEquals(1L, d.vocabularyProgress.single().wrongAttempts)
    }

    @Test
    fun genuinelyOldEncryptedFixtureReadsNewButOldStrictShapeRejectsNew() {
        val old = """{"schema":1,"profiles":[],"activeProfileId":null}"""
        val restored = Backup.restore(encryptedFixture(old), password).data
        assertTrue(restored.vocabularyCards.isEmpty() && restored.vocabularySessions.isEmpty())
        val encoded = dataJson.encodeToString(restored)
        val keys = dataJson.parseToJsonElement(encoded).jsonObject.keys
        assertTrue("vocabularyCards" in keys)
        // Actual strict pre-vocabulary wire shape, not a permissive unknown-key parser.
        dataJson.decodeFromString<PreVocabularySchoolData>(old)
        assertFails { dataJson.decodeFromString<PreVocabularySchoolData>(encoded) }
        assertFails {
            dataJson.decodeFromString<SchoolData>(encoded.dropLast(1) + ",\"unknownField\":true}")
        }
    }

    @Test
    fun authenticatedInvalidVocabularyAndBadPasswordNeverProduceRestoreResult() {
        val d = twoProfiles().copy(documents = emptyList())
        val invalid =
            d.copy(
                vocabularySessions =
                    d.vocabularySessions.map { it.copy(pending = listOf("not-a-selected-word")) }
            )
        val original = dataJson.encodeToString(d)
        assertFails { Backup.restore(encryptedFixture(dataJson.encodeToString(invalid)), password) }
        val bytes = Backup.export(d, password) { error("No documents") }
        assertFailsWith<IllegalArgumentException> {
            Backup.restore(bytes, UUID.randomUUID().toString().toCharArray())
        }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException> { Backup.restore(bytes, password) }
        assertEquals(original, dataJson.encodeToString(d))
    }

    @Test
    fun exportRejectsDataJsonLargerThanRestoreEntryLimitBeforeReadingFiles() {
        val base = twoProfiles()
        val large =
            base.copy(
                assessments =
                    base.assessments.map {
                        it.copy(material = "x".repeat(MAX_DOCUMENT_BYTES.toInt()))
                    }
            )
        large.validate()
        var reads = 0
        assertFailsWith<IllegalArgumentException> {
            Backup.export(large, password) {
                reads++
                "%PDF-1".toByteArray()
            }
        }
        assertEquals(0, reads)
    }

    @Test
    fun maximumDocumentCountRoundtripHasRoomForDataJsonEntry() {
        val base = twoProfiles()
        val documents =
            (1..2000).map {
                Document(
                    id = "d$it",
                    assessmentId = "exam",
                    name = "Example",
                    mime = "application/pdf",
                    file = "${it.toString(16)}.pdf",
                    size = 1,
                )
            }
        val d = base.copy(documents = documents)
        val restored = Backup.restore(Backup.export(d, password) { byteArrayOf(1) }, password)
        assertEquals(d, restored.data)
        assertEquals(2000, restored.files.size)
    }
}

@Serializable
private data class PreVocabularySchoolData(
    val schema: Int = 1,
    val profiles: List<Profile> = emptyList(),
    val activeProfileId: String? = null,
    val subjects: List<Subject> = emptyList(),
    val assessments: List<Assessment> = emptyList(),
    val officials: List<Official> = emptyList(),
    val timetables: List<Timetable> = emptyList(),
    val daysOff: List<DayOff> = emptyList(),
    val exceptions: List<ExceptionLesson> = emptyList(),
    val studies: List<Study> = emptyList(),
    val documents: List<Document> = emptyList(),
    val delivered: Set<String> = emptySet(),
    val graduation: Map<String, GraduationInput> = emptyMap(),
    val catalogVersion: Int = 0,
)
