package com.healthyagent.running.agent

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryToolConsent
import com.healthyagent.running.coordinator.CommandOutcome
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 隔离模拟器经真实 HTTPS 连接本地 Worker；禁止读取用户配置或调用外部模型。 */
@RunWith(AndroidJUnit4::class)
class StreamWorkerJointTest {
    @Test fun memoryToolRoundClearsDraftAndCommitsOnlyFinalAnswer() = runBlocking { verify("tools") }
    @Test fun brokenTcpRecoversSameCursorWithoutResubmitting() = runBlocking { verify("disconnect") }
    @Test fun withdrawalDiscardsLateProviderText() = runBlocking { verify("withdraw") }
    @Test fun revokedMemoryGenerationHidesDraftAndRejectsLateWrite() = runBlocking { verify("revoke") }
    @Test fun truncatedProviderCannotCommitDraftAsAnswer() = runBlocking { verify("truncated") }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private suspend fun verify(name: String) {
        val raw = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("只能由隔离联合夹具启动", raw != null)
        val fixture = Json.parseToJsonElement(String(Base64.decode(raw!!, Base64.DEFAULT), Charsets.UTF_8)).jsonObject
        require(fixture["fixtureOnly"] == JsonPrimitive(true))
        val origin = fixture.text("origin").also { require(it.matches(Regex("https://127\\.0\\.0\\.1:[0-9]{1,5}"))) }
        val current = fixture.getValue("cases").jsonArray.single { it.jsonObject.text("name") == name }.jsonObject
        val identity = CloudTaskIdentity(origin, current.text("ownerId"), current.text("deviceId"), "synthetic-stream-$name")
        val token = current.text("token")
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            Base64.decode(fixture.text("certificate"), Base64.DEFAULT).inputStream())
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        // 仅信任本次夹具证书，仍核对主机名并保留生产禁重试策略。
        val http = CloudTaskHttpClient.clientBuilder().sslSocketFactory(tls.socketFactory, manager).build()
        val api = CloudTaskHttpClient(origin, CloudCredentialSource { checked ->
            require(checked == identity); CloudDeviceBearer(token)
        }, http, true)
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AgentDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var storedConsent: String? = null
        val consent = MemoryToolConsent({ storedConsent }, { storedConsent = it })
        val host = CloudAgentService(database, { identity }, { api }, scope, memoryConsent = consent)
        suspend fun call(command: String, body: JsonObject = buildJsonObject {}): JsonObject {
            val outcome = host.handle(command, body)
            assertTrue("原生命令 $command 必须成功", outcome is CommandOutcome.AcceptedWithData)
            return (outcome as CommandOutcome.AcceptedWithData).data
        }
        suspend fun fixtureCall(path: String): JsonObject = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("$origin/__fixture/$path").header("Authorization", "Bearer $token")
                .tag(AtomicBoolean::class.java, AtomicBoolean()).build())
                .execute().use {
                    check(it.code == 200); Json.parseToJsonElement(it.body!!.string()).jsonObject
                }
        }
        suspend fun item() = call("cloud.list").getValue("items").jsonArray.single().jsonObject
        suspend fun until(check: suspend () -> Boolean) = withTimeout(18000) { while (!check()) delay(25) }
        suspend fun enable(value: Boolean) = call("cloud.memoryConsent", buildJsonObject {
            put("identityToken", consent.context(identity)); put("enabled", value)
        })
        try {
            host.setMcpForeground(true)
            if (name in setOf("tools", "revoke")) enable(true)
            val conversation = call("cloud.newConversation").getValue("conversationId")
            val preview = call("cloud.prepare", buildJsonObject {
                put("conversationId", conversation); put("prompt", "我喜欢夜跑"); put("memoryRefs", JsonArray(emptyList()))
            })
            val requestId = preview.text("token")
            call("cloud.confirm", buildJsonObject { put("token", requestId); put("approved", true) })
            val initial = "合成首段🙂"
            until { item()["draftAnswer"] == JsonPrimitive(initial) }
            assertNull("正式回执不能提前含候选", database.cloudIntentDao().receipt(identity, requestId)!!.resultJson)
            assertEquals(1, fixtureCall("stats").getValue("models").jsonPrimitive.int)

            when (name) {
                "tools" -> {
                    fixtureCall("tool")
                    until { fixtureCall("stats").getValue("models").jsonPrimitive.int == 2 }
                    until { item()["draftAnswer"] == null }
                    fixtureCall("answer")
                    until { item()["draftAnswer"] == JsonPrimitive("合成最终首段") }
                    assertEquals(1, database.memoryDao().count("local"))
                    assertNull(database.cloudIntentDao().receipt(identity, requestId)!!.resultJson)
                }
                "disconnect" -> {
                    fixtureCall("cut")
                    until { item()["busy"] == JsonPrimitive(false) }
                    assertNull(item()["draftAnswer"])
                    val stopped = fixtureCall("stats")
                    assertEquals(1, stopped.getValue("cursors").jsonArray.size)
                    assertTrue(stopped.getValue("failedGets").jsonPrimitive.int > 0)
                    fixtureCall("restore")
                    call("cloud.recover", buildJsonObject { put("requestId", requestId) })
                    until { fixtureCall("stats").getValue("cursors").jsonArray.size == 2 }
                    assertEquals(listOf(0, 2), fixtureCall("stats").getValue("cursors").jsonArray.map { it.jsonPrimitive.int })
                    // 续订建立后先只显示已有前缀，不因相同文字猜测去重。
                    until { item()["draftAnswer"] == JsonPrimitive(initial) }
                }
                "withdraw" -> {
                    call("cloud.withdraw", buildJsonObject { put("requestId", requestId) })
                    assertNull(item()["draftAnswer"])
                    // 本机隐藏立即生效，云端确认是异步的；确认之后再释放迟到模型正文。
                    until { item()["confirmedControl"] == JsonPrimitive("withdraw") && item()["busy"] == JsonPrimitive(false) }
                }
                "revoke" -> {
                    val generation = consent.current(identity)!!.generation
                    enable(false); enable(true)
                    assertNotEquals(generation, consent.current(identity)!!.generation)
                    assertNull(item()["draftAnswer"])
                    fixtureCall("tool")
                    until { fixtureCall("stats").getValue("models").jsonPrimitive.int == 2 }
                    assertEquals(0, database.memoryDao().count("local"))
                    assertNull(item()["draftAnswer"])
                    fixtureCall("answer")
                }
            }
            fixtureCall("finish")
            until { item()["busy"] == JsonPrimitive(false) }
            val row = item()
            val expectedStatus = when (name) { "withdraw" -> "cancelled"; "truncated" -> "failed"; else -> "completed" }
            assertEquals(expectedStatus, row.text("status"))
            assertNull(row["draftAnswer"])
            if (name in setOf("withdraw", "truncated")) {
                assertEquals(JsonNull, row["answer"])
                assertFalse(database.cloudIntentDao().receipt(identity, requestId)?.resultJson?.contains(initial) == true)
            } else if (name != "revoke") {
                val answer = if (name == "tools") "合成最终首段，完整尾句。" else "$initial，完整尾句。"
                assertEquals(JsonPrimitive(answer), row["answer"])
                if (name == "tools") assertFalse(database.cloudIntentDao().receipt(identity, requestId)!!.resultJson!!.contains(initial))
            }
            val before = fixtureCall("stats")
            call("cloud.recover", buildJsonObject { put("requestId", requestId) })
            until { item()["busy"] == JsonPrimitive(false) }
            val after = fixtureCall("stats")
            assertEquals(1, after.getValue("submits").jsonPrimitive.int)
            assertEquals(before["models"], after["models"])
            assertEquals(before["memoryReplies"], after["memoryReplies"])
            assertEquals(before["cursors"], after["cursors"])
            if (name != "revoke") {
                val followup = call("cloud.prepare", buildJsonObject {
                    put("conversationId", conversation); put("prompt", "后续合成问题"); put("memoryRefs", JsonArray(emptyList()))
                }).getValue("conversation").jsonObject.getValue("messages").jsonArray
                if (name in setOf("withdraw", "truncated")) assertTrue(followup.isEmpty())
                else {
                    assertEquals(2, followup.size)
                    assertEquals(row["answer"], followup.last().jsonObject["text"])
                }
            }
        } finally {
            host.closeStreams(); scope.cancel(); scope.coroutineContext[Job]!!.join(); database.close()
        }
    }
}
