package com.healthyagent.running.agent.mcp

import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.coordinator.CommandOutcome
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 仅由本地工具启动；真正走 HTTPS、Room、正式 HTTP 与 Worker/DO，插件末端和模型使用合成替身。 */
@RunWith(AndroidJUnit4::class)
class McpWorkerJointTest {
    @Test fun readOnlyRoundTrip() = runBlocking { verify("read") }
    @Test fun approveActualArguments() = runBlocking { verify("approve") }
    @Test fun denyWithoutPluginDispatch() = runBlocking { verify("deny") }
    @Test fun lostReplyNeverReplays() = runBlocking { verify("lose_reply") }
    @Test fun cancelWhileAwaitingConfirmation() = runBlocking { verify("cancel") }
    @Test fun foregroundReturnDoesNotReviveWindow() = runBlocking { verify("background") }
    @Test fun privateRevocationDuringHandshake() = runBlocking { verify("revoke_private") }
    @Test fun cloudDisableDuringHandshake() = runBlocking { verify("disable_cloud") }
    @Test fun expiredConfirmationCannotBeApproved() = runBlocking { verify("expire") }
    @Test fun delayedNetworkRoundTrip() = runBlocking { verify("read_slow") }
    @Test fun delayedConfirmedNetworkRoundTrip() = runBlocking { verify("approve_slow") }
    @Test fun corruptCatalogCleanupWithLostReceipt() = runBlocking { verify("cleanup") }

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content
    private fun data(outcome: CommandOutcome): JsonObject {
        assertTrue("原生命令必须成功", outcome is CommandOutcome.AcceptedWithData)
        return (outcome as CommandOutcome.AcceptedWithData).data
    }

    private suspend fun verify(name: String) {
        val raw = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("只在明确的隔离联合夹具中运行", raw != null)
        val fixture = Json.parseToJsonElement(String(Base64.decode(raw!!, Base64.DEFAULT), Charsets.UTF_8)).jsonObject
        require(fixture.getValue("fixtureOnly") == JsonPrimitive(true))
        val origin = fixture.text("origin").also { require(it.matches(Regex("https://127\\.0\\.0\\.1:[0-9]{1,5}"))) }
        val current = fixture.getValue("cases").jsonArray.single { it.jsonObject.text("name") == name }.jsonObject
        val identity = CloudTaskIdentity(origin, current.text("ownerId"), current.text("deviceId"), "synthetic-joint-$name")
        val token = current.text("token")
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            Base64.decode(fixture.text("certificate"), Base64.DEFAULT).inputStream())
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        // 只信任本次临时证书；保留正常主机名验证、禁重试及单次网络提交标签。
        val http = CloudTaskHttpClient.clientBuilder().sslSocketFactory(tls.socketFactory, manager).build()
        val api = CloudTaskHttpClient(origin, CloudCredentialSource { checked ->
            require(checked == identity); CloudDeviceBearer(token)
        }, http)
        if (name == "cleanup") {
            try { verifyCleanup(current, identity, api) }
            finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
            return
        }
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AgentDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val methods = Collections.synchronizedList(mutableListOf<String>())
        val secret = "synthetic-plugin-$name"
        val files = mutableMapOf<String, ByteArray>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val config = McpPrivateConfiguration({ files[it] }, { file, bytes -> files[file] = bytes }, { key })
        val slot = McpPrivateSlot(identity, current.text("serviceId"))
        val endpoint = McpEndpoint.parse("https://tools.example.com/mcp")
        config.save(slot, config.status(slot).context, endpoint, McpBearer(secret))
        val confirmation = McpTaskConfirmation()
        val initialized = CompletableDeferred<Unit>()
        val releaseHandshake = CompletableDeferred<Unit>()
        val runner = McpTaskRunner({ identity }, config, { api }, { api }, confirmation::ask,
            exchangeFactory = { bound, authorize -> McpPhoneExchange(bound, config, authorize) { target, credentials, packet, dispatch ->
                val rpc = packet.body?.let { Json.parseToJsonElement(it).jsonObject }
                val method = rpc?.text("method") ?: packet.method
                dispatch {
                    assertEquals(endpoint.toString(), target.toString())
                    assertEquals(secret, credentials.read()?.value)
                    methods.add(method)
                }
                if (method == "initialize" && name in setOf("background", "revoke_private", "disable_cloud")) {
                    initialized.complete(Unit)
                    releaseHandshake.await()
                }
                val status = if (method == "DELETE") 204 else if (method.startsWith("notifications/")) 202 else 200
                val result = when (method) {
                    "initialize" -> buildJsonObject {
                        put("protocolVersion", "2025-11-25"); put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                        put("serverInfo", buildJsonObject { put("name", "synthetic"); put("version", "1") })
                    }
                    "tools/list" -> buildJsonObject { put("tools", JsonArray(listOf(fixture.getValue("definition")))) }
                    "tools/call" -> {
                        assertEquals(fixture.getValue("definition").jsonObject.getValue("name"), rpc!!.getValue("params").jsonObject.getValue("name"))
                        assertEquals(Json.parseToJsonElement(fixture.text("argumentsJson")), rpc.getValue("params").jsonObject.getValue("arguments"))
                        buildJsonObject { put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "手机合成工具结果") }))) }
                    }
                    "notifications/initialized", "DELETE" -> buildJsonObject {}
                    else -> error("出现未授权的合成 RPC")
                }
                val text = if (status == 200) buildJsonObject { put("jsonrpc", "2.0"); put("id", rpc!!.getValue("id")); put("result", result) }.toString() else ""
                McpHttpReply(status, text, "synthetic-session", false)
            } })
        val host = CloudAgentService(database, { identity }, { api }, scope, mcpRunner = runner)
        suspend fun call(command: String, body: JsonObject = buildJsonObject {}) = data(host.handle(command, body))
        suspend fun stats(): JsonObject = withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$origin/__fixture/stats").header("Authorization", "Bearer $token")
                .tag(AtomicBoolean::class.java, AtomicBoolean()).build()
            http.newCall(request).execute().use { response ->
                check(response.code == 200); Json.parseToJsonElement(response.body!!.string()).jsonObject
            }
        }
        suspend fun finished(): JsonObject = withTimeout(40000) {
            while (true) {
                val row = call("cloud.list").getValue("items").jsonArray.single().jsonObject
                if (!row.getValue("busy").jsonPrimitive.boolean) return@withTimeout row
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("未取得原任务终态")
        }
        try {
            host.setMcpForeground(true)
            val conversation = call("cloud.newConversation").getValue("conversationId")
            val preview = call("cloud.prepare", buildJsonObject {
                put("prompt", current.text("prompt")); put("conversationId", conversation)
                put("memoryRefs", JsonArray(emptyList())); put("mcpServices", JsonArray(listOf(JsonPrimitive(slot.serviceId))))
            })
            assertTrue(database.cloudIntentDao().page(identity, 0, 10).isEmpty())
            assertFalse(preview.toString().contains(secret))
            call("cloud.confirm", buildJsonObject { put("token", preview.getValue("token")); put("approved", true) })
            val intent = database.cloudIntentDao().page(identity, 0, 10).single()
            if (name in setOf("background", "revoke_private", "disable_cloud")) {
                withTimeout(15000) { initialized.await() }
                when (name) {
                    "background" -> { host.setMcpForeground(false); host.setMcpForeground(true) }
                    "revoke_private" -> config.revoke(slot, config.status(slot).context)
                    "disable_cloud" -> {
                        val service = api.getService(identity, slot.serviceId)
                        api.changeService(identity, slot.serviceId, "disable", buildJsonObject { put("revision", service.getValue("revision")) })
                    }
                }
                releaseHandshake.complete(Unit)
            }
            if (name in setOf("approve", "approve_slow", "deny", "cancel", "expire")) {
                val pending = withTimeout(15000) { confirmation.requesting.filterNotNull().first() }
                assertEquals("notes.add", pending.invocation.name)
                assertEquals(fixture.text("argumentsJson"), pending.invocation.argumentsJson)
                assertTrue(methods.isEmpty())
                if (name == "cancel") call("cloud.cancel", buildJsonObject { put("requestId", intent.clientRequestId) })
                else if (name == "expire") {
                    withTimeout(35000) { confirmation.requesting.first { it == null } }
                    assertFalse("到期的旧确认不能再次批准", confirmation.respond(pending, true))
                }
                else assertTrue(confirmation.respond(pending, name in setOf("approve", "approve_slow")))
            }
            val row = finished()
            assertEquals(when (name) { "cancel" -> "cancelled"; "expire" -> "timed_out"; else -> "completed" }, row.text("status"))
            if (name !in setOf("cancel", "expire")) assertEquals("合成联合回答", row.text("answer"))
            assertEquals(JsonNull, row.getValue("error"))
            val expected = when (name) {
                "deny", "cancel", "expire" -> emptyList()
                "background", "revoke_private", "disable_cloud" -> listOf("initialize")
                "lose_reply" -> listOf("initialize", "notifications/initialized", "tools/list", "tools/call")
                else -> listOf("initialize", "notifications/initialized", "tools/list", "tools/call", "DELETE")
            }
            assertEquals(expected, methods.toList())
            val saved = database.cloudIntentDao().receipt(identity, intent.clientRequestId)!!
            assertTrue(database.cloudIntentDao().find(identity, intent.clientRequestId)!!.submissionAttempted)
            assertFalse(saved.resultJson?.contains(secret) ?: false)
            val before = stats().filterKeys { it != "get" }
            repeat(2) {
                call("cloud.recover", buildJsonObject { put("requestId", intent.clientRequestId) })
                assertEquals(row.getValue("answer"), finished().getValue("answer"))
            }
            assertEquals("恢复不得补发提交、批准、插件交换或控制请求", before, stats().filterKeys { it != "get" })
            assertEquals(expected, methods.toList())
            assertNull(confirmation.requesting.value)
        } finally {
            host.setMcpForeground(false)
            scope.coroutineContext[Job]!!.cancelAndJoin()
            database.close(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }

    private suspend fun verifyCleanup(current: JsonObject, identity: CloudTaskIdentity, api: CloudTaskHttpClient) {
        val files = mutableMapOf<String, ByteArray>()
        val records = mutableMapOf<String, String>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val config = McpPrivateConfiguration({ files[it] }, { file, bytes -> files[file] = bytes }, { key })
        val journal = McpDiscoveryJournal({ records[it] }, { file, text -> records[file] = text })
        val discovery = McpDiscoveryService({ identity }, config, { api }, journal,
            exchangeFactory = { _, _ -> error("清理不得发起插件交换") })
        val host = McpManagementService({ identity }, config, { api }, discovery)
        val slot = McpPrivateSlot(identity, current.text("serviceId"))
        val other = McpPrivateSlot(identity, current.text("otherServiceId"))
        val endpoint = McpEndpoint.parse("https://tools.example.com/mcp")
        val staleContext = config.status(slot).context
        val saved = config.save(slot, staleContext, endpoint, McpBearer("synthetic-plugin-cleanup"))
        val otherSaved = config.save(other, config.status(other).context, endpoint, McpBearer("synthetic-plugin-other"))
        val requestId = UUID.randomUUID().toString()
        journal.begin(McpPhoneScope(identity, requestId, requestId, slot.serviceId, current.text("revision"), saved.generation!!, endpoint))
        val previousRecords = records.toMap()
        fun input(revision: String = current.text("revision"), context: String = saved.context) = buildJsonObject {
            put("identityToken", CloudChatConsent.context(identity)); put("id", slot.serviceId)
            put("revision", revision); put("context", context); put("confirmed", true)
        }
        suspend fun listed() = data(host.handle("mcp.list", buildJsonObject {
            put("identityToken", CloudChatConsent.context(identity)); put("after", JsonNull)
        })).getValue("services").jsonArray.map { it.jsonObject.text("id") }.toSet()
        assertEquals(setOf(slot.serviceId, other.serviceId), listed())
        assertTrue(host.handle("mcp.detail", buildJsonObject {
            put("identityToken", CloudChatConsent.context(identity)); put("id", slot.serviceId)
        }) is CommandOutcome.Rejected)
        assertTrue(host.handle("mcp.remove", input(revision = UUID.randomUUID().toString())) is CommandOutcome.Rejected)
        assertEquals(saved, config.status(slot))
        assertTrue(host.handle("mcp.remove", input(context = staleContext)) is CommandOutcome.Rejected)
        assertEquals(saved, config.status(slot))
        // Worker 实际删除成功后代理断开连接；原生只能报告未确认，不能伪造成功或自动补发。
        assertTrue(host.handle("mcp.remove", input()) is CommandOutcome.Rejected)
        assertFalse(config.status(slot).configured)
        repeat(2) { assertEquals(setOf(other.serviceId), listed()) }
        assertEquals(otherSaved, config.status(other))
        assertEquals(previousRecords, records)
        val status = data(host.handle("mcp.status", buildJsonObject {}))
        assertEquals(requestId, status.getValue("operations").jsonArray.single().jsonObject.text("requestId"))
        assertFalse(status.toString().contains("synthetic-plugin-"))
    }
}
