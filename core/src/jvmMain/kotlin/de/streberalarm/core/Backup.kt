package de.streberalarm.core

import java.io.*
import java.security.SecureRandom
import java.util.zip.*
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object Backup {
    private val magic = "SALM0001".toByteArray(Charsets.US_ASCII)
    const val LIMIT = 160 * 1024 * 1024
    private const val ITERATIONS = 600_000

    data class Restored(val data: SchoolData, val files: Map<String, ByteArray>)

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try {
            val raw =
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try {
                SecretKeySpec(raw, "AES")
            } finally {
                raw.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    fun export(data: SchoolData, password: CharArray, readFile: (String) -> ByteArray): ByteArray {
        require(password.size >= 8) { "Mindestens 8 Zeichen für das Backup-Passwort." }
        data.validate()
        require(data.documents.sumOf { it.size } < LIMIT / 2) {
            "Backup zu groß (maximal 80 MiB Dokumente)."
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("data.json", dataJson.encodeToString(data).toByteArray())
            data.documents.forEach { d ->
                val bytes = readFile(d.file)
                require(bytes.size.toLong() == d.size)
                put("documents/${d.file}", bytes)
            }
        }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        cipher.updateAAD(magic)
        val plain = out.toByteArray()
        return try {
            magic + salt + nonce + cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
    }

    fun restore(bytes: ByteArray, password: CharArray): Restored {
        require(bytes.size in 52..LIMIT && bytes.copyOfRange(0, 8).contentEquals(magic)) {
            "Ungültiges Backup-Format."
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(password, bytes.copyOfRange(8, 24)),
            GCMParameterSpec(128, bytes.copyOfRange(24, 36)),
        )
        cipher.updateAAD(magic)
        val plain =
            try {
                cipher.doFinal(bytes, 36, bytes.size - 36)
            } catch (e: java.security.GeneralSecurityException) {
                throw IllegalArgumentException("Passwort falsch oder Backup beschädigt.")
            }
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(plain)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(
                        !entry.isDirectory &&
                            (entry.name == "data.json" ||
                                entry.name.matches(Regex("documents/[a-f0-9-]+\\.(pdf|jpg|png)")))
                    ) {
                        "Unzulässiger Backup-Pfad."
                    }
                    require(entry.name !in entries && entries.size < 2000)
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= LIMIT && out.size().toLong() + n <= MAX_DOCUMENT_BYTES) {
                            "Backup überschreitet Größenlimit."
                        }
                        out.write(buffer, 0, n)
                    }
                    entries[entry.name] = out.toByteArray()
                }
            }
            val json = entries.remove("data.json") ?: error("Backup-Daten fehlen.")
            val data = dataJson.decodeFromString<SchoolData>(json.toString(Charsets.UTF_8))
            data.validate()
            require(entries.keys == data.documents.map { "documents/${it.file}" }.toSet()) {
                "Dokumentzuordnung unvollständig."
            }
            data.documents.forEach {
                require(entries.getValue("documents/${it.file}").size.toLong() == it.size)
            }
            return Restored(data, entries.mapKeys { it.key.removePrefix("documents/") })
        } finally {
            plain.fill(0)
        }
    }
}
