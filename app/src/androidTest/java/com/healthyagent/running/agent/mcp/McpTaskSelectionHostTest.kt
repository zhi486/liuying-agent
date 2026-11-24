package com.healthyagent.running.agent.mcp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.coordinator.CommandOutcome
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** 暂停派发调度器，只用合成云目录；不连接插件、模型或真实手机。 */
@RunWith(AndroidJUnit4::class)
class McpTaskSelectionHostTest {
    private val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
    private val id = "11111111-1111-4111-8111-111111111111"
    private val revision = "22222222-2222-4222-8222-222222222222"
    private val endpoint = "https://tools.example.com/mcp"
    private val files = mutableMapOf<String, ByteArray>()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val privateConfig = McpPrivateConfiguration({ files[it] }, { name, bytes -> files[name] = bytes }, { key })
    private val slot = McpPrivateSlot(identity, id)
    private lateinit var database: AgentDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var host: CloudAgentService
    private var chatStored: String? = null
    private val chatConsent = CloudChatConsent({ chatStored }, { chatStored = it })
    private var service = buildJsonObject {
        put("id", id); put("revision", revision); put("endpoint", endpoint); put("enabled", true)
        put("catalog", buildJsonObject { put("fingerprint", "a".repeat(64)); put("tools", JsonArray(listOf(buildJsonObject {
            put("name", "notes.search"); put("definition", buildJsonObject { put("name", "notes.search"); put("inputSchema", buildJsonObject { put("type", "object") }) })
        }))) })
        put("grants", JsonArray(listOf(buildJsonObject { put("name", "notes.search"); put("mode", "read"); put("modelName", "mcp_${id.replace("-", "")}_0") })))
    }
    private val management = object : McpManagementApi {
        override suspend fun getService(identity: CloudTaskIdentity, id: String, summaryOnly: Boolean): JsonObject {
            check(!summaryOnly)
            return service
        }
        override suspend fun listServices(identity: CloudTaskIdentity, after: String?): JsonObject = error("不得发现其他服务")
        override suspend fun changeService(identity: CloudTaskIdentity, id: String?, action: String, body: JsonObject): JsonObject = error("不得更改权限")
    }
    private val api = object : CloudTaskApi {
        override suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse = error("不得派发")
        override suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse = error("不得查询")
        override suspend fun get(identity: CloudTaskIdentity, taskId: String): CloudTaskResponse = error("不得查询")
        override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse = error("不得控制")
    }
    private fun data(outcome: CommandOutcome) = (outcome as CommandOutcome.AcceptedWithData).data
    private suspend fun prepare(): JsonObject {
        val conversation = database.conversationDao().create(identity, 1000)
        return data(host.handle("cloud.prepare", buildJsonObject {
            put("prompt", "查看今晚的安排"); put("conversationId", conversation.id); put("memoryRefs", JsonArray(emptyList()))
            put("mcpServices", JsonArray(listOf(JsonPrimitive(id))))
        }))
    }
    private suspend fun send(preview: JsonObject, sustained: Boolean = false) = host.handle(if (sustained) "cloud.send" else "cloud.confirm", buildJsonObject {
        put("token", preview.getValue("token")); if (!sustained) put("approved", true)
    })
    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AgentDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        privateConfig.save(slot, privateConfig.status(slot).context, McpEndpoint.parse(endpoint), McpBearer("synthetic-only"))
        val runner = McpTaskRunner({ identity }, privateConfig, { error("不得启动执行器") }, { management }, { error("不得确认调用") })
        runner.setForeground(true)
        host = CloudAgentService(database, { identity }, { api }, scope, now = { 1000 }, chatConsent = chatConsent, mcpRunner = runner)
    }
    @After fun teardown() { scope.cancel(); database.close() }

    @Test fun 预览不保存任务确认后只保存原生版本引用() = runBlocking {
        val preview = prepare()
        val plugin = preview.getValue("mcpServices").jsonArray.single().jsonObject
        assertEquals(setOf("id", "endpoint", "tools"), plugin.keys)
        assertFalse(preview.toString().contains("synthetic-only"))
        assertTrue(database.cloudIntentDao().page(identity, 0, 20).isEmpty())
        assertTrue(send(preview) is CommandOutcome.AcceptedWithData)
        val intent = database.cloudIntentDao().page(identity, 0, 20).single()
        assertFalse(intent.submissionAttempted)
        val config = requireNotNull(intent.mcpTools()).wire().toString()
        assertTrue(config.contains(revision)); assertTrue(config.contains(privateConfig.status(slot).generation!!))
        assertFalse(config.contains(endpoint)); assertFalse(config.contains("synthetic-only"))
    }
    @Test fun 本机撤销或云版本变化使旧预览不能提交() = runBlocking {
        val first = prepare()
        privateConfig.revoke(slot, privateConfig.status(slot).context)
        assertTrue(send(first) is CommandOutcome.Rejected)
        privateConfig.save(slot, privateConfig.status(slot).context, McpEndpoint.parse(endpoint), null)
        val second = prepare()
        service = JsonObject(service + ("revision" to JsonPrimitive("33333333-3333-4333-8333-333333333333")))
        assertTrue(send(second) is CommandOutcome.Rejected)
        assertTrue(database.cloudIntentDao().page(identity, 0, 20).isEmpty())
    }
    @Test fun 普通聊天持续授权不能绕过本次插件确认() = runBlocking {
        chatConsent.set(identity, CloudChatConsent.context(identity), true)
        assertTrue(send(prepare(), sustained = true) is CommandOutcome.Rejected)
        assertTrue(database.cloudIntentDao().page(identity, 0, 20).isEmpty())
    }
}
