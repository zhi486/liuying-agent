package com.healthyagent.running.agent.cloud

import com.healthyagent.running.agent.memory.MemoryContextState
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ConversationSummaryHttpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ConversationSummaryHttpClient
    private lateinit var identity: CloudTaskIdentity
    private val headersReceived = CountDownLatch(1)
    private val id = UUID.randomUUID().toString()
    private val hash = "a".repeat(64)
    private val candidate = ConversationSummaryPolicy.Candidate(hash, listOf(
        ConversationSummaryPolicy.Turn(UUID.randomUUID().toString(), "合成问题", "合成回答", MemoryContextState())
    ), emptySet())
    private val snapshot = ConversationSummaryProtocol.Snapshot(id, hash,
        ConversationSummaryProtocol.State.ATTEMPTED, ConversationSummaryProtocol.Cost.UNCERTAIN)
    private fun step(action: ConversationSummaryProtocol.Action) = ConversationSummaryProtocol.Step(snapshot, action)
    private fun receipt(state: String = "completed", finished: Boolean = true): JsonObject = buildJsonObject {
        put("version", "summary_v1"); put("purpose", "conversation_summary")
        put("requestId", id); put("fingerprint", hash); put("state", state); put("remoteFinished", finished)
        put("summary", if (state == "completed") JsonPrimitive("合成摘要") else JsonNull)
        put("cost", "usage"); put("usage", buildJsonObject { put("inputTokens", 7); put("outputTokens", 2) })
    }
    private fun reply(value: JsonObject) = MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())

    @Before fun setUp() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply {
            useHttps(serverTls.sslSocketFactory(), false)
            start(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0)
        }
        identity = CloudTaskIdentity("https://127.0.0.1:${server.port}", "owner", "device", "generation")
        client = ConversationSummaryHttpClient(CloudCredentialSource { CloudDeviceBearer("lyd_" + "a".repeat(64)) },
            CloudTaskHttpClient.clientBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .eventListener(object : okhttp3.EventListener() {
                    override fun responseHeadersEnd(call: okhttp3.Call, response: okhttp3.Response) { headersReceived.countDown() }
                })
                .callTimeout(3, TimeUnit.SECONDS).build())
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun 独立请求只发送候选来源且查询不再发送正文() = runBlocking {
        server.enqueue(reply(receipt()))
        val received = client.execute(identity, step(ConversationSummaryProtocol.Action.SUBMIT), candidate)
        assertEquals("合成摘要", received.acceptedSummary(candidate))
        assertNull(received.acceptedSummary(null))
        val changed = ConversationSummaryPolicy.Candidate("b".repeat(64), candidate.sources, emptySet())
        assertNull(received.acceptedSummary(changed))
        assertTrue(received.remoteFinished)
        assertEquals(7L, received.usage?.inputTokens)
        val submitted = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/agent/summaries", submitted.path)
        assertEquals("POST", submitted.method)
        assertEquals("Bearer lyd_" + "a".repeat(64), submitted.getHeader("Authorization"))
        val body = Json.parseToJsonElement(submitted.body.readUtf8()).jsonObject
        assertEquals(setOf("version", "requestId", "fingerprint", "approved", "sources"), body.keys)
        assertEquals(id, body["requestId"]!!.jsonPrimitive.content)
        assertEquals("合成问题", body["sources"]!!.jsonArray.single().jsonObject["prompt"]!!.jsonPrimitive.content)
        server.enqueue(reply(receipt("withdrawn", false)))
        val withdrawn = client.execute(identity, step(ConversationSummaryProtocol.Action.QUERY), null)
        assertFalse(withdrawn.remoteFinished); assertNull(withdrawn.acceptedSummary(candidate))
        val query = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", query.method); assertEquals("/v1/agent/summaries/$id", query.path)
        assertEquals(0L, query.bodySize)
        assertEquals(2, server.requestCount)
    }

    @Test fun 提交失联不重试后续只查询原请求() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertNotNull(runCatching { client.execute(identity, step(ConversationSummaryProtocol.Action.SUBMIT), candidate) }.exceptionOrNull())
        assertEquals(1, server.requestCount)
        server.enqueue(reply(receipt()))
        client.execute(identity, step(ConversationSummaryProtocol.Action.QUERY), null)
        assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
        assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
        assertEquals(2, server.requestCount)
    }

    @Test fun 非法回执身份数值及状态组合均拒绝() {
        val valid = receipt()
        val invalid = listOf(
            valid + ("requestId" to JsonPrimitive(UUID.randomUUID().toString())),
            valid + ("fingerprint" to JsonPrimitive("b".repeat(64))),
            valid + ("purpose" to JsonPrimitive("chat")),
            valid + ("remoteFinished" to JsonPrimitive(false)),
            valid + ("sources" to JsonArray(emptyList())),
            valid + ("usage" to JsonNull),
            valid + ("state" to JsonPrimitive("withdrawn")),
        ) + listOf(JsonPrimitive("7"), JsonPrimitive(-1), JsonPrimitive(1.5), JsonPrimitive(9007199254740992L)).map {
            valid + ("usage" to buildJsonObject { put("inputTokens", it); put("outputTokens", 2) })
        }
        for (value in invalid) assertNotNull(runCatching {
            ConversationSummaryHttpClient.parse(JsonObject(value).toString(), id, hash)
        }.exceptionOrNull())
        val unknown = receipt("withdrawn", false).toMutableMap().apply {
            put("usage", JsonNull); put("cost", JsonPrimitive("uncertain"))
        }
        assertNull(ConversationSummaryHttpClient.parse(JsonObject(unknown).toString(), id, hash).usage)
    }

    @Test fun 重定向错误状态及媒体类型拒绝且不重试() = runBlocking {
        val responses = listOf(MockResponse().setResponseCode(302).setHeader("Location", server.url("/wrong")),
            MockResponse().setResponseCode(401), MockResponse().setResponseCode(503),
            MockResponse().setHeader("Content-Type", "text/html").setBody("秘密"))
        for (response in responses) {
            server.enqueue(response)
            val failure = runCatching { client.execute(identity, step(ConversationSummaryProtocol.Action.QUERY), null) }.exceptionOrNull()
            assertEquals("摘要服务暂不可用；仅查询原请求", failure?.message)
        }
        assertEquals(responses.size, server.requestCount)
    }

    @Test fun 撤回回执保留远端结束证据且绑定错误拒绝入账() {
        for (finished in listOf(false, true)) {
            val receipt = ConversationSummaryHttpClient.parse(receipt("withdrawn", finished).toString(), id, hash)
            val result = receipt.result(id, hash, candidate) as ConversationSummaryProtocol.Result.Withdrawn
            assertEquals(finished, result.remoteFinished)
            assertEquals(7L, result.usage?.inputTokens)
            assertNotNull(runCatching { receipt.result(UUID.randomUUID().toString(), hash, candidate) }.exceptionOrNull())
        }
        val completed = ConversationSummaryHttpClient.parse(receipt().toString(), id, hash)
        assertTrue(completed.result(id, hash, null) is ConversationSummaryProtocol.Result.Failed)
        assertTrue(completed.result(id, hash, candidate) is ConversationSummaryProtocol.Result.Completed)
    }

    @Test fun 取消等待不重试() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async { client.execute(identity, step(ConversationSummaryProtocol.Action.QUERY), null) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled); assertEquals(1, server.requestCount)
    }

    @Test fun 收到响应头后取消慢正文无需等待请求超时() = runBlocking {
        server.enqueue(reply(receipt()).throttleBody(1, 1, TimeUnit.SECONDS))
        val pending = async { client.execute(identity, step(ConversationSummaryProtocol.Action.QUERY), null) }
        withContext(Dispatchers.IO) { assertTrue(headersReceived.await(2, TimeUnit.SECONDS)) }
        withTimeout(1500) { pending.cancelAndJoin() }
        assertTrue(pending.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun 超长错误编码及压缩正文拒绝且不重试() = runBlocking {
        val responses = listOf(
            MockResponse().setHeader("Content-Type", "application/json").setBody("x".repeat(20001)),
            MockResponse().setHeader("Content-Type", "application/json")
                .setChunkedBody("x".repeat(20001), 1000),
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody(okio.Buffer().write(byteArrayOf(0xc3.toByte(), 0x28))),
            reply(receipt()).setHeader("Content-Type", "application/json; charset=iso-8859-1"),
            reply(receipt()).setHeader("Content-Encoding", "gzip"),
        )
        for (response in responses) {
            server.enqueue(response)
            val failure = runCatching { client.execute(identity, step(ConversationSummaryProtocol.Action.QUERY), null) }.exceptionOrNull()
            assertEquals("摘要服务暂不可用；仅查询原请求", failure?.message)
        }
        assertEquals(responses.size, server.requestCount)
    }
}
