package de.streberalarm.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import de.streberalarm.core.Document
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DocumentPage(a: MainActivity, doc: Document) {
    var page by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(1) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(doc.id, page) {
        runCatching {
                withContext(Dispatchers.IO) {
                    val file = File(a.repo.documentsDir, doc.file)
                    if (doc.mime == "application/pdf")
                        PdfRenderer(
                                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            )
                            .use { renderer ->
                                val n = renderer.pageCount
                                val b =
                                    renderer.openPage(page.coerceIn(0, n - 1)).use { p ->
                                        val width = 1200
                                        val height =
                                            (width.toLong() * p.height / p.width)
                                                .coerceAtMost(4000)
                                                .toInt()
                                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                            .also {
                                                it.eraseColor(android.graphics.Color.WHITE)
                                                p.render(
                                                    it,
                                                    null,
                                                    null,
                                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                                )
                                            }
                                    }
                                n to b
                            }
                    else {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.path, options)
                        require(options.outWidth > 0 && options.outHeight > 0)
                        options.inSampleSize =
                            maxOf(1, maxOf(options.outWidth, options.outHeight) / 1600)
                        options.inJustDecodeBounds = false
                        1 to BitmapFactory.decodeFile(file.path, options)
                    }
                }
            }
            .onSuccess {
                count = it.first
                bitmap = it.second
            }
            .onFailure { error = "Dokument konnte nicht angezeigt werden." }
    }
    Page(doc.name, "Im privaten App-Speicher") {
        if (error.isNotBlank()) Text(error)
        Text("Seite ${page+1} von $count")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton({ page-- }, enabled = page > 0) { Text("Vorherige") }
            OutlinedButton({ page++ }, enabled = page < count - 1) { Text("Nächste") }
        }
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = "${doc.name}, Seite ${page+1}",
                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                contentScale = ContentScale.FillWidth,
            )
        } ?: CircularProgressIndicator()
    }
}
