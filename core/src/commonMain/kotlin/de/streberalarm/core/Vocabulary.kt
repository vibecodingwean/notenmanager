package de.streberalarm.core

import kotlinx.serialization.Serializable

const val MAX_VOCABULARY_COLLECTIONS = 1000
const val MAX_VOCABULARY_CARDS = 10000
const val MAX_VOCABULARY_CARDS_PER_COLLECTION = 2000
const val MAX_VOCABULARY_ROUND_CARDS = 10
const val MAX_VOCABULARY_ATTEMPTS = 1_000_000_000L
const val MAX_VOCABULARY_ROUND_WRONG = 1_000_000L
const val MAX_VOCABULARY_RESPONSE = 120

@Serializable
data class VocabularyCollection(val id: String, val profileId: String, val name: String)

@Serializable
data class VocabularyCard(
    val id: String,
    val collectionId: String,
    val german: String,
    val english: String,
    val alternatives: List<String> = emptyList(),
    val revision: Int = 1,
)

@Serializable
data class VocabularyProgress(
    val cardId: String,
    val revision: Int,
    val stage: Int = 0,
    val dueDate: String? = null,
    val lastScheduleDate: String? = null,
    val correctAttempts: Long = 0,
    val wrongAttempts: Long = 0,
)

@Serializable
enum class VocabularyMode {
    ALL,
    UNSURE,
    DUE,
}

@Serializable
enum class VocabularyPhase {
    ASKING,
    REVEALED,
    FEEDBACK,
    COMPLETE,
}

@Serializable enum class VocabularyStyle { TYPED, FLIP }

enum class VocabularyReplay {
    ALL,
    FAILED,
}

@Serializable data class VocabularyCardRevision(val cardId: String, val revision: Int)

@Serializable
data class VocabularyRoundCounts(
    val cardId: String,
    val correctAttempts: Long = 0,
    val wrongAttempts: Long = 0,
)

@Serializable
data class VocabularyFeedback(
    val cardId: String,
    val revision: Int,
    val response: String,
    val correct: Boolean,
    val turn: Long,
    val dontKnow: Boolean = false,
    val selfGraded: Boolean = false,
)

@Serializable
data class VocabularySession(
    val id: String,
    val profileId: String,
    val collectionId: String,
    val mode: VocabularyMode,
    val selected: List<VocabularyCardRevision>,
    val pending: List<String>,
    val completed: Set<String> = emptySet(),
    val results: List<VocabularyRoundCounts>,
    val phase: VocabularyPhase = VocabularyPhase.ASKING,
    val feedback: VocabularyFeedback? = null,
    val turn: Long = 0,
    val style: VocabularyStyle = VocabularyStyle.TYPED,
) {
    val currentCardId: String?
        get() = if (phase == VocabularyPhase.ASKING || phase == VocabularyPhase.REVEALED) pending.firstOrNull() else null

    val correctAttempts: Long
        get() = results.sumOf { it.correctAttempts }

    val wrongAttempts: Long
        get() = results.sumOf { it.wrongAttempts }

    val failedCardIds: Set<String>
        get() = results.filter { it.wrongAttempts > 0 }.map { it.cardId }.toSet()

    val completedWords: Int
        get() = completed.size

    val openWords: Int
        get() = pending.size
}

/** Case and whitespace only: punctuation, accents and word order stay meaningful. */
fun normalizeVocabularyAnswer(value: String): String {
    val result = StringBuilder()
    var space = false
    for (char in value.trim()) {
        if (char.isWhitespace()) space = true
        else {
            if (space && result.isNotEmpty()) result.append(' ')
            result.append(char)
            space = false
        }
    }
    return result.toString().lowercase()
}

fun VocabularyCard.acceptsVocabularyAnswer(response: String): Boolean {
    val normalized = normalizeVocabularyAnswer(response)
    return normalized.isNotEmpty() &&
        (listOf(english) + alternatives).any { normalizeVocabularyAnswer(it) == normalized }
}

private fun vocabularyId(value: String) {
    require(value.isNotBlank() && value.length <= 100 && value.none(::vocabularyControl)) {
        "Ungültige Vokabel-ID."
    }
}

private fun vocabularyControl(c: Char) = c.code in 0..31 || c.code in 127..159

private fun vocabularyText(value: String, max: Int) {
    require(value.isNotBlank() && value.length <= max && value.none(::vocabularyControl)) {
        "Text darf nicht leer sein oder Steuerzeichen enthalten (maximal $max Zeichen)."
    }
}

private fun canonicalVocabularyText(value: String, max: Int): String {
    require(value.none(::vocabularyControl)) { "Text darf keine Steuerzeichen enthalten." }
    return value.trim().also { vocabularyText(it, max) }
}

private fun vocabularyDate(value: String): SchoolDate {
    val date = SchoolDate.parse(value)
    require(
        date.toString() == value && date in SchoolDate.of(2000, 1, 1)..SchoolDate.of(2200, 12, 31)
    ) {
        "Vokabeldatum muss zwischen 2000 und 2200 liegen."
    }
    return date
}

private fun vocabularyToday(today: SchoolDate): SchoolDate = vocabularyDate(today.toString())

/** Strict import/write validation; malformed sessions never turn into earned progress. */
fun SchoolData.validateVocabulary() {
    require(
        vocabularyCollections.size <= MAX_VOCABULARY_COLLECTIONS &&
            vocabularyCards.size <= MAX_VOCABULARY_CARDS &&
            vocabularyProgress.size <= vocabularyCards.size &&
            vocabularySessions.size <= profiles.size
    ) {
        "Zu viele Vokabeleinträge."
    }
    fun <T> unique(items: List<T>, key: (T) -> String) {
        require(items.map(key).distinct().size == items.size) { "Doppelte Vokabel-ID." }
    }
    unique(vocabularyCollections) { it.id }
    unique(vocabularyCards) { it.id }
    unique(vocabularyProgress) { it.cardId }
    unique(vocabularySessions) { it.id }
    unique(vocabularySessions) { it.profileId }
    val owners = profiles.map { it.id }.toSet()
    val collections = vocabularyCollections.associateBy { it.id }
    val cards = vocabularyCards.associateBy { it.id }
    vocabularyCollections.forEach {
        vocabularyId(it.id)
        vocabularyId(it.profileId)
        require(it.profileId in owners)
        vocabularyText(it.name, 80)
    }
    require(
        vocabularyCollections
            .map { it.profileId to normalizeVocabularyAnswer(it.name) }
            .distinct()
            .size == vocabularyCollections.size
    ) {
        "Diese Sammlung gibt es bereits."
    }
    require(
        vocabularyCards
            .groupingBy { it.collectionId }
            .eachCount()
            .values
            .all { it <= MAX_VOCABULARY_CARDS_PER_COLLECTION }
    )
    vocabularyCards.forEach {
        vocabularyId(it.id)
        vocabularyId(it.collectionId)
        require(it.collectionId in collections && it.revision >= 1)
        vocabularyText(it.german, 160)
        vocabularyText(it.english, 120)
        require(it.alternatives.size <= 4)
        it.alternatives.forEach { answer -> vocabularyText(answer, 120) }
        val answers = (listOf(it.english) + it.alternatives).map(::normalizeVocabularyAnswer)
        require(answers.distinct().size == answers.size) { "Doppelte Übersetzung." }
    }
    require(
        vocabularyCards
            .map { it.collectionId to normalizeVocabularyAnswer(it.german) }
            .distinct()
            .size == vocabularyCards.size
    ) {
        "Dieses Wort gibt es in der Sammlung bereits."
    }
    vocabularyProgress.forEach {
        vocabularyId(it.cardId)
        require(cards[it.cardId]?.revision == it.revision)
        require(
            it.stage in 0..5 &&
                it.correctAttempts in 0..MAX_VOCABULARY_ATTEMPTS &&
                it.wrongAttempts in 0..MAX_VOCABULARY_ATTEMPTS
        )
        val due = it.dueDate?.let(::vocabularyDate)
        val last = it.lastScheduleDate?.let(::vocabularyDate)
        require((due == null) == (last == null))
        if (it.stage > 0)
            require(due != null && last != null && due > last && it.correctAttempts > 0)
        if (it.correctAttempts + it.wrongAttempts > 0) require(due != null && last != null)
    }
    vocabularySessions.forEach { session ->
        vocabularyId(session.id)
        vocabularyId(session.profileId)
        vocabularyId(session.collectionId)
        require(collections[session.collectionId]?.profileId == session.profileId)
        require(session.selected.size in 1..MAX_VOCABULARY_ROUND_CARDS)
        val selected = session.selected.associateBy { it.cardId }
        require(selected.size == session.selected.size)
        session.selected.forEach {
            require(
                cards[it.cardId]?.let { card ->
                    card.collectionId == session.collectionId && card.revision == it.revision
                } == true
            )
        }
        require(
            session.pending.size <= MAX_VOCABULARY_ROUND_CARDS &&
                session.completed.size <= MAX_VOCABULARY_ROUND_CARDS &&
                session.results.size == selected.size
        )
        require(session.pending.distinct().size == session.pending.size)
        require(
            session.pending.toSet().intersect(session.completed).isEmpty() &&
                session.pending.toSet() + session.completed == selected.keys
        )
        require(
            session.results.map { it.cardId }.toSet() == selected.keys &&
                session.results.map { it.cardId }.distinct().size == session.results.size
        )
        session.results.forEach {
            require(
                it.correctAttempts == (if (it.cardId in session.completed) 1L else 0L) &&
                    it.wrongAttempts in 0..MAX_VOCABULARY_ROUND_WRONG
            )
        }
        require(session.turn == session.correctAttempts + session.wrongAttempts)
        val progress = vocabularyProgress.associateBy { it.cardId }
        session.results.forEach {
            val learned = progress[it.cardId]
            require(
                it.correctAttempts <= (learned?.correctAttempts ?: 0) &&
                    it.wrongAttempts <= (learned?.wrongAttempts ?: 0)
            )
        }
        when (session.phase) {
            VocabularyPhase.ASKING ->
                require(session.pending.isNotEmpty() && session.feedback == null)
            VocabularyPhase.REVEALED ->
                require(session.style == VocabularyStyle.FLIP && session.pending.isNotEmpty() && session.feedback == null)
            VocabularyPhase.COMPLETE ->
                require(session.pending.isEmpty() && session.feedback == null)
            VocabularyPhase.FEEDBACK -> {
                val feedback = requireNotNull(session.feedback)
                require(
                    feedback.turn == session.turn &&
                        feedback.turn > 0 &&
                        selected[feedback.cardId]?.revision == feedback.revision
                )
                require(
                    feedback.response.length <= MAX_VOCABULARY_RESPONSE &&
                        feedback.response.none(::vocabularyControl)
                )
                val counts = session.results.first { it.cardId == feedback.cardId }
                if (feedback.selfGraded) require(session.style == VocabularyStyle.FLIP && feedback.response.isEmpty() && !feedback.dontKnow)
                else if (feedback.dontKnow) require(feedback.response.isEmpty() && !feedback.correct)
                else
                    require(
                        feedback.response.isNotBlank() &&
                            cards
                                .getValue(feedback.cardId)
                                .acceptsVocabularyAnswer(feedback.response) == feedback.correct
                    )
                if (feedback.correct)
                    require(feedback.cardId in session.completed && counts.correctAttempts == 1L)
                else {
                    require(feedback.cardId in session.pending && counts.wrongAttempts > 0)
                    require(
                        session.pending.indexOf(feedback.cardId) ==
                            minOf(2, session.pending.size - 1)
                    )
                }
            }
        }
    }
}

/**
 * Pure immutable commands. Call against the latest SchoolData inside the repository mutex. IDs and
 * dates are caller-supplied; no clock, side effects, optimistic state or hidden history.
 */
object Vocabulary {
    private val intervals = listOf(1L, 3L, 7L, 14L, 30L)

    private fun SchoolData.owner(profileId: String) {
        require(activeProfileId == profileId && profiles.any { it.id == profileId }) {
            "Das Schulprofil hat sich geändert."
        }
    }

    private fun SchoolData.collection(
        profileId: String,
        collectionId: String,
    ): VocabularyCollection {
        owner(profileId)
        return requireNotNull(
            vocabularyCollections.find { it.id == collectionId && it.profileId == profileId }
        ) {
            "Sammlung nicht gefunden."
        }
    }

    private fun SchoolData.card(profileId: String, cardId: String, revision: Int): VocabularyCard {
        owner(profileId)
        val card =
            requireNotNull(vocabularyCards.find { it.id == cardId }) { "Wort nicht gefunden." }
        collection(profileId, card.collectionId)
        require(card.revision == revision) { "Das Wort wurde inzwischen geändert." }
        return card
    }

    private fun checked(data: SchoolData): SchoolData = data.also { it.validate() }

    fun createCollection(
        data: SchoolData,
        profileId: String,
        collectionId: String,
        name: String,
    ): SchoolData {
        data.owner(profileId)
        val candidate =
            VocabularyCollection(collectionId, profileId, canonicalVocabularyText(name, 80))
        val old = data.vocabularyCollections.find { it.id == collectionId }
        if (old != null) {
            require(old == candidate) { "Sammlungs-ID ist bereits vergeben." }
            return checked(data)
        }
        return checked(data.copy(vocabularyCollections = data.vocabularyCollections + candidate))
    }

    fun renameCollection(
        data: SchoolData,
        profileId: String,
        collectionId: String,
        expectedName: String,
        name: String,
    ): SchoolData {
        val old = data.collection(profileId, collectionId)
        require(old.name == expectedName) { "Die Sammlung wurde inzwischen geändert." }
        val candidate = old.copy(name = canonicalVocabularyText(name, 80))
        if (candidate == old) return checked(data)
        return checked(
            data.copy(
                vocabularyCollections =
                    data.vocabularyCollections.map { if (it.id == collectionId) candidate else it }
            )
        )
    }

    /** null expectedRevision creates with a stable ID; an identical create retry is a no-op. */
    fun saveCard(
        data: SchoolData,
        profileId: String,
        collectionId: String,
        cardId: String,
        expectedRevision: Int?,
        german: String,
        english: String,
        alternatives: List<String> = emptyList(),
    ): SchoolData {
        data.collection(profileId, collectionId)
        val old = data.vocabularyCards.find { it.id == cardId }
        val candidate =
            VocabularyCard(
                cardId,
                collectionId,
                canonicalVocabularyText(german, 160),
                canonicalVocabularyText(english, 120),
                alternatives.map { canonicalVocabularyText(it, 120) },
                old?.revision ?: 1,
            )
        if (expectedRevision == null) {
            if (old != null) {
                require(old == candidate && old.revision == 1) { "Wort-ID ist bereits vergeben." }
                return checked(data)
            }
            return checked(data.copy(vocabularyCards = data.vocabularyCards + candidate))
        }
        require(
            old != null && old.collectionId == collectionId && old.revision == expectedRevision
        ) {
            "Das Wort wurde geändert oder gelöscht."
        }
        if (old == candidate) return checked(data)
        require(old.revision < Int.MAX_VALUE) { "Zu viele Änderungen an diesem Wort." }
        return checked(
            data.copy(
                vocabularyCards =
                    data.vocabularyCards.map {
                        if (it.id == cardId) candidate.copy(revision = old.revision + 1) else it
                    },
                vocabularyProgress = data.vocabularyProgress.filterNot { it.cardId == cardId },
                vocabularySessions =
                    data.vocabularySessions.filterNot { s ->
                        s.selected.any { it.cardId == cardId }
                    },
            )
        )
    }

    fun deleteCard(
        data: SchoolData,
        profileId: String,
        cardId: String,
        expectedRevision: Int,
    ): SchoolData {
        data.card(profileId, cardId, expectedRevision)
        return checked(
            data.copy(
                vocabularyCards = data.vocabularyCards.filterNot { it.id == cardId },
                vocabularyProgress = data.vocabularyProgress.filterNot { it.cardId == cardId },
                vocabularySessions =
                    data.vocabularySessions.filterNot { s ->
                        s.selected.any { it.cardId == cardId }
                    },
            )
        )
    }

    fun deleteCollection(data: SchoolData, profileId: String, collectionId: String): SchoolData {
        data.collection(profileId, collectionId)
        val ids =
            data.vocabularyCards.filter { it.collectionId == collectionId }.map { it.id }.toSet()
        return checked(
            data.copy(
                vocabularyCollections =
                    data.vocabularyCollections.filterNot { it.id == collectionId },
                vocabularyCards = data.vocabularyCards.filterNot { it.id in ids },
                vocabularyProgress = data.vocabularyProgress.filterNot { it.cardId in ids },
                vocabularySessions =
                    data.vocabularySessions.filterNot { it.collectionId == collectionId },
            )
        )
    }

    /** Read-only selector; rounds freeze this deterministic, bounded selection at start. */
    fun eligibleCards(
        data: SchoolData,
        profileId: String,
        collectionId: String,
        mode: VocabularyMode,
        today: SchoolDate,
    ): List<VocabularyCard> {
        require(
            data.vocabularyCollections.any { it.id == collectionId && it.profileId == profileId }
        )
        vocabularyToday(today)
        val progress = data.vocabularyProgress.associateBy { it.cardId }
        return data.vocabularyCards
            .filter { card ->
                card.collectionId == collectionId &&
                    when (mode) {
                        VocabularyMode.ALL -> true
                        VocabularyMode.UNSURE -> (progress[card.id]?.stage ?: 0) == 0
                        VocabularyMode.DUE ->
                            progress[card.id]?.dueDate?.let { vocabularyDate(it) <= today } ?: true
                    }
            }
            .sortedWith(
                compareBy<VocabularyCard>(
                    { progress[it.id]?.dueDate ?: "" },
                    {
                        (progress[it.id]?.correctAttempts ?: 0) +
                            (progress[it.id]?.wrongAttempts ?: 0)
                    },
                    { it.id },
                )
            )
    }

    private fun newSession(
        data: SchoolData,
        profileId: String,
        collectionId: String,
        sessionId: String,
        mode: VocabularyMode,
        cards: List<VocabularyCard>,
        style: VocabularyStyle = VocabularyStyle.TYPED,
    ): SchoolData {
        require(cards.isNotEmpty()) { "Für diese Runde gibt es keine Wörter." }
        vocabularyId(sessionId)
        require(data.vocabularySessions.none { it.id == sessionId }) {
            "Runden-ID ist bereits vergeben."
        }
        val session =
            VocabularySession(
                sessionId,
                profileId,
                collectionId,
                mode,
                cards.map { VocabularyCardRevision(it.id, it.revision) },
                cards.map { it.id },
                results = cards.map { VocabularyRoundCounts(it.id) },
                style = style,
            )
        return checked(
            data.copy(
                vocabularySessions =
                    data.vocabularySessions.filterNot { it.profileId == profileId } + session
            )
        )
    }

    private fun checkReplacement(
        data: SchoolData,
        profileId: String,
        expectedSessionId: String?,
        replaceUnfinished: Boolean,
    ) {
        data.owner(profileId)
        val old = data.vocabularySessions.find { it.profileId == profileId }
        require(old?.id == expectedSessionId) { "Die Runde hat sich geändert." }
        require(old == null || old.phase == VocabularyPhase.COMPLETE || replaceUnfinished) {
            "Bitte die offene Runde fortsetzen oder ausdrücklich ersetzen."
        }
    }

    fun startRound(
        data: SchoolData,
        profileId: String,
        collectionId: String,
        sessionId: String,
        mode: VocabularyMode,
        today: SchoolDate,
        expectedSessionId: String? = null,
        replaceUnfinished: Boolean = false,
        style: VocabularyStyle = VocabularyStyle.TYPED,
    ): SchoolData {
        data.collection(profileId, collectionId)
        checkReplacement(data, profileId, expectedSessionId, replaceUnfinished)
        val eligible = eligibleCards(data, profileId, collectionId, mode, today)
        val previous = data.vocabularySessions.find { it.profileId == profileId }
        // Early successes must not leave the same ten future/failed cards at the front forever.
        // On follow-up rounds, balance lifetime attempts; the original due ordering breaks ties.
        // No extra cursor/history is persisted, and explicit replay bypasses this selection.
        val selected =
            if (previous?.collectionId == collectionId && previous.mode == mode) {
                val progress = data.vocabularyProgress.associateBy { it.cardId }
                val order = eligible.mapIndexed { index, card -> card.id to index }.toMap()
                eligible.sortedWith(
                    compareBy<VocabularyCard>(
                        {
                            (progress[it.id]?.correctAttempts ?: 0) +
                                (progress[it.id]?.wrongAttempts ?: 0)
                        },
                        { order.getValue(it.id) },
                    )
                )
            } else eligible
        return newSession(
            data,
            profileId,
            collectionId,
            sessionId,
            mode,
            selected.take(MAX_VOCABULARY_ROUND_CARDS),
            style,
        )
    }

    fun repeatRound(
        data: SchoolData,
        profileId: String,
        expectedSessionId: String,
        sessionId: String,
        replay: VocabularyReplay,
    ): SchoolData {
        data.owner(profileId)
        val old =
            requireNotNull(
                data.vocabularySessions.find {
                    it.id == expectedSessionId && it.profileId == profileId
                }
            ) {
                "Runde nicht gefunden."
            }
        require(old.phase == VocabularyPhase.COMPLETE) { "Die Runde ist noch offen." }
        val selected =
            old.selected.filter { replay == VocabularyReplay.ALL || it.cardId in old.failedCardIds }
        val cards = selected.map { data.card(profileId, it.cardId, it.revision) }
        return newSession(data, profileId, old.collectionId, sessionId, old.mode, cards, old.style)
    }

    private fun session(
        data: SchoolData,
        profileId: String,
        sessionId: String,
        expectedTurn: Long,
        phase: VocabularyPhase,
    ): VocabularySession {
        data.owner(profileId)
        val session =
            requireNotNull(
                data.vocabularySessions.find { it.profileId == profileId && it.id == sessionId }
            ) {
                "Die Runde hat sich geändert."
            }
        require(session.turn == expectedTurn && session.phase == phase) {
            "Diese Antwort oder dieser Übergang wurde bereits verarbeitet."
        }
        return session
    }

    private fun schedule(
        old: VocabularyProgress,
        correct: Boolean,
        today: SchoolDate,
    ): VocabularyProgress {
        vocabularyToday(today)
        val due = old.dueDate?.let(::vocabularyDate)
        val last = old.lastScheduleDate?.let(::vocabularyDate)
        if (!correct) {
            val tomorrow = vocabularyDate(today.plusDays(1).toString())
            return old.copy(
                stage = 0,
                dueDate = minOf(due ?: tomorrow, tomorrow).toString(),
                lastScheduleDate = maxOf(last ?: today, today).toString(),
            )
        }
        if ((last == null || today > last) && (due == null || due <= today)) {
            val stage = (old.stage + 1).coerceAtMost(5)
            val next = vocabularyDate(today.plusDays(intervals[stage - 1]).toString())
            return old.copy(
                stage = stage,
                dueDate = next.toString(),
                lastScheduleDate = today.toString(),
            )
        }
        return old
    }

    /** Blank Check is a no-op; Don't know is explicit and stores an empty wrong response. */
    fun submitAnswer(
        data: SchoolData,
        profileId: String,
        sessionId: String,
        expectedTurn: Long,
        cardId: String,
        expectedRevision: Int,
        response: String,
        today: SchoolDate,
        dontKnow: Boolean = false,
    ): SchoolData = recordAnswer(data, profileId, sessionId, expectedTurn, cardId,
        expectedRevision, response, today, dontKnow, false, null)

    fun reveal(data: SchoolData, profileId: String, sessionId: String, expectedTurn: Long,
               cardId: String, expectedRevision: Int): SchoolData {
        val old = session(data, profileId, sessionId, expectedTurn, VocabularyPhase.ASKING)
        require(old.style == VocabularyStyle.FLIP && old.currentCardId == cardId &&
            old.selected.any { it.cardId == cardId && it.revision == expectedRevision })
        data.card(profileId, cardId, expectedRevision)
        return checked(data.copy(vocabularySessions = data.vocabularySessions.map {
            if (it.id == sessionId) old.copy(phase = VocabularyPhase.REVEALED) else it
        }))
    }

    fun gradeSelf(data: SchoolData, profileId: String, sessionId: String, expectedTurn: Long,
                  cardId: String, expectedRevision: Int, known: Boolean, today: SchoolDate): SchoolData =
        recordAnswer(data, profileId, sessionId, expectedTurn, cardId, expectedRevision,
            "", today, false, true, known)

    private fun recordAnswer(
        data: SchoolData, profileId: String, sessionId: String, expectedTurn: Long,
        cardId: String, expectedRevision: Int, response: String, today: SchoolDate,
        dontKnow: Boolean, selfGraded: Boolean, known: Boolean?,
    ): SchoolData {
        val old = session(data, profileId, sessionId, expectedTurn,
            if (selfGraded) VocabularyPhase.REVEALED else VocabularyPhase.ASKING)
        require(old.style == if (selfGraded) VocabularyStyle.FLIP else VocabularyStyle.TYPED)
        require(
            old.currentCardId == cardId &&
                old.selected.any { it.cardId == cardId && it.revision == expectedRevision }
        ) {
            "Dieses Wort ist nicht die aktuelle Frage."
        }
        val card = data.card(profileId, cardId, expectedRevision)
        require(response.length <= MAX_VOCABULARY_RESPONSE && response.none(::vocabularyControl)) {
            "Antwort zu lang oder mit Steuerzeichen."
        }
        if (!selfGraded && !dontKnow && response.isBlank()) return checked(data)
        require(!dontKnow || response.isEmpty())
        val correct = known ?: (!dontKnow && card.acceptsVocabularyAnswer(response))
        val counts = old.results.first { it.cardId == cardId }
        require(correct || counts.wrongAttempts < MAX_VOCABULARY_ROUND_WRONG) {
            "Diese Runde hat sehr viele Versuche. Bitte pausieren oder eine neue Runde beginnen."
        }
        val progress =
            data.vocabularyProgress.find { it.cardId == cardId }
                ?: VocabularyProgress(cardId, expectedRevision)
        require(
            if (correct) progress.correctAttempts < MAX_VOCABULARY_ATTEMPTS
            else progress.wrongAttempts < MAX_VOCABULARY_ATTEMPTS
        ) {
            "Versuchslimit erreicht."
        }
        val learned =
            schedule(progress, correct, today)
                .copy(
                    correctAttempts = progress.correctAttempts + if (correct) 1 else 0,
                    wrongAttempts = progress.wrongAttempts + if (correct) 0 else 1,
                )
        val pending = old.pending.drop(1).toMutableList()
        if (!correct) pending.add(minOf(2, pending.size), cardId)
        val next =
            old.copy(
                pending = pending.toList(),
                completed = if (correct) old.completed + cardId else old.completed,
                results =
                    old.results.map {
                        if (it.cardId != cardId) it
                        else
                            it.copy(
                                correctAttempts = it.correctAttempts + if (correct) 1 else 0,
                                wrongAttempts = it.wrongAttempts + if (correct) 0 else 1,
                            )
                    },
                phase = VocabularyPhase.FEEDBACK,
                feedback =
                    VocabularyFeedback(
                        cardId,
                        expectedRevision,
                        response,
                        correct,
                        old.turn + 1,
                        dontKnow,
                        selfGraded,
                    ),
                turn = old.turn + 1,
            )
        return checked(
            data.copy(
                vocabularyProgress =
                    data.vocabularyProgress.filterNot { it.cardId == cardId } + learned,
                vocabularySessions =
                    data.vocabularySessions.map { if (it.id == sessionId) next else it },
            )
        )
    }

    fun continueRound(
        data: SchoolData,
        profileId: String,
        sessionId: String,
        expectedFeedbackTurn: Long,
    ): SchoolData {
        val old =
            session(data, profileId, sessionId, expectedFeedbackTurn, VocabularyPhase.FEEDBACK)
        val next =
            old.copy(
                feedback = null,
                phase =
                    if (old.pending.isEmpty()) VocabularyPhase.COMPLETE else VocabularyPhase.ASKING,
            )
        return checked(
            data.copy(
                vocabularySessions =
                    data.vocabularySessions.map { if (it.id == sessionId) next else it }
            )
        )
    }
}

/** Also usable by Android profile deletion: remove vocabulary before removing the owner. */
fun SchoolData.removeProfileVocabulary(profileId: String): SchoolData {
    val collectionIds =
        vocabularyCollections.filter { it.profileId == profileId }.map { it.id }.toSet()
    val cardIds = vocabularyCards.filter { it.collectionId in collectionIds }.map { it.id }.toSet()
    return copy(
        vocabularyCollections = vocabularyCollections.filterNot { it.id in collectionIds },
        vocabularyCards = vocabularyCards.filterNot { it.id in cardIds },
        vocabularyProgress = vocabularyProgress.filterNot { it.cardId in cardIds },
        vocabularySessions = vocabularySessions.filterNot { it.profileId == profileId },
    )
}
