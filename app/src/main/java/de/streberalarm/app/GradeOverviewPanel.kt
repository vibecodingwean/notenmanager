package de.streberalarm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.streberalarm.core.*

@Composable
fun GradeOverviewPanel(p: Profile, d: SchoolData) {
    val result = remember(p, d) { GradeOverviewCalculator.calculate(p, d) }
    Column(
        Modifier.fillMaxWidth().testTag("grade-overview"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Panel("Dein Gesamtschnitt") {
            Text(
                "Ø ${number(result.average)}${if (p.points) " Punkte" else ""}",
                Modifier.testTag("overall-average"),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = Green,
            )
            Text(
                "Aus ${result.averageCount} von ${result.subjectCount} Fächern · jedes Fach zählt einmal.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Panel("Vorrücken") {
            Row(
                Modifier.testTag("promotion-${result.light.name.lowercase()}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier.clearAndSetSemantics {},
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(
                            TrafficLight.RED to Color(0xFFDB4059),
                            TrafficLight.AMBER to Color(0xFFE7AD28),
                            TrafficLight.GREEN to Color(0xFF28B578),
                        )
                        .forEach { (light, color) ->
                            Box(
                                Modifier.size(14.dp)
                                    .background(
                                        if (light == result.light) color
                                        else
                                            MaterialTheme.colorScheme.outlineVariant.copy(
                                                alpha = 0.5f
                                            ),
                                        CircleShape,
                                    )
                            )
                        }
                }
                Text(
                    (when (result.light) {
                        TrafficLight.GREEN -> "Grün"
                        TrafficLight.AMBER -> "Gelb"
                        TrafficLight.RED -> "Rot"
                        else -> "Grau"
                    }) + " · " + result.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(result.reason)
            Text(result.basis, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            val mainCompensation = result.compensation.firstOrNull()
            val primary = mainCompensation ?: result.improvements.firstOrNull()
            Text(
                if (mainCompensation != null) "Notenausgleich" else "Das hilft dir",
                fontWeight = FontWeight.Bold,
            )
            primary?.let { plan ->
                Text(
                    (if (plan.alreadyMet) "Schon passend: " else "Ziel im Zeugnis: ") +
                        plan.goals.joinToString(" + ") { "${it.name} ${it.grade}" },
                    Modifier.testTag("promotion-goal"),
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(result.advice)
            Text(
                "Orientierung – die Entscheidung trifft deine Schule.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        HelpPanel("Weitere Wege & Berechnung") {
            if (result.improvements.isNotEmpty()) {
                Text("Schwache Fächer verbessern", fontWeight = FontWeight.Bold)
                result.improvements.forEach { plan ->
                    Text("• " + plan.goals.joinToString(" + ") { "${it.name} auf ${it.grade}" })
                }
            }
            if (result.compensation.isNotEmpty()) {
                Text("Mögliche Ausgleichsnoten", fontWeight = FontWeight.Bold)
                result.compensation.forEach { plan ->
                    Text(
                        "• " +
                            plan.goals.joinToString(" + ") { "${it.name}: ${it.grade}" } +
                            if (plan.alreadyMet) " – Notenmuster passt bereits"
                            else " – mögliches Ziel"
                    )
                }
            }
            result.notes.forEach { Text(it) }
            if (result.standings.isNotEmpty()) {
                Text("Für die Ampel berücksichtigt", fontWeight = FontWeight.Bold)
                result.standings.forEach { row ->
                    Text(
                        "${row.subject.name}: ${row.grade ?: "fehlt"}" +
                            if (row.grade == null) ""
                            else if (row.annual) " · Jahreszeugnis" else " · gerundeter Fachschnitt"
                    )
                }
            }
            result.sources.forEach { Source(it) }
        }
    }
}
