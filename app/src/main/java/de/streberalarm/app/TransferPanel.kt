package de.streberalarm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.streberalarm.core.*

@Composable
fun TransferPanel(p: Profile, d: SchoolData, edit: () -> Unit) {
    val r = remember(p, d) { Transfer.calculate(p, d) }
    Column(
        Modifier.testTag("transfer-overview"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel("Dein nächster Schulweg") {
            Text("Die passende Schule zählt. Jeder Weg bietet dir Möglichkeiten.")
            Text(
                if (p.grade == 3)
                    "Klasse 3: Zeit zum Entdecken. Der Übertritt steht erst nach Klasse 4 an."
                else "Entscheidet gemeinsam mit deiner Familie und deiner Lehrkraft."
            )
            Text(
                if (r.official) "Noten aus dem Übertrittszeugnis"
                else "Vorschau aus bisherigen Noten",
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Ø ${if(r.official && r.average!=null && r.values.filterNotNull().sum().toInt()%3!=0) r.values.filterNotNull().sum().toInt().let { "${it/3},${if(it%3==1) "33…" else "66…"}" } else number(r.average)}",
                Modifier.testTag("transfer-average"),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                if (r.official)
                    "Nur Deutsch, Mathematik und HSU · Prüfung mit der ungerundeten Summe der drei Zeugnisnoten."
                else
                    "Nur Deutsch, Mathematik und HSU · keine Zeugnisnote oder verbindliche Eignung.",
                style = MaterialTheme.typography.bodySmall,
            )
            Transfer.NAMES.forEachIndexed { i, name -> Text("$name: ${number(r.values[i])}") }
            if (r.duplicateSubjects)
                Text(
                    "Ein Übertrittsfach ist mehrfach angelegt. Bitte die Fächerliste klären; wir wählen keine Note auf Verdacht."
                )
            if (!r.supported)
                Text(
                    "Für dieses Bundesland oder Schuljahr ist die Übertrittsprüfung noch nicht verfügbar."
                )
            if (p.grade == 4 && r.supported) Action("Übertrittsnoten eintragen", edit)
        }
        fun label(route: TransferRoute) =
            when (route) {
                TransferRoute.DIRECT -> "Notenbedingung erfüllt"
                TransferRoute.FORECAST -> "Bisher rechnerisch im Bereich"
                TransferRoute.PROBE_NEEDED ->
                    if (r.official) "Probeunterricht als weiterer Weg"
                    else "Im Moment außerhalb des Notenbereichs"
                TransferRoute.PROBE_PASSED -> "Probeunterricht bestanden"
                TransferRoute.PARENTS -> "4 und 4: Eltern können Aufnahme beantragen"
                TransferRoute.PROBE_FAILED -> "Probeunterricht: Noten reichen noch nicht"
                TransferRoute.UNKNOWN -> "Noch keine vollständige Grundlage"
            }
        Panel("Mittelschule") {
            Text(
                if (r.supported) "Der Wechsel nach Klasse 4 ist grundsätzlich möglich."
                else "Informationen zum Schulweg mit deiner Schule klären."
            )
            Text("Praktisch lernen und verschiedene Abschlüsse erreichen.")
        }
        listOf(Triple("Realschule", r.realschule, 8), Triple("Gymnasium", r.gymnasium, 7))
            .forEach { (name, route, limit) ->
                Panel(name) {
                    Text(
                        label(route),
                        Modifier.testTag("transfer-$name-${route.name}"),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (name == "Realschule")
                            "Allgemeinbildung mit praktischen und theoretischen Schwerpunkten."
                        else "Vertieft lernen und auf das Abitur vorbereiten."
                    )
                    Text(
                        "Übertrittszeugnis: Deutsch + Mathematik + HSU zusammen höchstens $limit (Schnitt ${if(limit==7) "2,33" else "2,66"} oder besser).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        HelpPanel("Für euch & eure Eltern") {
            Text(
                "Die Vorschau mittelt die drei bekannten Fachschnitte. Einschätzungen zählen nicht. Die Lehrkraft legt die Zeugnisnoten fest. Sobald Übertrittsnoten eingetragen sind, müssen alle drei vorliegen; fehlende werden nicht durch Schätzungen ersetzt."
            )
            Text(
                "Das Übertrittszeugnis kommt in Klasse 4 Anfang Mai und gilt für das folgende Schuljahr. Diese Ansicht betrifft den regulären Wechsel von einer öffentlichen oder staatlich anerkannten Grundschule in Bayern."
            )
            Text(
                "Für Gymnasium und Realschule zählen auch der schulische Eignungsvermerk und die Aufnahmevoraussetzungen: am 30. September des Eintrittsjahres noch unter 12 Jahre (Ausnahmen durch die Schule), Anmeldung und gegebenenfalls besondere Eignung, etwa für ein musisches Gymnasium. Diese Notenprüfung garantiert keinen Schulplatz."
            )
            Text(
                "Probeunterricht: Deutsch und Mathematik. Bestanden mit mindestens 3 und 4, in beliebiger Reihenfolge. Bei 4 und 4 können die Eltern die Aufnahme beantragen. Eine bessere Note gleicht eine 5 nicht aus. Ergebnisse beider Schularten getrennt eintragen. Ein erfolgreicher Gymnasial-Probeunterricht wird auch für die Realschule berücksichtigt; auch 4 und 4 dort ermöglichen den Elternantrag für die Realschule."
            )
            Text(
                "Nach nicht bestandenem Realschul-Probeunterricht ist der Gymnasial-Probeunterricht nicht möglich. Nach nicht bestandenem Gymnasial-Probeunterricht kann ein späterer Realschul-Probeunterricht möglich sein; Termine und Zulassung klärt die Schule."
            )
            Text(
                "Besondere Sprachbiografie: Unter den Voraussetzungen des § 6 Abs. 6 GrSO kann die Schule bis 3,33 die Eignung feststellen. Das gilt nicht automatisch; bitte mit der Grundschule besprechen. Auch Überspringen oder andere Sonderfälle werden hier nicht automatisch entschieden."
            )
            Transfer.sources.forEach { Source(it) }
        }
    }
}

@Composable
fun TransferForm(p: Profile, d: SchoolData, save: ((SchoolData) -> SchoolData) -> Unit) {
    val subjects = remember(p, d.subjects) { Transfer.subjects(p, d) }
    val key = "${p.id}:uebertritt"
    val original = d.graduation[key] ?: GraduationInput(procedure = "uebertritt")
    var mode by rememberSaveable { mutableStateOf("Übertrittszeugnis") }
    var german by rememberSaveable {
        mutableStateOf(
            subjects[0]
                ?.let { s ->
                    d.officials
                        .find { it.subjectId == s.id && it.period == Transfer.PERIOD }
                        ?.value
                        ?.toString()
                }
                .orEmpty()
        )
    }
    var math by rememberSaveable {
        mutableStateOf(
            subjects[1]
                ?.let { s ->
                    d.officials
                        .find { it.subjectId == s.id && it.period == Transfer.PERIOD }
                        ?.value
                        ?.toString()
                }
                .orEmpty()
        )
    }
    var hsu by rememberSaveable {
        mutableStateOf(
            subjects[2]
                ?.let { s ->
                    d.officials
                        .find { it.subjectId == s.id && it.period == Transfer.PERIOD }
                        ?.value
                        ?.toString()
                }
                .orEmpty()
        )
    }
    var gd by rememberSaveable {
        mutableStateOf(original.numbers["gymDeutsch"]?.toString().orEmpty())
    }
    var gm by rememberSaveable {
        mutableStateOf(original.numbers["gymMathematik"]?.toString().orEmpty())
    }
    var rd by rememberSaveable {
        mutableStateOf(original.numbers["rsDeutsch"]?.toString().orEmpty())
    }
    var rm by rememberSaveable {
        mutableStateOf(original.numbers["rsMathematik"]?.toString().orEmpty())
    }
    Page(
        "Deine Übertrittsnoten",
        "Trage mit deinen Eltern nur die tatsächlich mitgeteilten Noten ein.",
    ) {
        if (p.school != School.GRUNDSCHULE || p.grade != 4 || !Rules.supported(p)) {
            Text("Diese Eingabe gibt es für Klasse 4 in Bayern mit passendem Regelpaket.")
            return@Page
        }
        Pick(
            "Noten aus",
            mode,
            listOf("Übertrittszeugnis", "Probeunterricht Gymnasium", "Probeunterricht Realschule"),
        ) {
            mode = it
        }
        if (mode == "Übertrittszeugnis") {
            GradePick("Deutsch", german, false) { german = it }
            GradePick("Mathematik", math, false) { math = it }
            GradePick("Heimat- und Sachunterricht", hsu, false) { hsu = it }
            if (subjects.any { it == null })
                Text(
                    "Deutsch, Mathematik und HSU müssen jeweils genau einmal bei deinen Fächern stehen. Ergänze oder kläre zuerst die Fächerliste."
                )
        } else {
            val gym = mode.endsWith("Gymnasium")
            GradePick("Deutsch", if (gym) gd else rd, false) { if (gym) gd = it else rd = it }
            GradePick("Mathematik", if (gym) gm else rm, false) { if (gym) gm = it else rm = it }
            Text(
                "Nur die offiziellen Ergebnisse dieses Probeunterrichts. 4 und 4 braucht zusätzlich den Antrag deiner Eltern."
            )
        }
        Action(
            "Übertrittsnoten speichern",
            enabled = subjects.all { it != null },
            onClick = {
                val values = listOf(german, math, hsu).map { it.toIntOrNull() }
                save { current ->
                    val ids = subjects.map { it!!.id }.toSet()
                    current.copy(
                        officials =
                            if (mode != "Übertrittszeugnis") current.officials
                            else
                                current.officials.filterNot {
                                    it.subjectId in ids && it.period == Transfer.PERIOD
                                } +
                                    subjects
                                        .mapIndexed { i, s ->
                                            val old =
                                                current.officials.find {
                                                    it.subjectId == s!!.id &&
                                                        it.period == Transfer.PERIOD
                                                }
                                            Official(
                                                id = old?.id ?: id(),
                                                subjectId = s!!.id,
                                                period = Transfer.PERIOD,
                                                value = values[i],
                                                omitted = old?.omitted == true && values[i] == null,
                                            )
                                        }
                                        .filter { it.value != null || it.omitted },
                        graduation =
                            current.graduation +
                                (key to
                                    original.copy(
                                        numbers =
                                            original.numbers +
                                                mapOf(
                                                    "gymDeutsch" to gd.toIntOrNull(),
                                                    "gymMathematik" to gm.toIntOrNull(),
                                                    "rsDeutsch" to rd.toIntOrNull(),
                                                    "rsMathematik" to rm.toIntOrNull(),
                                                )
                                    )),
                    )
                }
            },
        )
    }
}
