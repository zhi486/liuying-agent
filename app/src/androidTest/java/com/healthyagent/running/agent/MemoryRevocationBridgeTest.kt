package com.healthyagent.running.agent

import android.os.Build
import android.util.Base64
import android.webkit.WebView
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryToolConsent
import com.healthyagent.running.bridge.BridgeSession
import com.healthyagent.running.coordinator.AppSnapshot
import com.healthyagent.running.coordinator.CommandOutcome
import com.healthyagent.running.core.model.RunConfig
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 仅隔离模拟器：真实 WebMessage／Bridge／Room 与注入助手联合验证，不连接外部网络。 */
@RunWith(AndroidJUnit4::class)
class MemoryRevocationBridgeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val origin = "https://appassets.androidplatform.net"
    private val prompt = "我的专业是什么？请只检索已保存的专业，不保存或修改任何记忆。"

    @Test fun acceptedConfirmationQueuesRevocationAfterNativeCommit() = runBlocking { verify(false, false) }
    @Test fun acceptedSustainedSendQueuesRevocationAfterNativeCommit() = runBlocking { verify(true, false) }
    @Test fun expiredConfirmationDoesNotRevokeOrSubmit() = runBlocking { verify(false, true) }

    private suspend fun verify(sustained: Boolean, expired: Boolean) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "禁止在真实手机运行" }
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("isolatedProbe") == "true") { "必须由隔离驱动启动" }
        val source = String(Base64.decode(checkNotNull(arguments.getString("probeSource")), Base64.DEFAULT), Charsets.UTF_8)
        check(source.startsWith("// 用途：") && source.contains("export function installMemoryRevocationProbe"))
        val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AgentDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val clock = AtomicLong(1000)
        val identity = CloudTaskIdentity("https://synthetic.invalid", "synthetic-owner", "synthetic-device", "synthetic-credential")
        var stored: String? = null
        var chatStored: String? = null
        val consent = MemoryToolConsent({ stored }, { stored = it })
        val chat = CloudChatConsent({ chatStored }, { chatStored = it })
        val submitted = CompletableDeferred<CloudTaskIntent>()
        val release = CompletableDeferred<Unit>()
        val received = CompletableDeferred<JsonObject>()
        val submits = AtomicInteger()
        val replies = AtomicInteger()
        val controls = AtomicInteger()
        val commands = Collections.synchronizedList(mutableListOf<String>())
        val cloudId = UUID.randomUUID().toString()
        var generation: String? = null
        fun finished(state: String = "available") = CloudTaskResponse(cloudId, "completed", null, 1000, 31000, 2000, state,
            if (state == "available") buildJsonObject {
                put("status", "completed"); put("answer", "合成工具已拒绝"); put("actions", JsonArray(emptyList()))
            } else null)
        val api = object : CloudTaskApi {
            override suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse = error("不能退化为无记忆的新提交")
            override suspend fun submitMemory(intent: CloudTaskIntent, context: JsonObject?, generation: String, factsAllowed: Boolean): CloudTaskResponse {
                submits.incrementAndGet()
                assertNull(context)
                assertFalse(factsAllowed)
                submitted.complete(intent)
                release.await()
                return CloudTaskResponse(cloudId, "running", null, 1000, 31000, 1000, "none", null)
            }
            override suspend fun memory(identity: CloudTaskIdentity, taskId: String): CloudMemoryTool =
                CloudMemoryTool(submitted.await().clientRequestId, taskId, checkNotNull(generation), "search_1", "memory.search",
                    buildJsonObject { put("query", "专业") })
            override suspend fun replyMemory(identity: CloudTaskIdentity, tool: CloudMemoryTool, observation: JsonObject) {
                replies.incrementAndGet()
                received.complete(observation)
            }
            override suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse = error("不得补发或查其他请求")
            override suspend fun get(identity: CloudTaskIdentity, taskId: String) = finished()
            override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse {
                assertEquals("acknowledge", action)
                controls.incrementAndGet()
                return finished("acknowledged")
            }
        }
        val host = CloudAgentService(database, { identity }, { api }, scope, now = clock::get, memoryConsent = consent, chatConsent = chat)
        var web: WebView? = null
        try {
            val memory = database.memoryDao().create("local", UUID.randomUUID().toString(), "我的专业是流萤记忆验收工程", 500)
            suspend fun call(name: String, body: JsonObject = buildJsonObject {}): JsonObject =
                (host.handle(name, body) as CommandOutcome.AcceptedWithData).data
            call("cloud.memoryConsent", buildJsonObject {
                put("identityToken", consent.context(identity)); put("enabled", true); put("factsAllowed", false)
            })
            generation = checkNotNull(consent.current(identity)).generation
            val context = consent.context(identity)
            val conversation = call("cloud.newConversation").getValue("conversationId").jsonPrimitive.content
            lateinit var session: BridgeSession
            var proxy: JavaScriptReplyProxy? = null
            instrumentation.runOnMainSync {
                val view = WebView(instrumentation.targetContext)
                web = view
                view.settings.javaScriptEnabled = true
                view.settings.blockNetworkLoads = true
                session = BridgeSession("synthetic-probe", { AppSnapshot.initial(RunConfig(), 1000) }, { name, body ->
                    commands.add(name)
                    host.handle(name, body)
                }, { message -> proxy?.postMessage(message) })
                WebViewCompat.addWebMessageListener(view, "LiuyingNative", setOf(origin)) { _, message, sourceOrigin, mainFrame, reply ->
                    check(mainFrame && sourceOrigin.toString() == origin)
                    proxy = reply
                    scope.launch { session.onMessage(checkNotNull(message.data)) }
                }
                view.loadDataWithBaseURL(origin + "/probe", "<!doctype html><title>隔离撤权验证</title>", "text/html", "UTF-8", null)
            }
            val view = checkNotNull(web)
            until("页面来源") { Json.parseToJsonElement(evaluate(view, "location.origin")).jsonPrimitive.content == origin }
            evaluate(view, """
                window.pending = new Map(); window.helloId = crypto.randomUUID();
                LiuyingNative.onmessage = event => {
                    const value = JSON.parse(event.data);
                    if (value.type === 'ready') window.ready = true;
                    if (value.type === 'result') { window.pending.get(value.id)?.(value); window.pending.delete(value.id); }
                };
                window.command = (name, body) => new Promise(resolve => {
                    const id = crypto.randomUUID(); window.pending.set(id, resolve);
                    LiuyingNative.postMessage(JSON.stringify({contractVersion:1, type:'command', id, sentAt:new Date().toISOString(),
                        payload:{helloId:window.helloId, name, body}}));
                });
                LiuyingNative.postMessage(JSON.stringify({contractVersion:1, type:'hello', id:window.helloId,
                    sentAt:new Date().toISOString(), payload:{buildId:'synthetic', supportedContractVersions:[1]}}));
            """.trimIndent())
            until("真实 Bridge 握手") { evaluate(view, "window.ready === true") == "true" }
            val options = buildJsonObject {
                put("authorizedOneShot", true); put("prompt", prompt); put("context", context)
                put("cloudOrigin", identity.origin); put("durationMs", 60000)
            }
            evaluate(view, source.replaceFirst("export function", "function") + "\nwindow.probe = installMemoryRevocationProbe(window, $options);")
            evaluate(view, "window.command('cloud.prepare', {prompt:${JSONObject.quote(prompt)}, conversationId:${JSONObject.quote(conversation)}, memoryRefs:[]}).then(v => window.preview = v);")
            until("助手接受原生预览") { evaluate(view, "window.probe.snapshot().phase") == JSONObject.quote("preview_ready") }
            val requestId = Json.parseToJsonElement(evaluate(view, "window.preview.payload.data.token")).jsonPrimitive.content
            assertNull("预览不能提前保存任务", database.cloudIntentDao().find(identity, requestId))
            assertEquals(listOf("cloud.prepare"), commands.toList())
            if (expired) clock.addAndGet(120001)
            val name = if (sustained) "cloud.send" else "cloud.confirm"
            val body = if (sustained) "{token:window.preview.payload.data.token}" else "{token:window.preview.payload.data.token, approved:true}"
            evaluate(view, "window.command('$name', $body).then(v => window.confirmation = v);")
            val expected = if (expired) "confirmation_unconfirmed" else "revoked"
            until("助手终态 $expected") { evaluate(view, "window.probe.snapshot().phase") == JSONObject.quote(expected) }
            if (expired) {
                assertEquals(JSONObject.quote("rejected"), evaluate(view, "window.confirmation.payload.status"))
                assertEquals("false", evaluate(view, "window.probe.snapshot().confirmed"))
                assertNotNull(consent.current(identity))
                assertNull(database.cloudIntentDao().find(identity, requestId))
                assertEquals(0, submits.get())
                assertEquals(listOf("cloud.prepare", name), commands.toList())
            } else {
                assertEquals("true", evaluate(view, "window.probe.snapshot().confirmed"))
                assertEquals(JSONObject.quote(requestId), evaluate(view, "window.probe.snapshot().requestId"))
                assertEquals(listOf("cloud.prepare", name, "cloud.memoryConsent"), commands.toList())
                assertNull(consent.current(identity))
                assertNotEquals(context, consent.context(identity))
                val row = checkNotNull(database.cloudIntentDao().find(identity, requestId))
                assertEquals(prompt, row.prompt)
                assertEquals(generation, database.memoryToolBindingDao().find(requestId)!!.grantGeneration)
                assertFalse(database.memoryToolBindingDao().find(requestId)!!.factsAllowed)
                withTimeout(5000) { submitted.await() }
                release.complete(Unit)
                assertEquals(buildJsonObject { put("status", "rejected"); put("reason", "consent_required") },
                    withTimeout(5000) { received.await() })
                until("旧代次工具拒绝后任务结束") { call("cloud.list").getValue("items").jsonArray.single().jsonObject["busy"] == JsonPrimitive(false) }
                assertEquals(1, submits.get()); assertEquals(1, replies.get()); assertEquals(1, controls.get())
                assertTrue(database.memoryContextDependenciesDao().find(requestId)!!.state().refs.isEmpty())
            }
            assertEquals(listOf(memory), database.memoryDao().page("local", 0, 20).items)
            val before = commands.toList()
            evaluate(view, "window.probe.stop(); window.probe.stop();")
            assertEquals(before, commands.toList())
            call("cloud.memoryConsent", buildJsonObject {
                put("identityToken", consent.context(identity)); put("enabled", false); put("factsAllowed", false)
            })
            assertNull(consent.current(identity))
        } finally {
            instrumentation.runOnMainSync { web?.destroy() }
            scope.cancel(); scope.coroutineContext[Job]!!.join()
            database.close()
        }
    }

    private suspend fun until(stage: String, check: suspend () -> Boolean) {
        try { withTimeout(10000) { while (!check()) delay(20) } }
        catch (failure: TimeoutCancellationException) { throw AssertionError("等待阶段失败：$stage", failure) }
    }
    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("WebView 回调超时", latch.await(5, TimeUnit.SECONDS))
        return result
    }
}
