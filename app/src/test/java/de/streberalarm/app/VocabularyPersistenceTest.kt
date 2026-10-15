package de.streberalarm.app

import de.streberalarm.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class VocabularyPersistenceTest {
    private val profile = Profile(id = "profile")
    private val date = SchoolDate.parse("2026-10-11")

    private fun fixture(): SchoolData {
        var d = SchoolData(profiles = listOf(profile), activeProfileId = profile.id)
        d = Vocabulary.createCollection(d, profile.id, "collection", "Tiere")
        d = Vocabulary.saveCard(d, profile.id, "collection", "word", null, "Hund", "dog")
        return Vocabulary.startRound(d, profile.id, "collection", "round", VocabularyMode.ALL, date)
    }

    private fun answer(d: SchoolData) =
        Vocabulary.submitAnswer(d, profile.id, "round", 0, "word", 1, "dog", date)

    @Test
    fun failureDoesNotPublishAndRetryCommitsOnlyOnce() = runBlocking {
        val initial = fixture()
        val state = MutableStateFlow<SchoolData?>(initial)
        var fail = true
        var stored = dataJson.encodeToString(initial)
        var commits = 0
        val writer =
            VocabularyPersistence(Mutex(), state, { 0 }) {
                if (fail) throw java.io.IOException("Injected precommit failure")
                stored = dataJson.encodeToString(it)
                commits++
            }
        try {
            writer.update(0, ::answer)
            fail("Expected failure")
        } catch (_: java.io.IOException) {}
        assertEquals(initial, state.value)
        assertEquals(initial, dataJson.decodeFromString<SchoolData>(stored))
        fail = false
        writer.update(0, ::answer)
        assertEquals(1, commits)
        assertEquals(VocabularyPhase.FEEDBACK, state.value!!.vocabularySessions.single().phase)
        assertEquals(1L, state.value!!.vocabularySessions.single().correctAttempts)
        assertEquals(state.value, dataJson.decodeFromString<SchoolData>(stored))
    }

    @Test
    fun stateRemainsOldUntilWriteCompletes() = runBlocking {
        val state = MutableStateFlow<SchoolData?>(fixture())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer =
            VocabularyPersistence(Mutex(), state, { 0 }) {
                started.complete(Unit)
                release.await()
            }
        val job = launch { writer.update(0, ::answer) }
        started.await()
        assertEquals(VocabularyPhase.ASKING, state.value!!.vocabularySessions.single().phase)
        assertEquals(0L, state.value!!.vocabularySessions.single().correctAttempts)
        release.complete(Unit)
        job.join()
        assertEquals(VocabularyPhase.FEEDBACK, state.value!!.vocabularySessions.single().phase)
    }

    @Test
    fun simultaneousCapturedSubmitsDoNotDoubleCount() = runBlocking {
        val state = MutableStateFlow<SchoolData?>(fixture())
        var commits = 0
        val writer =
            VocabularyPersistence(Mutex(), state, { 0 }) {
                yield()
                commits++
            }
        val results =
            (1..2).map { async { runCatching { writer.update(0, ::answer) }.isSuccess } }.awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(1, commits)
        assertEquals(1L, state.value!!.vocabularySessions.single().turn)
        assertEquals(1, state.value!!.vocabularySessions.single().completedWords)
    }

    @Test
    fun cancellingUiDuringCommitStillPublishesDurableFeedback() = runBlocking {
        val state = MutableStateFlow<SchoolData?>(fixture())
        var stored = dataJson.encodeToString(state.value!!)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer =
            VocabularyPersistence(Mutex(), state, { 0 }) {
                started.complete(Unit)
                release.await()
                stored = dataJson.encodeToString(it)
            }
        val job = launch { writer.update(0, ::answer) }
        started.await()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertEquals(state.value, dataJson.decodeFromString<SchoolData>(stored))
        assertEquals(VocabularyPhase.FEEDBACK, state.value!!.vocabularySessions.single().phase)
        assertEquals(1L, state.value!!.vocabularySessions.single().turn)
    }

    @Test
    fun cancelledWaitingCommandNeverCommits() = runBlocking {
        val initial = fixture()
        val state = MutableStateFlow<SchoolData?>(initial)
        val lock = Mutex(locked = true)
        var commits = 0
        val writer = VocabularyPersistence(lock, state, { 0 }) { commits++ }
        val waiting = launch { writer.update(0, ::answer) }
        yield()
        waiting.cancelAndJoin()
        lock.unlock()
        assertEquals(0, commits)
        assertEquals(initial, state.value)
    }

    @Test
    fun restoredIdenticalIdsStillRejectOldGenerationAndKeepProfilesSeparate() = runBlocking {
        val initial = fixture()
        val second = profile.copy(id = "second")
        val state = MutableStateFlow<SchoolData?>(initial.copy(profiles = listOf(profile, second)))
        var epoch = 1L
        var commits = 0
        val writer = VocabularyPersistence(Mutex(), state, { epoch }) { commits++ }
        try {
            writer.update(0, ::answer)
            fail("Stale restore command")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, commits)
        state.value = state.value!!.copy(activeProfileId = second.id)
        epoch++
        try {
            writer.update(epoch, ::answer)
            fail("Inactive profile command")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, commits)
        assertEquals(initial.vocabularySessions, state.value!!.vocabularySessions)
    }
}
