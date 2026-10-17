package com.healthyagent.running.agent.voice

import com.healthyagent.running.coordinator.CommandOutcome
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputServiceTest {
    private fun body(id: String, approved: Boolean = false) = buildJsonObject {
        put("sessionId", id); if (approved) put("approved", true)
    }
    private fun status(service: VoiceInputService, id: String) =
        (service.handle("voice.status", body(id)) as CommandOutcome.AcceptedWithData).data

    @Test fun 未经同意不录音且权限拒绝不请求识别() = runTest {
        var recorded = 0; var sent = 0
        val service = VoiceInputService({ recorded++; byteArrayOf() }, { sent++; "文本" }, this)
        val id = UUID.randomUUID().toString()
        assertThrows(IllegalArgumentException::class.java) { service.handle("voice.begin", body(id)) }
        service.handle("voice.begin", body(id, true)); runCurrent()
        assertEquals(id, service.permissionRequested.value); assertEquals(0, recorded)
        service.permissionResult(id, false); runCurrent()
        assertEquals("failed", status(service, id)["state"]?.jsonPrimitive?.content)
        assertEquals(0, sent)
    }

    @Test fun 完成只提供文本清除音频且同会话不重复启动() = runTest {
        val audio = ByteArray(3200) { 1 }; var sent = 0
        val service = VoiceInputService({ audio }, { sent++; " 今晚轻松跑 " }, this)
        val id = UUID.randomUUID().toString()
        service.handle("voice.begin", body(id, true)); service.permissionResult(id, true); runCurrent()
        assertEquals("今晚轻松跑", status(service, id)["text"]?.jsonPrimitive?.content)
        assertTrue(audio.all { it == 0.toByte() }); assertEquals(1, sent)
        assertTrue(service.handle("voice.begin", body(id, true)) is CommandOutcome.Rejected)
        service.permissionResult(id, true); runCurrent(); assertEquals(1, sent)
    }

    @Test fun 结束录音仅识别一次而取消不再上传() = runTest {
        var sent = 0
        val service = VoiceInputService({ stop -> while (!stop.get()) delay(20); ByteArray(3200) }, { sent++; "文本" }, this)
        val id = UUID.randomUUID().toString()
        service.handle("voice.begin", body(id, true)); service.permissionResult(id, true); runCurrent()
        service.handle("voice.finish", body(id)); service.handle("voice.finish", body(id)); advanceUntilIdle()
        assertEquals(1, sent)
        service.cancel()
        val next = UUID.randomUUID().toString()
        service.handle("voice.begin", body(next, true)); service.permissionResult(next, true); runCurrent()
        service.cancel(); advanceUntilIdle(); assertEquals(1, sent)
        assertTrue(service.handle("voice.status", body(next)) is CommandOutcome.Rejected)
    }

    @Test fun 取消后的旧权限回调不会启动新会话且超时不重试() = runTest {
        var recorded = 0; var sent = 0
        val service = VoiceInputService({ recorded++; ByteArray(3200) }, { sent++; awaitCancellation() }, this)
        val old = UUID.randomUUID().toString(); val next = UUID.randomUUID().toString()
        service.handle("voice.begin", body(old, true)); service.cancel()
        service.handle("voice.begin", body(next, true)); service.permissionResult(old, true); runCurrent()
        assertEquals(0, recorded)
        service.permissionResult(next, true); advanceUntilIdle()
        assertEquals("failed", status(service, next)["state"]?.jsonPrimitive?.content)
        assertEquals(1, sent)
    }

    @Test fun 拒绝伪授权多余字段非法会话() {
        for (json in listOf("""{"sessionId":"bad","approved":true}""",
            """{"sessionId":"11111111-1111-4111-8111-111111111111","approved":"true"}""",
            """{"sessionId":"11111111-1111-4111-8111-111111111111","approved":true,"key":"fake"}""")) {
            assertTrue(runCatching { VoiceInputService.validate("voice.begin", Json.parseToJsonElement(json).jsonObject) }.isFailure)
        }
    }

    @Test fun 安全连接错误显示具体原因未知错误不泄露正文() = runTest {
        for (failure in listOf(VoiceConnectionFailure(401), VoiceConnectionFailure(null),
            IllegalStateException("秘密内容"))) {
            var calls = 0
            val service = VoiceInputService(VoiceStream { _, _, _, _ -> calls++; throw failure }, this)
            val id = UUID.randomUUID().toString()
            service.handle("voice.begin", body(id, true)); service.permissionResult(id, true)
            runCurrent()
            val result = status(service, id)
            assertEquals("failed", result["state"]?.jsonPrimitive?.content)
            val message = result["error"]!!.jsonPrimitive.content
            if (failure is VoiceConnectionFailure) assertEquals(failure.userMessage, message)
            assertFalse(message.contains("秘密内容"))
            assertEquals(JsonNull, result["text"])
            assertEquals(1, calls)
            service.cancel()
        }
    }

    @Test fun 流式连接和预览独立于草稿取消后清空() = runTest {
        var released = false
        val service = VoiceInputService(VoiceStream { _, ready, preview, _ ->
            try {
                delay(100); ready(); preview("青松"); delay(100); preview("轻松跑"); awaitCancellation()
            } finally { released = true }
        }, this)
        val id = UUID.randomUUID().toString()
        service.handle("voice.begin", body(id, true)); service.permissionResult(id, true); runCurrent()
        assertEquals("connecting", status(service, id)["state"]?.jsonPrimitive?.content)
        advanceTimeBy(101); runCurrent()
        assertEquals("recording", status(service, id)["state"]?.jsonPrimitive?.content)
        assertEquals("青松", status(service, id)["preview"]?.jsonPrimitive?.content)
        advanceTimeBy(100); runCurrent()
        assertEquals("轻松跑", status(service, id)["preview"]?.jsonPrimitive?.content)
        assertEquals(JsonNull, status(service, id)["text"])
        val cancelled = (service.handle("voice.cancel", body(id)) as CommandOutcome.AcceptedWithData).data
        runCurrent(); assertTrue(released); assertEquals(JsonNull, cancelled["preview"])
    }
}
