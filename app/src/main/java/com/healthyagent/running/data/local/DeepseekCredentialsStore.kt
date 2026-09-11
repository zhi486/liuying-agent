package com.healthyagent.running.data.local

import com.healthyagent.running.ai.DeepseekRequestConfig
import com.healthyagent.running.core.model.CoachTone
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeepseekCredentialsStore(private val file: File) {
    private var apiKey: String? = load()
    private val _configured = MutableStateFlow(apiKey != null)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    @Synchronized
    fun capture(coachTone: CoachTone): DeepseekRequestConfig? = apiKey?.let { DeepseekRequestConfig(it, coachTone) }

    @Synchronized
    fun save(input: String): Boolean {
        val value = validKey(input) ?: return false
        val temporary = File(file.parentFile, "${file.name}.tmp")
        var createdTemporary = false
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(temporary).use {
                createdTemporary = true
                it.write(value.toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            apiKey = value
            _configured.value = true
            true
        } catch (_: Exception) {
            false
        } finally {
            if (createdTemporary) temporary.delete()
        }
    }

    private fun load(): String? = try {
        file.inputStream().use { input ->
            val buffer = ByteArray(2049)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read == -1) break
                count += read
            }
            if (count == buffer.size) null else validKey(Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(buffer, 0, count)).toString())
        }
    } catch (_: Exception) {
        null
    }

    private fun validKey(input: String): String? = input.trim().takeIf { value ->
        value.isNotEmpty() && value.length <= 512 && value.none { it.isWhitespace() || it.isISOControl() }
    }
}
