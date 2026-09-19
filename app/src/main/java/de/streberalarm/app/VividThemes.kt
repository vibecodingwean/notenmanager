package de.streberalarm.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** Wide liquid ribbons with narrow chrome highlights, drawn locally at any resolution. */
internal fun DrawScope.drawLiquid(phase: Float) {
    val w = size.width
    val h = size.height
    drawRect(Color(0xFF090720))
    val drift = sin(phase * 2 * PI).toFloat()
    for (i in -2..7) {
        val y = h * (i / 6f)
        val bend = sin(i * 1.9f + phase * 2 * PI).toFloat()
        val path =
            Path().apply {
                moveTo(-w * .2f, y)
                cubicTo(
                    w * .15f,
                    y - h * (.45f + bend * .08f),
                    w * .42f,
                    y + h * (.65f + drift * .06f),
                    w * .65f,
                    y + h * .14f,
                )
                cubicTo(w * .9f, y - h * .42f, w * .96f, y + h * .05f, w * 1.2f, y - h * .16f)
            }
        val palette =
            if (i % 2 == 0)
                listOf(
                    Color(0xFF17104E),
                    Color(0xFF2455FF),
                    Color(0xFF04E7FF),
                    Color(0xFFCDFFFF),
                    Color(0xFFB916ED),
                    Color(0xFFFF28BD),
                    Color(0xFFFFEA57),
                )
            else
                listOf(
                    Color(0xFFFFD529),
                    Color(0xFFFF4098),
                    Color(0xFFC81CE8),
                    Color(0xFF362AFF),
                    Color(0xFF0CF0EB),
                    Color(0xFF73FF68),
                    Color(0xFF17104E),
                )
        drawPath(path, Color(0xFF02030E), style = Stroke(h * .22f))
        drawPath(
            path,
            Brush.linearGradient(palette, Offset(0f, y - h * .18f), Offset(w, y + h * .2f)),
            style = Stroke(h * .18f),
        )
        drawPath(
            path,
            Brush.linearGradient(
                listOf(
                    Color.White.copy(alpha = .75f),
                    Color(0xFF81EEFF),
                    Color.Transparent,
                    Color(0xFFFFD8F6),
                ),
                Offset.Zero,
                Offset(w, h),
            ),
            style = Stroke(h * .013f),
        )
    }
}

/** Faceted, glowing stadium cushions and angled light bars; no photographic background. */
internal fun DrawScope.drawArena(phase: Float) {
    val w = size.width
    val h = size.height
    drawRect(Brush.linearGradient(listOf(Color(0xFF030305), Color(0xFF4D0010), Color(0xFF100307))))
    val cellW = w / 4f
    val cellH = h / 4f
    for (row in -1..5) for (col in -1..5) {
        val x = col * cellW + if (row % 2 == 0) 0f else cellW / 2
        val y = row * cellH
        val path =
            Path().apply {
                moveTo(x, y - cellH / 2)
                quadraticBezierTo(x + cellW * .25f, y - cellH * .18f, x + cellW / 2, y)
                quadraticBezierTo(x + cellW * .25f, y + cellH * .18f, x, y + cellH / 2)
                quadraticBezierTo(x - cellW * .25f, y + cellH * .18f, x - cellW / 2, y)
                quadraticBezierTo(x - cellW * .25f, y - cellH * .18f, x, y - cellH / 2)
                close()
            }
        val glow = .75f + .15f * sin(phase * 2 * PI + col * .6 + row * .3).toFloat()
        drawPath(
            path,
            Brush.radialGradient(
                listOf(Color(0xFFFF1745).copy(alpha = glow), Color(0xFFBA0025), Color(0xFF42000D)),
                Offset(x, y),
                cellW * .62f,
            ),
        )
        drawPath(path, Color(0xFF150007), style = Stroke(2.dp.toPx()))
        drawPath(path, Color(0xFFFF4963).copy(alpha = .4f), style = Stroke(.5.dp.toPx()))
    }
    drawRect(
        Brush.horizontalGradient(
            listOf(Color.Black.copy(alpha = .08f), Color.Black.copy(alpha = .6f))
        )
    )
    drawLine(Color(0xFFFF123E), Offset(w * .60f, 0f), Offset(w, h * .46f), 3.dp.toPx())
    drawLine(Color(0xFFFF8699), Offset(w * .60f, 0f), Offset(w, h * .46f), .7.dp.toPx())
}

@Composable
internal fun VividPageHeading(title: String, subtitle: String?) {
    val look = LocalLook.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).testTag("vivid-heading")) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
            if (look == AppLook.BAYERN) drawArena(.2f) else drawLiquid(.2f)
        }
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(color = Color(0xFF100B19), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        if (look == AppLook.BAYERN) "FC BAYERN" else "FARBFLUSS",
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                if (look == AppLook.BAYERN)
                    Image(
                        painterResource(R.drawable.fc_bayern),
                        "FC Bayern München",
                        Modifier.size(48.dp),
                    )
                else Spacer(Modifier.height(48.dp))
            }
            Surface(color = Color(0xFF100B19), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    if (subtitle != null)
                        Text(
                            subtitle,
                            color = Color(0xFFE1CEDD),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                }
            }
        }
    }
}
