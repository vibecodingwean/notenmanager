package de.streberalarm.app

import de.streberalarm.core.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shares the repository lock. Publish only after durable commit, including on UI cancellation. */
internal class VocabularyPersistence(
    private val lock: Mutex,
    private val state: MutableStateFlow<SchoolData?>,
    private val generation: () -> Long,
    private val persist: suspend (SchoolData) -> Unit,
) {
    suspend fun update(expectedGeneration: Long, change: (SchoolData) -> SchoolData) {
        lock.withLock {
            require(generation() == expectedGeneration) {
                "Die Daten wurden gewechselt. Bitte neu öffnen."
            }
            val next = Defaults.updateCatalog(change(requireNotNull(state.value)))
            next.validate()
            // Once dispatched, finish this small transaction and its readback publication together.
            withContext(NonCancellable) {
                persist(next)
                state.value = next
            }
        }
    }
}
