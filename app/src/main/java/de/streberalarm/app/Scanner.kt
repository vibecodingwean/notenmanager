package de.streberalarm.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.exifinterface.media.ExifInterface
import de.streberalarm.core.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Disabled outside deterministic scanner cancellation tests. */
internal object ScanTiming {
    @Volatile var beforePageWrite: (() -> Unit)? = null
    @Volatile var afterPageWrite: (() -> Unit)? = null
    @Volatile var afterPageWorker: (() -> Unit)? = null
    @Volatile var beforePdfAttach: (() -> Unit)? = null
    @Volatile var afterPdfAttach: (() -> Unit)? = null
    @Volatile var demoPreview: ((Bitmap, Int) -> Unit)? = null
    @Volatile var demoCropBounds: ((List<Float>) -> Unit)? = null
}

object ScanImages {
    fun rotate(source: Bitmap, quarterTurns: Int): Bitmap {
        val normalized = ((quarterTurns % 4) + 4) % 4
        return if (normalized == 0) source else Bitmap.createBitmap(
            source, 0, 0, source.width, source.height,
            Matrix().apply { postRotate(normalized * 90f) }, true)
    }

    fun load(file: File): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0)
        var sample = 1
        while (maxOf(options.outWidth, options.outHeight) / sample > 2200) sample *= 2
        options.inSampleSize = sample
        options.inJustDecodeBounds = false
        val b =
            BitmapFactory.decodeFile(file.path, options) ?: error("Bild kann nicht gelesen werden.")
        val exif = ExifInterface(file)
        val degrees = exif.rotationDegrees
        if (degrees == 0 && !exif.isFlipped) return b
        val matrix =
            Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(degrees.toFloat())
            }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, matrix, true).also {
            if (it !== b) b.recycle()
        }
    }

    fun crop(b: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        require(left >= 0 && top >= 0 && right <= 1 && bottom <= 1 && left < right && top < bottom)
        val x = (left * b.width).toInt()
        val y = (top * b.height).toInt()
        val w = ((right - left) * b.width).toInt().coerceAtLeast(1)
        val h = ((bottom - top) * b.height).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(b, x, y, minOf(w, b.width - x), minOf(h, b.height - y))
    }

    /** The worker must never own the preview bitmap, even for an unchanged full-page crop. */
    fun detachedCrop(source: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val cropped = crop(source, left, top, right, bottom)
        return if (cropped === source) requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false)) else cropped
    }

    fun pdf(pages: List<File>, output: File) {
        require(pages.isNotEmpty() && pages.size <= 30)
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { index, file ->
                val image = load(file)
                try {
                    val page =
                        pdf.startPage(
                            PdfDocument.PageInfo.Builder(image.width, image.height, index + 1)
                                .create()
                        )
                    page.canvas.drawBitmap(image, 0f, 0f, null)
                    pdf.finishPage(page)
                } finally {
                    image.recycle()
                }
            }
            output.outputStream().use {
                pdf.writeTo(it)
                it.fd.sync()
            }
        } finally {
            pdf.close()
        }
    }
}

@Composable
fun ScannerPage(activity: MainActivity, examId: String, done: () -> Unit) {
    val demo by activity.repo.isDemo.collectAsState()
    if (demo && !activity.repo.isGradeDraft(examId)) DemoScannerPage(activity, examId, done)
    else RealScannerPage(activity, examId, done)
}

@Composable
private fun RealScannerPage(activity: MainActivity, examId: String, done: () -> Unit) {
    val generation = remember(examId) { activity.repo.vocabularyGeneration.value }
    val initialDemo = remember(examId) { activity.repo.isDemo.value }
    val scanDir = remember(examId) {
        if (activity.repo.isGradeDraft(examId)) File(activity.cacheDir, "grade-scan-$examId")
        else activity.cacheDir
    }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            granted = it
        }
    var shots by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var current by rememberSaveable { mutableStateOf<String?>(null) }
    var turns by rememberSaveable { mutableIntStateOf(0) }
    var cropLeft by rememberSaveable { mutableFloatStateOf(0f) }
    var cropRight by rememberSaveable { mutableFloatStateOf(1f) }
    var cropTop by rememberSaveable { mutableFloatStateOf(0f) }
    var cropBottom by rememberSaveable { mutableFloatStateOf(1f) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf("") }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    val horizontal = cropLeft..cropRight
    val vertical = cropTop..cropBottom
    var saving by remember { mutableStateOf(false) }
    var cameraScreenActive by remember { mutableStateOf(true) }
    fun active() = cameraScreenActive &&
        activity.repo.isDemo.value == initialDemo &&
        activity.repo.vocabularyGeneration.value == generation
    val providerFuture = remember { ProcessCameraProvider.getInstance(activity) }
    LaunchedEffect(current, turns) {
        bitmap =
            current?.let { path ->
                runCatching { withContext(Dispatchers.IO) {
                    val source = ScanImages.load(File(path))
                    if (turns == 0) source else ScanImages.rotate(source, turns).also { source.recycle() }
                } }
                    .getOrElse {
                        error = "Aufnahme nicht verfügbar."
                        null
                    }
            }
    }
    DisposableEffect(bitmap) {
        val displayed = bitmap
        onDispose { displayed?.recycle() }
    }
    DisposableEffect(Unit) {
        onDispose {
            cameraScreenActive = false
            if (providerFuture.isDone) providerFuture.get().unbindAll()
        }
    }
    Page("Arbeit scannen", "${shots.size} Seiten vorbereitet · vollständig lokal") {
        if (!granted) {
            Text(
                "Für die Aufnahme benötigt NotenManager Zugriff auf die Kamera. Bilder und PDFs kannst du auch ohne Kamerafreigabe importieren."
            )
            Action("Kamera erlauben") { permission.launch(Manifest.permission.CAMERA) }
        } else if (current == null) {
            AndroidView(
                factory = { context ->
                    PreviewView(context).also { view ->
                        providerFuture.addListener(
                            {
                                try {
                                    if (!cameraScreenActive) return@addListener
                                    val provider = providerFuture.get()
                                    val preview =
                                        Preview.Builder().build().also {
                                            it.surfaceProvider = view.surfaceProvider
                                        }
                                    val image =
                                        ImageCapture.Builder()
                                            .setCaptureMode(
                                                ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                                            )
                                            .build()
                                    provider.unbindAll()
                                    provider.bindToLifecycle(
                                        activity,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        image,
                                    )
                                    capture = image
                                } catch (e: Exception) {
                                    error =
                                        "Kamera nicht verfügbar. Du kannst vorhandene Bilder/PDFs importieren."
                                }
                            },
                            ContextCompat.getMainExecutor(context),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().height(330.dp),
            )
            Action(
                "Seite fotografieren",
                {
                    val image = capture
                    if (image != null) {
                        scanDir.mkdirs()
                        val file = File(scanDir, "scan-${id()}.jpg")
                        saving = true
                        image.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(activity),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                    if (!active()) {
                                        file.delete()
                                        return
                                    }
                                    turns = 0
                                    cropLeft = 0f; cropRight = 1f
                                    cropTop = 0f; cropBottom = 1f
                                    current = file.path
                                    saving = false
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    file.delete()
                                    if (!active()) return
                                    error = "Aufnahme fehlgeschlagen."
                                    saving = false
                                }
                            },
                        )
                    }
                },
                capture != null && !saving && shots.size < 30,
            )
        } else {
            bitmap?.let { b ->
                Box(Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)) {
                    Image(
                        b.asImageBitmap(),
                        "Aufgenommene Seite",
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(
                            Color(0xFF66FF99),
                            topLeft =
                                Offset(horizontal.start * size.width, vertical.start * size.height),
                            size =
                                Size(
                                    (horizontal.endInclusive - horizontal.start) * size.width,
                                    (vertical.endInclusive - vertical.start) * size.height,
                                ),
                            style = Stroke(4.dp.toPx()),
                        )
                    }
                }
                Text("Linken und rechten Rand zuschneiden")
                RangeSlider(
                    horizontal,
                    { if (it.endInclusive - it.start >= 0.05f) {
                        cropLeft = it.start; cropRight = it.endInclusive
                    } },
                    modifier = Modifier.testTag("real-crop-horizontal"), enabled = !saving,
                )
                Text("Oberen und unteren Rand zuschneiden")
                RangeSlider(vertical, { if (it.endInclusive - it.start >= 0.05f) {
                    cropTop = it.start; cropBottom = it.endInclusive
                } }, modifier = Modifier.testTag("real-crop-vertical"), enabled = !saving)
                OutlinedButton(
                    {
                        turns = (turns + 1) % 4
                        cropLeft = 0f; cropRight = 1f
                        cropTop = 0f; cropBottom = 1f
                    },
                    Modifier.fillMaxWidth(), enabled = !saving, colors = bayernButtonColors(ButtonDefaults.outlinedButtonColors())) {
                    Text("90° drehen")
                }
                Action(
                    "Seite übernehmen",
                    {
                        saving = true
                        val image =
                            ScanImages.detachedCrop(
                                b,
                                horizontal.start,
                                vertical.start,
                                horizontal.endInclusive,
                                vertical.endInclusive,
                            )
                        // Enter try/finally synchronously so cancellation before IO starts still
                        // releases the detached bitmap. Activity recreation may recycle the preview.
                        activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                withContext(Dispatchers.IO) {
                                    if (!active()) return@withContext
                                    scanDir.mkdirs()
                                    val file = File(scanDir, "scan-page-${id()}.jpg")
                                    try {
                                        ScanTiming.beforePageWrite?.invoke()
                                        file.outputStream().use {
                                            check(image.compress(Bitmap.CompressFormat.JPEG, 90, it))
                                        }
                                        ScanTiming.afterPageWrite?.invoke()
                                        withContext(Dispatchers.Main) {
                                            if (!active()) {
                                                file.delete()
                                                return@withContext
                                            }
                                            shots = ArrayList(shots + file.path)
                                            current?.let { File(it).delete() }
                                            current = null
                                            bitmap = null
                                        }
                                    } catch (e: Exception) {
                                        file.delete()
                                        throw e
                                    } finally { ScanTiming.afterPageWorker?.invoke() }
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { if (active()) error = e.message ?: "Seite konnte nicht gespeichert werden." }
                            finally {
                                image.recycle()
                                saving = false
                            }
                        }
                    },
                    !saving,
                )
            }
            TextButton({
                current?.let { File(it).delete() }
                current = null
                bitmap = null
            }, enabled = !saving, colors = bayernButtonColors(ButtonDefaults.textButtonColors())) {
                Text("Aufnahme verwerfen")
            }
        }
        shots.forEachIndexed { index, path ->
            Row(Modifier.fillMaxWidth()) {
                Text("Seite ${index+1}", Modifier.weight(1f))
                TextButton({
                    shots = ArrayList(shots.filterNot { it == path })
                    File(path).delete()
                }, colors = bayernButtonColors(ButtonDefaults.textButtonColors())) {
                    Text("Entfernen")
                }
                if (index > 0)
                    TextButton({
                        val changed = ArrayList(shots)
                        java.util.Collections.swap(changed, index, index - 1)
                        shots = changed
                    }, colors = bayernButtonColors(ButtonDefaults.textButtonColors())) {
                        Text("Nach oben")
                    }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Action(
            "${shots.size} Seiten als PDF speichern",
            {
                saving = true
                activity.action {
                    try {
                        if (!active()) return@action
                        scanDir.mkdirs()
                        val pdf = File(scanDir, "scan-${id()}.pdf")
                        try {
                            ScanImages.pdf(shots.map { File(it) }, pdf)
                            activity.repo.addLocalPdf(examId, pdf, generation)
                            shots.forEach { File(it).delete() }
                            withContext(Dispatchers.Main) {
                                shots = arrayListOf()
                                done()
                            }
                        } finally {
                            pdf.delete()
                        }
                    } finally {
                        withContext(Dispatchers.Main) { saving = false }
                    }
                }
            },
            shots.isNotEmpty() && current == null && !saving,
        )
    }
}
