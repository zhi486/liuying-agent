package com.healthyagent.running.agent

import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.MainActivity
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryService
import com.healthyagent.running.agent.memory.MemoryToolConsent
import com.healthyagent.running.coordinator.CommandOutcome
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.data.health.*
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** 仅隔离模拟器：APK 页面和真实 Bridge 发送合成任务，经本地 HTTPS／Worker 返回流式内容。 */
@RunWith(AndroidJUnit4::class)
class StreamWebViewJointTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun toolRoundReplacesDraftAndKeyboardRemainsUsable() = runBlocking { verify("tools") }
    @Test fun backgroundClearsDraftAndExplicitRecoveryDoesNotResubmit() = runBlocking { verify("background") }

    private suspend fun verify(name: String) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "禁止在真实手机运行" }
        val raw = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue("只能由本地联合夹具启动", raw != null)
        val fixture = Json.parseToJsonElement(String(Base64.decode(raw!!, Base64.DEFAULT), Charsets.UTF_8)).jsonObject
        require(fixture["fixtureOnly"] == JsonPrimitive(true))
        fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
        val origin = fixture.text("origin").also { require(it.matches(Regex("https://127\\.0\\.0\\.1:[0-9]{1,5}"))) }
        val current = fixture.getValue("cases").jsonArray.single { it.jsonObject.text("name") == name }.jsonObject
        val identity = CloudTaskIdentity(origin, current.text("ownerId"), current.text("deviceId"), "synthetic-webview-$name")
        val token = current.text("token")
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            Base64.decode(fixture.text("certificate"), Base64.DEFAULT).inputStream())
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        val http = CloudTaskHttpClient.clientBuilder().sslSocketFactory(tls.socketFactory, manager).build()
        val api = CloudTaskHttpClient(origin, CloudCredentialSource { checked -> require(checked == identity); CloudDeviceBearer(token) }, http, true)
        val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AgentDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var stored: String? = null
        val consent = MemoryToolConsent({ stored }, { stored = it })
        val host = CloudAgentService(database, { identity }, { api }, scope, memoryConsent = consent)
        suspend fun call(command: String, body: JsonObject = buildJsonObject {}): JsonObject {
            val outcome = host.handle(command, body)
            assertTrue("原生命令 $command 必须成功", outcome is CommandOutcome.AcceptedWithData)
            return (outcome as CommandOutcome.AcceptedWithData).data
        }
        suspend fun fixtureCall(path: String): JsonObject = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("$origin/__fixture/$path").header("Authorization", "Bearer $token")
                .tag(AtomicBoolean::class.java, AtomicBoolean()).build()).execute().use {
                check(it.code == 200); Json.parseToJsonElement(it.body!!.string()).jsonObject
            }
        }
        var stage = "启动"
        var diagnosticWeb: WebView? = null
        suspend fun until(check: suspend () -> Boolean) {
            try { withTimeout(18000) { while (!check()) delay(50) } }
            catch (failure: TimeoutCancellationException) {
                val page = diagnosticWeb?.let { runCatching { body(it) }.getOrNull() }
                val local = call("cloud.list")
                File(instrumentation.targetContext.getExternalFilesDir(null), "batch62-$name-failure.txt")
                    .writeText("阶段：$stage\n页面：$page\n本机：$local", Charsets.UTF_8)
                throw AssertionError("$name：等待 $stage 超时", failure)
            }
        }
        suspend fun item() = call("cloud.list").getValue("items").jsonArray.single().jsonObject
        val koin = GlobalContext.get()
        val originalCloud = koin.get<CloudAgentService>()
        val originalMemory = koin.get<MemoryService>()
        val originalTasks = koin.get<AgentTaskService>()
        val originalHealth = koin.get<HealthConnectSync>()
        try {
            if (name == "tools") call("cloud.memoryConsent", buildJsonObject {
                put("identityToken", consent.context(identity)); put("enabled", true)
            })
            val conversation = call("cloud.newConversation").getValue("conversationId")
            koin.declare(host, allowOverride = true)
            koin.declare(MemoryService(database.memoryDao()), allowOverride = true)
            koin.declare(AgentTaskService(database.taskDao()), allowOverride = true)
            koin.declare<HealthConnectSync>(object : HealthConnectSync {
                override val requestedPermissions = emptySet<String>()
                override val permissionState = MutableStateFlow(HealthConnectPermissionState(HealthConnectAvailability.UNAVAILABLE, checked = true))
                override fun availability() = HealthConnectAvailability.UNAVAILABLE
                override suspend fun refreshPermissions() = permissionState.value
                override fun updateGrantedPermissions(grantedPermissions: Set<String>) = error("测试不能授权健康数据")
                override suspend fun syncRun(session: RunSession): HealthConnectSyncResult = error("测试不能开始跑步")
            }, allowOverride = true)
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var found: WebView? = null
                until { scenario.onActivity { found = findWeb(it.window.decorView) }; found != null }
                val web = checkNotNull(found)
                diagnosticWeb = web
                stage = "拒绝跑步权限窗口并取得前台焦点"
                until {
                    denyPermissionDialog()
                    var focused = false
                    instrumentation.runOnMainSync { focused = web.hasWindowFocus() }
                    focused
                }
                stage = "真实 Bridge 就绪"
                until { evaluate(web, "!!document.querySelector('#agent-draft:not(:disabled)')") == "true" }
                fill(web, "我喜欢夜跑")
                click(web, "在线执行")
                click(web, "同意本次上传并发送")
                stage = "首段显示"
                until { draft(web) == JSONObject.quote("流萤合成首段🙂") }
                val requestId = item().text("requestId")
                assertNull("候选不能提前落正式回执", database.cloudIntentDao().receipt(identity, requestId)!!.resultJson)
                assertEquals(1, fixtureCall("stats").getValue("models").jsonPrimitive.int)
                screenshot("batch62-$name-first.png", web)
                if (name == "tools") {
                    stage = "工具换轮"
                    fixtureCall("tool")
                    until { fixtureCall("stats").getValue("models").jsonPrimitive.int == 2 }
                    until { draft(web) == JSONObject.quote("") }
                    fixtureCall("answer")
                    until { draft(web) == JSONObject.quote("流萤合成最终首段") }
                    assertFalse(body(web).contains("合成首段"))
                    assertEquals(1, database.memoryDao().count("local"))
                    fill(web, "保留本机草稿，不发送")
                    val height = evaluate(web, "innerHeight").toInt()
                    instrumentation.runOnMainSync {
                        web.requestFocus()
                        web.evaluateJavascript("document.querySelector('#agent-draft').focus()", null)
                        (web.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(web, InputMethodManager.SHOW_IMPLICIT)
                    }
                    stage = "真实软键盘缩小 WebView"
                    until {
                        var shown = false
                        instrumentation.runOnMainSync { shown = ViewCompat.getRootWindowInsets(web)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
                        shown && evaluate(web, "innerHeight").toInt() < height
                    }
                    assertEquals("true", evaluate(web, """(() => {
                        const t = document.querySelector('#agent-draft'), r = t.getBoundingClientRect();
                        return !t.disabled && t.value === '保留本机草稿，不发送' && r.top >= 0 && r.bottom <= innerHeight && document.documentElement.scrollWidth <= innerWidth;
                    })()"""))
                    screenshot("batch62-tools-keyboard.png", web)
                    instrumentation.runOnMainSync {
                        (web.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(web.windowToken, 0)
                    }
                    // 切换会话必须移除上一会话的候选；新会话不能自动发送草稿。
                    click(web, "开始新对话")
                    stage = "新会话及后台任务完成"
                    until { call("cloud.list")["conversationId"] != conversation }
                    // 原生先切换、Bridge 结果随后送达 React；等待新会话真正渲染后再释放模型尾句。
                    until { draft(web) == JSONObject.quote("") && body(web).contains("开拓者，想聊些什么") }
                    fixtureCall("finish")
                    until { database.cloudIntentDao().receipt(identity, requestId)?.status == "completed" }
                    assertFalse(body(web).contains("合成最终首段"))
                    click(web, "打开侧边栏")
                    click(web, "我喜欢夜跑")
                    until { evaluate(web, "document.querySelector('aside[aria-modal=\"true\"]')?.getAttribute('aria-hidden')") == JSONObject.quote("true") }
                    until { evaluate(web, "document.querySelector('aside[aria-modal=\"true\"]')?.getBoundingClientRect().right <= 0.5") == "true" }
                } else {
                    stage = "切后台关闭订阅"
                    scenario.moveToState(Lifecycle.State.CREATED)
                    until { item()["draftAnswer"] == null && fixtureCall("stats").getValue("closedStreams").jsonPrimitive.int == 1 }
                    fixtureCall("cut")
                    stage = "后台读失败后停止查询"
                    until { item()["busy"] == JsonPrimitive(false) }
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    until { draft(web) == JSONObject.quote("") }
                    assertEquals(1, fixtureCall("stats").getValue("cursors").jsonArray.size)
                    fixtureCall("restore")
                    click(web, "恢复查询")
                    stage = "页面显式恢复首段"
                    until { draft(web) == JSONObject.quote("流萤合成首段🙂") }
                    assertEquals(listOf(0, 0), fixtureCall("stats").getValue("cursors").jsonArray.map { it.jsonPrimitive.int })
                    fixtureCall("finish")
                }
                val expected = if (name == "tools") "合成最终首段，完整尾句。" else "合成首段🙂，完整尾句。"
                stage = "正式回执显示"
                until { evaluate(web, "document.querySelector('section[aria-label=\"流萤的回答\"] p')?.textContent ?? ''") == JSONObject.quote(expected) }
                // 正常聊天成功后有意不显示“已完成”；应验证等待提示消失，而非要求增加无关提示。
                until { item()["busy"] == JsonPrimitive(false) &&
                    evaluate(web, "document.querySelector('article[aria-label=\"在线任务\"] [role=\"status\"]') === null") == "true" }
                assertEquals(JSONObject.quote(""), draft(web))
                assertEquals(1, fixtureCall("stats").getValue("submits").jsonPrimitive.int)
                screenshot("batch62-$name-final.png", web)
            }
        } finally {
            host.closeStreams(); scope.cancel(); scope.coroutineContext[Job]!!.join()
            koin.declare(originalCloud, allowOverride = true); koin.declare(originalMemory, allowOverride = true)
            koin.declare(originalTasks, allowOverride = true); koin.declare(originalHealth, allowOverride = true)
            database.close()
        }
    }

    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1); var result = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("WebView 回调超时", latch.await(5, TimeUnit.SECONDS)); return result
    }
    private fun body(web: WebView) = evaluate(web, "document.body.textContent")
    private fun draft(web: WebView) = evaluate(web, "document.querySelector('section[aria-label=\"流萤正在回答\"]')?.textContent ?? ''")
    private fun fill(web: WebView, text: String) {
        evaluate(web, """(() => {
            const input = document.querySelector('#agent-draft');
            Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(input, ${JSONObject.quote(text)});
            input.dispatchEvent(new Event('input', {bubbles:true}));
        })()""")
    }
    private fun click(web: WebView, text: String) {
        val selector = "Array.from(document.querySelectorAll('button')).find(b => (b.textContent.trim() === ${JSONObject.quote(text)} || b.getAttribute('aria-label') === ${JSONObject.quote(text)}) && !b.disabled)"
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (evaluate(web, "!!($selector)") != "true") {
            check(System.nanoTime() < deadline) { "找不到可用按钮：$text" }; Thread.sleep(50)
        }
        evaluate(web, "$selector.click()")
    }
    private fun denyPermissionDialog() {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.viewIdResourceName?.contains(":id/permission_deny") == true && node.isClickable) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::find)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
    private fun screenshot(name: String, web: WebView) {
        val drawn = CountDownLatch(1)
        instrumentation.runOnMainSync { web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { drawn.countDown() }
        }) }
        assertTrue("等待页面绘制超时", drawn.await(5, TimeUnit.SECONDS))
        // DOM／WebView 绘制回调并不保证系统合成器已经提交同一帧，截图前等待原生窗口稳定。
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 5000)
        Thread.sleep(250)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
