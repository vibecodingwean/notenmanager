package de.streberalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.streberalarm.core.*
import kotlinx.coroutines.launch

@Composable
fun LearningPage(
    a: MainActivity,
    p: Profile,
    d: SchoolData,
    open: (String) -> Unit,
    add: () -> Unit,
    addRecord: () -> Unit,
    grade: (String) -> Unit,
    estimate: (String) -> Unit,
) {
    var archive by rememberSaveable { mutableStateOf(false) }
    if (archive) {
        Column(Modifier.fillMaxSize()) {
            TextButton({ archive = false }) { Text("Zurück zu den Lernkarten") }
            Box(Modifier.weight(1f)) { ExamsPage(p, d, open, addRecord) }
        }
        return
    }
    var stage by rememberSaveable { mutableStateOf(Stage.PLANNED) }
    val rows =
        d.assessments
            .filter { e ->
                e.stage == stage && d.subjects.any { it.id == e.subjectId && it.profileId == p.id }
            }
            .let {
                if (stage == Stage.PLANNED) it.sortedBy { e -> e.date + e.time.orEmpty() }
                else it.sortedByDescending { e -> e.date }
            }
    Page("Deine Lernkarten", "Ein Thema. Eine kleine Runde. Dann Pause.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                    Stage.PLANNED to "Kommt bald",
                    Stage.WRITTEN to "Geschrieben",
                    Stage.GRADED to "Note zurück",
                )
                .forEach { (value, label) ->
                    FilterChip(
                        stage == value,
                        { stage = value },
                        label = { Text(label) },
                        modifier = Modifier.testTag("learn-stage-${value.name}"),
                    )
                }
        }
        if (rows.isEmpty())
            Panel {
                Text(
                    when (stage) {
                        Stage.PLANNED ->
                            "Gerade steht keine Probe an. Hier kannst du die nächste planen."
                        Stage.WRITTEN -> "Hier warten geschriebene Proben auf ihre Note."
                        else -> "Hier findest du deine benoteten Proben."
                    }
                )
            }
        else
            key(stage, p.id) {
                val pager = rememberPagerState(pageCount = { rows.size })
                val scope = rememberCoroutineScope()
                HorizontalPager(
                    pager,
                    modifier = Modifier.fillMaxWidth().testTag("learning-deck"),
                    key = { rows[it].id },
                    verticalAlignment = Alignment.Top,
                ) { index ->
                    val e = rows[index]
                    ProbeCard(a, d, e, { grade(e.id) }, { estimate(e.id) }, { open(e.id) })
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                        enabled = pager.currentPage > 0,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Vorherige Probe")
                    }
                    Text("${pager.currentPage + 1} von ${rows.size} · Wische zum Blättern")
                    IconButton(
                        { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                        enabled = pager.currentPage < rows.lastIndex,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Nächste Probe")
                    }
                }
            }
        OutlinedButton(
            add,
            Modifier.fillMaxWidth(),
            shape =
                if (LocalLook.current == AppLook.HACKER || LocalLook.current == AppLook.PIXEL)
                    lookShape(0.dp)
                else ButtonDefaults.outlinedShape,
        ) {
            Text("Probe planen")
        }
        TextButton({ archive = true }) { Text("Alle Proben") }
    }
}
