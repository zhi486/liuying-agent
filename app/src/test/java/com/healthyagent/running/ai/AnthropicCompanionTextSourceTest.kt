package com.healthyagent.running.ai

import com.healthyagent.running.core.model.RuleTrigger
import com.healthyagent.running.core.model.Severity
import com.healthyagent.running.core.model.SpeechPriority
import com.healthyagent.running.speech.SpeechRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.Protocol
import okhttp3.MediaType
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnthropicCompanionTextSourceTest {
    private val originalKey = LlmConfig.apiKey
    private val originalModel = LlmConfig.anthropicModel
    @After fun restore() {
        LlmConfig.apiKey = originalKey
        LlmConfig.anthropicModel = originalModel
    }

    @Test fun `普通请求只传核心不伪造分段且使用当前模型并关闭响应`() = runTest {
        LlmConfig.apiKey = "test-only-key"
        LlmConfig.anthropicModel = "test-model"
        val factory = FakeFactory()
        val pending = async { AnthropicCompanionTextSource(factory).create(request()) }
        runCurrent()
        assertEquals(1, factory.calls.size)
        val call = factory.calls.single()
        val payload = Json.parseToJsonElement(Buffer().also { call.request().body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals("test-model", payload["model"]!!.jsonPrimitive.content)
        val prompt = payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(prompt.contains("跑步已暂停。"))
        assertFalse(prompt.contains("split"))
        assertFalse(prompt.contains("session_id"))
        val system = payload["system"]!!.jsonPrimitive.content
        assertTrue(system.contains("禁止"))
        assertTrue(system.contains("健康"))
        val body = call.respond("{\"content\":[{\"type\":\"text\",\"text\":\"能陪在你身边，我很开心。\"}]}")
        runCurrent()
        assertEquals("能陪在你身边，我很开心。", pending.await())
        assertTrue(body.closed)
        assertEquals(0, call.executeCalls)
    }

    @Test fun `两秒超时实际cancel且迟到响应关闭不交付`() = runTest {
        LlmConfig.apiKey = "test-only-key"
        val factory = FakeFactory()
        val pending = async { withTimeoutOrNull(2_000) { AnthropicCompanionTextSource(factory).create(request()) } }
        runCurrent()
        assertEquals(1, factory.calls.size)
        advanceTimeBy(2_000)
        runCurrent()
        val call = factory.calls.single()
        assertTrue(call.isCanceled())
        assertNull(pending.await())
        val lateBody = call.respond("{\"content\":[{\"text\":\"迟到陪伴句。\"}]}")
        assertTrue(lateBody.closed)
    }

    @Test fun `调用者取消继续抛出取消且不吞成空结果`() = runTest {
        LlmConfig.apiKey = "test-only-key"
        val factory = FakeFactory()
        val pending = async { AnthropicCompanionTextSource(factory).create(request()) }
        runCurrent()
        assertEquals(1, factory.calls.size)
        pending.cancel()
        runCurrent()
        assertTrue(pending.isCancelled)
        assertTrue(factory.calls.single().isCanceled())
    }

    @Test fun `缺少密钥不请求网络`() = runTest {
        LlmConfig.apiKey = ""
        val factory = FakeFactory()
        assertNull(AnthropicCompanionTextSource(factory).create(request()))
        assertTrue(factory.calls.isEmpty())
    }

    @Test fun `公里请求复用真实摘要而不发送会话标识且HTTP失败关闭响应`() = runTest {
        LlmConfig.apiKey = "test-only-key"
        val factory = FakeFactory()
        val analysis = LlmAnalyzeRequest("private-session", 3,
            LlmSplitData(3, 1.0, 367, 367.0, 145, 170),
            LlmSessionSummary(3.0, 1100, 366.7))
        val request = SpeechRequest(RuleTrigger("km_split", Severity.NORMAL,
            "第三公里，用时六分零七秒，配速每公里六分零七秒。", SpeechPriority.SPLIT), true, analysis)
        val pending = async { AnthropicCompanionTextSource(factory).create(request) }
        runCurrent()
        val call = factory.calls.single()
        val payload = Buffer().also { call.request().body!!.writeTo(it) }.readUtf8()
        assertTrue(payload.contains("367"))
        assertTrue(payload.contains("split_data"))
        assertTrue(payload.contains("session_summary"))
        assertFalse(payload.contains("private-session"))
        val body = call.respond("测试错误响应正文不可进入诊断", 500)
        assertNull(pending.await())
        assertTrue(body.closed)
    }

    private fun request() = SpeechRequest(RuleTrigger("session_paused", Severity.NORMAL, "跑步已暂停。", SpeechPriority.APP), true)
    private class FakeFactory : Call.Factory {
        val calls = mutableListOf<FakeCall>()
        override fun newCall(request: Request): Call = FakeCall(request).also(calls::add)
    }
    private class FakeCall(private val request: Request) : Call {
        lateinit var callback: Callback
        var executeCalls = 0
        private var cancelled = false
        override fun request() = request
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun execute(): Response { executeCalls++; error("测试禁止同步请求") }
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = ::callback.isInitialized
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall(request)
        fun respond(text: String, code: Int = 200): TrackingBody {
            val body = TrackingBody(text)
            callback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("测试响应").body(body).build())
            return body
        }
    }
    private class TrackingBody(text: String) : ResponseBody() {
        private val buffer = Buffer().writeUtf8(text)
        var closed = false
        override fun contentType(): MediaType? = null
        override fun contentLength() = buffer.size
        override fun source(): BufferedSource = buffer
        override fun close() { closed = true; super.close() }
    }
}
