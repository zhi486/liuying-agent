package com.healthyagent.running.ui.web

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import com.healthyagent.running.BuildConfig
import com.healthyagent.running.bridge.BridgeErrorCode
import com.healthyagent.running.bridge.BridgeSession
import com.healthyagent.running.coordinator.AppCoordinator
import com.healthyagent.running.coordinator.CommandOutcome
import com.healthyagent.running.coordinator.RunCommandDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** 本地 React 文档与原生协调器之间的受限 WebMessage 通道。 */
class NativeWebBridge(
    private val coordinator: AppCoordinator,
    private val commandDispatcher: RunCommandDispatcher,
    private val scope: CoroutineScope,
) {
    private var replyProxy: JavaScriptReplyProxy? = null
    private var snapshotJob: Job? = null
    private val session = BridgeSession(
        buildId = "${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_TYPE}",
        snapshotProvider = { coordinator.snapshot.value },
        commandHandler = ::handleCommand,
        send = { message -> replyProxy?.postMessage(message) },
    )

    fun attach(webView: WebView) {
        WebViewCompat.addWebMessageListener(
            webView,
            JS_OBJECT_NAME,
            setOf(APP_ORIGIN),
        ) { _, message: WebMessageCompat, sourceOrigin: Uri, isMainFrame: Boolean, proxy ->
            if (!isMainFrame || sourceOrigin.toString() != APP_ORIGIN) return@addWebMessageListener
            val data = message.data ?: return@addWebMessageListener
            replyProxy = proxy
            scope.launch { session.onMessage(data) }
        }

        snapshotJob = scope.launch {
            coordinator.snapshot.drop(1).collect(session::pushSnapshot)
        }
    }

    fun resetDocument() {
        replyProxy = null
        session.reset()
    }

    fun detach() {
        snapshotJob?.cancel()
        snapshotJob = null
        resetDocument()
    }

    private suspend fun handleCommand(name: String, body: JsonObject): CommandOutcome = when (name) {
        "device.connect" -> commandDispatcher.connectSensor()
        "device.disconnect" -> commandDispatcher.disconnectSensor()
        "run.start" -> commandDispatcher.startRun()
        "run.pause" -> commandDispatcher.pauseRun()
        "run.resume" -> commandDispatcher.resumeRun()
        "run.stop" -> commandDispatcher.stopRun()
        "history.query" -> coordinator.queryHistory(
            page = body.getValue("page").jsonPrimitive.int,
            pageSize = body.getValue("pageSize").jsonPrimitive.int,
        )
        "settings.query" -> coordinator.querySettings()
        "settings.configureDeepseek" -> coordinator.requestDeepseekConfiguration()
        "settings.update" -> coordinator.updateSettings(body.toSettingsPatch())
        "permission.request" -> coordinator.requestRunningPermissions()
        else -> CommandOutcome.Rejected(BridgeErrorCode.INVALID_MESSAGE, "未知命令")
    }

    companion object {
        const val APP_ORIGIN = "https://appassets.androidplatform.net"
        const val JS_OBJECT_NAME = "LiuyingNative"
    }
}
