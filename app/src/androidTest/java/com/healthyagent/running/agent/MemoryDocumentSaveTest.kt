package com.healthyagent.running.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.MainActivity
import com.healthyagent.running.agent.local.AgentDatabase
import com.healthyagent.running.agent.memory.MemoryService
import com.healthyagent.running.core.model.RunSession
import com.healthyagent.running.data.health.HealthConnectAvailability
import com.healthyagent.running.data.health.HealthConnectPermissionState
import com.healthyagent.running.data.health.HealthConnectSync
import com.healthyagent.running.data.health.HealthConnectSyncResult
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** 仅限专用模拟器：真实 WebView／系统文件窗口，仅导出隔离内存数据库中的合成资料。 */
@RunWith(AndroidJUnit4::class)
class MemoryDocumentSaveTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var database: AgentDatabase
    private lateinit var original: MemoryService
    private lateinit var service: MemoryService
    private lateinit var originalHealth: HealthConnectSync
    private val owner = "export-test-${UUID.randomUUID()}"

    @Before fun setup() = runBlocking {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "禁止在真实手机运行文件保存测试" }
        val koin = GlobalContext.get()
        original = koin.get()
        originalHealth = koin.get()
        database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AgentDatabase::class.java).build()
        service = MemoryService(database.memoryDao(), owner) { 1234567890000L }
        koin.declare(service, allowOverride = true)
        // 只隔离与导出无关的健康数据入口，不授予传感器或 Health Connect 权限。
        koin.declare<HealthConnectSync>(object : HealthConnectSync {
            override val requestedPermissions = emptySet<String>()
            override val permissionState = MutableStateFlow(HealthConnectPermissionState(HealthConnectAvailability.UNAVAILABLE, checked = true))
            override fun availability() = HealthConnectAvailability.UNAVAILABLE
            override suspend fun refreshPermissions() = permissionState.value
            override fun updateGrantedPermissions(grantedPermissions: Set<String>) = error("本测试不能授权健康数据")
            override suspend fun syncRun(session: RunSession): HealthConnectSyncResult = error("本测试不能开始跑步")
        }, allowOverride = true)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    @After fun close() {
        if (::original.isInitialized) GlobalContext.get().declare(original, allowOverride = true)
        if (::originalHealth.isInitialized) GlobalContext.get().declare(originalHealth, allowOverride = true)
        if (::database.isInitialized) database.close()
    }

    @Test fun completeSnapshotIsWrittenBySystemPickerAndReadBackAsUtf8() = runBlocking {
        val dao = database.memoryDao()
        repeat(121) { dao.create(owner, UUID.randomUUID().toString(), "合成偏好-$it：周三轻松跑 🏃\n```不执行```", 1000) }
        val deleted = UUID.randomUUID().toString()
        dao.create(owner, deleted, "已删除合成内容", 1000)
        dao.delete(owner, deleted, 1, 2000)
        dao.create("another-synthetic-owner", UUID.randomUUID().toString(), "其他归属合成内容", 1000)
        val expected = buildString { service.document().writeMarkdown(this) }
        val name = "liuying-memory-${UUID.randomUUID()}.md"
        withDocument { _, web ->
            assertEquals("20", evaluate(web, "document.querySelectorAll('#memory-document-title').length && document.querySelector('section[aria-labelledby=\"memory-document-title\"] ol').children.length"))
            click(web, "保存完整 Markdown")
            saveInDownloads(name)
            awaitCondition { body(web).contains("已保存 121 条有效记忆") }
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8), downloaded(name))
            assertEquals(expected, buildString { service.document().writeMarkdown(this) })
            assertEquals("true", evaluate(web, "document.documentElement.scrollWidth <= innerWidth"))
            screenshot("batch49-memory-save-success.png", web)
        }
    }

    @Test fun cancellingPickerLeavesMemoryUntouchedAndAllowsAnotherSave() = runBlocking {
        database.memoryDao().create(owner, UUID.randomUUID().toString(), "取消后仍保留的合成偏好", 1000)
        val expected = buildString { service.document().writeMarkdown(this) }
        withDocument { _, web ->
            click(web, "保存完整 Markdown")
            awaitPicker()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitCondition { body(web).contains("已取消保存，未写入记忆正文") }
            screenshot("batch49-memory-save-cancelled.png", web)
            assertEquals(expected, buildString { service.document().writeMarkdown(this) })
            val name = "liuying-memory-retry-${UUID.randomUUID()}.md"
            click(web, "保存完整 Markdown")
            saveInDownloads(name)
            awaitCondition { body(web).contains("已保存 1 条有效记忆") }
            assertArrayEquals(expected.toByteArray(Charsets.UTF_8), downloaded(name))
        }
    }

    @Test fun reloadedDocumentDoesNotConsumeLatePickerResultOrWriteOldMemory() = runBlocking {
        database.memoryDao().create(owner, UUID.randomUUID().toString(), "重载不能写出的合成偏好", 1000)
        val name = "liuying-memory-stale-${UUID.randomUUID()}.md"
        withDocument { _, web ->
            click(web, "保存完整 Markdown")
            awaitPicker()
            instrumentation.runOnMainSync { web.reload() }
            awaitCondition { evaluate(web, "document.querySelector('#memory-document-title') === null") == "true" }
            saveInDownloads(name)
            awaitCondition { evaluate(web, "document.readyState === 'complete'") == "true" }
            assertTrue("迟到选择结果不得写入正文", downloaded(name).isEmpty())
            evaluate(web, "location.hash = '#/memories'")
            click(web, "查看记忆文档")
            awaitCondition { body(web).contains("共 1 条") }
            assertFalse(exportStatus(web).contains("已保存 "))
            // 原选择已返回，新的显式保存仍可使用，不能永久锁死导出入口。
            val retry = "liuying-memory-reloaded-${UUID.randomUUID()}.md"
            click(web, "保存完整 Markdown")
            saveInDownloads(retry)
            awaitCondition { body(web).contains("已保存 1 条有效记忆") }
            assertTrue(downloaded(retry).toString(Charsets.UTF_8).contains("重载不能写出的合成偏好"))
        }
    }

    @Test fun recreatedActivityIgnoresOldPickerResultAndNewHostCanSave() = runBlocking {
        database.memoryDao().create(owner, UUID.randomUUID().toString(), "Activity 重建合成偏好", 1000)
        val name = "liuying-memory-recreated-${UUID.randomUUID()}.md"
        withDocument { scenario, oldWeb ->
            lateinit var oldActivity: MainActivity
            scenario.onActivity { oldActivity = it }
            click(oldWeb, "保存完整 Markdown")
            awaitPicker()
            // ActivityScenario.recreate 会先要求前台 RESUMED；系统文件窗口在前时改用真实 Activity 重建。
            instrumentation.runOnMainSync { oldActivity.recreate() }
            awaitCondition {
                var destroyed = false
                instrumentation.runOnMainSync { destroyed = oldActivity.isDestroyed }
                destroyed
            }
            saveInDownloads(name)
            var newWeb: WebView? = null
            awaitCondition { scenario.onActivity { newWeb = findWeb(it.window.decorView) }; newWeb != null }
            val web = checkNotNull(newWeb)
            assertNotSame(oldWeb, web)
            awaitCondition { evaluate(web, "document.querySelector('#agent-draft') !== null") == "true" }
            assertTrue("宿主重建后不能写入旧操作的正文", downloaded(name).isEmpty())
            evaluate(web, "location.hash = '#/memories'")
            click(web, "查看记忆文档")
            awaitCondition { body(web).contains("共 1 条") }
            assertFalse(exportStatus(web).contains("已保存 "))
            val retry = "liuying-memory-newhost-${UUID.randomUUID()}.md"
            click(web, "保存完整 Markdown")
            saveInDownloads(retry)
            awaitCondition { body(web).contains("已保存 1 条有效记忆") }
            assertArrayEquals(buildString { service.document().writeMarkdown(this) }.toByteArray(Charsets.UTF_8), downloaded(retry))
        }
    }

    private suspend fun withDocument(block: suspend (ActivityScenario<MainActivity>, WebView) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var web: WebView? = null
            awaitCondition { scenario.onActivity { web = findWeb(it.window.decorView) }; web != null }
            val view = checkNotNull(web)
            awaitCondition {
                node { it.viewIdResourceName?.contains(":id/permission_deny") == true && it.isClickable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                var focused = false
                instrumentation.runOnMainSync { focused = view.hasWindowFocus() }
                focused
            }
            awaitCondition { evaluate(view, "document.querySelector('#agent-draft') !== null") == "true" }
            evaluate(view, "location.hash = '#/memories'")
            click(view, "查看记忆文档")
            awaitCondition { body(view).contains("共 ") }
            try { block(scenario, view) } finally {
                if (node { it.packageName?.toString()?.endsWith("documentsui") == true } != null)
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
        }
    }

    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun evaluate(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync { web.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue("WebView 回调超时", latch.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun body(web: WebView) = evaluate(web, "document.body.textContent")

    private fun exportStatus(web: WebView) = evaluate(web,
        "Array.from(document.querySelectorAll('section[aria-labelledby=\"memory-document-title\"] [role=\"status\"]')).map(n => n.textContent).join(' ')")

    private fun click(web: WebView, text: String) {
        val selector = "Array.from(document.querySelectorAll('button')).find(b => b.textContent === ${JSONObject.quote(text)} && !b.disabled)"
        awaitCondition { evaluate(web, "Boolean($selector)") == "true" }
        evaluate(web, "$selector.click()")
    }

    private fun node(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(current: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(current)) return current
            for (index in 0 until current.childCount) current.getChild(index)?.let { find(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }

    private fun awaitPicker() = awaitCondition {
        node { it.packageName?.toString()?.endsWith("documentsui") == true && it.className == "android.widget.EditText" } != null
    }

    private fun saveInDownloads(name: String) {
        awaitPicker()
        // 系统选择器自身导航，不伪造 ActivityResult，也不替换 ContentResolver。
        if (node { it.text?.toString() in setOf("Downloads", "下载") } == null) {
            val roots = checkNotNull(node { it.contentDescription?.toString() in setOf("Show roots", "显示根目录") })
            assertTrue(roots.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitCondition { node { it.text?.toString() in setOf("Downloads", "下载") } != null }
            var downloads = checkNotNull(node { it.text?.toString() in setOf("Downloads", "下载") })
            while (!downloads.isClickable) downloads = checkNotNull(downloads.parent)
            assertTrue(downloads.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        val edit = checkNotNull(node { it.className == "android.widget.EditText" })
        assertTrue(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
        }))
        screenshot("batch49-system-picker.png")
        awaitCondition {
            val save = node { it.text?.toString() in setOf("SAVE", "Save", "保存") && it.isClickable && it.isEnabled }
            if (save == null) false else {
                assertTrue(save.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                true
            }
        }
        awaitCondition { node { it.packageName?.toString()?.endsWith("documentsui") == true } == null }
    }

    private fun downloaded(name: String): ByteArray {
        require(Regex("liuying-memory-[a-z0-9-]+\\.md").matches(name))
        // 只读当前测试生成的精确路径；不遍历共享存储或用户文件。
        val size = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("stat -c %s /sdcard/Download/$name"))
            .bufferedReader().use { it.readText().trim().toLongOrNull() }
        assertNotNull("系统选择器应已创建精确目标文件，不能把读取失败当作空文件", size)
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$name")).use { it.readBytes() }
        assertEquals("实际读回长度应与目标文件一致", size, bytes.size.toLong())
        return bytes
    }

    private fun screenshot(name: String, web: WebView? = null) {
        if (web != null) {
            evaluate(web, "document.querySelector('section[aria-labelledby=\"memory-document-title\"]').scrollIntoView({block:'start'})")
            val drawn = CountDownLatch(1)
            instrumentation.runOnMainSync {
                web.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) { drawn.countDown() }
                })
            }
            assertTrue("等待保存状态绘制超时", drawn.await(5, TimeUnit.SECONDS))
        }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(200, 5000)
        Thread.sleep(200)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("等待系统文件窗口或页面状态超时")
    }
}
