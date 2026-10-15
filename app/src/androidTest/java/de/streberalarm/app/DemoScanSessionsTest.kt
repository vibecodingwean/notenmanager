package de.streberalarm.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DemoScanSessionsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun jpeg(file: File) {
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { check(image.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
        finally { image.recycle() }
    }

    @Test fun lateCameraCallbackDrainsBeforeDeleteAndCannotPublish() = runBlocking {
        val root = File(context.cacheDir, "demo-session-camera-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sessions = DemoScanSessions(root, scope)
        sessions.prepare()
        val first = sessions.open("exam-1", 1, null, sessions.routeToken("exam-1"))
        val capture = sessions.reserve(first, DemoScanSessions.Kind.CAMERA)!!
        jpeg(capture.output)
        assertTrue(ScanImages.load(capture.output).run { recycle(); true })
        val closed = sessions.closeAllAdmission()
        val deletion = scope.async(start = CoroutineStart.UNDISPATCHED) {
            sessions.drainAndDeleteAll(closed)
        }
        assertFalse(deletion.isCompleted)
        assertFalse(sessions.finishCapture(capture, true))
        withTimeout(10_000) { deletion.await() }
        assertFalse(root.exists())
        val second = sessions.open("exam-2", 2, null, sessions.routeToken("exam-2"))
        assertTrue(second.id != first.id && second.state.value.current == null)
        sessions.drainAndDeleteAll(sessions.closeAllAdmission())
    }

    @Test fun closingOneSessionCannotDeleteAnotherSessionsImage() = runBlocking {
        val root = File(context.cacheDir, "demo-session-isolation-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sessions = DemoScanSessions(root, scope)
        sessions.prepare()
        val first = sessions.open("exam-1", 1, null, sessions.routeToken("exam-1"))
        val second = sessions.open("exam-2", 1, null, sessions.routeToken("exam-2"))
        val a = sessions.reserve(first, DemoScanSessions.Kind.CAMERA)!!
        val b = sessions.reserve(second, DemoScanSessions.Kind.CAMERA)!!
        jpeg(a.output); jpeg(b.output)
        assertTrue(sessions.finishCapture(a, true))
        assertTrue(sessions.finishCapture(b, true))
        val preserved = b.output.readBytes()
        withTimeout(10_000) { sessions.close(first.id)!!.join() }
        assertFalse(first.folder.exists())
        assertTrue(b.output.exists() && b.output.readBytes().contentEquals(preserved))
        assertTrue(second.state.value.current == b.output)
        val firstPage = sessions.reserve(second, DemoScanSessions.Kind.CROP)!!
        jpeg(firstPage.output)
        assertTrue(sessions.finishCrop(firstPage, true))
        val nextCapture = sessions.reserve(second, DemoScanSessions.Kind.CAMERA)!!
        jpeg(nextCapture.output)
        assertTrue(sessions.finishCapture(nextCapture, true))
        val secondPage = sessions.reserve(second, DemoScanSessions.Kind.CROP)!!
        jpeg(secondPage.output)
        assertTrue(sessions.finishCrop(secondPage, true))
        assertTrue(second.state.value.pages == listOf(firstPage.output, secondPage.output))
        assertTrue(sessions.movePageEarlier(second, secondPage.output))
        assertTrue(second.state.value.pages == listOf(secondPage.output, firstPage.output))
        val pdf = sessions.reserve(second, DemoScanSessions.Kind.PDF)!!
        assertTrue(pdf.pages == listOf(secondPage.output, firstPage.output))
        assertFalse(sessions.removePage(second, secondPage.output))
        assertFalse(sessions.movePageEarlier(second, firstPage.output))
        assertTrue(sessions.reserve(second, DemoScanSessions.Kind.CAMERA) == null)
        sessions.abort(pdf)
        sessions.drainAndDeleteAll(sessions.closeAllAdmission())
        assertFalse(root.exists())
    }
}
