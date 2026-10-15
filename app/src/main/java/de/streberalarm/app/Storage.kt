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

    @Query("DELETE FROM state") suspend fun clear()
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

class Repository(private val context: Context) : java.io.Closeable {
    private val realDb =
        Room.databaseBuilder(context, SchoolDatabase::class.java, "streberalarm.db").build()
    private val demoDb =
        Room.databaseBuilder(context, SchoolDatabase::class.java, "streberalarm-demo.db").build()
    private val workspace = context.getSharedPreferences("school-workspace", Context.MODE_PRIVATE)
    private val demoMode = MutableStateFlow(workspace.getBoolean("demo", false))
    val isDemo = demoMode.asStateFlow()
    private val db get() = if (demoMode.value) demoDb else realDb
    private val lock = Mutex()
    private val workspaceTransition = Mutex()
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val demoScans by lazy {
        DemoScanSessions(File(context.cacheDir, "demo-scans"), scanScope)
    }
    @Volatile private var demoDeleting = false
    @Volatile private var demoCleanupPending = false
    private var loaded = false
    private val mutable = MutableStateFlow<SchoolData?>(null)
    val state = mutable.asStateFlow()
    private val vocabularyEpoch = MutableStateFlow(0L)
    val vocabularyGeneration = vocabularyEpoch.asStateFlow()
    private val gradeDrafts = GradeDrafts(context) { demoMode.value }
    private val gradeDraftEpoch = MutableStateFlow(0L)
    val gradeDraftRevision = gradeDraftEpoch.asStateFlow()
    // Internal precommit seam for deterministic storage-failure/slow-write journeys. Null in production.
    @Volatile internal var beforeVocabularyCommit: (suspend () -> Unit)? = null
    // Test seam for observing publication between workspace and data flows.
    @Volatile internal var beforeRealPublication: (suspend () -> Unit)? = null
    @Volatile internal var beforeGradeDraftCleanup: (suspend () -> Unit)? = null
    @Volatile internal var beforeDemoScanOpen: (suspend () -> Unit)? = null
    @Volatile internal var afterDemoScanOpenAttempt: (() -> Unit)? = null
    private val vocabularyWriter = VocabularyPersistence(lock, mutable, { vocabularyEpoch.value }) { data ->
        beforeVocabularyCommit?.invoke()
        db.withTransaction { db.state().write(StoredState(json = dataJson.encodeToString(data))) }
    }

    suspend fun updateVocabulary(expectedGeneration: Long, change: (SchoolData) -> SchoolData) {
        load()
        vocabularyWriter.update(expectedGeneration, change)
    }
    val documentsDir: File
        get() = File(context.filesDir, if (demoMode.value) "demo-documents" else "documents")
            .apply { mkdirs() }

    suspend fun openGradeDraft(profileId: String, subjectId: String, targetId: String? = null,
                               expectedGeneration: Long? = null): GradeDraft {
        load()
        return lock.withLock {
            require(expectedGeneration == null || vocabularyEpoch.value == expectedGeneration)
            val data = mutable.value!!
            require(data.activeProfileId == profileId && data.subjects.any {
                it.id == subjectId && it.profileId == profileId
            }) { "Fach oder Profil wurde gewechselt." }
            val baseline = targetId?.let { target ->
                requireNotNull(data.assessments.find { it.id == target && it.subjectId == subjectId }) {
                    "Die Prüfung wurde gewechselt."
                }
            }
            gradeDrafts.findOrCreate(profileId, subjectId, targetId, baseline)
        }
    }

    suspend fun resumeGradeDraft(draftId: String, profileId: String, expectedGeneration: Long): GradeDraft {
        load()
        return lock.withLock {
            require(vocabularyEpoch.value == expectedGeneration && mutable.value!!.activeProfileId == profileId) {
                "Das Profil wurde gewechselt."
            }
            val draft = requireNotNull(gradeDrafts.find(draftId)) { "Notenentwurf wurde entfernt." }
            require(draft.profileId == profileId && mutable.value!!.subjects.any {
                it.id == draft.subjectId && it.profileId == profileId
            }) { "Fach oder Profil wurde gewechselt." }
            draft
        }
    }

    suspend fun gradeDraftDocuments(draftId: String): List<Document> {
        load()
        return lock.withLock { gradeDrafts.find(draftId)?.documents.orEmpty() }
    }

    fun isGradeDraft(draftId: String): Boolean = gradeDrafts.find(draftId) != null

    suspend fun updateGradeDraftForm(draftId: String, date: String, kind: Kind?, topic: String,
                                     actual: Int?, weight: String, expectedGeneration: Long) {
        load()
        lock.withLock {
            require(vocabularyEpoch.value == expectedGeneration) { "Das Profil wurde gewechselt." }
            val draft = requireNotNull(gradeDrafts.find(draftId)) { "Notenentwurf wurde entfernt." }
            require(draft.profileId == mutable.value!!.activeProfileId) { "Das Profil wurde gewechselt." }
            gradeDrafts.updateForm(draft, date, kind, topic, actual, weight)
        }
    }

    suspend fun changeGradeDraft(draftId: String, subjectId: String, targetId: String?,
                                 expectedGeneration: Long): GradeDraft {
        load()
        return lock.withLock {
            require(vocabularyEpoch.value == expectedGeneration) { "Das Profil wurde gewechselt." }
            val data = mutable.value!!
            val draft = requireNotNull(gradeDrafts.find(draftId)) { "Notenentwurf wurde entfernt." }
            require(draft.profileId == data.activeProfileId) { "Das Profil wurde gewechselt." }
            require(data.subjects.any { it.id == subjectId && it.profileId == draft.profileId }) {
                "Das Fach wurde gewechselt."
            }
            require(draft.sourceSelection.startsWith("new:") ||
                subjectId == draft.subjectId && targetId == draft.targetId) {
                "Eine vorhandene Prüfung kann nicht einem anderen Fach zugeordnet werden."
            }
            val baseline = targetId?.let { target -> data.assessments.find {
                it.id == targetId && it.subjectId == subjectId && it.actual == null &&
                    it.stage != Stage.GRADED && it.stage != Stage.CANCELLED
            } }
            if (targetId != null) requireNotNull(baseline) {
                "Diese Prüfung hat bereits eine Note oder wurde gewechselt."
            }
            gradeDrafts.change(draft, subjectId, targetId, baseline).also { gradeDraftEpoch.value += 1 }
        }
    }

    suspend fun cancelGradeDraft(draftId: String, expectedGeneration: Long) {
        load()
        lock.withLock {
            if (vocabularyEpoch.value != expectedGeneration) return@withLock
            val draft = gradeDrafts.find(draftId) ?: return@withLock
            if (draft.profileId != mutable.value!!.activeProfileId) return@withLock
            gradeDrafts.delete(draftId)
            gradeDraftEpoch.value += 1
        }
    }

    suspend fun saveCompactGrade(draftId: String, date: String, kind: Kind, topic: String,
                                 actual: Int, weight: Double, expectedGeneration: Long): String {
        load()
        return lock.withLock {
            require(vocabularyEpoch.value == expectedGeneration) { "Die Daten wurden gewechselt. Bitte neu öffnen." }
            val data = mutable.value!!
            val draft = requireNotNull(gradeDrafts.find(draftId)) { "Notenentwurf wurde entfernt." }
            val profile = requireNotNull(data.active())
            require(draft.profileId == profile.id) { "Das Profil wurde gewechselt." }
            val subject = requireNotNull(data.subjects.find {
                it.id == draft.subjectId && it.profileId == profile.id
            }) { "Das Fach wurde gewechselt." }
            // A process can die after the DB transaction but before draft cleanup.
            draft.commitIntent?.let { committed ->
                if (data.assessments.any { it == committed } && draft.documents.all { doc ->
                    data.documents.any { saved ->
                        saved.id == doc.id && saved.assessmentId == committed.id &&
                            File(documentsDir, saved.file).isFile
                    }
                }) {
                    gradeDrafts.delete(draft.id)
                    gradeDraftEpoch.value += 1
                    return@withLock committed.id
                }
            }
            val existing = draft.targetId?.let { target ->
                requireNotNull(data.assessments.find { it.id == target && it.subjectId == subject.id }) {
                    "Die Prüfung wurde gewechselt."
                }.also {
                    require(it == draft.baseline) { "Die Prüfung wurde zwischenzeitlich geändert. Bitte neu öffnen." }
                    if (draft.sourceSelection.startsWith("new:"))
                        require(it.actual == null && it.stage != Stage.GRADED) {
                            "Diese Prüfung hat bereits eine Note."
                        }
                }
            }
            val next = if (existing == null) Assessment(
                id = draft.resultId, subjectId = subject.id, title = "${kind.label} · ${subject.name}",
                date = date, kind = kind, stage = Stage.GRADED, material = topic.trim(),
                actual = actual, weight = weight,
            ) else existing.copy(
                date = date, time = existing.time?.takeIf { date == existing.date },
                kind = kind, stage = Stage.GRADED,
                material = if (topic.trim() == existing.material.ifBlank { existing.notes }.trim())
                    existing.material else topic.trim(),
                actual = actual, weight = weight,
            )
            val day = java.time.LocalDate.parse(date)
            require(day >= java.time.LocalDate.of(profile.year, 8, 1) &&
                day <= java.time.LocalDate.of(profile.year + 1, 9, 30)) { "Datum liegt außerhalb des Schuljahrs." }
            next.validate(profile.points)
            withContext(NonCancellable) {
                gradeDrafts.markCommit(draft, next)
                val copied = mutableListOf<File>()
                try {
                    val docs = draft.documents.map { doc ->
                        val source = gradeDrafts.source(draft.id, doc.file)
                        require(source.isFile && source.length() == doc.size) {
                            "Eine Unterlage fehlt oder wurde verändert."
                        }
                        val target = File(documentsDir, "${doc.id}.${doc.file.substringAfterLast('.')}")
                        copied += target
                        source.inputStream().use { input ->
                            java.io.FileOutputStream(target).use { output ->
                                input.copyTo(output)
                                output.fd.sync()
                            }
                        }
                        require(target.length() == doc.size)
                        doc.copy(assessmentId = next.id, file = target.name)
                    }
                    val result = data.copy(
                        assessments = data.assessments.filterNot { it.id == next.id } + next,
                        documents = data.documents + docs,
                    )
                    write(result)
                } catch (e: Exception) {
                    copied.forEach { it.delete() }
                    throw e
                }
                beforeGradeDraftCleanup?.invoke()
                gradeDrafts.delete(draft.id)
                gradeDraftEpoch.value += 1
                if (!demoMode.value) {
                    try {
                        postEstimates(java.time.ZonedDateTime.now())
                        ReminderScheduler.schedule(context, mutable.value!!)
                    } catch (_: Exception) {
                        // The grade is already durable; notification retries on the next refresh.
                    }
                }
                collectOrphans()
            }
            next.id
        }
    }

    suspend fun load() =
        lock.withLock {
            if (!loaded) {
                if (demoMode.value && demoDb.state().read() == null) {
                    workspace.edit().putBoolean("demo", false).commit()
                    demoMode.value = false
                }
                val original = db.state().read()?.let {
                    decodeSchoolData(it.json)
                } ?: SchoolData()
                val normalized = Defaults.updateCatalog(original)
                if (normalized != original) write(normalized) else mutable.value = original
                loaded = true
            }
        }

    suspend fun current(): SchoolData {
        load()
        return mutable.value!!
    }

    /** A second database and file tree keep all demo edits and attachments out of real backups. */
    suspend fun loadDemo() = workspaceTransition.withLock {
        load()
        if (demoCleanupPending && !demoDeleting) {
            drainDemoScans(demoScans.closeAllAdmission())
            lock.withLock { demoCleanupPending = false }
        }
        lock.withLock {
            check(!demoDeleting) {
                "Demo-Aufnahmen werden noch entfernt. Bitte erneut versuchen."
            }
            if (demoMode.value) return@withLock
            withContext(NonCancellable) {
                var demo = demoDb.state().read()?.let {
                    decodeSchoolData(it.json)
                }
                if (demo == null) {
                    val folder = File(context.filesDir, "demo-documents").apply { mkdirs() }
                    val seed = DemoData.create()
                    val sources = listOf(DemoData.MATH_IMAGE, DemoData.ENGLISH_IMAGE)
                    val copied = mutableListOf<File>()
                    try {
                        val documents = seed.documents.mapIndexed { index, doc ->
                            val target = File(folder, doc.file)
                            context.assets.open(sources[index]).use { input ->
                                target.outputStream().use { output -> input.copyTo(output) }
                            }
                            copied += target
                            doc.copy(size = target.length())
                        }
                        demo = seed.copy(documents = documents).also { it.validate() }
                        demoDb.withTransaction {
                            demoDb.state().write(StoredState(json = dataJson.encodeToString(demo!!)))
                        }
                    } catch (e: Exception) {
                        copied.forEach { it.delete() }
                        throw e
                    }
                }
                check(workspace.edit().putBoolean("demo", true).commit()) {
                    "Demo-Auswahl konnte nicht gespeichert werden."
                }
                demoMode.value = true
                vocabularyEpoch.value += 1
                mutable.value = Defaults.updateCatalog(requireNotNull(demo))
            }
        }
    }

    suspend fun returnToReal() {
      val transition = scanScope.async { workspaceTransition.withLock {
        load()
        val closed = lock.withLock {
            demoCleanupPending = true
            val sessions = demoScans.closeAllAdmission()
            if (!demoMode.value) return@withLock sessions
            val real = realDb.state().read()?.let {
                decodeSchoolData(it.json)
            } ?: SchoolData()
            withContext(NonCancellable) {
                check(workspace.edit().putBoolean("demo", false).commit()) {
                    "Schuljahr konnte nicht gewechselt werden."
                }
                demoMode.value = false
                beforeRealPublication?.invoke()
                vocabularyEpoch.value += 1
                mutable.value = Defaults.updateCatalog(real)
            }
            sessions
        }
        drainDemoScans(closed)
        lock.withLock { demoCleanupPending = false }
      } }
      withContext(NonCancellable) { transition.await() }
    }

    suspend fun deleteDemo() {
      val transition = scanScope.async { workspaceTransition.withLock {
        load()
        val closed = lock.withLock {
            demoDeleting = true
            val sessions = demoScans.closeAllAdmission()
            val real = if (demoMode.value) realDb.state().read()?.let {
                decodeSchoolData(it.json)
            } ?: SchoolData() else null
            withContext(NonCancellable) {
                if (demoMode.value) {
                    check(workspace.edit().putBoolean("demo", false).commit()) {
                        "Schuljahr konnte nicht gewechselt werden."
                    }
                    demoMode.value = false
                    vocabularyEpoch.value += 1
                    mutable.value = Defaults.updateCatalog(requireNotNull(real))
                }
            }
            sessions
        }
        // A PDF lease may be waiting on the repository lock. Drain only after releasing it.
        drainDemoScans(closed)
        lock.withLock {
            withContext(NonCancellable) {
                demoDb.withTransaction { demoDb.state().clear() }
                val folder = File(context.filesDir, "demo-documents")
                if (folder.exists()) check(folder.deleteRecursively()) {
                    "Ein Demo-Dokument konnte nicht entfernt werden."
                }
                gradeDrafts.clearDemo()
                demoDeleting = false
                demoCleanupPending = false
            }
        }
      } }
      withContext(NonCancellable) { transition.await() }
    }

    internal suspend fun openDemoScan(examId: String, resumeId: String?,
                                      expectedGeneration: Long, routeToken: Long): DemoScanSessions.Session {
        beforeDemoScanOpen?.invoke()
        load()
        demoScans.prepare()
        try {
            return lock.withLock {
                check(demoMode.value && !demoDeleting && !demoCleanupPending &&
                    vocabularyEpoch.value == expectedGeneration) {
                    "Der Demo-Bereich wurde gewechselt."
                }
                val current = mutable.value!!
                val exam = current.assessments.find { it.id == examId }
                check(exam != null && current.subjects.any {
                    it.id == exam.subjectId && it.profileId == current.activeProfileId
                }) { "Prüfung wurde gewechselt." }
                demoScans.open(examId, expectedGeneration, resumeId, routeToken)
            }
        } finally {
            afterDemoScanOpenAttempt?.invoke()
        }
    }

    internal fun demoScanRouteToken(examId: String): Long = demoScans.routeToken(examId)

    private suspend fun drainDemoScans(closed: List<DemoScanSessions.Session>) {
        try {
            withTimeout(30_000) { demoScans.drainAndDeleteAll(closed) }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException(
                "Demo-Aufnahmen werden noch abgeschlossen. Bitte Testdaten erneut laden oder löschen.", e)
        }
    }

    internal fun closeDemoScan(id: String) { demoScans.close(id) }
    internal fun closeDemoScansForExam(examId: String) { demoScans.closeForExam(examId) }

    internal fun launchDemoScan(block: suspend () -> Unit): Job = scanScope.launch { block() }

    internal suspend fun addDemoScanPdf(lease: DemoScanSessions.Lease) {
        load()
        lock.withLock {
            require(demoMode.value && !demoDeleting && !demoCleanupPending &&
                vocabularyEpoch.value == lease.session.generation &&
                demoScans.mayAttach(lease)) { "Der Demo-Bereich wurde gewechselt." }
            val examId = lease.session.examId
            require(mutable.value!!.assessments.any { it.id == examId }) { "Prüfung wurde gewechselt." }
            require(lease.output.length() in 1..MAX_DOCUMENT_BYTES)
            val filename = "${id()}.pdf"
            val target = File(documentsDir, filename)
            try {
                lease.output.copyTo(target)
                val doc = Document(assessmentId = examId,
                    name = "Scan ${mutable.value!!.documents.count { it.assessmentId == examId } + 1}.pdf",
                    mime = "application/pdf", file = filename, size = target.length())
                write(mutable.value!!.copy(documents = mutable.value!!.documents + doc))
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
    }

    private suspend fun write(input: SchoolData, resetVocabulary: Boolean = false) {
        val data = Defaults.updateCatalog(input)
        data.validate()
        withContext(NonCancellable) {
            db.withTransaction { db.state().write(StoredState(json = dataJson.encodeToString(data))) }
            if (resetVocabulary || mutable.value?.activeProfileId != data.activeProfileId)
                vocabularyEpoch.value += 1
            mutable.value = data
        }
    }

    suspend fun update(change: (SchoolData) -> SchoolData) = update(null, change)

    suspend fun update(expectedGeneration: Long?, change: (SchoolData) -> SchoolData) {
        load()
        lock.withLock {
            require(expectedGeneration == null || vocabularyEpoch.value == expectedGeneration) {
                "Die Daten wurden gewechselt. Bitte neu öffnen."
            }
            val previous = mutable.value!!
            write(change(previous))
            if (demoMode.value && previous.activeProfileId != mutable.value!!.activeProfileId) {
                demoScans.closeAll()
            } else if (demoMode.value) {
                val current = mutable.value!!
                previous.assessments.filter { old ->
                    val next = current.assessments.find { it.id == old.id }
                    next == null || next.subjectId != old.subjectId ||
                        current.subjects.find { it.id == next.subjectId }?.profileId != current.activeProfileId
                }.forEach { demoScans.closeForExam(it.id) }
            }
            if (!demoMode.value) {
                postEstimates(java.time.ZonedDateTime.now())
                ReminderScheduler.schedule(context, mutable.value!!)
            }
            collectOrphans()
        }
    }

    suspend fun realCurrent(): SchoolData {
        load()
        return lock.withLock {
            if (!demoMode.value) mutable.value!! else realDb.state().read()?.let {
                decodeSchoolData(it.json)
            } ?: SchoolData()
        }
    }

    suspend fun deliverReal(keys: List<String>) {
        load()
        lock.withLock {
            val real = if (demoMode.value) realDb.state().read()?.let {
                decodeSchoolData(it.json)
            } ?: SchoolData() else mutable.value!!
            val next = real.copy(delivered = real.delivered + keys)
            withContext(NonCancellable) {
                realDb.withTransaction {
                    realDb.state().write(StoredState(json = dataJson.encodeToString(next)))
                }
                if (!demoMode.value) mutable.value = next
            }
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
            if (demoMode.value) {
                val original = realDb.state().read()?.let {
                    decodeSchoolData(it.json)
                } ?: SchoolData()
                val sent = EstimateNotifications.postDue(context, original, now)
                val real = if (sent.isEmpty()) original else original.copy(delivered = original.delivered + sent)
                if (sent.isNotEmpty()) realDb.withTransaction {
                    realDb.state().write(StoredState(json = dataJson.encodeToString(real)))
                }
                ReminderScheduler.schedule(context, real, now)
            } else {
                postEstimates(now)
                ReminderScheduler.schedule(context, mutable.value!!, now)
            }
        }
    }

    fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    suspend fun start(examId: String, expectedGeneration: Long? = null) = update(expectedGeneration) {
        Timers.start(it, examId, System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot())
    }

    suspend fun stop(expectedGeneration: Long? = null) = update(expectedGeneration) {
        Timers.stop(it, System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot())
    }

    suspend fun importDocuments(examId: String, uris: List<Uri>, expectedGeneration: Long? = null) {
        load()
        lock.withLock {
            require(expectedGeneration == null || vocabularyEpoch.value == expectedGeneration) {
                "Die Daten wurden gewechselt. Dokument bitte neu auswählen."
            }
            val current = mutable.value!!
            gradeDrafts.find(examId)?.let { initial ->
                require(initial.profileId == current.activeProfileId &&
                    current.subjects.any { it.id == initial.subjectId && it.profileId == initial.profileId }) {
                    "Fach oder Profil wurde gewechselt."
                }
                require(uris.size <= 30) { "Höchstens 30 Dokumente auf einmal." }
                var draft = initial
                try {
                    uris.forEach { uri -> draft = gradeDrafts.addUri(draft, uri, MAX_DOCUMENT_BYTES) }
                } catch (e: Exception) {
                    gradeDrafts.rollbackDocuments(initial, draft)
                    throw e
                }
                gradeDraftEpoch.value += 1
                return@withLock
            }
            val exam = current.assessments.find { it.id == examId }
            require(exam != null && current.subjects.any {
                it.id == exam.subjectId && it.profileId == current.activeProfileId
            }) { "Prüfung wurde gewechselt." }
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

    suspend fun addLocalPdf(examId: String, source: File, expectedGeneration: Long? = null) {
        load()
        lock.withLock {
            require(expectedGeneration == null || vocabularyEpoch.value == expectedGeneration) {
                "Die Daten wurden gewechselt. Scan bitte neu öffnen."
            }
            gradeDrafts.find(examId)?.let { draft ->
                require(draft.profileId == mutable.value!!.activeProfileId &&
                    mutable.value!!.subjects.any { it.id == draft.subjectId && it.profileId == draft.profileId }) {
                    "Fach oder Profil wurde gewechselt."
                }
                gradeDrafts.addPdf(draft, source, MAX_DOCUMENT_BYTES)
                gradeDraftEpoch.value += 1
                return@withLock
            }
            require(mutable.value!!.assessments.any { it.id == examId }) { "Prüfung wurde gewechselt." }
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

    suspend fun export(password: CharArray, expectedGeneration: Long? = null): ByteArray {
        load()
        return lock.withLock {
            require(expectedGeneration == null || vocabularyEpoch.value == expectedGeneration) {
                "Die Daten wurden gewechselt. Backup bitte neu starten."
            }
            require(!demoMode.value) { "Demo-Daten können nicht gesichert werden. Erst zu echten Daten wechseln." }
            Backup.export(mutable.value!!, password) { File(documentsDir, it).readBytes() }
        }
    }

    suspend fun restore(bytes: ByteArray, password: CharArray, expectedGeneration: Long? = null) {
        // Authentication, schema and full attachment validation precede any mutation.
        val restored = Backup.restore(bytes, password)
        load()
        lock.withLock {
            require(expectedGeneration == null || vocabularyEpoch.value == expectedGeneration) {
                "Die Daten wurden gewechselt. Wiederherstellung bitte neu starten."
            }
            require(!demoMode.value) { "Backup nur im echten Schuljahr wiederherstellen." }
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
                write(data, resetVocabulary = true)
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

    suspend fun deleteSubject(subjectId: String, expectedGeneration: Long? = null) = update(expectedGeneration) { data ->
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

    suspend fun deleteExam(id: String, expectedGeneration: Long? = null) = update(expectedGeneration) { it.deleteAssessment(id) }

    override fun close() { realDb.close(); demoDb.close() }
}
