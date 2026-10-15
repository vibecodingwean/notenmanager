package de.streberalarm.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.streberalarm.core.*
import java.time.LocalDate
import kotlinx.coroutines.*

private fun vocabularyToday() = SchoolDate.parse(LocalDate.now().toString())

@Composable
private fun VAction(text: String, tag: String, enabled: Boolean = true, click: () -> Unit) {
    Action(text, click, enabled, Modifier.testTag("vocab-$tag"))
}

@Composable
private fun VocabularyList(title: String, content: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().imePadding().testTag("vocab-list"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text(title, style = MaterialTheme.typography.headlineMedium) }
        content()
    }
}

/** Only routes and unsent drafts are local; accepted feedback/queue/counts come from SchoolData. */
@Composable
fun VocabularyPage(
    activity: MainActivity,
    profile: Profile,
    data: SchoolData,
    generation: Long,
    exit: () -> Unit,
    onBackChanged: ((() -> Unit)?) -> Unit,
) {
    val owner = profile.id
    var route by rememberSaveable(owner, generation) { mutableStateOf("overview") }
    var collectionId by rememberSaveable(owner, generation) { mutableStateOf("") }
    var cardId by rememberSaveable(owner, generation) { mutableStateOf("") }
    var cardRevision by rememberSaveable(owner, generation) { mutableStateOf<Int?>(null) }
    var draftId by rememberSaveable(owner, generation) { mutableStateOf(id()) }
    var modeName by rememberSaveable(owner, generation) { mutableStateOf(VocabularyMode.ALL.name) }
    var styleName by rememberSaveable(owner, generation) { mutableStateOf(VocabularyStyle.TYPED.name) }
    var pending by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var confirmation by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val effects = rememberPracticeEffects(activity)
    val keyboard = LocalSoftwareKeyboardController.current
    val collection =
        data.vocabularyCollections.find { it.id == collectionId && it.profileId == owner }
    val card = data.vocabularyCards.find { it.id == cardId && it.collectionId == collectionId }
    val session = data.vocabularySessions.find { it.profileId == owner }
    val mode = VocabularyMode.valueOf(modeName)

    fun navigate(next: String) {
        keyboard?.hide()
        error = null
        dirty = false
        route = next
    }
    fun back() {
        if (pending) return
        if (route in listOf("cardEdit", "collectionEdit") && dirty) confirmation = "discard"
        else
            when (route) {
                "overview" -> exit()
                "collection" -> navigate("overview")
                "collectionEdit" -> navigate(if (collection == null) "overview" else "collection")
                else -> navigate("collection")
            }
    }
    val latestBack by rememberUpdatedState<() -> Unit>({ back() })
    DisposableEffect(Unit) {
        onBackChanged { latestBack() }
        onDispose { onBackChanged(null) }
    }
    BackHandler { back() }

    // Scope disposal cancels callbacks, not a transaction already committing. No optimistic
    // navigation.
    fun dispatch(change: (SchoolData) -> SchoolData, saved: () -> Unit = {}) {
        if (pending) return
        pending = true
        error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { activity.repo.updateVocabulary(generation, change) }
                ensureActive()
                if (
                    activity.repo.vocabularyGeneration.value == generation &&
                        activity.repo.state.value?.activeProfileId == owner
                )
                    saved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Speichern ging nicht. Bitte noch einmal versuchen."
            } finally {
                pending = false
            }
        }
    }
    fun start(replace: Boolean = false) {
        val previous = session?.id
        val newId = id()
        dispatch({ latest ->
            Vocabulary.startRound(
                latest,
                owner,
                collectionId,
                newId,
                mode,
                vocabularyToday(),
                previous,
                replace,
                VocabularyStyle.valueOf(styleName),
            )
        }) {
            navigate("round")
        }
    }
    fun repeat(replay: VocabularyReplay) {
        val oldId = session!!.id
        val newId = id()
        dispatch({ Vocabulary.repeatRound(it, owner, oldId, newId, replay) }) { navigate("round") }
    }
    LaunchedEffect(route, collection?.id, card?.revision, session?.id, pending) {
        // A successful edit emits its new revision before its UI callback. Let that callback finish
        // (especially Save & next) before treating a revision change as an external stale editor.
        if (pending) return@LaunchedEffect
        // Composition may briefly lag behind the confirmed commit that opened this route.
        val latest = activity.repo.state.value ?: return@LaunchedEffect
        if (
            latest.activeProfileId != owner ||
                activity.repo.vocabularyGeneration.value != generation
        )
            return@LaunchedEffect
        val liveCollection =
            latest.vocabularyCollections.find { it.id == collectionId && it.profileId == owner }
        val liveCard =
            latest.vocabularyCards.find { it.id == cardId && it.collectionId == collectionId }
        val liveSession = latest.vocabularySessions.find { it.profileId == owner }
        if (
            (route !in listOf("overview", "collectionEdit") ||
                (route == "collectionEdit" && collectionId.isNotEmpty())) && liveCollection == null
        ) {
            navigate("overview")
            error = "Die Sammlung wurde geändert oder gelöscht."
        } else if (
            route == "cardEdit" && cardRevision != null && liveCard?.revision != cardRevision
        ) {
            navigate("collection")
            error = "Das Wort wurde geändert oder gelöscht. Bitte neu öffnen."
        } else if (
            route == "round" && (liveSession == null || liveSession.collectionId != collectionId)
        ) {
            navigate("collection")
            error = "Die Runde wurde durch eine Änderung beendet."
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (pending) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("vocab-saving"))
        error?.let {
            Text(
                it,
                Modifier.padding(20.dp).testTag("vocab-error"),
                color = MaterialTheme.colorScheme.error,
            )
        }
        when (route) {
            "overview" ->
                VocabularyList("Deine Vokabeln") {
                    item { Text("Deutsch → Englisch. Ohne Zeitdruck, alles auf diesem Gerät.") }
                    item { Panel { PracticeSoundControl(effects) } }
                    item {
                        VAction("Sammlung hinzufügen", "add-collection", !pending) {
                            collectionId = ""
                            draftId = id()
                            navigate("collectionEdit")
                        }
                    }
                    if (session != null)
                        item {
                            Panel("Deine gespeicherte Runde") {
                                Text(
                                    data.vocabularyCollections
                                        .find { it.id == session.collectionId }
                                        ?.name
                                        .orEmpty()
                                )
                                VAction(
                                    if (session.phase == VocabularyPhase.COMPLETE)
                                        "Ergebnis ansehen"
                                    else "Runde fortsetzen",
                                    "resume",
                                    !pending,
                                ) {
                                    collectionId = session.collectionId
                                    navigate("round")
                                }
                            }
                        }
                    val collections = data.vocabularyCollections.filter { it.profileId == owner }
                    if (collections.isEmpty())
                        item { Text("Noch keine Sammlung. Lege deine ersten Wörter an.") }
                    items(collections, key = { it.id }) { c ->
                        Panel(c.name) {
                            Text("${data.vocabularyCards.count { it.collectionId == c.id }} Wörter")
                            VAction("Sammlung öffnen", "collection-${c.id}", !pending) {
                                collectionId = c.id
                                navigate("collection")
                            }
                        }
                    }
                    item { VAction("Zurück zu Lernen", "exit", !pending, exit) }
                }
            "collectionEdit" ->
                key(draftId, collectionId) {
                    VocabularyCollectionEditor(
                        data,
                        owner,
                        collection,
                        draftId,
                        pending,
                        dirtyChanged = { dirty = it },
                        cancel = { back() },
                        save = { name, expectedName ->
                            val old = collection
                            val cid = old?.id ?: draftId
                            dispatch({
                                if (old == null) Vocabulary.createCollection(it, owner, cid, name)
                                else
                                    Vocabulary.renameCollection(
                                        it,
                                        owner,
                                        cid,
                                        expectedName!!,
                                        name,
                                    )
                            }) {
                                collectionId = cid
                                navigate("collection")
                            }
                        },
                    )
                }
            "collection" ->
                if (collection != null)
                    VocabularyList(collection.name) {
                        item {
                            Panel("Eine kleine Runde") {
                                Text("Höchstens 10 verschiedene Wörter pro Runde.")
                                VocabularyStyle.entries.forEach { style ->
                                    FilterChip(
                                        styleName == style.name,
                                        { styleName = style.name },
                                        enabled = !pending,
                                        label = { Text(if (style == VocabularyStyle.TYPED) "Abfragen · Antwort eingeben" else "Karteikarten · umdrehen") },
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("vocab-style-${style.name}"),
                                    )
                                }
                                val today = runCatching { vocabularyToday() }.getOrNull()
                                VocabularyMode.entries.forEach { m ->
                                    val count =
                                        today?.let {
                                            Vocabulary.eligibleCards(
                                                    data,
                                                    owner,
                                                    collectionId,
                                                    m,
                                                    it,
                                                )
                                                .size
                                        } ?: 0
                                    val label =
                                        when (m) {
                                            VocabularyMode.ALL -> "Alle"
                                            VocabularyMode.UNSURE -> "Unsicher"
                                            VocabularyMode.DUE -> "Fällig"
                                        }
                                    FilterChip(
                                        mode == m,
                                        { modeName = m.name },
                                        enabled = !pending,
                                        label = { Text("$label ($count)") },
                                        modifier =
                                            Modifier.heightIn(min = 48.dp)
                                                .testTag("vocab-mode-${m.name}"),
                                    )
                                }
                                Text(
                                    when (mode) {
                                        VocabularyMode.ALL -> "Alle Wörter, auch schon gelernte."
                                        VocabularyMode.UNSURE ->
                                            "Neue Wörter und Wörter, die noch Übung brauchen."
                                        VocabularyMode.DUE ->
                                            "Neue Wörter und Wörter mit Übungsdatum heute oder früher."
                                    }
                                )
                                val eligible =
                                    today?.let {
                                        Vocabulary.eligibleCards(
                                                data,
                                                owner,
                                                collectionId,
                                                mode,
                                                it,
                                            )
                                            .size
                                    } ?: 0
                                if (eligible == 0)
                                    Text(
                                        "Hier gibt es gerade keine Wörter für diese Auswahl.",
                                        Modifier.testTag("vocab-empty-mode"),
                                    )
                                VAction("Runde starten", "start", !pending && eligible > 0) {
                                    if (
                                        session != null && session.phase != VocabularyPhase.COMPLETE
                                    )
                                        confirmation = "replace"
                                    else start()
                                }
                                if (session?.collectionId == collectionId)
                                    VAction(
                                        if (session.phase == VocabularyPhase.COMPLETE)
                                            "Ergebnis ansehen"
                                        else "Runde fortsetzen",
                                        "resume",
                                        !pending,
                                    ) {
                                        navigate("round")
                                    }
                            }
                        }
                        item {
                            VAction("Wort hinzufügen", "add-card", !pending) {
                                cardId = ""
                                cardRevision = null
                                draftId = id()
                                navigate("cardEdit")
                            }
                        }
                        item {
                            Text(
                                "Übungsabstände: 1 / 3 / 7 / 14 / 30 Tage. Eine einfache Orientierung, kein optimaler Lernplan. Frühes Üben verschiebt das Datum nicht."
                            )
                        }
                        items(
                            data.vocabularyCards.filter { it.collectionId == collectionId },
                            key = { it.id },
                        ) { word ->
                            Panel(word.german) {
                                Text((listOf(word.english) + word.alternatives).joinToString(" · "))
                                data.vocabularyProgress
                                    .find { it.cardId == word.id }
                                    ?.dueDate
                                    ?.let { Text("Übungsdatum: ${dateLabel(it)}") }
                                VAction("Wort bearbeiten", "edit-${word.id}", !pending) {
                                    cardId = word.id
                                    cardRevision = word.revision
                                    draftId = id()
                                    navigate("cardEdit")
                                }
                                VAction("Wort löschen", "delete-${word.id}", !pending) {
                                    cardId = word.id
                                    cardRevision = word.revision
                                    confirmation = "deleteCard"
                                }
                            }
                        }
                        item {
                            VAction("Sammlung umbenennen", "rename", !pending) {
                                draftId = id()
                                navigate("collectionEdit")
                            }
                        }
                        item {
                            VAction("Sammlung löschen", "delete-collection", !pending) {
                                confirmation = "deleteCollection"
                            }
                        }
                        item {
                            VAction("Alle Sammlungen", "overview", !pending) {
                                navigate("overview")
                            }
                        }
                    }
            "cardEdit" ->
                if (collection != null && (cardRevision == null || card?.revision == cardRevision))
                    key(draftId, cardId, cardRevision) {
                        VocabularyCardEditor(
                            data,
                            owner,
                            collectionId,
                            card,
                            draftId,
                            pending,
                            dirtyChanged = { dirty = it },
                            cancel = { back() },
                            save = { german, english, alternatives, next ->
                                val cid = card?.id ?: draftId
                                val revision = cardRevision
                                dispatch({
                                    Vocabulary.saveCard(
                                        it,
                                        owner,
                                        collectionId,
                                        cid,
                                        revision,
                                        german,
                                        english,
                                        alternatives,
                                    )
                                }) {
                                    if (next) {
                                        cardId = ""
                                        cardRevision = null
                                        draftId = id()
                                        dirty = false
                                        error = null
                                    } else navigate("collection")
                                }
                            },
                        )
                    }
            "round" ->
                if (session != null && session.collectionId == collectionId)
                    key(session.id, session.turn, session.currentCardId) {
                        VocabularyRound(
                            data,
                            session,
                            pending,
                            effects,
                            reveal = {
                                val word = data.vocabularyCards.single { it.id == session.currentCardId }
                                dispatch({ Vocabulary.reveal(it, owner, session.id, session.turn, word.id, word.revision) })
                            },
                            grade = { known ->
                                val word = data.vocabularyCards.single { it.id == session.currentCardId }
                                dispatch({ Vocabulary.gradeSelf(it, owner, session.id, session.turn, word.id, word.revision, known, vocabularyToday()) }) {
                                    effects.persistedAnswer("vocab:${session.id}:${session.turn + 1}", known)
                                }
                            },
                            submit = { answer, dontKnow ->
                                val word =
                                    data.vocabularyCards.single { it.id == session.currentCardId }
                                dispatch({
                                    Vocabulary.submitAnswer(
                                        it,
                                        owner,
                                        session.id,
                                        session.turn,
                                        word.id,
                                        word.revision,
                                        answer,
                                        vocabularyToday(),
                                        dontKnow,
                                    )
                                }) {
                                    keyboard?.hide()
                                    val committed = activity.repo.state.value?.vocabularySessions
                                        ?.find { it.id == session.id }?.feedback
                                    if (committed?.turn == session.turn + 1)
                                        effects.persistedAnswer("vocab:${session.id}:${committed.turn}", committed.correct)
                                }
                            },
                            next = {
                                dispatch({
                                    Vocabulary.continueRound(it, owner, session.id, session.turn)
                                })
                            },
                            pause = { navigate("collection") },
                            repeat = { repeat(it) },
                            newRound = { navigate("collection") },
                        )
                    }
        }
    }
    confirmation?.let { kind ->
        AlertDialog(
            onDismissRequest = { if (!pending) confirmation = null },
            title = {
                Text(
                    when (kind) {
                        "discard" -> "Eingaben verwerfen?"
                        "replace" -> "Offene Runde ersetzen?"
                        else -> "Wirklich löschen?"
                    }
                )
            },
            text = {
                Text(
                    when (kind) {
                        "discard" -> "Deine ungespeicherten Eingaben gehen verloren."
                        "replace" ->
                            "Du kannst die gespeicherte Runde fortsetzen. Ersetzen beendet nur diese Runde; dein Lernstand bleibt."
                        else ->
                            "Das kann nicht rückgängig gemacht werden. Der zugehörige Lernstand und eine betroffene gespeicherte Runde werden entfernt."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    {
                        confirmation = null
                        when (kind) {
                            "discard" -> {
                                dirty = false
                                navigate(
                                    if (route == "cardEdit" || collection != null) "collection"
                                    else "overview"
                                )
                            }
                            "replace" -> start(true)
                            "deleteCard" -> {
                                val revision = cardRevision!!
                                val target = cardId
                                dispatch({ Vocabulary.deleteCard(it, owner, target, revision) })
                            }
                            "deleteCollection" ->
                                dispatch({ Vocabulary.deleteCollection(it, owner, collectionId) }) {
                                    navigate("overview")
                                }
                        }
                    },
                    enabled = !pending,
                    modifier = Modifier.testTag("vocab-confirm"),
                    colors = bayernButtonColors(ButtonDefaults.textButtonColors()),
                ) {
                    Text(
                        if (kind == "replace") "Ersetzen"
                        else if (kind == "discard") "Verwerfen" else "Löschen"
                    )
                }
            },
            dismissButton = {
                TextButton(
                    { confirmation = null },
                    enabled = !pending,
                    modifier = Modifier.testTag("vocab-cancel-confirm"),
                    colors = bayernButtonColors(ButtonDefaults.textButtonColors()),
                ) {
                    Text("Behalten")
                }
            },
        )
    }
}

@Composable
private fun VocabularyCollectionEditor(
    data: SchoolData,
    owner: String,
    collection: VocabularyCollection?,
    draftId: String,
    pending: Boolean,
    dirtyChanged: (Boolean) -> Unit,
    cancel: () -> Unit,
    save: (String, String?) -> Unit,
) {
    val originalName by rememberSaveable { mutableStateOf(collection?.name) }
    var name by rememberSaveable { mutableStateOf(originalName.orEmpty()) }
    val validation =
        runCatching {
                if (collection == null) Vocabulary.createCollection(data, owner, draftId, name)
                else Vocabulary.renameCollection(data, owner, collection.id, originalName!!, name)
            }
            .exceptionOrNull()
            ?.message
    SideEffect { dirtyChanged(name != originalName.orEmpty()) }
    Page(if (collection == null) "Neue Sammlung" else "Sammlung umbenennen") {
        VField("Name der Sammlung", "collection-name", name, pending, validation) { name = it }
        VAction("Sammlung speichern", "save-collection", !pending && validation == null) {
            save(name, originalName)
        }
        VAction("Abbrechen", "cancel", !pending, cancel)
    }
}

@Composable
private fun VField(
    label: String,
    tag: String,
    value: String,
    pending: Boolean,
    error: String? = null,
    done: (() -> Unit)? = null,
    change: (String) -> Unit,
) {
    OutlinedTextField(
        value,
        change,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("vocab-$tag"),
        enabled = !pending,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = if (done == null) ImeAction.Next else ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { if (!pending) done?.invoke() }),
        shape = monitorShape(18.dp),
    )
}

@Composable
private fun VocabularyCardEditor(
    data: SchoolData,
    owner: String,
    collectionId: String,
    card: VocabularyCard?,
    draftId: String,
    pending: Boolean,
    dirtyChanged: (Boolean) -> Unit,
    cancel: () -> Unit,
    save: (String, String, List<String>, Boolean) -> Unit,
) {
    var german by rememberSaveable { mutableStateOf(card?.german.orEmpty()) }
    var english by rememberSaveable { mutableStateOf(card?.english.orEmpty()) }
    var alternatives by
        rememberSaveable(
            stateSaver = listSaver<List<String>, String>(save = { it }, restore = { it })
        ) {
            mutableStateOf(card?.alternatives.orEmpty().toList())
        }
    val validation =
        runCatching {
                Vocabulary.saveCard(
                    data,
                    owner,
                    collectionId,
                    card?.id ?: draftId,
                    card?.revision,
                    german,
                    english,
                    alternatives,
                )
            }
            .exceptionOrNull()
            ?.message
    SideEffect {
        dirtyChanged(
            german != card?.german.orEmpty() ||
                english != card?.english.orEmpty() ||
                alternatives != card?.alternatives.orEmpty()
        )
    }
    Page(if (card == null) "Neues Wort" else "Wort bearbeiten") {
        if (card != null)
            Text(
                "Eine echte Änderung setzt den Lernstand dieses Wortes zurück und beendet eine betroffene gespeicherte Runde."
            )
        VField("Deutsch", "german", german, pending) { german = it }
        VField("Englisch", "english", english, pending) { english = it }
        Text(
            "Andere gültige Übersetzungen einzeln hinzufügen. Komma und Schrägstrich trennen nicht automatisch."
        )
        alternatives.forEachIndexed { index, value ->
            VField("Alternative ${index + 1}", "alternative-$index", value, pending) { updated ->
                alternatives =
                    alternatives.mapIndexed { i, old -> if (i == index) updated else old }
            }
            VAction("Alternative ${index + 1} entfernen", "remove-alternative-$index", !pending) {
                alternatives = alternatives.filterIndexed { i, _ -> i != index }
            }
        }
        VAction("Alternative hinzufügen", "add-alternative", !pending && alternatives.size < 4) {
            alternatives = alternatives + ""
        }
        validation?.let {
            Text(it, Modifier.testTag("vocab-validation"), color = MaterialTheme.colorScheme.error)
        }
        VAction("Wort speichern", "save-card", !pending && validation == null) {
            save(german, english, alternatives, false)
        }
        VAction("Speichern & nächstes Wort", "save-next", !pending && validation == null) {
            save(german, english, alternatives, true)
        }
        VAction("Abbrechen", "cancel", !pending, cancel)
    }
}

@Composable
private fun VocabularyRound(
    data: SchoolData,
    session: VocabularySession,
    pending: Boolean,
    effects: PracticeEffects,
    reveal: () -> Unit,
    grade: (Boolean) -> Unit,
    submit: (String, Boolean) -> Unit,
    next: () -> Unit,
    pause: () -> Unit,
    repeat: (VocabularyReplay) -> Unit,
    newRound: () -> Unit,
) {
    var answer by
        rememberSaveable(session.id, session.turn, session.currentCardId) { mutableStateOf("") }
    val tooLong = answer.length > MAX_VOCABULARY_RESPONSE
    val atCap =
        session.results.any {
            it.cardId == session.currentCardId && it.wrongAttempts >= MAX_VOCABULARY_ROUND_WRONG
        }
    Page(
        if (session.phase == VocabularyPhase.COMPLETE) "Runde geschafft" else "Deine Vokabelrunde"
    ) {
        Panel {
            Text(
                "Versuche: ${session.correctAttempts} richtig · ${session.wrongAttempts} falsch",
                Modifier.testTag("vocab-attempts"),
            )
            Text(
                "Wörter: ${session.completedWords} von ${session.selected.size} geschafft · ${session.openWords} offen",
                Modifier.testTag("vocab-words"),
            )
            val fraction by animateFloatAsState(
                targetValue = session.completedWords.toFloat() / session.selected.size,
                animationSpec = if (effects.animationEnabled()) tween(350) else snap(),
                label = "Vokabel-Fortschritt",
            )
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            PracticeSoundControl(effects)
        }
        if (session.style == VocabularyStyle.FLIP &&
            session.phase in listOf(VocabularyPhase.ASKING, VocabularyPhase.REVEALED)) {
            val word = data.vocabularyCards.single { it.id == session.currentCardId }
            Panel(if (session.phase == VocabularyPhase.ASKING) "Vorderseite" else "Rückseite") {
                val content: @Composable (VocabularyPhase) -> Unit = { phase ->
                    Text(
                        if (phase == VocabularyPhase.ASKING) word.german else word.english,
                        Modifier.testTag(if (phase == VocabularyPhase.ASKING) "vocab-prompt" else "vocab-flip-answer"),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                if (effects.animationEnabled()) AnimatedContent(session.phase, label = "Vokabel umdrehen") { phase -> content(phase) }
                else content(session.phase)
                if (session.phase == VocabularyPhase.REVEALED && word.alternatives.isNotEmpty())
                    Text("Auch gültig: ${word.alternatives.joinToString(" · ")}")
            }
        }
        when (session.phase) {
            VocabularyPhase.ASKING -> {
                val word = data.vocabularyCards.single { it.id == session.currentCardId }
                if (session.style == VocabularyStyle.FLIP) {
                    VAction("Umdrehen", "flip", !pending, reveal)
                } else {
                Panel("Deutsch → Englisch") {
                    Text(
                        word.german,
                        Modifier.testTag("vocab-prompt"),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                Text(
                    "Groß-/Kleinschreibung und zusätzliche Leerzeichen sind egal. Sonst zählt nur die eingetragene Übersetzung."
                )
                VField(
                    "Deine englische Antwort",
                    "answer",
                    answer,
                    pending,
                    if (tooLong) "Höchstens 120 Zeichen." else null,
                    done = { if (answer.isNotBlank() && !tooLong && !atCap) submit(answer, false) },
                ) {
                    answer = it
                }
                if (atCap)
                    Text(
                        "Diese Runde hat sehr viele Versuche. Du kannst pausieren oder eine neue Runde starten."
                    )
                VAction("Prüfen", "check", !pending && answer.isNotBlank() && !tooLong && !atCap) {
                    submit(answer, false)
                }
                VAction("Weiß ich noch nicht", "dont-know", !pending && !atCap) { submit("", true) }
                }
            }
            VocabularyPhase.REVEALED -> {
                VAction("Gewusst", "known", !pending) { grade(true) }
                VAction("Noch üben", "practice", !pending && !atCap) { grade(false) }
            }
            VocabularyPhase.FEEDBACK -> {
                val feedback = session.feedback!!
                val word = data.vocabularyCards.single { it.id == feedback.cardId }
                Panel(
                    if (feedback.correct) "Richtig!"
                    else "Noch nicht – du übst es gleich noch einmal"
                ) {
                    Text(word.german)
                    Text(
                        if (feedback.selfGraded) "Selbst bewertet: ${if (feedback.correct) "Gewusst" else "Noch üben"}"
                        else if (feedback.dontKnow) "Deine Antwort: Weiß ich noch nicht"
                        else "Deine Antwort: ${feedback.response}",
                        Modifier.testTag("vocab-response"),
                    )
                    Text("Englisch: ${word.english}", Modifier.testTag("vocab-feedback"))
                    if (word.alternatives.isNotEmpty())
                        Text("Auch gültig: ${word.alternatives.joinToString(" · ")}")
                    PracticeCelebration(effects, "vocab:${session.id}:${session.turn}")
                }
                VAction(
                    if (session.openWords == 0) "Ergebnis ansehen" else "Weiter",
                    "next",
                    !pending,
                    next,
                )
            }
            VocabularyPhase.COMPLETE -> {
                Text(
                    "Alle Wörter dieser Runde geschafft. Eine Pause passt jetzt auch.",
                    Modifier.testTag("vocab-summary"),
                )
                VAction("Alle Wörter dieser Runde wiederholen", "repeat-all", !pending) {
                    repeat(VocabularyReplay.ALL)
                }
                VAction(
                    "Nur Fehler dieser Runde wiederholen",
                    "repeat-failed",
                    !pending && session.failedCardIds.isNotEmpty(),
                ) {
                    repeat(VocabularyReplay.FAILED)
                }
                if (session.failedCardIds.isEmpty())
                    Text("Keine Fehler in dieser Runde – hier gibt es nichts zu wiederholen.")
                VAction("Neue Runde auswählen", "new-round", !pending, newRound)
            }
        }
        VAction(
            if (session.phase == VocabularyPhase.COMPLETE) "Zur Sammlung"
            else "Pause · zur Sammlung",
            "pause",
            !pending,
            pause,
        )
    }
}
