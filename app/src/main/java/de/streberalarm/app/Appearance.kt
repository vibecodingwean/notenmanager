package de.streberalarm.app

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.streberalarm.core.Subject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stable device-local IDs. School records and their backup format are independent of appearance.
 */
enum class AppLook(val id: String, val title: String, val hint: String, val dark: Boolean) {
    CLASSIC("classic", "Klassisch", "Dein vertrauter Look", false),
    HACKER("hacker", "Hacker", "Matrix-Code · leuchtender Röhrenbildschirm", true),
    SOCIAL("social", "Social Glow", "Rosé & Pfirsich · schwebende Lichtblasen", false),
    STREAMER("streamer", "Streamer", "Neon & Violett · leuchtende Bahnen", true),
    ORBIT("orbit", "Orbit", "Nachtblau · ruhig ziehende Sterne", true),
    PIXEL("pixel", "Pixelwelt", "Grasblöcke & Wolken · dein Bau-Abenteuer", false);

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

data class Appearance(val look: AppLook = AppLook.CLASSIC, val animations: Boolean = true)

class AppearancePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val mutable =
        MutableStateFlow(
            Appearance(
                AppLook.fromId(prefs.getString("look", null)),
                prefs.getBoolean("animations", true),
            )
        )
    val state = mutable.asStateFlow()

    fun select(look: AppLook) {
        prefs.edit().putString("look", look.id).apply()
        mutable.value = mutable.value.copy(look = look)
    }

    fun animate(enabled: Boolean) {
        prefs.edit().putBoolean("animations", enabled).apply()
        mutable.value = mutable.value.copy(animations = enabled)
    }
}

val LocalLook = staticCompositionLocalOf { AppLook.CLASSIC }

fun lookScheme(look: AppLook): ColorScheme =
    when (look) {
        AppLook.CLASSIC ->
            lightColorScheme(
                primary = Color(0xFF3653BD),
                onPrimary = Color.White,
                background = Color(0xFFF2F4FD),
                onBackground = Color(0xFF202743),
                surface = Color.White,
                onSurface = Color(0xFF202743),
                secondary = Color(0xFFAF2347),
                primaryContainer = Color(0xFFE1E8FF),
                onPrimaryContainer = Color(0xFF172755),
                onSurfaceVariant = Color(0xFF535E79),
                outlineVariant = Color(0xFFD6DCF0),
            )
        AppLook.SOCIAL ->
            lightColorScheme(
                primary = Color(0xFF993066),
                onPrimary = Color.White,
                secondary = Color(0xFF93421E),
                onSecondary = Color.White,
                background = Color(0xFFFFF3F6),
                onBackground = Color(0xFF38243A),
                surface = Color(0xFFFFFBFD),
                onSurface = Color(0xFF38243A),
                surfaceVariant = Color(0xFFF9E1ED),
                onSurfaceVariant = Color(0xFF75516A),
                primaryContainer = Color(0xFFFFDCEB),
                onPrimaryContainer = Color(0xFF621541),
                secondaryContainer = Color(0xFFFFE4D6),
                onSecondaryContainer = Color(0xFF672B16),
                outline = Color(0xFF99748C),
                outlineVariant = Color(0xFFDEC0D1),
            )
        AppLook.PIXEL ->
            lightColorScheme(
                primary = Color(0xFF285B31),
                onPrimary = Color.White,
                secondary = Color(0xFF75522E),
                onSecondary = Color.White,
                background = Color(0xFFDFF2FF),
                onBackground = Color(0xFF253124),
                surface = Color(0xFFFCF7E9),
                onSurface = Color(0xFF253124),
                surfaceVariant = Color(0xFFE4EACD),
                onSurfaceVariant = Color(0xFF4D5F4B),
                primaryContainer = Color(0xFFD3E9B8),
                onPrimaryContainer = Color(0xFF1D4219),
                secondaryContainer = Color(0xFFE4CEAC),
                onSecondaryContainer = Color(0xFF4A331B),
                outline = Color(0xFF657B53),
                outlineVariant = Color(0xFFB4C79F),
            )
        AppLook.HACKER ->
            darkColorScheme(
                primary = Color(0xFF39FF14),
                onPrimary = Color(0xFF092005),
                secondary = Color(0xFFB1FF83),
                onSecondary = Color(0xFF092005),
                background = Color(0xFF050505),
                onBackground = Color(0xFFB8FFA3),
                surface = Color(0xFF090E0A),
                onSurface = Color(0xFFB8FFA3),
                surfaceVariant = Color(0xFF18301A),
                onSurfaceVariant = Color(0xFFB3D9A7),
                primaryContainer = Color(0xFF183A10),
                onPrimaryContainer = Color(0xFFB8FFA3),
                secondaryContainer = Color(0xFF203619),
                onSecondaryContainer = Color(0xFFC8FFAF),
                tertiaryContainer = Color(0xFF243D1C),
                onTertiaryContainer = Color(0xFFD3FFC0),
                outline = Color(0xFF79A36B),
                outlineVariant = Color(0xFF355D2B),
            )
        else -> {
            val primary =
                when (look) {
                    AppLook.STREAMER -> Color(0xFFDFB6FF)
                    else -> Color(0xFFA1D9FF)
                }
            val surface =
                when (look) {
                    AppLook.STREAMER -> Color(0xFF231832)
                    else -> Color(0xFF13263E)
                }
            val background =
                when (look) {
                    AppLook.STREAMER -> Color(0xFF100A20)
                    else -> Color(0xFF080F24)
                }
            darkColorScheme(
                primary = primary,
                onPrimary = Color(0xFF10201F),
                secondary = if (look == AppLook.STREAMER) Color(0xFF79E7FA) else Color(0xFFFFBED3),
                onSecondary = Color(0xFF302030),
                background = background,
                onBackground = Color(0xFFEDF5FF),
                surface = surface,
                onSurface = Color(0xFFEDF5FF),
                surfaceVariant = Color(0xFF293647),
                onSurfaceVariant = Color(0xFFBCCAD9),
                primaryContainer =
                    when (look) {
                        AppLook.STREAMER -> Color(0xFF34254B)
                        else -> Color(0xFF203753)
                    },
                onPrimaryContainer = primary,
                secondaryContainer = Color(0xFF333050),
                onSecondaryContainer = Color(0xFFF0DFFF),
                tertiaryContainer = Color(0xFF193D4B),
                onTertiaryContainer = Color(0xFFCEEEFF),
                outline = Color(0xFF91A1B5),
                outlineVariant = Color(0xFF516174),
            )
        }
    }

fun categoryTint(subject: Subject?, look: AppLook): Color =
    if (!look.dark) subjectTint(subject)
    else
        when {
            subject?.core == true -> Color(0xFF342749)
            subject?.promotion == true -> Color(0xFF16394D)
            else -> Color(0xFF303943)
        }

@Composable fun themedSubjectTint(subject: Subject?) = categoryTint(subject, LocalLook.current)

val BreakTint: Color
    @Composable get() = if (LocalLook.current.dark) Color(0xFF493C20) else Color(0xFFFFE6A0)
val Ink: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
val Green: Color
    @Composable get() = MaterialTheme.colorScheme.primary

@Composable
fun lookShape(radius: Dp): CornerBasedShape =
    when (LocalLook.current) {
        AppLook.HACKER,
        AppLook.PIXEL -> RoundedCornerShape(0.dp)
        AppLook.STREAMER -> CutCornerShape(topStart = radius / 2, bottomEnd = radius / 2)
        else -> RoundedCornerShape(radius)
    }

@Composable
fun lookBorder(): BorderStroke? =
    if (LocalLook.current == AppLook.CLASSIC) null
    else
        BorderStroke(
            if (LocalLook.current == AppLook.PIXEL) 2.dp else 1.dp,
            MaterialTheme.colorScheme.primary.copy(
                alpha = if (LocalLook.current == AppLook.HACKER) 0.65f else 0.38f
            ),
        )

@Composable
fun AppearanceTheme(activity: MainActivity, content: @Composable () -> Unit) {
    val appearance by activity.appearance.state.collectAsState()
    val scheme = remember(appearance.look) { lookScheme(appearance.look) }
    SideEffect {
        val bars =
            if (appearance.look.dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                )
        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        activity.window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(scheme.surface.toArgb())
        )
    }
    val heading =
        if (appearance.look in listOf(AppLook.HACKER, AppLook.PIXEL)) FontFamily.Monospace
        else FontFamily.Default
    CompositionLocalProvider(LocalLook provides appearance.look) {
        MaterialTheme(
            colorScheme = scheme,
            shapes =
                Shapes(
                    small = lookShape(12.dp),
                    medium = lookShape(20.dp),
                    large = lookShape(26.dp),
                ),
            typography =
                Typography(
                    headlineMedium = Typography().headlineMedium.copy(fontFamily = heading),
                    headlineSmall = Typography().headlineSmall.copy(fontFamily = heading),
                    bodyLarge =
                        androidx.compose.ui.text.TextStyle(
                            fontFamily =
                                if (appearance.look == AppLook.HACKER) heading
                                else FontFamily.Default,
                            fontSize = 18.sp,
                            lineHeight = 26.sp,
                        ),
                    bodyMedium =
                        androidx.compose.ui.text.TextStyle(
                            fontFamily =
                                if (appearance.look == AppLook.HACKER) heading
                                else FontFamily.Default,
                            fontSize = 16.sp,
                            lineHeight = 23.sp,
                        ),
                    titleMedium =
                        androidx.compose.ui.text.TextStyle(
                            fontFamily = heading,
                            fontSize = 18.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    labelLarge =
                        androidx.compose.ui.text.TextStyle(
                            fontFamily = heading,
                            fontSize = 15.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                ),
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(scheme.background)
                    .testTag("appearance-${appearance.look.id}")
            ) {
                LookBackdrop(appearance, Modifier.matchParentSize())
                content()
                if (appearance.look == AppLook.HACKER) CrtGlass(Modifier.matchParentSize())
            }
        }
    }
}

/**
 * Only decoration animates. No position, hit target, learning duration or saved data depends on it.
 */
@Composable
private fun LookBackdrop(appearance: Appearance, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun systemAllowsMotion() =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    var resumed by remember {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var systemAnimations by remember { mutableStateOf(systemAllowsMotion()) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            systemAnimations = systemAllowsMotion()
        }
        val settingsObserver =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    systemAnimations = systemAllowsMotion()
                }
            }
        lifecycle.addObserver(observer)
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            settingsObserver,
        )
        onDispose {
            lifecycle.removeObserver(observer)
            context.contentResolver.unregisterContentObserver(settingsObserver)
        }
    }
    val moving =
        appearance.animations && resumed && systemAnimations && appearance.look != AppLook.CLASSIC
    val phase =
        if (moving) {
            rememberInfiniteTransition(label = "Look background")
                .animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing)),
                    label = "Slow drift",
                )
        } else remember { mutableFloatStateOf(0f) }
    val accent = MaterialTheme.colorScheme.primary
    val matrix = remember { MatrixRain() }
    Canvas(
        modifier
            .graphicsLayer()
            .testTag(if (moving) "look-moving" else "look-still")
            .clearAndSetSemantics {}
    ) {
        drawLook(appearance.look, phase.value, accent, matrix)
    }
}

/** Decorative phosphor characters only; never consumes touch or changes layout. */
private class MatrixRain {
    private var dimensions = Triple(0, 0, 0f)
    private var strips = emptyList<android.graphics.Bitmap>()
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

    fun draw(scope: DrawScope, phase: Float) =
        with(scope) {
            val column = 15.dp.toPx()
            val line = 16.dp.toPx()
            val width = kotlin.math.ceil(column).toInt()
            val rows = (size.height / line).toInt() + 25
            val height = kotlin.math.ceil(rows * line).toInt()
            val count = (size.width / column).toInt() + 1
            val key = Triple(count, height, density)
            if (dimensions != key) {
                // Rasterize glyphs once per size. Frames translate textures instead of shaping
                // thousands of characters and rebuilding phosphor shadows on the UI thread.
                val glyphs = "01アイウエオカキクケコサシスセソタチツテトナニヌネノ<>:/{}[]"
                val ink =
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        typeface = android.graphics.Typeface.MONOSPACE
                        textSize = 12.dp.toPx()
                    }
                strips =
                    List(count) { col ->
                        android.graphics.Bitmap.createBitmap(
                                width,
                                height,
                                android.graphics.Bitmap.Config.ARGB_8888,
                            )
                            .also { bitmap ->
                                val canvas = android.graphics.Canvas(bitmap)
                                val length = 12 + col % 13
                                repeat(rows) { row ->
                                    val distance = rows - 1 - row
                                    val tail = (1f - distance.toFloat() / length).coerceIn(0f, 1f)
                                    ink.color =
                                        if (distance == 0) android.graphics.Color.rgb(201, 255, 185)
                                        else android.graphics.Color.rgb(57, 255, 20)
                                    ink.alpha =
                                        if (distance == 0) 105 else (10 + 65 * tail * tail).toInt()
                                    if (distance == 0)
                                        ink.setShadowLayer(
                                            3.dp.toPx(),
                                            0f,
                                            0f,
                                            android.graphics.Color.argb(90, 57, 255, 20),
                                        )
                                    else ink.clearShadowLayer()
                                    canvas.drawText(
                                        glyphs[(col * 31 + row * 17) % glyphs.length].toString(),
                                        1.dp.toPx(),
                                        (row + 1) * line - 2.dp.toPx(),
                                        ink,
                                    )
                                }
                            }
                    }
                dimensions = key
            }
            val canvas = drawContext.canvas.nativeCanvas
            strips.forEachIndexed { col, bitmap ->
                // Integral laps close the loop seamlessly; subpixel offsets remove row stepping.
                val y = ((phase * (2 + col % 3) + col * 0.173f) % 1f) * height
                canvas.drawBitmap(bitmap, col * column, y - height, paint)
                canvas.drawBitmap(bitmap, col * column, y, paint)
            }
        }
}

@Composable
fun monitorShape(radius: Dp): CornerBasedShape =
    if (LocalLook.current == AppLook.HACKER) RoundedCornerShape(18.dp) else lookShape(radius)

/** Local glass, inset phosphor rim and a dark cabinet; drawing never moves controls. */
@Composable
fun Modifier.monitor(): Modifier {
    if (LocalLook.current != AppLook.HACKER) return this
    return this.drawWithCache {
        val inset = 5.dp.toPx()
        val radius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())
        val lines =
            Path().apply {
                var y = inset
                while (y < size.height - inset) {
                    moveTo(inset, y)
                    lineTo(size.width - inset, y)
                    y += 3.dp.toPx()
                }
            }
        val glass =
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = .035f),
                    Color.Transparent,
                    Color.Black.copy(alpha = .16f),
                )
            )
        val glow = Color(0xFF39FF14)
        onDrawWithContent {
            drawContent()
            drawRoundRect(glass, cornerRadius = radius)
            drawPath(lines, Color.Black.copy(alpha = .08f), style = Stroke(.6.dp.toPx()))
            drawRoundRect(Color(0xFF050505), cornerRadius = radius, style = Stroke(7.dp.toPx()))
            drawRoundRect(
                glow.copy(alpha = .08f),
                Offset(inset, inset),
                Size(size.width - 2 * inset, size.height - 2 * inset),
                radius,
                style = Stroke(5.dp.toPx()),
            )
            drawRoundRect(
                glow.copy(alpha = .55f),
                Offset(inset, inset),
                Size(size.width - 2 * inset, size.height - 2 * inset),
                radius,
                style = Stroke(.8.dp.toPx()),
            )
        }
    }
}

@Composable
private fun CrtGlass(modifier: Modifier) {
    Canvas(modifier.graphicsLayer().testTag("crt-overlay").clearAndSetSemantics {}) {
        val pitch = 3.dp.toPx()
        for (row in 0..(size.height / pitch).toInt()) {
            drawLine(
                Color.Black.copy(alpha = 0.08f),
                Offset(0f, row * pitch),
                Offset(size.width, row * pitch),
                0.7.dp.toPx(),
            )
            drawLine(
                Color(0xFF80FF60).copy(alpha = 0.015f),
                Offset(0f, row * pitch + 1.dp.toPx()),
                Offset(size.width, row * pitch + 1.dp.toPx()),
                0.5.dp.toPx(),
            )
        }
        // Soft glass edges, with a clear center for text. No flashing or moving controls.
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = 0.20f),
                0.06f to Color.Transparent,
                0.94f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.20f),
            )
        )
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.15f),
                0.08f to Color.Transparent,
                0.92f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.15f),
            )
        )
        drawRoundRect(
            Color(0xFF7FFF61).copy(alpha = 0.07f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(28.dp.toPx()),
            style = Stroke(2.dp.toPx()),
        )
    }
}

private fun DrawScope.drawLook(look: AppLook, phase: Float, accent: Color, matrix: MatrixRain) {
    val w = size.width
    val h = size.height
    when (look) {
        AppLook.CLASSIC -> Unit
        AppLook.HACKER -> matrix.draw(this, phase)
        AppLook.PIXEL -> {
            val block = 24.dp.toPx()
            repeat(5) { i ->
                val x = (((i * 0.32f + phase * 1.6f) % 1.6f) - 0.3f) * w
                val y = (0.07f + i * 0.17f) * h
                drawRect(Color.White.copy(alpha = 0.75f), Offset(x, y), Size(block * 4, block))
                drawRect(
                    Color.White.copy(alpha = 0.75f),
                    Offset(x + block, y - block),
                    Size(block * 2, block),
                )
            }
            for (i in 0..(w / block).toInt()) {
                val top = h - minOf(100.dp.toPx(), h * 0.2f) - block * (2 + (i / 3) % 3)
                drawRect(
                    Color(0xFF5D963E).copy(alpha = 0.4f),
                    Offset(i * block, top),
                    Size(block, block / 3),
                )
                drawRect(
                    Color(0xFF876240).copy(alpha = 0.25f),
                    Offset(i * block, top + block / 3),
                    Size(block, h - top),
                )
                drawRect(
                    Color(0xFFB98F61).copy(alpha = 0.4f),
                    Offset(i * block + block / 4, top + block),
                    Size(block / 4, block / 4),
                )
            }
        }
        AppLook.SOCIAL -> {
            repeat(6) { i ->
                val angle = (phase * 2 * PI + i).toFloat()
                val x = w * ((i % 3) / 2f) + sin(angle) * w * 0.09f
                val y = h * (i / 6f) + cos(angle) * 32.dp.toPx()
                val color = if (i % 2 == 0) Color(0xFFF66BA4) else Color(0xFFFFAA70)
                drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = 0.16f), Color.Transparent),
                        Offset(x, y),
                        w * 0.65f,
                    ),
                    w * 0.65f,
                    Offset(x, y),
                )
                drawCircle(
                    color.copy(alpha = 0.12f),
                    24.dp.toPx(),
                    Offset(x, y),
                    style = Stroke(1.dp.toPx()),
                )
            }
        }
        AppLook.STREAMER -> {
            repeat(7) { i ->
                val y = ((i / 7f + phase) % 1f) * (h + w) - w
                val color = if (i % 2 == 0) Color(0xFFB965FF) else Color(0xFF4FE4F1)
                drawLine(
                    color.copy(alpha = 0.05f),
                    Offset(0f, y),
                    Offset(w, y + w * 0.6f),
                    16.dp.toPx(),
                )
                drawLine(
                    color.copy(alpha = 0.18f),
                    Offset(0f, y),
                    Offset(w, y + w * 0.6f),
                    1.dp.toPx(),
                )
            }
        }
        AppLook.ORBIT -> {
            val center = Offset(w * 0.85f, h * 0.25f)
            drawCircle(
                Brush.radialGradient(
                    listOf(Color(0xFF4B57AE).copy(alpha = 0.24f), Color.Transparent),
                    center,
                    w,
                ),
                w,
                center,
            )
            repeat(64) { i ->
                val x = (((i * 73 + 19) % 137) / 137f + sin(phase * 2 * PI).toFloat() * 0.015f) * w
                val y =
                    (((i * i * 31 + 17) % 149) / 149f + cos(phase * 2 * PI).toFloat() * 0.015f) * h
                drawCircle(
                    accent.copy(alpha = if (i % 5 == 0) 0.45f else 0.2f),
                    if (i % 5 == 0) 1.6.dp.toPx() else 0.8.dp.toPx(),
                    Offset(x, y),
                )
            }
        }
    }
}

@Composable
fun AppearanceSettings(preferences: AppearancePreferences) {
    val appearance by preferences.state.collectAsState()
    Panel("Dein Look") {
        Text("Such dir deinen Style aus.")
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppLook.entries.forEach { look ->
                val scheme = lookScheme(look)
                val matrix = remember { MatrixRain() }
                val selected = appearance.look == look
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(scheme.surface)
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(16.dp),
                        )
                        .testTag("theme-${look.id}")
                        .selectable(
                            selected,
                            role = Role.RadioButton,
                            onClick = { preferences.select(look) },
                        )
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Canvas(
                        Modifier.size(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(scheme.background)
                            .clearAndSetSemantics {}
                    ) {
                        drawLook(look, 0.3f, scheme.primary, matrix)
                        drawCircle(scheme.primary, 8.dp.toPx(), center)
                        drawCircle(
                            scheme.primary.copy(alpha = 0.3f),
                            16.dp.toPx(),
                            center,
                            style = Stroke(2.dp.toPx()),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(look.title, color = scheme.onSurface, fontWeight = FontWeight.Bold)
                        Text(
                            look.hint,
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    RadioButton(
                        selected,
                        onClick = null,
                        colors =
                            RadioButtonDefaults.colors(
                                selectedColor = scheme.primary,
                                unselectedColor = scheme.onSurfaceVariant,
                            ),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Hintergrund animieren", Modifier.weight(1f))
            Switch(
                appearance.animations,
                preferences::animate,
                Modifier.testTag("theme-animations"),
            )
        }
        Text(
            "Alle Knöpfe bleiben an ihrem Platz. Ohne Animation bleibt dein Look erhalten. Die Android-Einstellung zum Ausschalten von Animationen gilt ebenfalls.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
