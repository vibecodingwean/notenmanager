package de.streberalarm.app

import androidx.compose.foundation.BorderStroke
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
        OverallAveragePanel(p, result)
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
            val affected = result.standings.filter { row -> (row.grade ?: 0) >= 5 }.map { it.subject.name }
            if (affected.isNotEmpty()) Text("Im Blick behalten: ${affected.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
            Text(
                if (result.standings.any { it.grade != null && !it.annual }) "Einschätzung aus bisherigen Noten"
                else if (result.standings.any { it.annual }) "Aus eingetragenen offiziellen Ergebnissen"
                else "Noch keine sichere Einschätzung möglich",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Die Entscheidung trifft deine Schule.", style = MaterialTheme.typography.bodySmall)
        }

    }
}

@Composable
fun OverallAveragePanel(p: Profile, result: GradeOverview) {
    val bayern = LocalLook.current == AppLook.BAYERN
    if (!bayern) {
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
        return
    }
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = lookShape(20.dp),
        border = if (bayern) BorderStroke(1.dp, scheme.onPrimaryContainer)
            else BorderStroke(1.dp, scheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = if (bayern) scheme.primaryContainer else scheme.surface,
            contentColor = if (bayern) scheme.onPrimaryContainer else scheme.onSurface,
        ),
    ) {
      Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Dein Gesamtschnitt", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Text(
            "Ø ${number(result.average)}${if (p.points) " Punkte" else ""}",
            Modifier.testTag("overall-average"),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = if (bayern) scheme.onPrimaryContainer else Green,
        )
        Text(
            "Aus ${result.averageCount} von ${result.subjectCount} Fächern · jedes Fach zählt einmal.",
            style = MaterialTheme.typography.bodySmall,
        )
      }
    }
}
