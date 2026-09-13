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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import de.streberalarm.core.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ScanImages {
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
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf("") }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var horizontal by remember { mutableStateOf(0f..1f) }
    var vertical by remember { mutableStateOf(0f..1f) }
    var saving by remember { mutableStateOf(false) }
    var cameraScreenActive by remember { mutableStateOf(true) }
    val providerFuture = remember { ProcessCameraProvider.getInstance(activity) }
    LaunchedEffect(current) {
        horizontal = 0f..1f
        vertical = 0f..1f
        bitmap =
            current?.let { path ->
                runCatching { withContext(Dispatchers.IO) { ScanImages.load(File(path)) } }
                    .getOrElse {
                        error = "Aufnahme nicht verfügbar."
                        null
                    }
            }
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
                "Für die Aufnahme benötigt StreberAlarm Zugriff auf die Kamera. Bilder und PDFs kannst du auch ohne Kamerafreigabe importieren."
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
                        val file = File(activity.cacheDir, "scan-${id()}.jpg")
                        saving = true
                        image.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(activity),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                    current = file.path
                                    saving = false
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    file.delete()
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
                    { if (it.endInclusive - it.start >= 0.05f) horizontal = it },
                )
                Text("Oberen und unteren Rand zuschneiden")
                RangeSlider(vertical, { if (it.endInclusive - it.start >= 0.05f) vertical = it })
                OutlinedButton(
                    {
                        bitmap =
                            Bitmap.createBitmap(
                                b,
                                0,
                                0,
                                b.width,
                                b.height,
                                Matrix().apply { postRotate(90f) },
                                true,
                            )
                        horizontal = 0f..1f
                        vertical = 0f..1f
                    },
                    Modifier.fillMaxWidth(),
                ) {
                    Text("90° drehen")
                }
                Action(
                    "Seite übernehmen",
                    {
                        saving = true
                        val image =
                            ScanImages.crop(
                                b,
                                horizontal.start,
                                vertical.start,
                                horizontal.endInclusive,
                                vertical.endInclusive,
                            )
                        activity.action {
                            try {
                                val file = File(activity.cacheDir, "scan-page-${id()}.jpg")
                                file.outputStream().use {
                                    image.compress(Bitmap.CompressFormat.JPEG, 90, it)
                                }
                                withContext(Dispatchers.Main) {
                                    shots = ArrayList(shots + file.path)
                                    current?.let { File(it).delete() }
                                    current = null
                                    bitmap = null
                                }
                            } finally {
                                withContext(Dispatchers.Main) { saving = false }
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
            }) {
                Text("Aufnahme verwerfen")
            }
        }
        shots.forEachIndexed { index, path ->
            Row(Modifier.fillMaxWidth()) {
                Text("Seite ${index+1}", Modifier.weight(1f))
                TextButton({
                    shots = ArrayList(shots.filterNot { it == path })
                    File(path).delete()
                }) {
                    Text("Entfernen")
                }
                if (index > 0)
                    TextButton({
                        val changed = ArrayList(shots)
                        java.util.Collections.swap(changed, index, index - 1)
                        shots = changed
                    }) {
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
                        val pdf = File(activity.cacheDir, "scan-${id()}.pdf")
                        try {
                            ScanImages.pdf(shots.map { File(it) }, pdf)
                            activity.repo.addLocalPdf(examId, pdf)
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
