package de.streberalarm.app

import de.streberalarm.core.id
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns unpublished demo scans independently of any Activity or Compose lifetime. */
internal class DemoScanSessions(private val root: File, private val scope: CoroutineScope) {
    internal data class Snapshot(
        val current: File? = null,
        val pages: List<File> = emptyList(),
        val busy: Boolean = false,
        val open: Boolean = true,
        val completed: Boolean = false,
        val error: String? = null,
    )

    internal enum class Kind { CAMERA, CROP, PDF }

    internal inner class Session internal constructor(
        val id: String,
        val examId: String,
        val generation: Long,
    ) {
        val folder = File(root, id)
        internal val mutable = MutableStateFlow(Snapshot())
        val state = mutable.asStateFlow()
        internal var open = true
        internal var leases = 0
        internal var idle = CompletableDeferred<Unit>().also { it.complete(Unit) }
        internal val cleanup = Mutex()
    }

    internal inner class Lease internal constructor(
        val session: Session,
        val kind: Kind,
        val output: File,
        val source: File?,
        val pages: List<File>,
    ) {
        internal var finished = false
    }

    private val gate = Any()
    private val sessions = mutableMapOf<String, Session>()
    private val routeEpochs = mutableMapOf<String, Long>()
    private val preparation = Mutex()
    @Volatile private var prepared = false
    internal fun registeredSessions(): Int = synchronized(gate) { sessions.size }
    fun routeToken(examId: String): Long = synchronized(gate) { routeEpochs[examId] ?: 0L }

    suspend fun prepare() = preparation.withLock {
        if (!prepared) {
            // A process restart has no live leases. Sweep only this fixed demo staging root.
            withContext(Dispatchers.IO) {
                if (root.exists()) check(root.deleteRecursively()) {
                    "Alte Demo-Aufnahmen konnten nicht entfernt werden."
                }
            }
            prepared = true
        }
    }

    fun open(examId: String, generation: Long, resumeId: String?, routeToken: Long): Session = synchronized(gate) {
        check(prepared) { "Demo-Scanner ist noch nicht bereit." }
        check((routeEpochs[examId] ?: 0L) == routeToken) { "Der Demo-Scanner wurde verlassen." }
        sessions[resumeId]?.takeIf {
            it.open && it.examId == examId && it.generation == generation
        } ?: Session(id(), examId, generation).also { sessions[it.id] = it }
    }

    fun reserve(session: Session, kind: Kind): Lease? = synchronized(gate) {
        if (!session.open || sessions[session.id] !== session || session.leases != 0) return@synchronized null
        val source = session.state.value.current
        if (kind == Kind.CROP && source == null) return@synchronized null
        if (kind == Kind.CAMERA && source != null) return@synchronized null
        if (kind == Kind.PDF && (source != null || session.state.value.pages.isEmpty())) return@synchronized null
        if (session.leases == 0) session.idle = CompletableDeferred()
        session.leases++
        session.mutable.value = session.state.value.copy(busy = true, error = null)
        Lease(session, kind, File(session.folder, "${id()}.${if (kind == Kind.PDF) "pdf" else "jpg"}"),
            source, session.state.value.pages.toList())
    }

    fun mayAttach(lease: Lease): Boolean = synchronized(gate) {
        !lease.finished && lease.kind == Kind.PDF && lease.session.open &&
            sessions[lease.session.id] === lease.session
    }

    fun mayWrite(lease: Lease): Boolean = synchronized(gate) {
        !lease.finished && lease.session.open && sessions[lease.session.id] === lease.session
    }

    fun finishCapture(lease: Lease, saved: Boolean): Boolean = finish(lease, saved) { old ->
        if (old.current != null) null else old.copy(current = lease.output)
    }

    fun finishCrop(lease: Lease, written: Boolean): Boolean = finish(lease, written) { old ->
        if (old.current != lease.source) null else old.copy(
            current = null, pages = old.pages + lease.output)
    }

    fun finishPdf(lease: Lease, attached: Boolean): Boolean = finish(lease, attached) { old ->
        old.copy(open = false, completed = true)
    }.also { accepted ->
        if (accepted) close(lease.session.id)
    }

    fun abort(lease: Lease) { finish(lease, false) { null } }

    fun reportError(session: Session, message: String) = synchronized(gate) {
        if (session.open && sessions[session.id] === session)
            session.mutable.value = session.state.value.copy(error = message)
    }

    private fun finish(lease: Lease, accept: Boolean, publish: (Snapshot) -> Snapshot?): Boolean {
        val accepted = synchronized(gate) {
            if (lease.finished) return false
            val session = lease.session
            val next = if (accept && session.open && sessions[session.id] === session)
                publish(session.state.value) else null
            lease.finished = true
            if (next != null) session.open = next.open
            if (next != null) session.mutable.value = next
            next != null
        }
        if (!accepted || lease.kind == Kind.PDF) lease.output.delete()
        if (accepted && lease.kind == Kind.CROP) lease.source?.delete()
        synchronized(gate) {
            val session = lease.session
            session.leases--
            session.mutable.value = session.state.value.copy(busy = session.leases > 0)
            if (session.leases == 0) session.idle.complete(Unit)
        }
        return accepted
    }

    fun discardCurrent(session: Session): Boolean {
        val file = synchronized(gate) {
            if (!session.open || session.leases != 0) return@synchronized null
            session.state.value.current?.also { session.mutable.value = session.state.value.copy(current = null) }
        }
        file?.delete()
        return file != null
    }

    fun removePage(session: Session, page: File): Boolean {
        val removed = synchronized(gate) {
            val old = session.state.value
            if (!session.open || session.leases != 0 || page !in old.pages) return@synchronized false
            session.mutable.value = old.copy(pages = old.pages - page)
            true
        }
        if (removed) page.delete()
        return removed
    }

    fun movePageEarlier(session: Session, page: File): Boolean = synchronized(gate) {
        val old = session.state.value
        if (!session.open || session.leases != 0) return@synchronized false
        val index = old.pages.indexOf(page)
        if (index <= 0) return@synchronized false
        val pages = old.pages.toMutableList()
        java.util.Collections.swap(pages, index, index - 1)
        session.mutable.value = old.copy(pages = pages)
        true
    }

    /** Close admission immediately; the owner scope drains and deletes without UI lifecycle dependence. */
    fun close(id: String): Job? {
        val session = synchronized(gate) {
            sessions[id]?.also {
                it.open = false
                it.mutable.value = it.state.value.copy(open = false)
            }
        } ?: return null
        return scope.launch {
            try {
                drainAndDelete(listOf(session))
            } catch (e: Exception) {
                android.util.Log.e("DemoScanSessions", "Demo scan staging cleanup failed", e)
                synchronized(gate) {
                    session.mutable.value = session.state.value.copy(
                        error = "Demo-Aufnahme konnte nicht entfernt werden. Bitte Testdaten löschen.")
                }
            }
        }
    }

    fun closeForExam(examId: String) {
        val ids = synchronized(gate) {
            routeEpochs[examId] = (routeEpochs[examId] ?: 0L) + 1
            sessions.values.filter { it.examId == examId }.onEach {
                it.open = false
                it.mutable.value = it.state.value.copy(open = false)
            }.map { it.id }
        }
        ids.forEach(::close)
    }

    fun closeAll() { closeAllAdmission().forEach { close(it.id) } }

    fun closeAllAdmission(): List<Session> = synchronized(gate) {
        sessions.values.toList().also { list ->
            list.forEach { it.open = false; it.mutable.value = it.state.value.copy(open = false) }
        }
    }

    suspend fun drainAndDelete(closed: List<Session>) {
        closed.forEach { session ->
            session.cleanup.withLock {
                session.idle.await()
                withContext(Dispatchers.IO) {
                    if (session.folder.exists()) check(session.folder.deleteRecursively()) {
                        "Demo-Aufnahmen konnten nicht entfernt werden."
                    }
                }
                synchronized(gate) { if (sessions[session.id] === session) sessions.remove(session.id) }
            }
        }
    }

    suspend fun drainAndDeleteAll(closed: List<Session>) {
        drainAndDelete(closed)
        preparation.withLock {
            withContext(Dispatchers.IO) {
                if (root.exists()) check(root.deleteRecursively()) {
                    "Demo-Aufnahmen konnten nicht entfernt werden."
                }
            }
        }
    }
}
