package com.healthyagent.running.agent

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.MainActivity
import com.healthyagent.running.agent.local.AgentDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.healthyagent.running.agent.cloud.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.koin.core.context.GlobalContext
import java.util.concurrent.CopyOnWriteArrayList

/** 仅在隔离模拟器执行：使用真实 APK/WebView/Bridge/Room，不启动跑步或网络请求。 */
@RunWith(AndroidJUnit4::class)
class AgentHomepageTest {
    @Test fun 单次授权发送回答重载与撤回通过真实WebView和原生假云() = runBlocking {
        val koin = GlobalContext.get()
        val original = koin.get<CloudAgentService>()
        val originalVoice = koin.get<com.healthyagent.running.agent.voice.VoiceInputService>()
        val voiceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val voiceCalls = CopyOnWriteArrayList<String>()
        val voice = com.healthyagent.running.agent.voice.VoiceInputService(
            com.healthyagent.running.agent.voice.VoiceStream { stop, ready, preview, finishing ->
                ready(); preview("今晚青松"); delay(100); preview("今晚轻松跑")
                while (!stop.get()) delay(10)
                finishing(); voiceCalls += "recognize"; "今晚轻松跑"
            }, voiceScope,
        )
        val database = koin.get<AgentDatabase>()
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val identity = CloudTaskIdentity("https://synthetic.example.com", "owner", "device", UUID.randomUUID().toString())
        val memoryId = UUID.randomUUID().toString()
        val content = "在线记忆-${memoryId.take(8)}：偏好晚跑"
        database.memoryDao().create("local", memoryId, content, System.currentTimeMillis())
        val calls = CopyOnWriteArrayList<String>()
        val answer = "合成验收回答：已收到本次选中的晚跑偏好。"
        val cloudId = UUID.randomUUID().toString()
        val knowledge = buildJsonObject { put("version", "curated_knowledge_v1"); put("library", "firefly"); put("allowSpoilers", true) }
        val sources = JsonArray(listOf(buildJsonObject {
            put("citationId", "synthetic_v1_1"); put("title", "官网来源展示（合成验收）")
            put("url", "https://hsr.hoyoverse.com/zh-tw/character"); put("publishedAt", JsonNull); put("checkedAtMillis", 1000)
        }))
        val cost = buildJsonObject {
            put("currency", "USD"); put("estimatedMicros", 78); put("inputTokens", 100); put("outputTokens", 40)
            put("usageComplete", true); put("rateStatus", "stale")
        }
        fun response(state: String = "available") = CloudTaskResponse(cloudId, "completed", null, 1000, 31000, 2000, state,
            if (state == "available") buildJsonObject { put("status", "completed"); put("answer", answer); put("actions", JsonArray(emptyList())); put("sources", sources); put("cost", cost) } else null)
        val api = object : CloudTaskApi {
            override suspend fun submit(intent: CloudTaskIntent): CloudTaskResponse { error("本轮必须包含选中的记忆") }
            override suspend fun submitSelected(intent: CloudTaskIntent, memoryContext: JsonObject): CloudTaskResponse {
                assertEquals(knowledge, intent.knowledge())
                assertEquals(content, memoryContext.getValue("items").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content)
                calls += "submit"; return response()
            }
            override suspend fun find(identity: CloudTaskIdentity, requestId: String): CloudTaskResponse { calls += "find"; return response() }
            override suspend fun get(identity: CloudTaskIdentity, taskId: String): CloudTaskResponse { calls += "get"; return response() }
            override suspend fun control(identity: CloudTaskIdentity, taskId: String, action: String): CloudTaskResponse { calls += action; return response(if (action == "withdraw") "withdrawn" else "acknowledged") }
        }
        koin.declare(CloudAgentService(database, { identity }, { api }, testScope), allowOverride = true)
        koin.declare(voice, allowOverride = true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var web: WebView? = null
                awaitCondition { scenario.onActivity { web = findWeb(it.window.decorView) }; web != null }
                val view = checkNotNull(web)
                awaitForeground(view)
                fun click(text: String) {
                    val value = JSONObject.quote(text)
                    awaitCondition { evaluate(view, "Array.from(document.querySelectorAll('button')).some(b => b.textContent === $value && !b.disabled)") == "true" }
                    evaluate(view, "Array.from(document.querySelectorAll('button')).find(b => b.textContent === $value && !b.disabled).click()")
                }
                awaitCondition { evaluate(view, "document.querySelector('#agent-draft') !== null") == "true" }
                evaluate(view, "(() => {const i=document.querySelector('#agent-draft'); Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(i,'原有草稿'); i.dispatchEvent(new Event('input',{bubbles:true}));})()")
                click("语音输入")
                assertTrue(voiceCalls.isEmpty())
                assertTrue(calls.isEmpty())
                assertEquals("true", evaluate(view, "Array.from(document.querySelectorAll('button')).find(b => b.textContent === '配置在线 Agent').disabled"))
                assertEquals("true", evaluate(view, "document.documentElement.scrollWidth <= innerWidth"))
                screenshot(view, "phone-voice-consent.png")
                click("同意本次录音并识别")
                awaitCondition { evaluate(view, "document.querySelector('[aria-label=\"实时识别预览\"]')?.textContent === '今晚轻松跑'") == "true" }
                assertEquals("\"原有草稿\"", evaluate(view, "document.querySelector('#agent-draft').value"))
                assertTrue(calls.isEmpty()); assertTrue(voiceCalls.isEmpty())
                assertEquals("true", evaluate(view, "document.documentElement.scrollWidth <= innerWidth"))
                screenshot(view, "phone-voice-preview.png")
                click("结束并识别")
                awaitCondition { evaluate(view, "document.querySelector('#agent-draft').value === '原有草稿\\n今晚轻松跑'") == "true" }
                assertEquals(listOf("recognize"), voiceCalls.toList())
                assertTrue(calls.isEmpty())
                screenshot(view, "phone-voice-draft.png")
                assertEquals("\"none\"", evaluate(view, "document.querySelector('#knowledge-library').value"))
                evaluate(view, "(() => {const i=document.querySelector('#knowledge-library'); i.value='firefly'; i.dispatchEvent(new Event('change',{bubbles:true}));})()")
                awaitCondition { evaluate(view, "Array.from(document.querySelectorAll('label')).some(l => l.textContent.includes('本次允许身份与身世剧透'))") == "true" }
                assertEquals("false", evaluate(view, "Array.from(document.querySelectorAll('label')).find(l => l.textContent.includes('本次允许身份与身世剧透')).querySelector('input').checked"))
                evaluate(view, "Array.from(document.querySelectorAll('label')).find(l => l.textContent.includes('本次允许身份与身世剧透')).querySelector('input').click()")
                evaluate(view, "(() => {const i=document.querySelector('#agent-draft'); Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(i,'今晚怎么跑'); i.dispatchEvent(new Event('input',{bubbles:true}));})()")
                click("选择本次记忆")
                val quoted = JSONObject.quote(content)
                awaitCondition { evaluate(view, "Array.from(document.querySelectorAll('label')).some(l => l.textContent.includes($quoted))") == "true" }
                evaluate(view, "Array.from(document.querySelectorAll('label')).find(l => l.textContent.includes($quoted)).querySelector('input').click()")
                click("在线执行")
                awaitCondition { evaluate(view, "document.querySelector('[aria-label=\"本次云端授权\"]')?.textContent.includes($quoted) === true") == "true" }
                assertTrue(calls.isEmpty())
                assertEquals("true", evaluate(view, "document.querySelector('[aria-label=\"本次云端授权\"]').textContent.includes('允许身份与身世剧透')"))
                assertEquals("true", evaluate(view, "document.documentElement.scrollWidth <= innerWidth"))
                screenshot(view, "phone-cloud-consent.png")
                click("暂不发送")
                assertTrue(calls.isEmpty())
                click("在线执行")
                click("同意本次上传并发送")
                awaitCondition { evaluate(view, "document.querySelector('[aria-label=\"流萤的回答\"]')?.textContent.includes(${JSONObject.quote(answer)}) === true") == "true" }
                assertEquals("true", evaluate(view, "document.querySelector('[aria-label=\"知识来源\"]').textContent.includes('https://hsr.hoyoverse.com/zh-tw/character')"))
                evaluate(view, "document.querySelector('[aria-label=\"流萤的回答\"]').scrollIntoView({block:'center'})")
                screenshot(view, "phone-cloud-answer.png")
                assertEquals("true", evaluate(view, "document.querySelector('[aria-label=\"本次费用估算\"]').textContent.includes('0.000078')"))
                evaluate(view, "document.querySelector('[aria-label=\"本次费用估算\"]').scrollIntoView({block:'center'})")
                screenshot(view, "phone-cloud-cost.png")
                assertEquals(1, calls.count { it == "submit" })
                evaluate(view, "window.__voiceTestBeforeReload = true")
                InstrumentationRegistry.getInstrumentation().runOnMainSync { view.reload() }
                awaitCondition { evaluate(view, "window.__voiceTestBeforeReload === undefined") == "true" }
                awaitCondition { evaluate(view, "document.querySelector('[aria-label=\"流萤的回答\"]')?.textContent.includes(${JSONObject.quote(answer)}) === true") == "true" }
                assertEquals("true", evaluate(view, "document.querySelector('[aria-label=\"本次费用估算\"]').textContent.includes('0.000078')"))
                assertEquals(1, calls.count { it == "submit" })
                click("撤回回答"); click("确认撤回")
                awaitCondition { calls.contains("withdraw") }
                awaitCondition { evaluate(view, "document.querySelector('[aria-label=\"流萤的回答\"]') === null") == "true" }
                assertEquals("true", evaluate(view, "document.querySelector('[aria-label=\"知识来源\"]') === null"))
                assertEquals("true", evaluate(view, "document.querySelector('[aria-label=\"本次费用估算\"]') === null"))
                assertEquals("true", evaluate(view, "document.documentElement.scrollWidth <= innerWidth"))
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { voice.cancel() }
            voiceScope.coroutineContext[Job]?.cancelAndJoin()
            koin.declare(originalVoice, allowOverride = true)
            testScope.coroutineContext[Job]?.cancelAndJoin()
            koin.declare(original, allowOverride = true)
        }
    }
    @Test fun 首页选择记忆只预览且编辑删除后旧版本不能再次核对() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = AgentDatabase.create(context)
        val memoryId = UUID.randomUUID().toString()
        val excludedId = UUID.randomUUID().toString()
        val content = "首页记忆核对-${memoryId.take(8)}"
        val edited = "$content-已更新"
        try {
            database.memoryDao().create("local", excludedId, "未选择-$excludedId", System.currentTimeMillis())
            database.memoryDao().create("local", memoryId, content, System.currentTimeMillis())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var web: WebView? = null
                awaitCondition { scenario.onActivity { web = findWeb(it.window.decorView) }; web != null }
                val view = checkNotNull(web)
                awaitForeground(view)
                fun click(text: String) {
                    val quoted = JSONObject.quote(text)
                    awaitCondition { evaluate(view, "Array.from(document.querySelectorAll('button')).some(b => b.textContent === $quoted && !b.disabled)") == "true" }
                    evaluate(view, "Array.from(document.querySelectorAll('button')).find(b => b.textContent === $quoted && !b.disabled).click()")
                }
                fun select(text: String) {
                    val quoted = JSONObject.quote(text)
                    awaitCondition { evaluate(view, "Array.from(document.querySelectorAll('label')).some(l => l.textContent.includes($quoted) && l.querySelector('input[type=checkbox]')?.disabled === false)") == "true" }
                    evaluate(view, "Array.from(document.querySelectorAll('label')).find(l => l.textContent.includes($quoted)).querySelector('input').click()")
                }
                click("选择本次记忆")
                awaitCondition { evaluate(view, "document.querySelector('section[aria-labelledby=\"memory-selection-title\"]')?.textContent.includes(${JSONObject.quote(content)}) === true") == "true" }
                assertEquals("true", evaluate(view, "Array.from(document.querySelectorAll('input[type=checkbox]')).every(input => !input.checked)"))
                select(content)
                click("核对本次记忆")
                awaitCondition { evaluate(view, "document.querySelector('[aria-label=\"已核对的记忆预览\"]')?.textContent.includes(${JSONObject.quote(content)}) === true") == "true" }
                assertEquals("false", evaluate(view, "document.querySelector('[aria-label=\"已核对的记忆预览\"]').textContent.includes(${JSONObject.quote(excludedId)})"))
                assertEquals("true", evaluate(view, "Array.from(document.querySelectorAll('button')).some(b => b.textContent === '在线执行' && b.disabled)"))
                assertEquals("true", evaluate(view, "document.documentElement.scrollWidth <= innerWidth"))
                evaluate(view, "document.querySelector('[aria-label=\"已核对的记忆预览\"]').scrollIntoView({block:'center'})")
                screenshot(view, "memory-context-preview.png")
                database.memoryDao().update("local", memoryId, 1, edited, System.currentTimeMillis())
                click("核对本次记忆")
                awaitCondition { evaluate(view, "document.querySelector('[role=alert]')?.textContent.includes('核对失败') === true") == "true" }
                assertEquals("true", evaluate(view, "document.querySelector('[aria-label=\"已核对的记忆预览\"]') === null"))
                click("刷新可选记忆")
                select(edited)
                click("核对本次记忆")
                awaitCondition { evaluate(view, "document.querySelector('[aria-label=\"已核对的记忆预览\"]')?.textContent.includes(${JSONObject.quote(edited)}) === true") == "true" }
                database.memoryDao().delete("local", memoryId, 2, System.currentTimeMillis())
                click("核对本次记忆")
                awaitCondition { evaluate(view, "document.querySelector('[role=alert]')?.textContent.includes('核对失败') === true") == "true" }
                assertEquals("true", evaluate(view, "document.querySelector('[aria-label=\"已核对的记忆预览\"]') === null"))
                click("收起记忆选择")
                click("选择本次记忆")
                awaitCondition { evaluate(view, "document.querySelector('section[aria-labelledby=\"memory-selection-title\"]')?.textContent.includes('已选择 0 / 5 条') === true") == "true" }
            }
        } finally { database.close() }
    }

    @Test fun 首页保存重载恢复取消及路由均通过真实原生接口() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var web: WebView? = null
            awaitCondition {
                scenario.onActivity { web = findWeb(it.window.decorView) }
                web != null
            }
            val view = checkNotNull(web)
            awaitForeground(view)
            awaitCondition { evaluate(view, "document.querySelector('#agent-draft') !== null") == "true" }
            assertEquals("\"#/agent\"", evaluate(view, "window.location.hash"))
            val prompt = "本机任务验收-${UUID.randomUUID().toString().take(8)}"
            val quoted = JSONObject.quote(prompt)
            evaluate(view, """(() => {
                const input = document.querySelector('#agent-draft');
                Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(input, $quoted);
                input.dispatchEvent(new Event('input', {bubbles:true}));
                return true;
            })()""".trimIndent())
            awaitCondition { evaluate(view, "Array.from(document.querySelectorAll('button')).some(b => b.textContent === '仅保存' && !b.disabled)") == "true" }
            assertEquals("true", evaluate(view, "Array.from(document.querySelectorAll('button')).some(b => b.textContent === '在线执行' && b.disabled)"))
            evaluate(view, "Array.from(document.querySelectorAll('button')).find(b => b.textContent === '仅保存').click()")
            awaitCondition { evaluate(view, "document.querySelector('[aria-label=\"任务详情\"]')?.textContent.includes($quoted) === true") == "true" }
            assertEquals("true", evaluate(view, "document.querySelector('[aria-label=\"任务详情\"]').textContent.includes('未上传，也未执行')"))
            awaitCondition { evaluate(view, "document.querySelector('#agent-draft').value === '' && !document.querySelector('#agent-draft').disabled") == "true" }
            evaluate(view, "document.querySelector('[aria-label=\"任务详情\"]').scrollIntoView({block:'center'})")
            screenshot(view, "agent-local-detail.png")

            evaluate(view, "window.__agentReloadProbe = true")
            InstrumentationRegistry.getInstrumentation().runOnMainSync { view.reload() }
            val taskLabel = JSONObject.quote("查看任务：$prompt")
            awaitCondition { evaluate(view, "window.__agentReloadProbe === undefined && Array.from(document.querySelectorAll('button')).some(b => b.getAttribute('aria-label') === $taskLabel && !b.disabled)") == "true" }
            evaluate(view, "Array.from(document.querySelectorAll('button')).find(b => b.getAttribute('aria-label') === $taskLabel).click()")
            awaitCondition { evaluate(view, "Array.from(document.querySelectorAll('button')).some(b => b.textContent === '取消任务' && !b.disabled)") == "true" }
            evaluate(view, "Array.from(document.querySelectorAll('button')).find(b => b.textContent === '取消任务').click()")
            awaitCondition { evaluate(view, "document.querySelector('[aria-label=\"任务详情\"]')?.textContent.includes('已取消') === true") == "true" }
            evaluate(view, "document.querySelector('[aria-label=\"任务详情\"]').scrollIntoView({block:'center'})")
            screenshot(view, "agent-local-cancelled.png")

            evaluate(view, "window.location.hash = '/run'")
            awaitCondition { evaluate(view, "document.querySelector('h1')?.textContent === '跑步'") == "true" }
            evaluate(view, "window.location.hash = '/agent'")
            awaitCondition { evaluate(view, "Array.from(document.querySelectorAll('button')).some(b => b.getAttribute('aria-label') === $taskLabel && b.textContent.includes('已取消'))") == "true" }
            assertEquals("360", evaluate(view, "window.innerWidth"))
            assertEquals("true", evaluate(view, "document.documentElement.scrollWidth <= window.innerWidth"))
            evaluate(view, "window.scrollTo(0, 0)")
            assertEquals("true", evaluate(view, "(() => { const r = document.querySelector('#agent-draft').getBoundingClientRect(); return r.top >= 0 && r.bottom <= window.innerHeight; })()"))
            screenshot(view, "agent-local-home.png")
        }
    }

    @Test fun 记忆新增编辑删除及重载通过真实Bridge且窄屏无溢出() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var web: WebView? = null
            awaitCondition { scenario.onActivity { web = findWeb(it.window.decorView) }; web != null }
            val view = checkNotNull(web)
            awaitForeground(view)
            awaitCondition { evaluate(view, "document.querySelector('a[href=\"#/memories\"]') !== null") == "true" }
            evaluate(view, "document.querySelector('a[href=\"#/memories\"]').click()")
            awaitCondition { evaluate(view, "document.querySelector('#memory-draft')?.disabled === false") == "true" }
            awaitCondition { evaluate(view, "document.querySelector('#memory-list-title')?.textContent.includes('·') === true") == "true" }
            val initialMemoryCount = evaluate(view, "document.querySelector('#memory-list-title').textContent")
            val content = "记忆验收-${UUID.randomUUID().toString().take(8)}"
            val edited = "$content-偏好夜跑"
            fun fill(selector: String, text: String) {
                evaluate(view, """(() => {
                    const input = document.querySelector('$selector');
                    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set.call(input, ${JSONObject.quote(text)});
                    input.dispatchEvent(new Event('input', {bubbles:true}));
                })()""".trimIndent())
            }
            fun click(text: String) {
                val quoted = JSONObject.quote(text)
                awaitCondition { evaluate(view, "Array.from(document.querySelectorAll('button')).some(b => b.textContent === $quoted && !b.disabled)") == "true" }
                evaluate(view, "Array.from(document.querySelectorAll('button')).find(b => b.textContent === $quoted && !b.disabled).click()")
            }
            fun visible(text: String) = evaluate(view, "document.querySelector('ul')?.textContent.includes(${JSONObject.quote(text)}) === true") == "true"
            fun rowAction(action: String, text: String) {
                val label = JSONObject.quote("${action}记忆：$text")
                awaitCondition { evaluate(view, "Array.from(document.querySelectorAll('button')).some(b => b.getAttribute('aria-label') === $label && !b.disabled)") == "true" }
                evaluate(view, "Array.from(document.querySelectorAll('button')).find(b => b.getAttribute('aria-label') === $label).click()")
            }
            fun reload() {
                evaluate(view, "window.__memoryReloadProbe = true")
                InstrumentationRegistry.getInstrumentation().runOnMainSync { view.reload() }
                awaitCondition { evaluate(view, "window.__memoryReloadProbe === undefined && document.querySelector('#memory-draft')?.disabled === false") == "true" }
            }
            fun assertVisibleDialog() {
                assertEquals("删除确认标题和按钮必须完整可见", "true", evaluate(view, """(() => {
                    const d = document.querySelector('dialog'); const bounds = d.getBoundingClientRect();
                    return d.open && bounds.height >= 180 && bounds.top >= 0 && bounds.bottom <= innerHeight &&
                        [d.querySelector('h2'), ...d.querySelectorAll('button')].every(element => {
                            const r = element.getBoundingClientRect();
                            return r.top >= bounds.top && r.bottom <= bounds.bottom &&
                                element.contains(document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2));
                        });
                })()""".trimIndent()))
            }
            fill("#memory-draft", content)
            click("保存记忆")
            awaitCondition { visible(content) }
            reload()
            awaitCondition { visible(content) }
            rowAction("编辑", content)
            fill("#memory-edit", edited)
            click("保存修改")
            awaitCondition { visible(edited) }
            assertEquals("true", evaluate(view, "document.documentElement.scrollWidth <= window.innerWidth"))
            evaluate(view, "window.scrollTo(0, 0)")
            screenshot(view, "memory-local-list.png")
            evaluate(view, "document.querySelector('section[aria-labelledby=\"memory-list-title\"]').scrollIntoView({block:'center'})")
            screenshot(view, "memory-local-card.png")
            rowAction("删除", edited)
            awaitCondition { evaluate(view, "document.querySelector('dialog').open") == "true" }
            val dialogGeometry = evaluate(view, """(() => {
                const d = document.querySelector('dialog'); const r = d.getBoundingClientRect(); const s = getComputedStyle(d);
                return JSON.stringify({ width: r.width, height: r.height, top: r.top, bottom: r.bottom,
                    innerHeight, scrollHeight: d.scrollHeight, scrollTop: d.scrollTop, style: { height: s.height, maxHeight: s.maxHeight, padding: s.padding, display: s.display },
                    children: Array.from(d.children).map(c => ({ tag: c.tagName, height: c.getBoundingClientRect().height, display: getComputedStyle(c).display })) });
            })()""".trimIndent())
            assertEquals("删除弹窗标题与正文必须可见：$dialogGeometry", "true", evaluate(view, "document.querySelector('dialog').getBoundingClientRect().height >= 180"))
            assertVisibleDialog()
            assertEquals("true", evaluate(view, """(() => {
                const d = document.querySelector('dialog'); const r = d.getBoundingClientRect();
                return r.width > 0 && r.top >= 0 && r.bottom <= innerHeight &&
                    d.contains(document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2));
            })()""".trimIndent()))
            screenshot(view, "memory-delete-confirm.png")
            click("保留记忆")
            assertTrue(visible(edited))
            rowAction("删除", edited)
            click("确认删除")
            awaitCondition { !visible(edited) && evaluate(view, "document.querySelector('dialog').open") == "false" }
            reload()
            awaitCondition { evaluate(view, "document.querySelector('#memory-list-title')?.textContent") == initialMemoryCount }
            assertTrue(!visible(edited))
            assertEquals("true", evaluate(view, "document.documentElement.scrollWidth <= window.innerWidth"))
            screenshot(view, "memory-local-after-delete.png")
            // 长记忆正文单独滚动，不能把确认标题和操作按钮挤出可视区域。
            val longContent = "长记忆".repeat(166) + "结束"
            assertEquals(500, longContent.length)
            fill("#memory-draft", longContent)
            click("保存记忆")
            awaitCondition { visible(longContent) }
            rowAction("删除", longContent)
            awaitCondition { evaluate(view, "document.querySelector('dialog').open") == "true" }
            assertVisibleDialog()
            assertEquals("true", evaluate(view, """(() => {
                const text = document.querySelector('dialog > p');
                text.scrollTop = text.scrollHeight;
                return text.scrollHeight > text.clientHeight && text.scrollTop > 0 && text.textContent.endsWith('结束');
            })()""".trimIndent()))
            screenshot(view, "memory-delete-long.png")
            click("确认删除")
            awaitCondition { !visible(longContent) && evaluate(view, "document.querySelector('dialog').open") == "false" }
        }
    }

    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun evaluate(view: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue("WebView 脚本回调超时", latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(80)
        }
        throw AssertionError("等待真实页面状态超时")
    }

    private fun awaitForeground(view: WebView) {
        awaitCondition {
            var focused = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                focused = view.isShown && view.hasWindowFocus()
            }
            focused
        }
    }

    private fun screenshot(view: WebView, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        awaitForeground(view)
        val drawn = CountDownLatch(1)
        instrumentation.runOnMainSync {
            view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { drawn.countDown() }
            })
        }
        assertTrue("页面绘制超时，不能将后台 DOM 结果当作视觉验收", drawn.await(5, TimeUnit.SECONDS))
        // 绘制回调先于 Surface 合成；等浏览器后续帧及系统合成，避免截到前一状态。
        evaluate(view, "window.__screenshotFrameReady = false; requestAnimationFrame(() => requestAnimationFrame(() => { window.__screenshotFrameReady = true; }))")
        awaitCondition { evaluate(view, "window.__screenshotFrameReady") == "true" }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(300, 5000)
        Thread.sleep(400)
        val image = instrumentation.uiAutomation.takeScreenshot()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
