package de.streberalarm.core

import kotlinx.serialization.Serializable

private const val MAX_SETS = 1000
private const val MAX_CARDS = 10000
private const val MAX_ROUND = 20

@Serializable data class FlashcardSet(val id: String, val profileId: String, val name: String)

@Serializable
data class Flashcard(
    val id: String,
    val setId: String,
    val front: String,
    val back: String,
    val revision: Int = 1,
)

@Serializable
data class FlashcardProgress(
    val cardId: String,
    val revision: Int,
    val known: Long = 0,
    val practice: Long = 0,
)

@Serializable
enum class FlashcardPhase {
    QUESTION,
    REVEALED,
    FEEDBACK,
    COMPLETE,
}

@Serializable
data class FlashcardRoundCount(val cardId: String, val known: Int = 0, val practice: Int = 0)

@Serializable
data class FlashcardSession(
    val id: String,
    val profileId: String,
    val setId: String,
    val selected: List<String>,
    val pending: List<String>,
    val completed: Set<String> = emptySet(),
    val results: List<FlashcardRoundCount>,
    val phase: FlashcardPhase = FlashcardPhase.QUESTION,
    val feedbackCardId: String? = null,
    val feedbackKnown: Boolean? = null,
    val turn: Long = 0,
) {
    val currentCardId: String?
        get() =
            if (phase == FlashcardPhase.QUESTION || phase == FlashcardPhase.REVEALED)
                pending.firstOrNull()
            else null

    val openCards: Int
        get() = pending.size

    val failedCards: Set<String>
        get() = results.filter { it.practice > 0 }.map { it.cardId }.toSet()
}

private fun flashControl(c: Char) = c.code in 0..31 || c.code in 127..159

private fun flashId(value: String) {
    require(value.isNotBlank() && value.length <= 100 && value.none(::flashControl)) {
        "Ungültige Karten-ID."
    }
}

private fun flashText(value: String, limit: Int): String {
    require(value.none(::flashControl)) { "Text enthält Steuerzeichen." }
    return value.trim().also {
        require(it.isNotEmpty() && it.length <= limit) { "Text muss 1 bis $limit Zeichen haben." }
    }
}

fun SchoolData.validateFlashcards() {
    require(
        flashcardSets.size <= MAX_SETS &&
            flashcards.size <= MAX_CARDS &&
            flashcardProgress.size <= flashcards.size &&
            flashcardSessions.size <= profiles.size
    ) {
        "Zu viele Lernkarten."
    }
    fun <T> unique(items: List<T>, key: (T) -> String) =
        require(items.map(key).distinct().size == items.size) { "Doppelte Karten-ID." }
    unique(flashcardSets) { it.id }
    unique(flashcards) { it.id }
    unique(flashcardProgress) { it.cardId }
    unique(flashcardSessions) { it.profileId }
    unique(flashcardSessions) { it.id }
    val owners = profiles.map { it.id }.toSet()
    val sets = flashcardSets.associateBy { it.id }
    val cards = flashcards.associateBy { it.id }
    flashcardSets.forEach {
        flashId(it.id)
        flashId(it.profileId)
        require(it.profileId in owners)
        flashText(it.name, 80)
    }
    require(
        flashcardSets.map { it.profileId to it.name.trim().lowercase() }.distinct().size ==
            flashcardSets.size
    ) {
        "Dieses Set gibt es bereits."
    }
    require(flashcards.groupingBy { it.setId }.eachCount().values.all { it <= 2000 })
    flashcards.forEach {
        flashId(it.id)
        flashId(it.setId)
        require(it.setId in sets && it.revision > 0)
        flashText(it.front, 300)
        flashText(it.back, 500)
    }
    flashcardProgress.forEach {
        flashId(it.cardId)
        require(cards[it.cardId]?.revision == it.revision)
        require(it.known in 0..1_000_000_000 && it.practice in 0..1_000_000_000)
    }
    flashcardSessions.forEach { s ->
        flashId(s.id)
        flashId(s.profileId)
        flashId(s.setId)
        require(sets[s.setId]?.profileId == s.profileId)
        require(s.selected.size in 1..MAX_ROUND && s.selected.distinct().size == s.selected.size)
        require(s.selected.all { cards[it]?.setId == s.setId })
        require(s.pending.size <= MAX_ROUND && s.pending.distinct().size == s.pending.size)
        require(
            s.completed.intersect(s.pending.toSet()).isEmpty() &&
                s.completed + s.pending == s.selected.toSet()
        )
        require(
            s.results.map { it.cardId }.toSet() == s.selected.toSet() &&
                s.results.size == s.selected.size
        )
        require(
            s.results.all {
                it.known == (if (it.cardId in s.completed) 1 else 0) && it.practice in 0..1_000_000
            }
        )
        require(s.turn == s.results.sumOf { it.known.toLong() + it.practice })
        s.results.forEach { r ->
            val p = flashcardProgress.find { it.cardId == r.cardId }
            require(r.known <= (p?.known ?: 0) && r.practice <= (p?.practice ?: 0))
        }
        when (s.phase) {
            FlashcardPhase.QUESTION,
            FlashcardPhase.REVEALED ->
                require(
                    s.pending.isNotEmpty() && s.feedbackCardId == null && s.feedbackKnown == null
                )
            FlashcardPhase.COMPLETE ->
                require(s.pending.isEmpty() && s.feedbackCardId == null && s.feedbackKnown == null)
            FlashcardPhase.FEEDBACK -> {
                require(s.feedbackCardId in s.selected && s.feedbackKnown != null && s.turn > 0)
                require(
                    if (s.feedbackKnown == true) s.feedbackCardId in s.completed
                    else s.feedbackCardId in s.pending
                )
            }
        }
    }
}

/** Pure commands; call with the latest SchoolData under the repository lock. */
object Flashcards {
    private fun checked(data: SchoolData) = data.also { it.validate() }

    private fun owner(data: SchoolData, profileId: String) =
        require(data.activeProfileId == profileId && data.profiles.any { it.id == profileId }) {
            "Das Schulprofil hat sich geändert."
        }

    private fun set(data: SchoolData, profileId: String, setId: String): FlashcardSet {
        owner(data, profileId)
        return requireNotNull(
            data.flashcardSets.find { it.id == setId && it.profileId == profileId }
        ) {
            "Set nicht gefunden."
        }
    }

    fun createSet(data: SchoolData, profileId: String, setId: String, name: String): SchoolData {
        owner(data, profileId)
        flashId(setId)
        val candidate = FlashcardSet(setId, profileId, flashText(name, 80))
        data.flashcardSets
            .find { it.id == setId }
            ?.let { old ->
                require(old == candidate) { "Set-ID ist bereits vergeben." }
                return checked(data)
            }
        return checked(data.copy(flashcardSets = data.flashcardSets + candidate))
    }

    fun renameSet(
        data: SchoolData,
        profileId: String,
        setId: String,
        expectedName: String,
        name: String,
    ): SchoolData {
        val old = set(data, profileId, setId)
        require(old.name == expectedName) { "Das Set wurde geändert." }
        return checked(
            data.copy(
                flashcardSets =
                    data.flashcardSets.map {
                        if (it.id == setId) it.copy(name = flashText(name, 80)) else it
                    }
            )
        )
    }

    fun deleteSet(data: SchoolData, profileId: String, setId: String): SchoolData {
        set(data, profileId, setId)
        val ids = data.flashcards.filter { it.setId == setId }.map { it.id }.toSet()
        return checked(
            data.copy(
                flashcardSets = data.flashcardSets.filterNot { it.id == setId },
                flashcards = data.flashcards.filterNot { it.id in ids },
                flashcardProgress = data.flashcardProgress.filterNot { it.cardId in ids },
                flashcardSessions = data.flashcardSessions.filterNot { it.setId == setId },
            )
        )
    }

    fun saveCard(
        data: SchoolData,
        profileId: String,
        setId: String,
        cardId: String,
        expectedRevision: Int?,
        front: String,
        back: String,
    ): SchoolData {
        set(data, profileId, setId)
        flashId(cardId)
        val old = data.flashcards.find { it.id == cardId }
        val candidate =
            Flashcard(
                cardId,
                setId,
                flashText(front, 300),
                flashText(back, 500),
                (old?.revision ?: 1),
            )
        if (expectedRevision == null) {
            if (old != null) {
                require(old == candidate && old.revision == 1) { "Karten-ID ist bereits vergeben." }
                return checked(data)
            }
            return checked(data.copy(flashcards = data.flashcards + candidate))
        }
        require(old?.setId == setId && old.revision == expectedRevision) {
            "Die Karte wurde geändert oder gelöscht."
        }
        if (old == candidate) return checked(data)
        require(old.revision < Int.MAX_VALUE)
        return checked(
            data.copy(
                flashcards =
                    data.flashcards.map {
                        if (it.id == cardId) candidate.copy(revision = old.revision + 1) else it
                    },
                flashcardProgress = data.flashcardProgress.filterNot { it.cardId == cardId },
                flashcardSessions = data.flashcardSessions.filterNot { cardId in it.selected },
            )
        )
    }

    fun deleteCard(
        data: SchoolData,
        profileId: String,
        cardId: String,
        expectedRevision: Int,
    ): SchoolData {
        val card =
            requireNotNull(
                data.flashcards.find { it.id == cardId && it.revision == expectedRevision }
            ) {
                "Die Karte wurde geändert oder gelöscht."
            }
        set(data, profileId, card.setId)
        return checked(
            data.copy(
                flashcards = data.flashcards.filterNot { it.id == cardId },
                flashcardProgress = data.flashcardProgress.filterNot { it.cardId == cardId },
                flashcardSessions = data.flashcardSessions.filterNot { cardId in it.selected },
            )
        )
    }

    fun start(
        data: SchoolData,
        profileId: String,
        setId: String,
        sessionId: String,
        expectedSessionId: String? = null,
        replace: Boolean = false,
        onlyOpen: Boolean = false,
    ): SchoolData {
        set(data, profileId, setId)
        flashId(sessionId)
        val old = data.flashcardSessions.find { it.profileId == profileId }
        require(
            old?.id == expectedSessionId &&
                (old == null || old.phase == FlashcardPhase.COMPLETE || replace)
        ) {
            "Bitte die offene Runde fortsetzen oder ausdrücklich ersetzen."
        }
        val cards =
            if (onlyOpen) {
                require(old?.setId == setId && old.phase == FlashcardPhase.COMPLETE)
                data.flashcards.filter { it.id in old.failedCards }
            } else data.flashcards.filter { it.setId == setId }
        require(cards.isNotEmpty()) { "Für diese Runde gibt es keine Karten." }
        val progress = data.flashcardProgress.associateBy { it.cardId }
        val order = cards.mapIndexed { index, card -> card.id to index }.toMap()
        val selected =
            cards
                .sortedWith(
                    compareBy<Flashcard>(
                        { (progress[it.id]?.known ?: 0) + (progress[it.id]?.practice ?: 0) },
                        { order.getValue(it.id) },
                    )
                )
                .take(MAX_ROUND)
                .map { it.id }
        val next =
            FlashcardSession(
                sessionId,
                profileId,
                setId,
                selected,
                selected,
                results = selected.map { FlashcardRoundCount(it) },
            )
        return checked(
            data.copy(
                flashcardSessions =
                    data.flashcardSessions.filterNot { it.profileId == profileId } + next
            )
        )
    }

    private fun session(
        data: SchoolData,
        profileId: String,
        sessionId: String,
        turn: Long,
        phase: FlashcardPhase,
    ): FlashcardSession {
        owner(data, profileId)
        return requireNotNull(
            data.flashcardSessions.find {
                it.profileId == profileId &&
                    it.id == sessionId &&
                    it.turn == turn &&
                    it.phase == phase
            }
        ) {
            "Diese Aktion wurde bereits verarbeitet oder die Runde geändert."
        }
    }

    fun reveal(
        data: SchoolData,
        profileId: String,
        sessionId: String,
        turn: Long,
        cardId: String,
    ): SchoolData {
        val old = session(data, profileId, sessionId, turn, FlashcardPhase.QUESTION)
        require(old.currentCardId == cardId)
        return checked(
            data.copy(
                flashcardSessions =
                    data.flashcardSessions.map {
                        if (it.id == sessionId) old.copy(phase = FlashcardPhase.REVEALED) else it
                    }
            )
        )
    }

    fun grade(
        data: SchoolData,
        profileId: String,
        sessionId: String,
        turn: Long,
        cardId: String,
        known: Boolean,
    ): SchoolData {
        val old = session(data, profileId, sessionId, turn, FlashcardPhase.REVEALED)
        require(old.currentCardId == cardId)
        val count = old.results.first { it.cardId == cardId }
        require(!known || count.known == 0)
        require(known || count.practice < 1_000_000)
        val card = data.flashcards.single { it.id == cardId && it.setId == old.setId }
        val previous =
            data.flashcardProgress.find { it.cardId == cardId }
                ?: FlashcardProgress(cardId, card.revision)
        require(previous.known < 1_000_000_000 && previous.practice < 1_000_000_000)
        val progress =
            previous.copy(
                known = previous.known + if (known) 1 else 0,
                practice = previous.practice + if (known) 0 else 1,
            )
        // A missed card returns after all other open cards, not immediately after the flip.
        val pending = old.pending.drop(1) + if (known) emptyList() else listOf(cardId)
        val next =
            old.copy(
                pending = pending,
                completed = if (known) old.completed + cardId else old.completed,
                results =
                    old.results.map {
                        if (it.cardId == cardId)
                            it.copy(
                                known = it.known + if (known) 1 else 0,
                                practice = it.practice + if (known) 0 else 1,
                            )
                        else it
                    },
                phase = FlashcardPhase.FEEDBACK,
                feedbackCardId = cardId,
                feedbackKnown = known,
                turn = old.turn + 1,
            )
        return checked(
            data.copy(
                flashcardProgress =
                    data.flashcardProgress.filterNot { it.cardId == cardId } + progress,
                flashcardSessions =
                    data.flashcardSessions.map { if (it.id == sessionId) next else it },
            )
        )
    }

    fun continueRound(
        data: SchoolData,
        profileId: String,
        sessionId: String,
        turn: Long,
    ): SchoolData {
        val old = session(data, profileId, sessionId, turn, FlashcardPhase.FEEDBACK)
        val next =
            old.copy(
                phase =
                    if (old.pending.isEmpty()) FlashcardPhase.COMPLETE else FlashcardPhase.QUESTION,
                feedbackCardId = null,
                feedbackKnown = null,
            )
        return checked(
            data.copy(
                flashcardSessions =
                    data.flashcardSessions.map { if (it.id == sessionId) next else it }
            )
        )
    }
}

fun SchoolData.removeProfileFlashcards(profileId: String): SchoolData {
    val setIds = flashcardSets.filter { it.profileId == profileId }.map { it.id }.toSet()
    val cardIds = flashcards.filter { it.setId in setIds }.map { it.id }.toSet()
    return copy(
        flashcardSets = flashcardSets.filterNot { it.id in setIds },
        flashcards = flashcards.filterNot { it.id in cardIds },
        flashcardProgress = flashcardProgress.filterNot { it.cardId in cardIds },
        flashcardSessions = flashcardSessions.filterNot { it.profileId == profileId },
    )
}
