package de.streberalarm.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.common.MlKit
import com.google.mlkit.vision.documentscanner.*
import de.streberalarm.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    val appearance by lazy { AppearancePreferences(applicationContext) }
    val repo
        get() = (application as StreberAlarmApp).repository

    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val estimateRequest = MutableStateFlow<String?>(null)
    private var targetExam: String? = null
    private var backupPassword: CharArray? = null
    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            action { repo.refreshEstimates() }
        }
    private val importer =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty())
                targetExam?.let { exam ->
                    action {
                        repo.importDocuments(exam, uris)
                        message.value = "${uris.size} Dokumente gespeichert."
                    }
                }
        }
    private val googleScanner =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                val uris =
                    scan?.pdf?.uri?.let { listOf(it) } ?: scan?.pages?.map { it.imageUri }.orEmpty()
                if (uris.isNotEmpty())
                    targetExam?.let { exam ->
                        action {
                            repo.importDocuments(exam, uris)
                            message.value = "Google-Scan gespeichert."
                        }
                    }
            }
        }
    private val exporter =
        registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            val password = backupPassword
            backupPassword = null
            if (uri != null && password != null)
                action {
                    try {
                        val bytes = repo.export(password)
                        contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                        message.value = "Verschlüsseltes Backup gespeichert."
                    } finally {
                        password.fill('\u0000')
                    }
                }
            else password?.fill('\u0000')
        }
    private val restorer =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val password = backupPassword
            backupPassword = null
            if (uri != null && password != null)
                action {
                    try {
                        val bytes =
                            contentResolver.openInputStream(uri)!!.use {
                                readLimited(it, Backup.LIMIT)
                            }
                        require(bytes.size <= Backup.LIMIT) { "Backup ist zu groß." }
                        repo.restore(bytes, password)
                        message.value = "Backup vollständig wiederhergestellt."
                    } finally {
                        password.fill('\u0000')
                    }
                }
            else password?.fill('\u0000')
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        targetExam = savedInstanceState?.getString("targetExam")
        if (savedInstanceState == null)
            estimateRequest.value = intent.getStringExtra(EstimateNotifications.EXTRA_EXAM)
        setContent { StreberAlarm(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        estimateRequest.value = intent.getStringExtra(EstimateNotifications.EXTRA_EXAM)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("targetExam", targetExam)
    }

    override fun onResume() {
        super.onResume()
        action { repo.refreshEstimates() }
    }

    fun action(block: suspend () -> Unit) {
        lifecycleScope.launch {
            busy.value = true
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message.value = e.message ?: "Aktion konnte nicht abgeschlossen werden."
            } finally {
                busy.value = false
            }
        }
    }

    fun requestNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
    }

    fun requestExact() {
        if (android.os.Build.VERSION.SDK_INT >= 31)
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:$packageName"),
                )
            )
    }

    fun import(examId: String) {
        targetExam = examId
        importer.launch(arrayOf("application/pdf", "image/jpeg", "image/png"))
    }

    /** Called only by the explicit Google privacy disclosure confirmation. */
    fun scanGoogle(examId: String) {
        targetExam = examId
        try {
            MlKit.initialize(applicationContext)
            val options =
                GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(30)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build()
            GmsDocumentScanning.getClient(options)
                .getStartScanIntent(this)
                .addOnSuccessListener {
                    googleScanner.launch(IntentSenderRequest.Builder(it).build())
                }
                .addOnFailureListener {
                    message.value =
                        "Google-Scanner nicht verfügbar. Für den ersten Start sind aktuelle Google Play-Dienste und ein Download erforderlich. Der lokale Scanner und der Dateiimport bleiben verfügbar."
                }
        } catch (e: Exception) {
            message.value =
                "Google-Scanner konnte nicht gestartet werden. Bitte den lokalen Scanner oder Dateiimport verwenden."
        }
    }

    fun export(password: String) {
        backupPassword?.fill('\u0000')
        backupPassword = password.toCharArray()
        exporter.launch("StreberAlarm-${java.time.LocalDate.now()}.sabackup")
    }

    fun restore(password: String) {
        backupPassword?.fill('\u0000')
        backupPassword = password.toCharArray()
        restorer.launch(arrayOf("*/*"))
    }
}

fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        require(out.size().toLong() + n <= limit) { "Datei überschreitet Größenlimit." }
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
