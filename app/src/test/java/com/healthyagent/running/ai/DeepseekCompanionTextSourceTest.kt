package com.healthyagent.running.ai

import com.healthyagent.running.core.model.CoachTone
import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.speech.SpeechRequest
import com.healthyagent.running.testing.FakeDiagnosticLogger
import com.healthyagent.running.diagnostics.DeepseekOutcome
import com.healthyagent.running.diagnostics.DiagnosticEvent
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.*
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeepseekCompanionTextSourceTest {
    @Test fun `默认客户端禁用重试重定向且调用上限两秒`() {
        val client = DeepseekCompanionTextSource.defaultHttpClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(2_000, client.callTimeoutMillis)
    }

    @Test fun `固定专属目标及非思考请求且普通提示仅含核心和语气`() = runTest {
        val factory = FakeFactory()
        val pending = async { DeepseekCompanionTextSource(factory).create(request()) }
        runCurrent()
        assertEquals(1, factory.calls.size)
        val call = factory.calls.single()
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", call.request().url.toString())
        assertEquals("test-only-deepseek", call.request().header("x-api-key"))
        val payload = call.payload()
        assertEquals("deepseek-flash", payload["model"]!!.jsonPrimitive.content)
        assertEquals("disabled", payload["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(false, payload["stream"]!!.jsonPrimitive.boolean)
        assertEquals(100, payload["max_tokens"]!!.jsonPrimitive.int)
        val prompt = Json.parseToJsonElement(payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content).jsonObject
        assertEquals(setOf("固定核心", "coachTone"), prompt.keys)
        assertEquals("跑步已暂停。", prompt["固定核心"]!!.jsonPrimitive.content)
        assertEquals("BALANCED", prompt["coachTone"]!!.jsonPrimitive.content)
        val body = call.respond(response("能陪在你身边，我很开心。"))
        assertEquals("能陪在你身边，我很开心。", pending.await())
        assertTrue(body.closed)
    }

    @Test fun `公里只附现有摘要而不发送会话标识或跑步配置`() = runTest {
        val factory = FakeFactory()
        val analysis = LlmAnalyzeRequest("private-session-marker", 3,
            LlmSplitData(3, 1.0, 367, 367.0, 145, 170), LlmSessionSummary(3.0, 1100, 366.7))
        val pending = async { DeepseekCompanionTextSource(factory).create(request("km_split").copy(analysis = analysis)) }
        runCurrent()
        assertEquals(1, factory.calls.size)
        val call = factory.calls.single()
        val prompt = Json.parseToJsonElement(call.payload()["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content).jsonObject
        assertEquals(setOf("固定核心", "coachTone", "split_data", "session_summary"), prompt.keys)
        assertTrue(prompt.toString().contains("367"))
        assertFalse(prompt.toString().contains("private-session-marker"))
        call.respond(response("有你相伴，沿途的风景都更温柔了。"))
        assertNotNull(pending.await())
    }

    @Test fun `未配置关闭紧急和蓝牙通知均无文字请求`() = runTest {
        val factory = FakeFactory()
        val source = DeepseekCompanionTextSource(factory)
        listOf(request().copy(deepseekConfig = null), request().copy(companionEnabled = false),
            request("data_stale"), request().let { it.copy(trigger = it.trigger.copy(ttsPriority = SpeechPriority.URGENT)) })
            .forEach { assertNull(source.create(it)) }
        assertTrue(factory.calls.isEmpty())
    }

    @Test fun `自然完成的多个文本块合并为单句`() = runTest {
        val factory = FakeFactory()
        val pending = async { DeepseekCompanionTextSource(factory).create(request()) }
        runCurrent()
        assertEquals(1, factory.calls.size)
        factory.calls.single().respond("""{"stop_reason":"end_turn","content":[{"type":"text","text":"有你相伴，"},{"type":"text","text":"我很开心。"}]}""")
        assertEquals("有你相伴，我很开心。", pending.await())
    }

    @Test fun `陪伴句里的非数值固定词组可以保留`() = runTest {
        val factory = FakeFactory()
        val pending = async { DeepseekCompanionTextSource(factory).create(request()) }
        runCurrent()
        factory.calls.single().respond(response("能和你一起看沿途的风景，我一直很开心。"))
        assertEquals("能和你一起看沿途的风景，我一直很开心。", pending.await())
    }

    @Test fun `拒绝截断非文本空和结构异常响应`() = runTest {
        val invalid = listOf(
            "{}", "not-json", """{"content":[{"type":"text","text":"陪着你。"}]}""",
            response("陪着你。").replace("end_turn", "max_tokens"),
            response("陪着你。").replace("\"type\":\"text\"", "\"type\":\"thinking\""),
            """{"stop_reason":"end_turn","content":[]}""",
            """{"stop_reason":"end_turn","content":[{"type":"text","text":123}]}""",
            """{"stop_reason":"end_turn","content":[{"type":"text","text":"陪着你。"},{"type":"tool_use"}]}""",
        )
        for (text in invalid) {
            val factory = FakeFactory()
            val pending = async { DeepseekCompanionTextSource(factory).create(request()) }
            runCurrent()
            assertEquals(1, factory.calls.size)
            val body = factory.calls.single().respond(text)
            assertNull(pending.await())
            assertTrue(body.closed)
        }
    }

    @Test fun `拒绝新增数字动作重复核心多句控制字符和过长内容`() = runTest {
        val invalid = listOf("", "陪伴".repeat(26), "今天跑了3公里。", "再走三公里。", "跑步已暂停。能陪着你真好。",
            "跑步已暂停", "跑步已暂停，能陪着你真好。", "你已经跑了一小时。", "你已经跑了壹小时。",
            "慢点，加速吧。", "喝水休息一下吧。", "调整呼吸。", "陪着你。真开心。", "（轻笑）陪着你。", "{陪伴}",
            "陪着你\n真开心。", "陪着你\u0000真开心。", "陪着你\u2028真开心。", "陪着你\u200b真开心。")
        for (text in invalid) {
            val factory = FakeFactory()
            val pending = async { DeepseekCompanionTextSource(factory).create(request()) }
            runCurrent()
            assertEquals(1, factory.calls.size)
            factory.calls.single().respond(response(text))
            assertNull("非法陪伴句应被拒绝", pending.await())
        }
    }

    @Test fun `两秒取消底层请求且迟到响应关闭而不记录成功`() = runTest {
        val factory = FakeFactory()
        val logger = FakeDiagnosticLogger()
        val pending = async { withTimeoutOrNull(2_000) { DeepseekCompanionTextSource(factory, logger).create(request()) } }
        runCurrent()
        assertEquals(1, factory.calls.size)
        advanceTimeBy(2_000)
        runCurrent()
        val call = factory.calls.single()
        assertTrue(call.isCanceled())
        assertNull(pending.await())
        assertEquals(listOf(DeepseekOutcome.ATTEMPTED, DeepseekOutcome.TIMEOUT), outcomes(logger))
        val count = logger.events.size
        assertTrue(call.respond(response("迟到陪伴句。" )).closed)
        assertEquals(count, logger.events.size)
    }

    @Test fun `显式取消保持取消语义且失败正文不进入诊断`() = runTest {
        val factory = FakeFactory()
        val logger = FakeDiagnosticLogger()
        val pending = async { DeepseekCompanionTextSource(factory, logger).create(request()) }
        runCurrent()
        assertEquals(1, factory.calls.size)
        pending.cancel()
        runCurrent()
        assertTrue(pending.isCancelled)
        assertTrue(factory.calls.single().isCanceled())
        assertEquals(listOf(DeepseekOutcome.ATTEMPTED, DeepseekOutcome.CANCELLED), outcomes(logger))
        factory.calls.single().fail(IOException("test-only-private-error"))
        assertFalse(logger.events.toString().contains("test-only"))
    }

    @Test fun `HTTP失败均关闭且不会返回正文`() = runTest {
        for (code in listOf(301, 401, 403, 429, 500)) {
            val factory = FakeFactory()
            val logger = FakeDiagnosticLogger()
            val pending = async { DeepseekCompanionTextSource(factory, logger).create(request()) }
            runCurrent()
            assertEquals(1, factory.calls.size)
            val body = factory.calls.single().respond("test-only-private-body", code)
            assertNull(pending.await())
            assertTrue(body.closed)
            assertFalse(logger.events.toString().contains("test-only"))
            assertEquals(listOf(DeepseekOutcome.ATTEMPTED, when (code) {
                401, 403 -> DeepseekOutcome.AUTH
                429 -> DeepseekOutcome.RATE_LIMIT
                else -> DeepseekOutcome.SERVER
            }), outcomes(logger))
        }
    }

    @Test fun `网络与套接字超时仅返回安全类别且不重试`() = runTest {
        for (error in listOf(IOException("test-only-private-error"), SocketTimeoutException("test-only-private-timeout"))) {
            val factory = FakeFactory()
            val logger = FakeDiagnosticLogger()
            val pending = async { DeepseekCompanionTextSource(factory, logger).create(request()) }
            runCurrent()
            factory.calls.single().fail(error)
            assertNull(pending.await())
            assertEquals(1, factory.calls.size)
            assertEquals(listOf(DeepseekOutcome.ATTEMPTED,
                if (error is SocketTimeoutException) DeepseekOutcome.TIMEOUT else DeepseekOutcome.NETWORK), outcomes(logger))
            assertFalse(logger.events.toString().contains("test-only"))
        }
    }

    private fun outcomes(logger: FakeDiagnosticLogger) = logger.events.filterIsInstance<DiagnosticEvent.DeepseekCompanionResult>().map { it.outcome }

    @Test fun `响应上限64KiB且未知长度达到上限保守拒绝`() = runTest {
        val small = response("能陪着你，我很开心。")
        val atLimit = small + " ".repeat(65536 - small.toByteArray().size)
        for ((text, knownLength, accepted) in listOf(Triple(atLimit, true, true), Triple(atLimit + " ", true, false),
            Triple(small, false, true), Triple(atLimit, false, false), Triple(atLimit + " ", false, false))) {
            val factory = FakeFactory()
            val pending = async { DeepseekCompanionTextSource(factory).create(request()) }
            runCurrent()
            assertEquals(1, factory.calls.size)
            val body = factory.calls.single().respond(text, knownLength = knownLength)
            assertEquals(accepted, pending.await() != null)
            assertTrue(body.closed)
            assertTrue(body.bytesRead <= 65536)
        }
    }

    private fun request(ruleId: String = "session_paused") = SpeechRequest(
        RuleTrigger(ruleId, Severity.NORMAL, "跑步已暂停。", SpeechPriority.APP), true,
        deepseekConfig = DeepseekRequestConfig("test-only-deepseek", CoachTone.BALANCED),
    )
    private fun response(text: String) = buildJsonObject {
        put("stop_reason", "end_turn")
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
    }.toString()

    private class FakeFactory : Call.Factory {
        val calls = mutableListOf<FakeCall>()
        override fun newCall(request: Request): Call = FakeCall(request).also(calls::add)
    }
    private class FakeCall(private val request: Request) : Call {
        private lateinit var callback: Callback
        private var cancelled = false
        override fun request() = request
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun execute(): Response = error("测试禁止同步请求")
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = ::callback.isInitialized
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall(request)
        fun fail(error: IOException) = callback.onFailure(this, error)
        fun payload() = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
        fun respond(text: String, code: Int = 200, knownLength: Boolean = true): TrackingBody {
            val body = TrackingBody(text, knownLength)
            callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("测试响应").body(body).build())
            return body
        }
    }
    private class TrackingBody(text: String, private val knownLength: Boolean) : ResponseBody() {
        private val buffer = Buffer().writeUtf8(text)
        private val length = buffer.size
        var closed = false
        val bytesRead get() = length - buffer.size
        override fun contentType(): MediaType? = null
        override fun contentLength() = if (knownLength) length else -1L
        override fun source(): BufferedSource = buffer
        override fun close() { closed = true }
    }
}
