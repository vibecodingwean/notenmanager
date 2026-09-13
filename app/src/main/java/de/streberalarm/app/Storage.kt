package de.streberalarm.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.room.*
import de.streberalarm.core.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Entity(tableName = "state") data class StoredState(@PrimaryKey val id: Int = 1, val json: String)

@Dao
interface StateDao {
    @Query("SELECT * FROM state WHERE id = 1") suspend fun read(): StoredState?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun write(value: StoredState)
}

@Database(entities = [StoredState::class], version = 1, exportSchema = true)
abstract class SchoolDatabase : RoomDatabase() {
    abstract fun state(): StateDao
}

class StreberAlarmApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository by lazy { Repository(this) }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            repository.load()
            repository.refreshEstimates()
        }
    }
}

class Repository(private val context: Context) {
    private val db =
        Room.databaseBuilder(context, SchoolDatabase::class.java, "streberalarm.db").build()
    private val lock = Mutex()
    private var loaded = false
    private val mutable = MutableStateFlow<SchoolData?>(null)
    val state = mutable.asStateFlow()
    val documentsDir = File(context.filesDir, "documents").apply { mkdirs() }

    suspend fun load() =
        lock.withLock {
            if (!loaded) {
                val original =
                    db.state().read()?.let { dataJson.decodeFromString<SchoolData>(it.json) }
                        ?: SchoolData()
                val normalized = Defaults.updateCatalog(original)
                if (normalized != original) write(normalized) else mutable.value = original
                loaded = true
            }
        }

    suspend fun current(): SchoolData {
        load()
        return mutable.value!!
    }

    private suspend fun write(input: SchoolData) {
        val data = Defaults.updateCatalog(input)
        data.validate()
        db.withTransaction { db.state().write(StoredState(json = dataJson.encodeToString(data))) }
        mutable.value = data
    }

    suspend fun update(change: (SchoolData) -> SchoolData) {
        load()
        lock.withLock {
            write(change(mutable.value!!))
            postEstimates(java.time.ZonedDateTime.now())
            ReminderScheduler.schedule(context, mutable.value!!)
            collectOrphans()
        }
    }

    private suspend fun postEstimates(now: java.time.ZonedDateTime) {
        val sent = EstimateNotifications.postDue(context, mutable.value!!, now)
        if (sent.isNotEmpty())
            write(mutable.value!!.copy(delivered = mutable.value!!.delivered + sent))
    }

    suspend fun refreshEstimates(now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()) {
        load()
        lock.withLock {
            postEstimates(now)
            ReminderScheduler.schedule(context, mutable.value!!, now)
        }
    }

    fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    suspend fun start(examId: String) = update {
        Timers.start(it, examId, System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot())
    }

    suspend fun stop() = update {
        Timers.stop(it, System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot())
    }

    suspend fun importDocuments(examId: String, uris: List<Uri>) {
        load()
        lock.withLock {
            require(uris.size <= 30) { "Höchstens 30 Dokumente auf einmal." }
            val added = mutableListOf<Document>()
            try {
                uris.forEachIndexed { index, uri ->
                    val mime = context.contentResolver.getType(uri) ?: ""
                    val ext =
                        when (mime) {
                            "application/pdf" -> "pdf"
                            "image/jpeg" -> "jpg"
                            "image/png" -> "png"
                            else -> error("Nur JPEG, PNG und PDF werden unterstützt.")
                        }
                    val file = "${id()}.$ext"
                    val target = File(documentsDir, file)
                    try {
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                var total = 0L
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    total += n
                                    require(total <= MAX_DOCUMENT_BYTES) {
                                        "Dokument ist größer als 40 MiB."
                                    }
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                        require(target.length() > 0)
                        val head = ByteArray(minOf(8L, target.length()).toInt())
                        java.io.DataInputStream(target.inputStream()).use { it.readFully(head) }
                        require(
                            when (ext) {
                                "pdf" ->
                                    head.take(5).toByteArray().contentEquals("%PDF-".toByteArray())
                                "jpg" ->
                                    head.size >= 2 &&
                                        head[0] == 0xff.toByte() &&
                                        head[1] == 0xd8.toByte()
                                else ->
                                    head.contentEquals(
                                        byteArrayOf(
                                            0x89.toByte(),
                                            0x50,
                                            0x4e,
                                            0x47,
                                            0x0d,
                                            0x0a,
                                            0x1a,
                                            0x0a,
                                        )
                                    )
                            }
                        ) {
                            "Dateiinhalt passt nicht zum Dokumenttyp."
                        }
                        added +=
                            Document(
                                assessmentId = examId,
                                name =
                                    "Dokument ${mutable.value!!.documents.count{it.assessmentId==examId}+index+1}.$ext",
                                mime = mime,
                                file = file,
                                size = target.length(),
                            )
                    } catch (e: Exception) {
                        target.delete()
                        throw e
                    }
                }
                write(mutable.value!!.copy(documents = mutable.value!!.documents + added))
            } catch (e: Exception) {
                added.forEach { File(documentsDir, it.file).delete() }
                throw e
            }
        }
    }

    suspend fun addLocalPdf(examId: String, source: File) {
        load()
        lock.withLock {
            require(source.length() in 1..MAX_DOCUMENT_BYTES)
            val filename = "${id()}.pdf"
            val target = File(documentsDir, filename)
            try {
                source.copyTo(target)
                val doc =
                    Document(
                        assessmentId = examId,
                        name =
                            "Scan ${mutable.value!!.documents.count{it.assessmentId==examId}+1}.pdf",
                        mime = "application/pdf",
                        file = filename,
                        size = target.length(),
                    )
                write(mutable.value!!.copy(documents = mutable.value!!.documents + doc))
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
    }

    suspend fun export(password: CharArray): ByteArray {
        load()
        return lock.withLock {
            Backup.export(mutable.value!!, password) { File(documentsDir, it).readBytes() }
        }
    }

    suspend fun restore(bytes: ByteArray, password: CharArray) {
        // Authentication, schema and full attachment validation precede any mutation.
        val restored = Backup.restore(bytes, password)
        load()
        lock.withLock {
            val written = mutableListOf<File>()
            try {
                val remapped =
                    restored.data.documents.map { doc ->
                        val file = "${id()}.${doc.file.substringAfterLast('.')}"
                        val target = File(documentsDir, file)
                        written.add(target)
                        target.outputStream().use {
                            it.write(restored.files.getValue(doc.file))
                            it.fd.sync()
                        }
                        doc.copy(file = file)
                    }
                // A running timer from another device must be corrected, never kept on its old
                // monotonic clock.
                val data =
                    restored.data.copy(
                        documents = remapped,
                        studies =
                            restored.data.studies.map {
                                if (it.end == null)
                                    it.copy(
                                        end = maxOf(it.start, System.currentTimeMillis()),
                                        correction =
                                            "Aus Backup wiederhergestellt: Sitzungsdauer bitte prüfen.",
                                    )
                                else it
                            },
                    )
                write(data)
            } catch (e: Exception) {
                written.forEach { it.delete() }
                throw e
            }
            postEstimates(java.time.ZonedDateTime.now())
            ReminderScheduler.schedule(context, mutable.value!!)
            collectOrphans()
        }
    }

    private fun collectOrphans() {
        val keep = mutable.value!!.documents.map { it.file }.toSet()
        documentsDir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    suspend fun deleteSubject(subjectId: String) = update { data ->
        val withoutExams =
            data.assessments
                .filter { it.subjectId == subjectId }
                .fold(data) { state, exam -> state.deleteAssessment(exam.id) }
        withoutExams.copy(
            subjects = withoutExams.subjects.filterNot { it.id == subjectId },
            officials = withoutExams.officials.filterNot { it.subjectId == subjectId },
            timetables =
                withoutExams.timetables.map {
                    it.copy(blocks = it.blocks.filterNot { b -> b.subjectId == subjectId })
                },
            exceptions = withoutExams.exceptions.filterNot { it.subjectId == subjectId },
        )
    }

    suspend fun deleteExam(id: String) = update { it.deleteAssessment(id) }
}
