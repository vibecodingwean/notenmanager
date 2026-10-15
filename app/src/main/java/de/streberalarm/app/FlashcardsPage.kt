package de.streberalarm.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.streberalarm.core.*
import kotlinx.coroutines.*

@Composable
fun FlashcardsPage(
    activity: MainActivity,
    profile: Profile,
    data: SchoolData,
    generation: Long,
    exit: () -> Unit,
    onBackChanged: ((() -> Unit)?) -> Unit,
) {
    val owner = profile.id
    var route by rememberSaveable(owner, generation) { mutableStateOf("overview") }
    var setId by rememberSaveable(owner, generation) { mutableStateOf("") }
    var cardId by rememberSaveable(owner, generation) { mutableStateOf("") }
    var cardRevision by rememberSaveable(owner, generation) { mutableStateOf<Int?>(null) }
    var draftId by rememberSaveable(owner, generation) { mutableStateOf(id()) }
    var name by rememberSaveable(owner, generation, draftId) { mutableStateOf("") }
    var front by rememberSaveable(owner, generation, draftId) { mutableStateOf("") }
    var backText by rememberSaveable(owner, generation, draftId) { mutableStateOf("") }
    var pending by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val effects = rememberPracticeEffects(activity)
    val set = data.flashcardSets.find { it.id == setId && it.profileId == owner }
    val card = data.flashcards.find { it.id == cardId && it.setId == setId }
    val session = data.flashcardSessions.find { it.profileId == owner }

    fun navigate(to: String) {
        error = null
        route = to
    }
    fun back() {
        if (pending) return
        when (route) {
            "overview" -> exit()
            "set" -> navigate("overview")
            "setEdit" -> navigate(if (set == null) "overview" else "set")
            else -> navigate("set")
        }
    }
    val latestBack by rememberUpdatedState<() -> Unit>({ back() })
    DisposableEffect(Unit) {
        onBackChanged { latestBack() }
        onDispose { onBackChanged(null) }
    }
    BackHandler { back() }
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
                error = e.message ?: "Speichern ging nicht. Bitte erneut versuchen."
            } finally {
                pending = false
            }
        }
    }
    fun start(replace: Boolean = false, onlyOpen: Boolean = false) {
        val newId = id()
        val previousId = session?.id
        dispatch({ Flashcards.start(it, owner, setId, newId, previousId, replace, onlyOpen) }) {
            navigate("round")
        }
    }
    // A profile/workspace switch or external edit invalidates this page's route and callbacks.
    LaunchedEffect(route, set?.id, card?.revision, session?.id, pending) {
        if (pending) return@LaunchedEffect
        val latest = activity.repo.state.value ?: return@LaunchedEffect
        if (
            latest.activeProfileId != owner ||
                activity.repo.vocabularyGeneration.value != generation
        )
            return@LaunchedEffect
        if (
            route !in listOf("overview", "setEdit") &&
                latest.flashcardSets.none { it.id == setId && it.profileId == owner }
        ) {
            navigate("overview")
            error = "Das Set wurde geändert oder gelöscht."
        } else if (
            route == "round" &&
                latest.flashcardSessions.none { it.profileId == owner && it.setId == setId }
        ) {
            navigate("set")
            error = "Die Runde wurde durch eine Änderung beendet."
        } else if (
            route == "cardEdit" &&
                cardRevision != null &&
                latest.flashcards.none {
                    it.id == cardId && it.setId == setId && it.revision == cardRevision
                }
        ) {
            navigate("set")
            error = "Die Karte wurde geändert oder gelöscht. Bitte neu öffnen."
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (pending) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("flash-saving"))
        error?.let {
            Text(
                it,
                Modifier.padding(20.dp).testTag("flash-error"),
                color = MaterialTheme.colorScheme.error,
            )
        }
        when (route) {
            "overview" ->
                LazyColumn(
                    Modifier.fillMaxSize().testTag("flash-overview"),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item { Text("Lernkarten", style = MaterialTheme.typography.headlineMedium) }
                    item { Text("Erstelle eigene Fragen und Antworten. Übe ohne Zeitdruck.") }
                    item { Panel { PracticeSoundControl(effects) } }
                    item {
                        Action(
                            "Set hinzufügen",
                            {
                                setId = ""
                                draftId = id()
                                name = ""
                                navigate("setEdit")
                            },
                            !pending,
                            Modifier.testTag("flash-add-set"),
                        )
                    }
                    if (session != null)
                        item {
                            Panel("Gespeicherte Runde") {
                                Text(
                                    data.flashcardSets
                                        .find { it.id == session.setId }
                                        ?.name
                                        .orEmpty()
                                )
                                Action(
                                    if (session.phase == FlashcardPhase.COMPLETE) "Ergebnis ansehen"
                                    else "Fortsetzen",
                                    {
                                        setId = session.setId
                                        navigate("round")
                                    },
                                    !pending,
                                )
                            }
                        }
                    val sets = data.flashcardSets.filter { it.profileId == owner }
                    if (sets.isEmpty()) item { Text("Noch keine Sets. Lege dein erstes Set an.") }
                    items(sets, key = { it.id }) { s ->
                        Panel(s.name) {
                            Text("${data.flashcards.count { it.setId == s.id }} Karten")
                            Action(
                                "Öffnen",
                                {
                                    setId = s.id
                                    navigate("set")
                                },
                                !pending,
                                Modifier.testTag("flash-set-${s.id}"),
                            )
                        }
                    }
                    item { Action("Zurück zu Lernen", exit, !pending) }
                }
            "setEdit" ->
                Page(if (set == null) "Neues Set" else "Set umbenennen") {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        enabled = !pending,
                        modifier = Modifier.fillMaxWidth().testTag("flash-set-name"),
                    )
                    Action(
                        "Speichern",
                        {
                            val sid = set?.id ?: draftId
                            val original = set?.name
                            dispatch({
                                if (original == null) Flashcards.createSet(it, owner, sid, name)
                                else Flashcards.renameSet(it, owner, sid, original, name)
                            }) {
                                setId = sid
                                navigate("set")
                            }
                        },
                        !pending && name.isNotBlank(),
                        Modifier.testTag("flash-save-set"),
                    )
                    Action("Abbrechen", { back() }, !pending)
                }
            "set" ->
                if (set != null)
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("flash-set"),
                        contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item { Text(set.name, style = MaterialTheme.typography.headlineMedium) }
                        item {
                            Panel("Üben") {
                                Text("${data.flashcards.count { it.setId == setId }} Karten im Set")
                                Text(
                                    "Bis zu 20 Karten pro Runde. Offene Karten kommen am Ende wieder."
                                )
                                Action(
                                    "Runde starten",
                                    {
                                        if (
                                            session != null &&
                                                session.phase != FlashcardPhase.COMPLETE
                                        )
                                            confirm = "replace"
                                        else start()
                                    },
                                    !pending && data.flashcards.any { it.setId == setId },
                                    Modifier.testTag("flash-start"),
                                )
                                if (session?.setId == setId)
                                    Action(
                                        if (session.phase == FlashcardPhase.COMPLETE)
                                            "Ergebnis ansehen"
                                        else "Runde fortsetzen",
                                        { navigate("round") },
                                        !pending,
                                    )
                            }
                        }
                        item {
                            Action(
                                "Karte hinzufügen",
                                {
                                    cardId = ""
                                    cardRevision = null
                                    draftId = id()
                                    front = ""
                                    backText = ""
                                    navigate("cardEdit")
                                },
                                !pending,
                                Modifier.testTag("flash-add-card"),
                            )
                        }
                        items(data.flashcards.filter { it.setId == setId }, key = { it.id }) { c ->
                            Panel(c.front) {
                                Text(c.back)
                                Action(
                                    "Bearbeiten",
                                    {
                                        cardId = c.id
                                        cardRevision = c.revision
                                        draftId = id()
                                        front = c.front
                                        backText = c.back
                                        navigate("cardEdit")
                                    },
                                    !pending,
                                )
                                Action(
                                    "Löschen",
                                    {
                                        cardId = c.id
                                        confirm = "card"
                                    },
                                    !pending,
                                )
                            }
                        }
                        item {
                            Action(
                                "Set umbenennen",
                                {
                                    name = set.name
                                    navigate("setEdit")
                                },
                                !pending,
                            )
                        }
                        item { Action("Set löschen", { confirm = "set" }, !pending) }
                        item { Action("Alle Sets", { navigate("overview") }, !pending) }
                    }
            "cardEdit" ->
                if (set != null && (cardRevision == null || card?.revision == cardRevision))
                    Page(if (card == null) "Neue Karte" else "Karte bearbeiten") {
                        if (card != null)
                            Text(
                                "Eine Änderung setzt den Lernstand dieser Karte zurück und beendet eine betroffene Runde."
                            )
                        OutlinedTextField(
                            front,
                            { front = it },
                            label = { Text("Vorderseite · Frage") },
                            enabled = !pending,
                            modifier = Modifier.fillMaxWidth().testTag("flash-front"),
                        )
                        OutlinedTextField(
                            backText,
                            { backText = it },
                            label = { Text("Rückseite · Antwort") },
                            enabled = !pending,
                            modifier = Modifier.fillMaxWidth().testTag("flash-back"),
                        )
                        Action(
                            "Karte speichern",
                            {
                                val cid = card?.id ?: draftId
                                val revision = cardRevision
                                dispatch({
                                    Flashcards.saveCard(
                                        it,
                                        owner,
                                        setId,
                                        cid,
                                        revision,
                                        front,
                                        backText,
                                    )
                                }) {
                                    navigate("set")
                                }
                            },
                            !pending && front.isNotBlank() && backText.isNotBlank(),
                            Modifier.testTag("flash-save-card"),
                        )
                        Action("Abbrechen", { back() }, !pending)
                    }
            "round" ->
                if (session != null && session.setId == setId)
                    Page(
                        if (session.phase == FlashcardPhase.COMPLETE) "Runde geschafft"
                        else "Lernkarten üben"
                    ) {
                        Panel {
                            Text(
                                "${session.completed.size} von ${session.selected.size} geschafft · ${session.openCards} offen",
                                Modifier.testTag("flash-progress"),
                            )
                            Text(
                                "${session.results.sumOf { it.known }} gewusst · ${session.results.sumOf { it.practice }} noch üben"
                            )
                            val fraction by
                                animateFloatAsState(
                                    targetValue =
                                        session.completed.size.toFloat() / session.selected.size,
                                    animationSpec =
                                        if (effects.animationEnabled()) tween(350) else snap(),
                                    label = "Lernkarten-Fortschritt",
                                )
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            PracticeSoundControl(effects)
                        }
                        when (session.phase) {
                            FlashcardPhase.QUESTION,
                            FlashcardPhase.REVEALED -> {
                                val shown =
                                    data.flashcards.single { it.id == session.currentCardId }
                                Panel(
                                    if (session.phase == FlashcardPhase.QUESTION) "Vorderseite"
                                    else "Rückseite"
                                ) {
                                    val content: @Composable (FlashcardPhase) -> Unit = { phase ->
                                        Text(
                                            if (phase == FlashcardPhase.QUESTION) shown.front
                                            else shown.back,
                                            Modifier.testTag(
                                                if (phase == FlashcardPhase.QUESTION)
                                                    "flash-question"
                                                else "flash-answer"
                                            ),
                                            style = MaterialTheme.typography.headlineMedium,
                                        )
                                    }
                                    if (effects.animationEnabled())
                                        AnimatedContent(session.phase, label = "Karte umdrehen") {
                                            phase ->
                                            content(phase)
                                        }
                                    else content(session.phase)
                                }
                                if (session.phase == FlashcardPhase.QUESTION)
                                    Action(
                                        "Umdrehen",
                                        {
                                            dispatch({
                                                Flashcards.reveal(
                                                    it,
                                                    owner,
                                                    session.id,
                                                    session.turn,
                                                    shown.id,
                                                )
                                            })
                                        },
                                        !pending,
                                        Modifier.testTag("flash-flip"),
                                    )
                                else {
                                    Action(
                                        "Gewusst",
                                        {
                                            dispatch({
                                                Flashcards.grade(
                                                    it,
                                                    owner,
                                                    session.id,
                                                    session.turn,
                                                    shown.id,
                                                    true,
                                                )
                                            }) {
                                                effects.persistedAnswer(
                                                    "flash:${session.id}:${session.turn + 1}",
                                                    true,
                                                )
                                            }
                                        },
                                        !pending,
                                        Modifier.testTag("flash-known"),
                                    )
                                    Action(
                                        "Noch üben",
                                        {
                                            dispatch({
                                                Flashcards.grade(
                                                    it,
                                                    owner,
                                                    session.id,
                                                    session.turn,
                                                    shown.id,
                                                    false,
                                                )
                                            }) {
                                                effects.persistedAnswer(
                                                    "flash:${session.id}:${session.turn + 1}",
                                                    false,
                                                )
                                            }
                                        },
                                        !pending,
                                        Modifier.testTag("flash-practice"),
                                    )
                                }
                            }
                            FlashcardPhase.FEEDBACK -> {
                                Panel(
                                    if (session.feedbackKnown == true) "Gewusst!"
                                    else "Du siehst diese Karte am Ende wieder"
                                ) {
                                    val shown =
                                        data.flashcards.single { it.id == session.feedbackCardId }
                                    Text(shown.front)
                                    Text(shown.back)
                                    PracticeCelebration(
                                        effects,
                                        "flash:${session.id}:${session.turn}",
                                    )
                                }
                                Action(
                                    if (session.openCards == 0) "Ergebnis ansehen" else "Weiter",
                                    {
                                        dispatch({
                                            Flashcards.continueRound(
                                                it,
                                                owner,
                                                session.id,
                                                session.turn,
                                            )
                                        })
                                    },
                                    !pending,
                                    Modifier.testTag("flash-next"),
                                )
                            }
                            FlashcardPhase.COMPLETE -> {
                                Text(
                                    "Alle Karten dieser Runde geschafft.",
                                    Modifier.testTag("flash-result"),
                                )
                                Action("Alle wiederholen", { start(onlyOpen = false) }, !pending)
                                Action(
                                    "Karten mit Fehlern wiederholen",
                                    { start(onlyOpen = true) },
                                    !pending && session.failedCards.isNotEmpty(),
                                    Modifier.testTag("flash-repeat-open"),
                                )
                                Action("Neue Runde", { navigate("set") }, !pending)
                            }
                        }
                        Action("Pause · zum Set", { navigate("set") }, !pending)
                    }
        }
    }
    confirm?.let { kind ->
        AlertDialog(
            onDismissRequest = { if (!pending) confirm = null },
            title = {
                Text(if (kind == "replace") "Offene Runde ersetzen?" else "Wirklich löschen?")
            },
            text = {
                Text(
                    if (kind == "replace") "Dein Lernstand bleibt erhalten."
                    else "Karten und ihr Lernstand werden entfernt."
                )
            },
            confirmButton = {
                TextButton(
                    {
                        confirm = null
                        when (kind) {
                            "replace" -> start(replace = true)
                            "set" ->
                                dispatch({ Flashcards.deleteSet(it, owner, setId) }) {
                                    navigate("overview")
                                }
                            "card" -> {
                                val target = card
                                if (target != null)
                                    dispatch({
                                        Flashcards.deleteCard(it, owner, target.id, target.revision)
                                    })
                            }
                        }
                    },
                    enabled = !pending,
                ) {
                    Text(if (kind == "replace") "Ersetzen" else "Löschen")
                }
            },
            dismissButton = {
                TextButton({ confirm = null }, enabled = !pending) { Text("Abbrechen") }
            },
        )
    }
}
