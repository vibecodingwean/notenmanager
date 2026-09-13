package de.streberalarm.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/** An elapsed-time dial: one minute-hand lap is one hour, one second-hand lap one minute. */
@Composable
fun StudyClock(seconds: Long, running: Boolean, enabled: Boolean = true, toggle: () -> Unit) {
    val elapsed = seconds.coerceAtLeast(0)
    val action = if (running) "Lernen beenden" else "Lernen starten"
    val accent = if (running) MaterialTheme.colorScheme.secondary else Green
    val ink = Ink
    val tickColor = MaterialTheme.colorScheme.outline
    val face = MaterialTheme.colorScheme.surface
    val decorativeRim = LocalLook.current != AppLook.CLASSIC
    val rimColor = MaterialTheme.colorScheme.secondary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = toggle,
            enabled = enabled,
            shape = CircleShape,
            color = face,
            shadowElevation = 5.dp,
            modifier =
                Modifier.size(216.dp).testTag("study-clock").semantics {
                    role = Role.Button
                    contentDescription = action
                    stateDescription =
                        "${elapsed / 60} Minuten, ${elapsed % 60} Sekunden. ${if (running) "Läuft" else "Gestoppt"}"
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                    val radius = size.minDimension / 2
                    fun point(angle: Double, length: Float): Offset {
                        val radians = Math.toRadians(angle - 90)
                        return center +
                            Offset(cos(radians).toFloat() * length, sin(radians).toFloat() * length)
                    }
                    drawCircle(
                        accent.copy(alpha = 0.14f),
                        radius - 2.dp.toPx(),
                        style = Stroke(4.dp.toPx()),
                    )
                    if (decorativeRim)
                        drawCircle(
                            Brush.sweepGradient(listOf(accent, rimColor, accent)),
                            radius - 2.dp.toPx(),
                            style = Stroke(3.dp.toPx()),
                        )
                    repeat(60) { tick ->
                        val major = tick % 5 == 0
                        drawLine(
                            if (major) ink else tickColor,
                            point(tick * 6.0, radius * if (major) 0.78f else 0.84f),
                            point(tick * 6.0, radius * 0.9f),
                            if (major) 2.dp.toPx() else 1.dp.toPx(),
                            StrokeCap.Round,
                        )
                    }
                    // Only elapsed learning time drives these hands, never the wall-clock time.
                    drawLine(
                        ink,
                        center,
                        point((elapsed % 3600) / 10.0, radius * 0.52f),
                        5.dp.toPx(),
                        StrokeCap.Round,
                    )
                    drawLine(
                        accent,
                        center,
                        point((elapsed % 60) * 6.0, radius * 0.73f),
                        2.dp.toPx(),
                        StrokeCap.Round,
                    )
                    drawCircle(accent, 5.dp.toPx())
                }
                Text(
                    String.format(
                        Locale.GERMANY,
                        "%02d:%02d:%02d",
                        elapsed / 3600,
                        elapsed / 60 % 60,
                        elapsed % 60,
                    ),
                    modifier =
                        Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = 39.dp)
                            .clearAndSetSemantics {},
                    color = Ink,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            action,
            fontWeight = FontWeight.Bold,
            color = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (!enabled) "Die Uhr ist für diesen Test nicht verfügbar."
            else if (running) "Tippe auf die Uhr, wenn du fertig bist."
            else "Tippe auf die Uhr und leg los.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
