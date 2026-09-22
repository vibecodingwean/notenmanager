package de.streberalarm.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

internal fun youthScheme(look: AppLook): ColorScheme {
    val speed = look == AppLook.SPEED
    val bubble = look == AppLook.BUBBLEGUM
    val ink = if (speed) Color.White else if (bubble) Color(0xFF321044) else Color(0xFF171511)
    val muted = if (speed) Color(0xFFD7DCE0) else if (bubble) Color(0xFF50265D) else Color(0xFF40382E)
    val bg = if (speed) Color(0xFF171A1E) else if (bubble) Color(0xFFFFEFF7) else Color(0xFFFFF7E8)
    val surface = if (speed) Color(0xFF22262B) else if (bubble) Color(0xFFFFFAFD) else Color(0xFFFFFCF5)
    val variant = if (speed) Color(0xFF30363B) else if (bubble) Color(0xFFF8DCEE) else Color(0xFFF0E4D1)
    val primary = if (speed) Color(0xFFC6FF37) else if (bubble) Color(0xFF84245F) else Color(0xFF873000)
    val container = if (speed) Color(0xFF303B20) else if (bubble) Color(0xFFFFD8ED) else Color(0xFFFFDEC3)
    val secondary = if (speed) Color.White else if (bubble) Color(0xFF255568) else Color(0xFF171511)
    val secondaryContainer = if (speed) Color(0xFF30363B) else if (bubble) Color(0xFFD6F5FF) else Color(0xFFEAE2D6)
    val base = if (speed) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = if (speed) Color(0xFF171A1E) else Color.White,
        primaryContainer = container, onPrimaryContainer = ink,
        secondary = secondary, onSecondary = if (speed) Color.Black else Color.White,
        secondaryContainer = secondaryContainer, onSecondaryContainer = ink,
        tertiary = secondary, onTertiary = if (speed) Color.Black else Color.White,
        tertiaryContainer = secondaryContainer, onTertiaryContainer = ink,
        background = bg, onBackground = ink, surface = surface, onSurface = ink,
        surfaceVariant = variant, onSurfaceVariant = muted,
        surfaceTint = Color.Transparent, outline = muted, outlineVariant = muted,
        surfaceDim = surface, surfaceBright = surface, surfaceContainerLowest = surface,
        surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = surface,
        error = if (speed) Color(0xFFFFBCB5) else Color(0xFF8C1420),
        onError = if (speed) Color(0xFF400008) else Color.White,
        errorContainer = if (speed) Color(0xFF650017) else Color(0xFFFFDAD6),
        onErrorContainer = if (speed) Color.White else Color(0xFF400008),
    )
}

/** Static eligibility is independent of user, lifecycle and system animation switches. */
internal fun themeAnimationEligible(look: AppLook) = !look.isYouth && look != AppLook.CLASSIC

private fun youthAccent(look: AppLook) = when (look) {
    AppLook.STREET_ART -> Color(0xFFFF6A00)
    AppLook.BUBBLEGUM -> Color(0xFFFF8BC6)
    else -> Color(0xFFC6FF37)
}

/** Background motifs stay in the outer 12dp gutter, outside page text. */
internal fun DrawScope.drawYouthBackdrop(look: AppLook) {
    val accent = youthAccent(look)
    val gutter = minOf(10.dp.toPx(), size.width * .16f)
    when (look) {
        AppLook.STREET_ART -> repeat(24) { i ->
            val y = (i + .5f) * size.height / 24
            drawCircle(accent, gutter * (.18f + (i % 3) * .1f), Offset(gutter / 2, y))
            drawLine(Color(0xFF171511), Offset(size.width - gutter, y), Offset(size.width, y - gutter), 2.dp.toPx())
        }
        AppLook.BUBBLEGUM -> repeat(14) { i ->
            val y = (i + .5f) * size.height / 14
            drawCircle(if (i % 2 == 0) accent else Color(0xFF91DBF3), gutter * .6f,
                Offset(if (i % 2 == 0) 0f else size.width, y), style = Stroke(3.dp.toPx()))
        }
        AppLook.SPEED -> repeat(24) { i ->
            val y = i * size.height / 24
            drawLine(accent, Offset(0f, y + gutter), Offset(gutter, y), 3.dp.toPx())
            drawLine(Color.White, Offset(size.width - gutter, y + gutter), Offset(size.width, y), 2.dp.toPx())
        }
        else -> Unit
    }
}

/** A dedicated decoration row reserves space: no drawing is placed over text or controls. */
@Composable
internal fun YouthRibbon(look: AppLook) {
    Canvas(Modifier.fillMaxWidth().height(24.dp).clearAndSetSemantics {}) {
        val a = youthAccent(look)
        when (look) {
            AppLook.STREET_ART -> {
                val paint = Path().apply {
                    moveTo(8.dp.toPx(), 6.dp.toPx()); lineTo(size.width * .68f, 3.dp.toPx())
                    lineTo(size.width * .61f, 12.dp.toPx()); lineTo(12.dp.toPx(), 16.dp.toPx()); close()
                }
                drawPath(paint, a)
                repeat(5) { i ->
                    val x = (20 + i * 13).dp.toPx()
                    drawLine(a, Offset(x, 10.dp.toPx()), Offset(x, (17 + i % 3 * 2).dp.toPx()), 3.dp.toPx())
                }
                drawLine(Color.Black, Offset(size.width * .74f, 15.dp.toPx()), Offset(size.width * .9f, 5.dp.toPx()), 3.dp.toPx())
            }
            AppLook.BUBBLEGUM -> {
                // Overlapping outlined sticker bubbles and a tiny sparkle, all above the content.
                listOf(a, Color(0xFFB4EDFF), Color(0xFFFFD8ED)).forEachIndexed { i, color ->
                    val center = Offset((24 + i * 19).dp.toPx(), 12.dp.toPx())
                    drawCircle(Color(0xFF321044), 10.dp.toPx(), center)
                    drawCircle(Color.White, 8.5.dp.toPx(), center)
                    drawCircle(color, 6.5.dp.toPx(), center)
                }
                val x = size.width - 30.dp.toPx()
                drawLine(Color(0xFF321044), Offset(x - 5.dp.toPx(), 12.dp.toPx()), Offset(x + 5.dp.toPx(), 12.dp.toPx()), 2.dp.toPx())
                drawLine(Color(0xFF321044), Offset(x, 7.dp.toPx()), Offset(x, 17.dp.toPx()), 2.dp.toPx())
            }
            AppLook.SPEED -> repeat(7) { i ->
                val x = size.width - (20 + i * 17).dp.toPx()
                drawLine(if (i < 2) Color.White else a, Offset(x, 18.dp.toPx()), Offset(x + 12.dp.toPx(), 4.dp.toPx()), 5.dp.toPx())
            }
            else -> Unit
        }
    }
}

@Composable
internal fun YouthPageHeading(title: String, subtitle: String?) {
    val look = LocalLook.current
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(c.surface, lookShape(18.dp))
        .border(2.dp, c.onSurface, lookShape(18.dp))) {
        YouthRibbon(look)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = c.onSurface, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black, fontStyle = if (look == AppLook.SPEED) FontStyle.Italic else FontStyle.Normal)
            subtitle?.let { Text(it, color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
