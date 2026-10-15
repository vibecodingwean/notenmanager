package de.streberalarm.app

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import de.streberalarm.core.Assessment
import de.streberalarm.core.Document
import de.streberalarm.core.Kind
import de.streberalarm.core.dataJson
import de.streberalarm.core.id
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** Attachments awaiting a grade commit live outside SchoolData and its backup/averages. */
@Serializable
data class GradeDraft(
    val id: String = id(),
    val resultId: String = id(),
    val profileId: String,
    val subjectId: String,
    val targetId: String? = null,
    val baseline: Assessment? = null,
    val sourceSelection: String = targetId ?: "new:$subjectId",
    val documents: List<Document> = emptyList(),
    val date: String? = null,
    val kind: Kind? = null,
    val topic: String? = null,
    val actual: Int? = null,
    val weight: String? = null,
    val commitIntent: Assessment? = null,
)

internal class GradeDrafts(private val context: Context, private val demo: () -> Boolean) {
    private val root: File
        get() =
            File(context.filesDir, if (demo()) "demo-grade-drafts" else "grade-drafts").apply {
                mkdirs()
            }

    private fun metadata(draftId: String) = File(root, "$draftId.json")

    private fun folder(draftId: String) = File(root, draftId)

    fun find(draftId: String): GradeDraft? =
        metadata(draftId)
            .takeIf { it.isFile }
            ?.let {
                dataJson.decodeFromString<GradeDraft>(AtomicFile(it).readFully().decodeToString())
            }

    fun findOrCreate(
        profileId: String,
        subjectId: String,
        targetId: String?,
        baseline: Assessment?,
    ): GradeDraft {
        root
            .listFiles()
            ?.filter { it.isDirectory && !metadata(it.name).isFile }
            ?.forEach { it.deleteRecursively() }
        val sourceSelection = targetId ?: "new:$subjectId"
        root
            .listFiles()
            ?.asSequence()
            ?.filter { it.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                        dataJson.decodeFromString<GradeDraft>(
                            AtomicFile(file).readFully().decodeToString()
                        )
                    }
                    .getOrNull()
            }
            ?.firstOrNull { it.profileId == profileId && it.sourceSelection == sourceSelection }
            ?.let {
                return it
            }
        return GradeDraft(
                profileId = profileId,
                subjectId = subjectId,
                targetId = targetId,
                baseline = baseline,
                sourceSelection = sourceSelection,
            )
            .also(::write)
    }

    private fun write(draft: GradeDraft) {
        val atomic = AtomicFile(metadata(draft.id))
        val stream = atomic.startWrite()
        try {
            stream.write(dataJson.encodeToString(draft).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }

    fun change(
        draft: GradeDraft,
        subjectId: String,
        targetId: String?,
        baseline: Assessment?,
    ): GradeDraft =
        draft.copy(subjectId = subjectId, targetId = targetId, baseline = baseline).also(::write)

    fun updateForm(
        draft: GradeDraft,
        date: String,
        kind: Kind?,
        topic: String,
        actual: Int?,
        weight: String,
    ): GradeDraft =
        draft
            .copy(date = date, kind = kind, topic = topic, actual = actual, weight = weight)
            .also(::write)

    fun markCommit(draft: GradeDraft, assessment: Assessment): GradeDraft =
        draft.copy(commitIntent = assessment).also(::write)

    fun rollbackDocuments(original: GradeDraft, current: GradeDraft) {
        write(original)
        current.documents.drop(original.documents.size).forEach {
            source(current.id, it.file).delete()
        }
    }

    fun addPdf(draft: GradeDraft, source: File, maxBytes: Long): GradeDraft {
        require(source.length() in 1..maxBytes)
        require(
            source.inputStream().use { input ->
                val head = ByteArray(5)
                input.read(head) == 5 && head.contentEquals("%PDF-".toByteArray())
            }
        ) {
            "Nur gültige PDF-Dateien werden unterstützt."
        }
        return addCopied(draft, "application/pdf", "pdf", "Scan ${draft.documents.size + 1}.pdf") {
            target ->
            source.inputStream().use { input ->
                java.io.FileOutputStream(target).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        }
    }

    fun addUri(draft: GradeDraft, uri: Uri, maxBytes: Long): GradeDraft {
        val mime = context.contentResolver.getType(uri) ?: ""
        val ext =
            when (mime) {
                "application/pdf" -> "pdf"
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                else -> error("Nur JPEG, PNG und PDF werden unterstützt.")
            }
        return addCopied(draft, mime, ext, "Dokument ${draft.documents.size + 1}.$ext") { target ->
            context.contentResolver.openInputStream(uri)!!.use { input ->
                java.io.FileOutputStream(target).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val size = input.read(buffer)
                        if (size < 0) break
                        total += size
                        require(total <= maxBytes) { "Dokument ist größer als 40 MiB." }
                        output.write(buffer, 0, size)
                    }
                    output.fd.sync()
                }
            }
            val head = ByteArray(minOf(8L, target.length()).toInt())
            java.io.DataInputStream(target.inputStream()).use { it.readFully(head) }
            require(
                when (ext) {
                    "pdf" -> head.take(5).toByteArray().contentEquals("%PDF-".toByteArray())
                    "jpg" -> head.size >= 2 && head[0] == 0xff.toByte() && head[1] == 0xd8.toByte()
                    else ->
                        head.contentEquals(
                            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
                        )
                }
            ) {
                "Dateiinhalt passt nicht zum Dokumenttyp."
            }
        }
    }

    private fun addCopied(
        draft: GradeDraft,
        mime: String,
        ext: String,
        name: String,
        copy: (File) -> Unit,
    ): GradeDraft {
        val file = "${id()}.$ext"
        val target = File(folder(draft.id).apply { mkdirs() }, file)
        try {
            copy(target)
            require(target.length() > 0)
            val next =
                draft.copy(
                    documents =
                        draft.documents +
                            Document(
                                assessmentId = draft.targetId ?: draft.id,
                                name = name,
                                mime = mime,
                                file = file,
                                size = target.length(),
                            )
                )
            write(next)
            return next
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    fun source(draftId: String, filename: String): File = File(folder(draftId), filename)

    fun delete(draftId: String) {
        metadata(draftId).delete()
        folder(draftId).deleteRecursively()
        File(context.cacheDir, "grade-scan-$draftId").deleteRecursively()
    }

    fun clearDemo() {
        val demoRoot = File(context.filesDir, "demo-grade-drafts")
        demoRoot
            .listFiles()
            ?.filter { it.extension == "json" }
            ?.forEach {
                File(context.cacheDir, "grade-scan-${it.nameWithoutExtension}").deleteRecursively()
            }
        demoRoot.deleteRecursively()
    }
}
