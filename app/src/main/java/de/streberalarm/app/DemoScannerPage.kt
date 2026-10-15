package de.streberalarm.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Demo capture UI reconnects to the repository session after Activity recreation. */
@Composable
internal fun DemoScannerPage(activity: MainActivity, examId: String, done: () -> Unit) {
    val repo = activity.repo
    val admissionGeneration = remember(examId) { repo.vocabularyGeneration.value }
    val routeToken = remember(examId) { repo.demoScanRouteToken(examId) }
    var savedSessionId by rememberSaveable(examId) { mutableStateOf<String?>(null) }
    var session by remember(examId) { mutableStateOf<DemoScanSessions.Session?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(examId, savedSessionId) {
        val requestedId = savedSessionId
        val opened = try {
            // Keep the result even if this composition is disposed during repository admission.
            withContext(NonCancellable) {
                repo.openDemoScan(examId, requestedId, admissionGeneration, routeToken)
            }
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive)
                error = e.message ?: "Demo-Scanner nicht verfügbar."
            return@LaunchedEffect
        }
        if (!currentCoroutineContext().isActive) {
            if (opened.id != requestedId) repo.closeDemoScan(opened.id)
            return@LaunchedEffect
        }
        session = opened
        if (requestedId != opened.id) {
            if (requestedId != null)
                error = "Die vorige Demo-Aufnahme ist nicht mehr verfügbar. Du kannst neu fotografieren."
            savedSessionId = opened.id
        }
    }
    val s = session
    if (s == null) {
        Page("Arbeit scannen") {
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            else CircularProgressIndicator()
        }
        return
    }
    val snapshot by s.state.collectAsState()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var horizontal by remember { mutableStateOf(0f..1f) }
    var vertical by remember { mutableStateOf(0f..1f) }
    var turns by rememberSaveable(s.id, snapshot.current?.path) { mutableIntStateOf(0) }
    var attached by remember { mutableStateOf(true) }
    val providerFuture = remember { ProcessCameraProvider.getInstance(activity) }
    LaunchedEffect(snapshot.current) {
        horizontal = 0f..1f
        vertical = 0f..1f
        bitmap = snapshot.current?.let { path ->
            runCatching { withContext(Dispatchers.IO) {
                val source = ScanImages.load(path)
                ScanImages.rotate(source, turns).also { if (it !== source) source.recycle() }
            } }
                .getOrElse { error = "Aufnahme nicht verfügbar."; null }
        }
        bitmap?.let { ScanTiming.demoPreview?.invoke(it, turns) }
    }
    LaunchedEffect(snapshot.completed) {
        if (snapshot.completed && attached) done()
    }
    DisposableEffect(s.id) {
        attached = true
        onDispose {
            attached = false
            if (providerFuture.isDone) providerFuture.get().unbindAll()
            // File lifetime belongs to the session, including rotation and backgrounding.
        }
    }
    Page("Arbeit scannen", "${snapshot.pages.size} Seiten vorbereitet · vollständig lokal") {
        if (!snapshot.open) {
            Text("Der Demo-Scanner wurde geschlossen.")
        } else if (!granted) {
            Text("Für die Aufnahme benötigt NotenManager Zugriff auf die Kamera. Bilder und PDFs kannst du auch ohne Kamerafreigabe importieren.")
            Action("Kamera erlauben") { permission.launch(Manifest.permission.CAMERA) }
        } else if (snapshot.current == null) {
            AndroidView(factory = { context ->
                PreviewView(context).also { view ->
                    providerFuture.addListener({
                        try {
                            if (!attached || !s.state.value.open) return@addListener
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = view.surfaceProvider
                            }
                            val image = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                            provider.unbindAll()
                            provider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, image)
                            capture = image
                        } catch (_: Exception) {
                            error = "Kamera nicht verfügbar. Du kannst vorhandene Bilder/PDFs importieren."
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
            }, modifier = Modifier.fillMaxWidth().height(330.dp))
            Action("Seite fotografieren", {
                val image = capture ?: return@Action
                val lease = repo.demoScans.reserve(s, DemoScanSessions.Kind.CAMERA) ?: return@Action
                try {
                    lease.output.parentFile?.mkdirs()
                    image.takePicture(ImageCapture.OutputFileOptions.Builder(lease.output).build(),
                        ContextCompat.getMainExecutor(activity),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                repo.demoScans.finishCapture(lease, lease.output.length() > 0)
                            }
                            override fun onError(exception: ImageCaptureException) {
                                repo.demoScans.abort(lease)
                                if (attached) error = "Aufnahme fehlgeschlagen."
                            }
                        })
                } catch (e: Exception) {
                    repo.demoScans.abort(lease)
                    error = "Aufnahme fehlgeschlagen."
                }
            }, capture != null && !snapshot.busy && snapshot.pages.size < 30)
        } else {
            bitmap?.let { b ->
                Box(Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height)) {
                    Image(b.asImageBitmap(), "Aufgenommene Seite", Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds)
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color(0xFF66FF99),
                            topLeft = Offset(horizontal.start * size.width, vertical.start * size.height),
                            size = Size((horizontal.endInclusive-horizontal.start)*size.width,
                                (vertical.endInclusive-vertical.start)*size.height),
                            style = Stroke(4.dp.toPx()))
                    }
                }
                Text("Linken und rechten Rand zuschneiden")
                RangeSlider(horizontal, { if (it.endInclusive-it.start >= 0.05f) horizontal = it },
                    modifier = Modifier.testTag("demo-crop-horizontal"))
                Text("Oberen und unteren Rand zuschneiden")
                RangeSlider(vertical, { if (it.endInclusive-it.start >= 0.05f) vertical = it },
                    modifier = Modifier.testTag("demo-crop-vertical"))
                OutlinedButton({
                    bitmap = ScanImages.rotate(b, 1)
                    turns = (turns + 1) % 4
                    bitmap?.let { ScanTiming.demoPreview?.invoke(it, turns) }
                    horizontal = 0f..1f; vertical = 0f..1f
                }, Modifier.fillMaxWidth(), colors = bayernButtonColors(ButtonDefaults.outlinedButtonColors())) {
                    Text("90° drehen")
                }
                Action("Seite übernehmen", {
                    val lease = repo.demoScans.reserve(s, DemoScanSessions.Kind.CROP) ?: return@Action
                    val bounds = listOf(horizontal.start, vertical.start,
                        horizontal.endInclusive, vertical.endInclusive)
                    ScanTiming.demoCropBounds?.invoke(bounds)
                    val rotation = turns
                    repo.launchDemoScan {
                        try {
                            ScanTiming.beforePageWrite?.invoke()
                            if (!repo.demoScans.mayWrite(lease)) return@launchDemoScan
                            lease.output.parentFile?.mkdirs()
                            val source = ScanImages.load(requireNotNull(lease.source))
                            try {
                                val turned = ScanImages.rotate(source, rotation)
                                try {
                                    val image = ScanImages.crop(turned, bounds[0], bounds[1], bounds[2], bounds[3])
                                    try {
                                        lease.output.outputStream().use { stream ->
                                            check(image.compress(Bitmap.CompressFormat.JPEG, 90, stream))
                                        }
                                    } finally { if (image !== turned) image.recycle() }
                                } finally { if (turned !== source) turned.recycle() }
                            } finally { source.recycle() }
                            ScanTiming.afterPageWrite?.invoke()
                            repo.demoScans.finishCrop(lease, lease.output.length() > 0)
                        } catch (e: Exception) {
                            android.util.Log.e("DemoScanner", "Demo crop failed", e)
                            repo.demoScans.reportError(s, "Seite konnte nicht verarbeitet werden.")
                        } finally {
                            if (!lease.finished) repo.demoScans.abort(lease)
                            ScanTiming.afterPageWorker?.invoke()
                        }
                    }
                }, !snapshot.busy)
            }
            TextButton({ repo.demoScans.discardCurrent(s) },
                colors = bayernButtonColors(ButtonDefaults.textButtonColors())) {
                Text("Aufnahme verwerfen")
            }
        }
        snapshot.pages.forEachIndexed { index, page ->
            Row(Modifier.fillMaxWidth()) {
                Text("Seite ${index+1}", Modifier.weight(1f))
                TextButton({ repo.demoScans.removePage(s, page) },
                    colors = bayernButtonColors(ButtonDefaults.textButtonColors())) { Text("Entfernen") }
                if (index > 0) TextButton({ repo.demoScans.movePageEarlier(s, page) },
                    colors = bayernButtonColors(ButtonDefaults.textButtonColors())) { Text("Nach oben") }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        snapshot.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Action("${snapshot.pages.size} Seiten als PDF speichern", {
            val lease = repo.demoScans.reserve(s, DemoScanSessions.Kind.PDF) ?: return@Action
            repo.launchDemoScan {
                try {
                    if (!repo.demoScans.mayWrite(lease)) return@launchDemoScan
                    lease.output.parentFile?.mkdirs()
                    ScanImages.pdf(lease.pages, lease.output)
                    ScanTiming.beforePdfAttach?.invoke()
                    repo.addDemoScanPdf(lease)
                    ScanTiming.afterPdfAttach?.invoke()
                    repo.demoScans.finishPdf(lease, true)
                } catch (e: Exception) {
                    android.util.Log.e("DemoScanner", "Demo PDF failed", e)
                    repo.demoScans.reportError(s, "PDF konnte nicht gespeichert werden.")
                } finally {
                    if (!lease.finished) repo.demoScans.abort(lease)
                }
            }
        }, snapshot.pages.isNotEmpty() && snapshot.current == null && !snapshot.busy)
    }
}
