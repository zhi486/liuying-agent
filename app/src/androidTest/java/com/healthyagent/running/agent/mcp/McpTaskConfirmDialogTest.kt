package com.healthyagent.running.agent.mcp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.WindowManager
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthyagent.running.MainActivity
import com.healthyagent.running.agent.cloud.*
import com.healthyagent.running.ui.settings.McpTaskConfirmDialog
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 只画合成对话框视图，不清除安全标记、不截图真实配置、不产生网络调用。 */
@RunWith(AndroidJUnit4::class)
class McpTaskConfirmDialogTest {
    private fun invocation(): McpTaskInvocation {
        fun id(seed: Int) = "%08x-1111-4111-8111-%012x".format(seed, seed.toLong())
        val start = System.currentTimeMillis()
        val endpoint = "https://tools.example.com/mcp/long-service-for-synthetic-mobile-layout-review"
        val identity = CloudTaskIdentity("https://agent.example.com", "owner", "device", "credential")
        val model = "mcp_${id(1).replace("-", "")}_0"
        val intent = CloudTaskIntent.confirm(identity, "合成测试，不发送", true, start).copy(
            conversationId = id(4), conversationJson = """{"version":"conversation_v1","id":"${id(4)}","approved":true,"messages":[]}""",
            mcpToolsJson = """{"version":"native_mcp_v1","services":[{"serviceId":"${id(1)}","revision":"${id(2)}","generation":"${id(3)}"}]}""",
            submissionAttempted = true,
        )
        val service = Json.parseToJsonElement("""{"id":"${id(1)}","revision":"${id(2)}","endpoint":"$endpoint","enabled":true,"catalog":{"fingerprint":"${"a".repeat(64)}","tools":[{"name":"notes.write","definition":{"name":"notes.write","inputSchema":{"type":"object"}}}]},"grants":[{"name":"notes.write","mode":"confirm","modelName":"$model"}]}""").jsonObject
        val receipt = CloudTaskReceipt(intent.clientRequestId, id(5), "running", null, start, start + 30000, start, "none", null, false, null)
        val context = McpTaskContext.capture(intent, receipt, listOf(service to McpPrivateStatus(true, false, endpoint, id(3), "b".repeat(64))))
        val args = buildJsonObject {
            put("title", "今晚轻松跑（合成测试）")
            put("content", "不是真实记忆。".repeat(100))
            put("literal", "<button>不执行 HTML</button>")
        }.toString()
        return McpTaskInvocation.parse(buildJsonObject {
            put("version", "native_mcp_v1"); put("requestId", intent.clientRequestId); put("taskId", id(5)); put("serviceId", id(1))
            put("revision", id(2)); put("generation", id(3)); put("endpoint", endpoint); put("invocationId", id(6))
            put("intentDigest", "c".repeat(64)); put("argumentsDigest", MessageDigest.getInstance("SHA-256").digest(args.toByteArray()).joinToString("") { "%02x".format(it) })
            put("modelName", model); put("name", "notes.write"); put("mode", "confirm"); put("argumentsJson", args); put("expiresAtMillis", start + 30000)
        }, context)
    }

    @Test fun 完整长参数可滚动两主题绘制且按钮对齐取消不批准() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (night in listOf(false, true)) {
                lateinit var prompt: McpTaskConfirmDialog
                lateinit var scope: CoroutineScope
                lateinit var confirmation: McpTaskConfirmation
                lateinit var request: McpTaskConfirmation.Request
                var answer: Boolean? = null
                var closed = 0
                scenario.onActivity { activity ->
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                    confirmation = McpTaskConfirmation()
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { answer = confirmation.ask(invocation()) }
                    request = requireNotNull(confirmation.requesting.value)
                    prompt = McpTaskConfirmDialog(activity, confirmation, request, night) { closed++ }
                    prompt.show()
                    assertNull(answer)
                    assertEquals(request.invocation.argumentsJson, prompt.arguments.text.toString())
                    assertTrue(prompt.dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    try {
                        assertTrue(prompt.dialog.isShowing)
                        assertEquals(prompt.approve.width, prompt.deny.width)
                        assertTrue(prompt.approve.height >= (48 * activity.resources.displayMetrics.density).toInt())
                        assertTrue(prompt.arguments.layout.lineCount > 10)
                        assertTrue(prompt.arguments.width < activity.resources.displayMetrics.widthPixels)
                        val view = prompt.dialog.window!!.decorView
                        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        view.draw(Canvas(bitmap))
                        File(activity.getExternalFilesDir(null), "mcp-confirm-${if (night) "night" else "morning"}.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                        val scroller = prompt.arguments.parent.parent as ScrollView
                        scroller.scrollTo(0, scroller.getChildAt(0).height)
                        assertTrue(scroller.scrollY > 0)
                        assertEquals(scroller.getChildAt(0).height - scroller.height, scroller.scrollY)
                        prompt.deny.performClick()
                        assertEquals(false, answer); assertEquals(1, closed)
                        assertEquals("", prompt.arguments.text.toString())
                        assertFalse(confirmation.respond(request, true))
                        prompt.dismiss(); assertEquals(1, closed)
                    } finally { prompt.dismiss(); scope.cancel() }
                }
            }
        }
    }

    @Test fun 当前窗口可批准一次旧窗口与系统取消均不批准() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val confirmation = McpTaskConfirmation()
            var answer: Boolean? = null
            lateinit var previous: McpTaskConfirmDialog
            var currentDialog: McpTaskConfirmDialog? = null
            try {
                scenario.onActivity { activity ->
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { answer = confirmation.ask(invocation()) }
                    val first = requireNotNull(confirmation.requesting.value)
                    previous = McpTaskConfirmDialog(activity, confirmation, first, false) {}
                    previous.show(); previous.dialog.cancel()
                }
                // 系统取消通过 Dialog 的主线程消息派发，不在同一回调内假定 OnDismiss 已执行。
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    assertEquals(false, answer)
                    answer = null
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { answer = confirmation.ask(invocation()) }
                    val current = requireNotNull(confirmation.requesting.value)
                    val prompt = McpTaskConfirmDialog(activity, confirmation, current, false) {}
                    currentDialog = prompt
                    prompt.show()
                    previous.approve.performClick()
                    assertNull(answer)
                    prompt.approve.performClick()
                    assertEquals(true, answer)
                    assertFalse(confirmation.respond(current, true))
                }
            } finally { scenario.onActivity { currentDialog?.dismiss(); scope.cancel() } }
        }
    }
}
