package de.streberalarm.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.streberalarm.core.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

// Slots stay numbered as lessons; time gaps are derived, never stored as extra lessons.
internal fun periodTimes(slot: Int, earliest: LocalTime): Pair<String, String> {
    val defaults =
        listOf("08:00", "08:45", "09:50", "10:35", "11:40", "12:25", "13:30", "14:30", "15:15")
    val start = maxOf(earliest, defaults.getOrNull(slot - 1)?.let(LocalTime::parse) ?: earliest)
    require(start.toSecondOfDay() + 45 * 60 < 24 * 3600)
    return start.toString() to start.plusMinutes(45).toString()
}

internal data class TimetableGap(val start: String, val end: String) {
    val minutes: Long
        get() = java.time.Duration.between(LocalTime.parse(start), LocalTime.parse(end)).toMinutes()
}

internal fun timetableGaps(rows: List<Lesson>): Map<String, TimetableGap> {
    val gaps = mutableMapOf<String, TimetableGap>()
    var previousEnd: String? = null
    for (row in rows.sortedBy { it.start }) {
        if (row.start >= row.end) continue
        previousEnd?.let { end -> if (end < row.start) gaps[row.id] = TimetableGap(end, row.start) }
        previousEnd = maxOf(previousEnd ?: row.end, row.end)
    }
    return gaps
}

@Composable
internal fun TimetablePause(gap: TimetableGap) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("gap-${gap.start}-${gap.end}"),
        color = BreakTint,
        shape = lookShape(18.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                "Pause · ${gap.minutes} ${if (gap.minutes == 1L) "Minute" else "Minuten"}",
                fontWeight = FontWeight.Bold,
            )
            Text("${gap.start}–${gap.end}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun timetableRows(initial: Timetable?): List<Lesson> {
    val weeks = if (initial?.blocks?.any { it.week != 0 } == true) listOf(1, 2) else listOf(0)
    val days = if (initial?.blocks?.any { it.day > 5 } == true) 1..7 else 1..5
    return weeks.flatMap { week ->
        days.flatMap { day ->
            var next = 1
            val saved =
                initial
                    ?.blocks
                    .orEmpty()
                    .filter { it.day == day && (it.week == 0 || it.week == week) }
                    .sortedBy { it.start }
                    .flatMap { b ->
                        if (b.slot > 0) {
                            next = maxOf(next, b.slot + 1)
                            listOf(b)
                        } else {
                            val start = LocalTime.parse(b.start)
                            val minutes =
                                java.time.Duration.between(start, LocalTime.parse(b.end))
                                    .toMinutes()
                            val count =
                                if (minutes > 45 && minutes % 45 == 0L && minutes <= 900)
                                    (minutes / 45).toInt()
                                else 1
                            (0 until count).map { i ->
                                b.copy(
                                    id = if (i == 0) b.id else id(),
                                    slot = next++,
                                    start =
                                        if (count == 1) b.start
                                        else start.plusMinutes(i * 45L).toString(),
                                    end =
                                        if (count == 1) b.end
                                        else start.plusMinutes((i + 1) * 45L).toString(),
                                )
                            }
                        }
                    }
            var cursor = LocalTime.of(8, 0)
            (1..maxOf(6, saved.maxOfOrNull { it.slot } ?: 0)).map { slot ->
                val old = saved.firstOrNull { it.slot == slot }
                val times = if (old == null) periodTimes(slot, cursor) else old.start to old.end
                val row =
                    old?.let { if (it.week == week) it else it.copy(id = id(), week = week) }
                        ?: Lesson(
                            subjectId = "",
                            day = day,
                            start = times.first,
                            end = times.second,
                            week = week,
                            slot = slot,
                        )
                cursor = LocalTime.parse(row.end)
                row
            }
        }
    }
}

@Composable
fun DayTabs(day: Int, weekend: Boolean = false, select: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (weekend) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val names = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        names.take(if (weekend) 7 else 5).forEachIndexed { i, name ->
            FilterChip(
                selected = day == i + 1,
                onClick = { select(i + 1) },
                label = { Text(name, fontWeight = FontWeight.Bold) },
                modifier = (if (weekend) Modifier else Modifier.weight(1f)).testTag("day-${i + 1}"),
            )
        }
    }
}

@Composable
fun TimetableForm(
    p: Profile,
    d: SchoolData,
    initialDay: Int = 1,
    addSubject: (Subject) -> Unit,
    save: (Timetable) -> Unit,
) {
    val initial = remember {
        d.timetables.filter { it.profileId == p.id }.maxByOrNull { it.validFrom }
    }
    var from by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var anchor by rememberSaveable {
        mutableStateOf(initial?.anchorMonday ?: LocalDate.now().with(DayOfWeek.MONDAY).toString())
    }
    val rowSaver =
        Saver<List<Lesson>, String>(
            save = { dataJson.encodeToString(it) },
            restore = { dataJson.decodeFromString(it) },
        )
    var rows by rememberSaveable(stateSaver = rowSaver) { mutableStateOf(timetableRows(initial)) }
    var day by rememberSaveable { mutableIntStateOf(initialDay.coerceIn(1, 7)) }
    var ab by rememberSaveable { mutableStateOf(initial?.blocks?.any { it.week != 0 } == true) }
    var week by rememberSaveable { mutableIntStateOf(if (ab) 1 else 0) }
    var newFor by rememberSaveable { mutableStateOf<String?>(null) }
    var newName by rememberSaveable { mutableStateOf("") }
    var timeFor by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    val subjects = d.subjects.filter { it.profileId == p.id }.alphabetical()
    fun change(row: Lesson) {
        rows = rows.map { if (it.id == row.id) row else it }
    }
    val daily = rows.filter { it.day == day && it.week == week }.sortedBy { it.slot }
    fun append(count: Int) {
        val last = daily.last()
        if (last.slot + count > 20) {
            error = "Für einen Tag sind schon sehr viele Stunden eingetragen."
            return
        }
        runCatching {
                var cursor = LocalTime.parse(last.end)
                val added =
                    (1..count).map { i ->
                        val times = periodTimes(last.slot + i, cursor)
                        cursor = LocalTime.parse(times.second)
                        Lesson(
                            subjectId = "",
                            day = day,
                            week = week,
                            start = times.first,
                            end = times.second,
                            slot = last.slot + i,
                        )
                    }
                rows = rows + added
            }
            .onFailure { error = "Der Block würde erst am nächsten Tag enden." }
    }

    val averages =
        remember(p, d.subjects, d.assessments) {
            d.subjects
                .filter { it.profileId == p.id }
                .associate { subject ->
                    subject.id to Grades.calculate(p, subject, d.assessments).value
                }
        }

    Page("Dein Stundenplan", "Tippe auf eine Stunde. Wähle ein Fach oder eine Pause.") {
        DayTabs(day, rows.any { it.day > 5 }) { day = it }
        if (ab)
            Pick("Woche", if (week == 1) "A-Woche" else "B-Woche", listOf("A-Woche", "B-Woche")) {
                week = if (it == "A-Woche") 1 else 2
            }
        val gaps = timetableGaps(daily)
        daily.forEach { row ->
            gaps[row.id]?.let { TimetablePause(it) }
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Pick(
                            "${row.slot}. Stunde · ${row.start}–${row.end}",
                            if (row.isBreak) "Pause"
                            else subjects.find { it.id == row.subjectId }?.name ?: "Noch frei",
                            listOf("Noch frei") +
                                (subjects.map { it.name }.filterNot { it == "Pause" } + "Pause")
                                    .sortedWith(
                                        java.text.Collator.getInstance(java.util.Locale.GERMAN)
                                    ),
                            addNew = {
                                newFor = row.id
                                newName = ""
                            },
                            tag = "Fach für Stunde ${row.slot}",
                            tint =
                                if (row.isBreak) BreakTint
                                else
                                    subjects
                                        .find { it.id == row.subjectId }
                                        ?.let { themedSubjectTint(it) },
                        ) { name ->
                            change(
                                row.copy(
                                    subjectId =
                                        subjects
                                            .find { it.name == name && name != "Pause" }
                                            ?.id
                                            .orEmpty(),
                                    isBreak = name == "Pause",
                                )
                            )
                        }
                        if (!row.isBreak)
                            averages[row.subjectId]?.let { average ->
                                Text(
                                    "Schnitt ${number(average)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                    }
                    IconButton({ timeFor = if (timeFor == row.id) null else row.id }) {
                        Icon(Icons.Outlined.Schedule, "Uhrzeit für Stunde ${row.slot}")
                    }
                }
                if (timeFor == row.id)
                    Panel {
                        ClockField("Beginn", row.start) { change(row.copy(start = it)) }
                        ClockField("Ende", row.end) { change(row.copy(end = it)) }
                        if (row.slot > 6)
                            TextButton({
                                rows = rows.filterNot { it.id == row.id }
                                timeFor = null
                            }) {
                                Text("Stunde entfernen")
                            }
                    }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ append(1) }, Modifier.weight(1f)) {
                Icon(Icons.Outlined.Add, null)
                Text("Stunde")
            }
            OutlinedButton({ append(2) }, Modifier.weight(1f)) {
                Icon(Icons.Outlined.Add, null)
                Text("Doppelstunde")
            }
        }
        HelpPanel("Ab wann gilt der Plan? / A- und B-Woche") {
            DateField("Gültig ab", from) { from = it }
            Text("Deine älteren Pläne bleiben gespeichert.")
            Check("Mein Plan wechselt jede Woche", ab) { enabled ->
                if (enabled != ab) {
                    rows =
                        if (enabled)
                            rows.flatMap {
                                listOf(it.copy(id = id(), week = 1), it.copy(id = id(), week = 2))
                            }
                        else rows.filter { it.week == 1 }.map { it.copy(week = 0) }
                    ab = enabled
                    week = if (enabled) 1 else 0
                }
            }
            if (ab) DateField("Montag einer A-Woche", anchor) { anchor = it }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Action("Stundenplan speichern") {
            runCatching {
                    val blocks = rows.filter { it.isBreak || it.subjectId.isNotBlank() }
                    require(blocks.all { LocalTime.parse(it.start) < LocalTime.parse(it.end) })
                    require(LocalDate.parse(anchor).dayOfWeek == DayOfWeek.MONDAY)
                    // Different subjects and breaks cannot occupy the same time in this day/week.
                    blocks
                        .groupBy { it.day to it.week }
                        .values
                        .forEach { list ->
                            list
                                .sortedBy { it.start }
                                .zipWithNext()
                                .forEach { (a, b) -> require(a.end <= b.start) }
                        }
                    save(
                        Timetable(
                            profileId = p.id,
                            validFrom = from,
                            anchorMonday = anchor,
                            blocks = blocks,
                        )
                    )
                }
                .onFailure {
                    error =
                        "Prüfe die Uhrzeiten: Stunden dürfen sich nicht überschneiden. Die A-Woche muss an einem Montag beginnen."
                }
        }
    }
    newFor?.let { target ->
        AlertDialog(
            onDismissRequest = { newFor = null },
            title = { Text("Ein neues Fach") },
            text = { Field("Wie heißt das Fach?", newName) { newName = it } },
            confirmButton = {
                TextButton(
                    {
                        val name = newName.trim()
                        val row = rows.first { it.id == target }
                        if (name.equals("Pause", true))
                            change(row.copy(subjectId = "", isBreak = true))
                        else {
                            val subject =
                                subjects.firstOrNull { it.name.equals(name, true) }
                                    ?: (Defaults.subjects(p).firstOrNull {
                                            it.name.equals(name, true)
                                        } ?: Subject(profileId = p.id, name = name))
                                        .also(addSubject)
                            change(row.copy(subjectId = subject.id, isBreak = false))
                        }
                        newFor = null
                    },
                    enabled = newName.isNotBlank(),
                ) {
                    Text("Fach hinzufügen")
                }
            },
            dismissButton = { TextButton({ newFor = null }) { Text("Abbrechen") } },
        )
    }
}
