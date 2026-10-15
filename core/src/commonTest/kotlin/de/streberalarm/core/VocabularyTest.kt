package de.streberalarm.core

import kotlin.test.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class VocabularyTest {
    private val today = SchoolDate.parse("2026-10-11")

    @Test
    fun flippedVocabularyRoundPersistsRevealAndSelfGradeWithoutTypedAnswer() {
        var d = Vocabulary.startRound(fixture(1), "p", "c", "flip", VocabularyMode.ALL,
            today, style = VocabularyStyle.FLIP)
        assertFails { Vocabulary.submitAnswer(d, "p", "flip", 0, "word1", 1, "word 1", today) }
        d = Vocabulary.reveal(d, "p", "flip", 0, "word1", 1)
        assertEquals(VocabularyPhase.REVEALED, decodeSchoolData(dataJson.encodeToString(d)).vocabularySessions.single().phase)
        assertFails { Vocabulary.reveal(d, "p", "flip", 0, "word1", 1) }
        d = Vocabulary.gradeSelf(d, "p", "flip", 0, "word1", 1, false, today)
        assertEquals(1L, d.vocabularyProgress.single().wrongAttempts)
        assertTrue(d.vocabularySessions.single().feedback!!.selfGraded)
        assertFails { Vocabulary.gradeSelf(d, "p", "flip", 0, "word1", 1, false, today) }
        d = Vocabulary.continueRound(d, "p", "flip", 1)
        d = Vocabulary.reveal(d, "p", "flip", 1, "word1", 1)
        d = Vocabulary.gradeSelf(d, "p", "flip", 1, "word1", 1, true, today)
        d = Vocabulary.continueRound(d, "p", "flip", 2)
        assertEquals(VocabularyPhase.COMPLETE, d.vocabularySessions.single().phase)
        assertEquals(VocabularyStyle.FLIP, Vocabulary.repeatRound(d, "p", "flip", "again", VocabularyReplay.FAILED).vocabularySessions.single().style)
    }

    private fun fixture(count: Int = 4): SchoolData {
        val profile = Profile(id = "p")
        var data = SchoolData(profiles = listOf(profile), activeProfileId = "p")
        data = Vocabulary.createCollection(data, "p", "c", "Words")
        for (i in 1..count) data =
            Vocabulary.saveCard(data, "p", "c", "word$i", null, "Wort $i", "word $i")
        return data
    }

    private fun start(
        data: SchoolData,
        mode: VocabularyMode = VocabularyMode.ALL,
        date: SchoolDate = today,
        sessionId: String = "s",
    ): SchoolData =
        Vocabulary.startRound(
            data,
            "p",
            "c",
            sessionId,
            mode,
            date,
            expectedSessionId = data.vocabularySessions.firstOrNull()?.id,
        )

    private fun answer(data: SchoolData, correct: Boolean, date: SchoolDate = today): SchoolData {
        val s = data.vocabularySessions.single()
        val card = data.vocabularyCards.first { it.id == s.currentCardId }
        return Vocabulary.submitAnswer(
            data,
            "p",
            s.id,
            s.turn,
            card.id,
            card.revision,
            if (correct) card.english else "wrong",
            date,
        )
    }

    private fun next(data: SchoolData): SchoolData {
        val s = data.vocabularySessions.single()
        return Vocabulary.continueRound(data, "p", s.id, s.turn)
    }

    private fun finish(data: SchoolData, date: SchoolDate = today): SchoolData {
        var d = data
        while (d.vocabularySessions.single().phase != VocabularyPhase.COMPLETE) {
            d =
                if (d.vocabularySessions.single().phase == VocabularyPhase.FEEDBACK) next(d)
                else answer(d, true, date)
        }
        return d
    }

    private fun restore(data: SchoolData): SchoolData =
        dataJson.decodeFromString<SchoolData>(dataJson.encodeToString(data)).also { it.validate() }

    @Test
    fun matchingIsCaseWhitespaceAndExplicitAlternatesOnly() {
        val card = VocabularyCard("a", "c", "Straße", "the street", listOf("road"))
        for (s in listOf(" THE   Street ", "Road", "road ")) assertTrue(
            card.acceptsVocabularyAnswer(s)
        )
        for (s in listOf("", "streets", "street the", "the street.", "röad", "street")) assertFalse(
            card.acceptsVocabularyAnswer(s)
        )
        assertEquals("foo bar", normalizeVocabularyAnswer(" FOO\t  bar\n"))
    }

    @Test
    fun crudStableIdsNoopAndDuplicateBoundaries() {
        val d = fixture(1)
        assertEquals(d, Vocabulary.createCollection(d, "p", "c", " Words "))
        assertEquals(d, Vocabulary.saveCard(d, "p", "c", "word1", null, "Wort 1", "word 1"))
        assertFailsWith<IllegalArgumentException> {
            Vocabulary.createCollection(d, "p", "new", "WORDS")
        }
        assertFailsWith<IllegalArgumentException> {
            Vocabulary.saveCard(d, "p", "c", "new", null, " WORT   1 ", "different")
        }
        assertFailsWith<IllegalArgumentException> {
            Vocabulary.saveCard(d, "p", "c", "new", null, "new", "road", listOf("ROAD"))
        }
        for (bad in listOf("", " ", "a".repeat(161), "a\n")) assertFailsWith<
            IllegalArgumentException
        > {
            Vocabulary.saveCard(d, "p", "c", "new", null, bad, "answer")
        }
        assertFailsWith<IllegalArgumentException> {
            Vocabulary.saveCard(d, "p", "c", "new", null, "new", "a".repeat(121))
        }
        val valid =
            Vocabulary.saveCard(
                d,
                "p",
                "c",
                "new",
                null,
                "a".repeat(160),
                "a".repeat(120),
                listOf("b", "c", "d", "e"),
            )
        valid.validate()
        assertFailsWith<IllegalArgumentException> {
            Vocabulary.saveCard(
                valid,
                "p",
                "c",
                "new",
                1,
                "new",
                "answer",
                listOf("a", "b", "c", "d", "e"),
            )
        }
        val other = Vocabulary.createCollection(d, "p", "other", "Other")
        Vocabulary.saveCard(other, "p", "other", "otherWord", null, "Wort 1", "word 1").validate()
        assertFailsWith<IllegalArgumentException> {
            Vocabulary.createCollection(d, "p", "c", "Changed")
        }
    }

    @Test
    fun queueWaitsForTwoAvailableQuestionsAndNeverDuplicates() {
        var d = start(fixture())
        d = restore(answer(d, false))
        assertEquals(
            listOf("word2", "word3", "word1", "word4"),
            d.vocabularySessions.single().pending,
        )
        assertEquals(VocabularyPhase.FEEDBACK, d.vocabularySessions.single().phase)
        assertEquals(null, d.vocabularySessions.single().currentCardId)
        d = next(d)
        d = next(answer(d, false))
        assertEquals(
            listOf("word3", "word1", "word2", "word4"),
            d.vocabularySessions.single().pending,
        )
        d = next(answer(d, true))
        assertEquals("word1", d.vocabularySessions.single().currentCardId)
        d = finish(d)
        val s = d.vocabularySessions.single()
        assertEquals(4L, s.correctAttempts)
        assertEquals(2L, s.wrongAttempts)
        assertEquals(4, s.completedWords)
        assertEquals(0, s.openWords)
        assertEquals(setOf("word1", "word2"), s.failedCardIds)
    }

    @Test
    fun oneAndTwoCardWrongFallbackAndBlankDontKnow() {
        assertFailsWith<IllegalArgumentException> { start(fixture(0)) }
        for (n in 1..2) {
            var d = start(fixture(n))
            val s = d.vocabularySessions.single()
            assertEquals(d, Vocabulary.submitAnswer(d, "p", "s", 0, "word1", 1, " ", today))
            for (i in 1..6) {
                val current = d.vocabularySessions.single()
                d =
                    Vocabulary.submitAnswer(
                        d,
                        "p",
                        "s",
                        current.turn,
                        current.currentCardId!!,
                        1,
                        "",
                        today,
                        dontKnow = true,
                    )
                assertEquals(n, d.vocabularySessions.single().openWords)
                assertEquals(i.toLong(), d.vocabularySessions.single().wrongAttempts)
                d = next(restore(d))
            }
            d = finish(d)
            assertEquals(n.toLong(), d.vocabularySessions.single().correctAttempts)
            assertEquals(s.selected, d.vocabularySessions.single().selected)
        }
    }

    @Test
    fun frozenRoundsAndResumeEveryPhaseKeepExactlyOneAcceptedTransition() {
        var d = restore(start(fixture(1)))
        d = restore(answer(d, false))
        assertEquals("wrong", d.vocabularySessions.single().feedback!!.response)
        assertEquals(1L, d.vocabularyProgress.single().wrongAttempts)
        assertFailsWith<IllegalArgumentException> {
            Vocabulary.submitAnswer(d, "p", "s", 0, "word1", 1, "wrong", today)
        }
        d = restore(next(d))
        d = restore(answer(d, true))
        assertEquals(VocabularyPhase.FEEDBACK, d.vocabularySessions.single().phase)
        assertTrue(d.vocabularySessions.single().pending.isEmpty())
        assertEquals(1L, d.vocabularySessions.single().correctAttempts)
        d = restore(next(d))
        assertEquals(VocabularyPhase.COMPLETE, d.vocabularySessions.single().phase)
        assertEquals(2L, d.vocabularySessions.single().turn)
        assertEquals(0, d.vocabularyProgress.single().stage)
    }

    @Test
    fun doubleSubmitContinueAndStaleIdentityNeverSkipOrGrade() {
        val d = start(fixture())
        val feedback = answer(d, true)
        assertFailsWith<IllegalArgumentException> {
            Vocabulary.submitAnswer(feedback, "p", "s", 0, "word1", 1, "word 1", today)
        }
        val asking = next(feedback)
        assertFailsWith<IllegalArgumentException> { next(asking) }
        for (bad in
            listOf(
                { Vocabulary.submitAnswer(asking, "p", "s", 0, "word2", 1, "word 2", today) },
                { Vocabulary.submitAnswer(asking, "p", "s", 1, "word1", 1, "word 1", today) },
                { Vocabulary.submitAnswer(asking, "p", "s", 1, "word2", 2, "word 2", today) },
                { Vocabulary.submitAnswer(asking, "other", "s", 1, "word2", 1, "word 2", today) },
                {
                    Vocabulary.submitAnswer(
                        asking,
                        "p",
                        "replacement",
                        1,
                        "word2",
                        1,
                        "word 2",
                        today,
                    )
                },
            )) assertFailsWith<IllegalArgumentException> { bad() }
        assertEquals(1L, asking.vocabularyProgress.single().correctAttempts)
        assertFailsWith<IllegalArgumentException> { start(asking) }
        val replaced =
            Vocabulary.startRound(
                asking,
                "p",
                "c",
                "replacement",
                VocabularyMode.ALL,
                today,
                "s",
                replaceUnfinished = true,
            )
        assertFailsWith<IllegalArgumentException> {
            next(feedback.copy(vocabularySessions = replaced.vocabularySessions))
        }
    }

    @Test
    fun replayAllIsExactAndFailuresIncludeLaterCorrectedWords() {
        val done = finish(next(answer(start(fixture(12)), false)))
        val original = done.vocabularySessions.single()
        assertEquals(10, original.selected.size)
        val failed = Vocabulary.repeatRound(done, "p", "s", "retry", VocabularyReplay.FAILED)
        assertEquals(listOf(original.selected.first()), failed.vocabularySessions.single().selected)
        assertEquals(0L, failed.vocabularySessions.single().turn)
        assertEquals(done.vocabularyProgress, failed.vocabularyProgress)
        val all = Vocabulary.repeatRound(done, "p", "s", "all", VocabularyReplay.ALL)
        assertEquals(original.selected, all.vocabularySessions.single().selected)
        val perfect = finish(start(fixture(1)))
        assertFailsWith<IllegalArgumentException> {
            Vocabulary.repeatRound(perfect, "p", "s", "retry", VocabularyReplay.FAILED)
        }
        val second = start(done, sessionId = "next")
        assertTrue(
            second.vocabularySessions.single().selected.any {
                it.cardId !in original.selected.map { c -> c.cardId }
            }
        )
    }

    @Test
    fun modesAndOrderingUseNewFailedAndDueDates() {
        val d =
            fixture()
                .copy(
                    vocabularyProgress =
                        listOf(
                            VocabularyProgress(
                                "word2",
                                1,
                                0,
                                "2026-10-12",
                                "2026-10-11",
                                wrongAttempts = 1,
                            ),
                            VocabularyProgress(
                                "word3",
                                1,
                                1,
                                "2026-10-11",
                                "2026-10-10",
                                correctAttempts = 1,
                            ),
                            VocabularyProgress(
                                "word4",
                                1,
                                2,
                                "2026-10-14",
                                "2026-10-11",
                                correctAttempts = 2,
                            ),
                        )
                )
        d.validate()
        fun ids(mode: VocabularyMode) =
            Vocabulary.eligibleCards(d, "p", "c", mode, today).map { it.id }
        assertEquals(listOf("word1", "word3", "word2", "word4"), ids(VocabularyMode.ALL))
        assertEquals(listOf("word1", "word2"), ids(VocabularyMode.UNSURE))
        assertEquals(listOf("word1", "word3"), ids(VocabularyMode.DUE))
        val frozen = start(d, VocabularyMode.DUE)
        assertEquals(listOf("word1", "word3"), frozen.vocabularySessions.single().pending)
    }

    @Test
    fun schedulingAllIntervalsCapEarlyPracticeAndDailyGuard() {
        var date = SchoolDate.parse("2026-12-31")
        var d = fixture(1)
        val intervals = listOf(1L, 3L, 7L, 14L, 30L, 30L)
        for ((i, interval) in intervals.withIndex()) {
            d = finish(start(d, date = date, sessionId = "round$i"), date)
            val p = d.vocabularyProgress.single()
            assertEquals(minOf(i + 1, 5), p.stage)
            assertEquals(date.plusDays(interval).toString(), p.dueDate)
            val before = p
            d = finish(start(d, date = date, sessionId = "same$i"), date)
            assertEquals(
                before.copy(correctAttempts = before.correctAttempts + 1),
                d.vocabularyProgress.single(),
            )
            if (interval > 1) {
                d =
                    finish(
                        start(d, date = date.plusDays(1), sessionId = "early$i"),
                        date.plusDays(1),
                    )
                assertEquals(before.dueDate, d.vocabularyProgress.single().dueDate)
                assertEquals(
                    before.lastScheduleDate,
                    d.vocabularyProgress.single().lastScheduleDate,
                )
            }
            date = date.plusDays(interval)
        }
    }

    @Test
    fun wrongShortensNeverDefersOverdueAndLaterRecoveryIsEligible() {
        var d = finish(start(fixture(1)))
        d = start(d, sessionId = "retry")
        d = answer(d, false)
        assertEquals(0, d.vocabularyProgress.single().stage)
        assertEquals(today.plusDays(1).toString(), d.vocabularyProgress.single().dueDate)
        d = finish(d)
        assertEquals(0, d.vocabularyProgress.single().stage)
        d = finish(start(d, date = today.plusDays(1), sessionId = "tomorrow"), today.plusDays(1))
        assertEquals(1, d.vocabularyProgress.single().stage)
        val overdue = today.plusDays(4)
        d = answer(start(d, date = overdue, sessionId = "overdue"), false, overdue)
        assertEquals(today.plusDays(2).toString(), d.vocabularyProgress.single().dueDate)
        d = next(d)
        d = answer(d, true, overdue.plusDays(1))
        assertEquals(1, d.vocabularyProgress.single().stage)
        assertEquals(setOf("word1"), d.vocabularySessions.single().failedCardIds)
    }

    @Test
    fun midnightLeapYearDstAndBackwardClockUseCalendarDays() {
        for ((date, due) in
            listOf(
                "2028-02-28" to "2028-02-29",
                "2026-03-28" to "2026-03-29",
                "2026-10-24" to "2026-10-25",
                "2026-12-31" to "2027-01-01",
            )) {
            val d = finish(start(fixture(1)), SchoolDate.parse(date))
            assertEquals(due, d.vocabularyProgress.single().dueDate)
        }
        var d = finish(start(fixture(1)))
        val p = d.vocabularyProgress.single()
        d = finish(start(d, date = today.minusDays(1), sessionId = "back"), today.minusDays(1))
        assertEquals(p.copy(correctAttempts = 2), d.vocabularyProgress.single())
        d =
            answer(
                start(d, date = today.minusDays(2), sessionId = "backWrong"),
                false,
                today.minusDays(2),
            )
        assertEquals(today.toString(), d.vocabularyProgress.single().lastScheduleDate)
        d = finish(d, today.minusDays(1))
        assertEquals(0, d.vocabularyProgress.single().stage)
        assertFailsWith<IllegalArgumentException> {
            answer(start(fixture(1)), true, SchoolDate.parse("2200-12-31"))
        }
    }

    @Test
    fun editOnlyResetsAffectedCardAndReferencingSessionRenameNoopDoesNot() {
        val done = finish(start(fixture(12)))
        val card =
            done.vocabularyCards.first {
                it.id == done.vocabularySessions.single().selected.first().cardId
            }
        val renamed = Vocabulary.renameCollection(done, "p", "c", "Words", "New")
        assertEquals(done.vocabularyProgress, renamed.vocabularyProgress)
        assertEquals(done.vocabularySessions, renamed.vocabularySessions)
        assertEquals(
            renamed,
            Vocabulary.saveCard(
                renamed,
                "p",
                "c",
                card.id,
                card.revision,
                " ${card.german} ",
                card.english,
            ),
        )
        val edited =
            Vocabulary.saveCard(
                renamed,
                "p",
                "c",
                card.id,
                1,
                card.german,
                card.english,
                listOf("alternate"),
            )
        assertTrue(edited.vocabularySessions.isEmpty())
        assertEquals(
            done.vocabularyProgress.filterNot { it.cardId == card.id },
            edited.vocabularyProgress,
        )
        assertEquals(2, edited.vocabularyCards.first { it.id == card.id }.revision)
        assertFailsWith<IllegalArgumentException> {
            Vocabulary.saveCard(edited, "p", "c", card.id, 1, card.german, "stale")
        }
        val notSelected =
            done.vocabularyCards.first { c ->
                done.vocabularySessions.single().selected.none { it.cardId == c.id }
            }
        val deleted = Vocabulary.deleteCard(done, "p", notSelected.id, 1)
        assertEquals(done.vocabularySessions, deleted.vocabularySessions)
        val selectedDeleted = Vocabulary.deleteCard(done, "p", card.id, 1)
        assertTrue(selectedDeleted.vocabularySessions.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            Vocabulary.saveCard(selectedDeleted, "p", "c", card.id, 1, card.german, card.english)
        }
        val empty = Vocabulary.deleteCollection(done, "p", "c")
        assertTrue(
            empty.vocabularyCards.isEmpty() &&
                empty.vocabularySessions.isEmpty() &&
                empty.vocabularyProgress.isEmpty()
        )
    }

    @Test
    fun profileDeletionAndSwitchPreserveOtherProfileExactlyIncludingLastProfile() {
        val first = finish(start(fixture(1)))
        val secondProfile = Profile(id = "q")
        var both = first.copy(profiles = first.profiles + secondProfile, activeProfileId = "q")
        both = Vocabulary.createCollection(both, "q", "d", "Words")
        both = Vocabulary.saveCard(both, "q", "d", "other", null, "Wort 1", "word 1")
        both = Vocabulary.startRound(both, "q", "d", "otherSession", VocabularyMode.ALL, today)
        both = Vocabulary.submitAnswer(both, "q", "otherSession", 0, "other", 1, "wrong", today)
        assertFailsWith<IllegalArgumentException> { Vocabulary.deleteCollection(both, "p", "c") }
        val bridge = IosBridge()
        val removed =
            dataJson.decodeFromString<SchoolData>(
                bridge.deleteProfile(dataJson.encodeToString(both), "p")
            )
        assertEquals(
            both.vocabularyCollections.filter { it.profileId == "q" },
            removed.vocabularyCollections,
        )
        assertEquals(
            both.vocabularyCards.filter { it.collectionId == "d" },
            removed.vocabularyCards,
        )
        assertEquals(
            both.vocabularySessions.filter { it.profileId == "q" },
            removed.vocabularySessions,
        )
        assertEquals(
            both.vocabularyProgress.filter { it.cardId == "other" },
            removed.vocabularyProgress,
        )
        val last =
            dataJson.decodeFromString<SchoolData>(
                bridge.deleteProfile(dataJson.encodeToString(removed), "q")
            )
        last.validate()
        assertTrue(
            last.profiles.isEmpty() &&
                last.vocabularyCollections.isEmpty() &&
                last.vocabularyCards.isEmpty() &&
                last.vocabularyProgress.isEmpty() &&
                last.vocabularySessions.isEmpty()
        )
    }

    @Test
    fun malformedImportsRejectReferencesPartitionsPhasesDatesCountsAndUnknownEnums() {
        val asking = start(fixture(1))
        val s = asking.vocabularySessions.single()
        val wrong = answer(asking, false)
        val w = wrong.vocabularySessions.single()
        val badSessions =
            listOf(
                s.copy(profileId = "other"),
                s.copy(collectionId = "missing"),
                s.copy(selected = listOf(VocabularyCardRevision("word1", 2))),
                s.copy(selected = s.selected + s.selected),
                s.copy(pending = listOf("word1", "word1")),
                s.copy(completed = setOf("word1")),
                s.copy(pending = emptyList()),
                s.copy(results = emptyList()),
                s.copy(results = listOf(VocabularyRoundCounts("word1", 1))),
                s.copy(phase = VocabularyPhase.COMPLETE),
                s.copy(turn = 1),
            )
        for (bad in badSessions) assertFailsWith<IllegalArgumentException> {
            asking.copy(vocabularySessions = listOf(bad)).validate()
        }
        for (bad in
            listOf(
                w.copy(feedback = null),
                w.copy(feedback = w.feedback!!.copy(turn = 0)),
                w.copy(feedback = w.feedback!!.copy(correct = true)),
                w.copy(feedback = w.feedback!!.copy(response = "word 1")),
                w.copy(feedback = w.feedback!!.copy(response = "a".repeat(121))),
                w.copy(
                    results =
                        listOf(
                            VocabularyRoundCounts(
                                "word1",
                                wrongAttempts = MAX_VOCABULARY_ROUND_WRONG + 1,
                            )
                        )
                ),
            )) assertFailsWith<IllegalArgumentException> {
            wrong.copy(vocabularySessions = listOf(bad)).validate()
        }
        val valid = wrong.vocabularyProgress.single()
        for (bad in
            listOf(
                valid.copy(cardId = "missing"),
                valid.copy(revision = 2),
                valid.copy(stage = 6),
                valid.copy(dueDate = "1999-12-31"),
                valid.copy(dueDate = "2201-01-01"),
                valid.copy(dueDate = "2026-02-30"),
                valid.copy(lastScheduleDate = null),
                valid.copy(correctAttempts = -1),
                valid.copy(wrongAttempts = MAX_VOCABULARY_ATTEMPTS + 1),
                valid.copy(stage = 1, correctAttempts = 0),
            )) assertFailsWith<IllegalArgumentException> {
            wrong.copy(vocabularyProgress = listOf(bad)).validate()
        }
        assertFails {
            dataJson.decodeFromString<SchoolData>(
                dataJson.encodeToString(asking).replace("\"ASKING\"", "\"UNKNOWN\"")
            )
        }
    }

    @Test
    fun exactAttemptCapsValidateAndOverflowIsRejectedWithoutMutation() {
        val wrong = answer(start(fixture(1)), false)
        val p = wrong.vocabularyProgress.single().copy(wrongAttempts = MAX_VOCABULARY_ATTEMPTS)
        val s =
            wrong.vocabularySessions
                .single()
                .copy(
                    results =
                        listOf(
                            VocabularyRoundCounts(
                                "word1",
                                wrongAttempts = MAX_VOCABULARY_ROUND_WRONG,
                            )
                        ),
                    turn = MAX_VOCABULARY_ROUND_WRONG,
                    feedback =
                        wrong.vocabularySessions
                            .single()
                            .feedback!!
                            .copy(turn = MAX_VOCABULARY_ROUND_WRONG),
                )
        val capped = wrong.copy(vocabularyProgress = listOf(p), vocabularySessions = listOf(s))
        capped.validate()
        val asking = next(capped)
        assertFailsWith<IllegalArgumentException> { answer(asking, false) }
        val correct = answer(asking, true)
        assertEquals(1L, correct.vocabularyProgress.single().correctAttempts)
        val maxCorrect =
            asking.copy(
                vocabularyProgress = listOf(p.copy(correctAttempts = MAX_VOCABULARY_ATTEMPTS))
            )
        maxCorrect.validate()
        assertFailsWith<IllegalArgumentException> { answer(maxCorrect, true) }
        assertEquals(
            MAX_VOCABULARY_ATTEMPTS,
            maxCorrect.vocabularyProgress.single().correctAttempts,
        )
    }

    @Test
    fun exactCardTextIdCollectionAndRoundLimitsValidate() {
        val base = fixture(0)
        val cards = (1..2000).map { VocabularyCard("w$it", "c", "Wort $it", "answer") }
        base.copy(vocabularyCards = cards).validate()
        assertFailsWith<IllegalArgumentException> {
            base
                .copy(vocabularyCards = cards + VocabularyCard("overflow", "c", "extra", "answer"))
                .validate()
        }
        val collections = (1..1000).map { VocabularyCollection("c$it", "p", "Collection $it") }
        base.copy(vocabularyCollections = collections).validate()
        assertFailsWith<IllegalArgumentException> {
            base
                .copy(
                    vocabularyCollections =
                        collections + VocabularyCollection("overflow", "p", "extra")
                )
                .validate()
        }
        val ten = start(fixture(11))
        assertEquals(10, ten.vocabularySessions.single().selected.size)
        assertFailsWith<IllegalArgumentException> {
            Vocabulary.createCollection(base, "p", "x".repeat(101), "ok")
        }
        Vocabulary.createCollection(base, "p", "x".repeat(100), "x".repeat(80)).validate()
    }

    @Test
    fun seededRoundTrialsKeepAttemptsWordsAndReplayExternallyConsistent() {
        val random = kotlin.random.Random(1234)
        for (n in 1..10) {
            var d = start(fixture(n))
            var correct = 0L
            var wrong = 0L
            val completed = mutableSetOf<String>()
            val failed = mutableSetOf<String>()
            for (i in 1..50) {
                val old = d.vocabularySessions.single()
                if (old.phase == VocabularyPhase.COMPLETE) break
                val cardId = old.currentCardId!!
                val success = random.nextBoolean()
                d = restore(answer(d, success))
                if (success) {
                    correct++
                    completed.add(cardId)
                } else {
                    wrong++
                    failed.add(cardId)
                }
                val s = d.vocabularySessions.single()
                assertEquals(correct, s.correctAttempts)
                assertEquals(wrong, s.wrongAttempts)
                assertEquals(completed, s.completed)
                assertEquals(failed, s.failedCardIds)
                assertEquals(n - completed.size, s.openWords)
                assertEquals(correct + wrong, s.turn)
                assertTrue(s.pending.none { it in completed })
                d = next(d)
            }
            d = finish(d)
            assertEquals(n, d.vocabularySessions.single().completedWords)
            assertEquals(failed, d.vocabularySessions.single().failedCardIds)
        }
    }

    @Test
    fun failureFromHighestStageShortensFutureDateAndClockGuardSurvivesRollback() {
        val original =
            fixture(1)
                .copy(
                    vocabularyProgress =
                        listOf(VocabularyProgress("word1", 1, 5, "2026-11-10", "2026-10-11", 5, 0))
                )
        var d = answer(start(original, sessionId = "high"), false)
        assertEquals(today.plusDays(1).toString(), d.vocabularyProgress.single().dueDate)
        assertEquals(0, d.vocabularyProgress.single().stage)
        d = finish(d)
        assertEquals(0, d.vocabularyProgress.single().stage)
        val futureGuard =
            original.copy(
                vocabularyProgress =
                    listOf(VocabularyProgress("word1", 1, 5, "2026-12-01", "2026-11-01", 5, 0))
            )
        val rollback = answer(start(futureGuard, sessionId = "rollback"), false)
        assertEquals("2026-11-01", rollback.vocabularyProgress.single().lastScheduleDate)
        assertEquals("2026-10-12", rollback.vocabularyProgress.single().dueDate)
    }

    @Test
    fun globalCardLimitsAndCrossProfileSessionReferencesRejectInvalidImports() {
        val base = fixture(0)
        val collections = (1..5).map { VocabularyCollection("collection$it", "p", "Words $it") }
        val cards =
            collections.flatMap { collection ->
                (1..2000).map {
                    VocabularyCard("${collection.id}-$it", collection.id, "Wort $it", "answer")
                }
            }
        val full = base.copy(vocabularyCollections = collections, vocabularyCards = cards)
        full.validate()
        assertFailsWith<IllegalArgumentException> {
            full
                .copy(vocabularyCards = cards + cards.first().copy(id = "extra", german = "Extra"))
                .validate()
        }
        val round = start(fixture(11))
        val session = round.vocabularySessions.single()
        val tooMany =
            session.copy(
                selected = round.vocabularyCards.map { VocabularyCardRevision(it.id, 1) },
                pending = round.vocabularyCards.map { it.id },
                results = round.vocabularyCards.map { VocabularyRoundCounts(it.id) },
            )
        assertFailsWith<IllegalArgumentException> {
            round.copy(vocabularySessions = listOf(tooMany)).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            round
                .copy(vocabularySessions = listOf(session, session.copy(id = "another")))
                .validate()
        }
        val learned = answer(start(fixture(1)), true)
        assertFailsWith<IllegalArgumentException> {
            learned
                .copy(vocabularyProgress = learned.vocabularyProgress + learned.vocabularyProgress)
                .validate()
        }
        val owners = round.copy(profiles = round.profiles + Profile(id = "q"))
        assertFailsWith<IllegalArgumentException> {
            owners.copy(vocabularySessions = listOf(session.copy(profileId = "q"))).validate()
        }
    }

    @Test
    fun repeatedEarlyAllRoundsDoNotStarveLaterFutureCards() {
        var d = fixture(30)
        d =
            d.copy(
                vocabularyProgress =
                    d.vocabularyCards.mapIndexed { index, card ->
                        VocabularyProgress(
                            card.id,
                            1,
                            1,
                            if (index < 10) "2026-10-12" else "2026-10-20",
                            "2026-10-10",
                            1,
                            0,
                        )
                    }
            )
        val seen = mutableSetOf<String>()
        for (i in 1..3) {
            d = start(d, sessionId = "fair$i")
            seen.addAll(d.vocabularySessions.single().selected.map { it.cardId })
            d = finish(d)
        }
        assertEquals(d.vocabularyCards.map { it.id }.toSet(), seen)
    }
}
