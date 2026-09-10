package com.healthyagent.running.speech

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AndroidBundledVoiceStore internal constructor(
    private val openAsset: (String) -> InputStream,
    private val outputDirectory: File,
) : BundledVoiceStore {
    constructor(context: Context) : this(
        { context.assets.open(it) },
        File(context.filesDir, "voice/liuying/v1"),
    )

    private val mutex = Mutex()

    override suspend fun open(ruleId: String): File? {
        val voice = BundledVoiceCatalog.find(ruleId) ?: return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val manifest = openAsset("$ASSET_DIRECTORY/manifest.json").bufferedReader(Charsets.UTF_8).use {
                        Json.decodeFromString<Manifest>(it.readText())
                    }
                    if (!manifest.isValid()) return@withLock null
                    val entry = manifest.entries.single { it.ruleId == ruleId }
                    val bytes = openAsset("$ASSET_DIRECTORY/${voice.fileName}").use { it.readBytes() }
                    if (bytes.isEmpty() || sha256(bytes) != entry.sha256) return@withLock null
                    currentCoroutineContext().ensureActive()
                    check(outputDirectory.isDirectory || outputDirectory.mkdirs())
                    val file = File(outputDirectory, voice.fileName)
                    if (!file.isFile || sha256(file.readBytes()) != entry.sha256) file.writeBytes(bytes)
                    currentCoroutineContext().ensureActive()
                    file
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    @Serializable
    private data class Manifest(val version: Int, val entries: List<Entry>) {
        fun isValid(): Boolean = version == 1 && entries.size == BundledVoiceCatalog.entries.size &&
            entries.map { it.ruleId }.toSet().size == entries.size && entries.all { entry ->
                val voice = BundledVoiceCatalog.find(entry.ruleId)
                voice != null && entry.text == voice.text && entry.fileName == voice.fileName &&
                    entry.sha256.matches(Regex("[0-9a-f]{64}")) && entry.duration.isFinite() && entry.duration > 0
            }
    }

    @Serializable
    private data class Entry(
        val ruleId: String,
        val text: String,
        val fileName: String,
        val sha256: String,
        val duration: Double,
    )

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val ASSET_DIRECTORY = "voice/liuying/v1"
    }
}
