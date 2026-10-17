package com.healthyagent.running.ui.web

import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.healthyagent.running.BuildConfig
import com.healthyagent.running.agent.AgentTaskService
import com.healthyagent.running.agent.cloud.CloudAgentService
import com.healthyagent.running.agent.memory.MemoryService
import com.healthyagent.running.coordinator.AppCoordinator
import com.healthyagent.running.coordinator.RunCommandDispatcher
import java.io.ByteArrayInputStream

private const val APP_ASSET_HOST = "appassets.androidplatform.net"
private const val WEB_ROOT_PATH = "/assets/web/"
private const val WEB_ENTRY_URL = "https://$APP_ASSET_HOST${WEB_ROOT_PATH}index.html#/agent"

/**
 * 正式 React 宿主：只加载 APK 内资源，并通过受限 WebMessage Bridge 消费原生快照。
 */
@Composable
fun WebPreviewHost(
    coordinator: AppCoordinator,
    commandDispatcher: RunCommandDispatcher,
    agentTaskService: AgentTaskService,
    memoryService: MemoryService,
    cloudAgentService: CloudAgentService,
    voiceInputService: com.healthyagent.running.agent.voice.VoiceInputService,
    modifier: Modifier = Modifier,
) {
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()
    val nativeBridge = remember(coordinator, commandDispatcher, agentTaskService, memoryService, cloudAgentService, voiceInputService, scope) {
        NativeWebBridge(coordinator, commandDispatcher, agentTaskService, memoryService, scope, cloudAgentService, voiceInputService)
    }

    DisposableEffect(Unit) {
        onDispose {
            nativeBridge.detach()
            activeWebView?.apply {
                stopLoading()
                loadUrl("about:blank")
                removeAllViews()
                destroy()
            }
            activeWebView = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain(APP_ASSET_HOST)
                .setHttpAllowed(false)
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()

            WebView(context).apply {
                activeWebView = this
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    mediaPlaybackRequiresUserGesture = true
                }
                nativeBridge.attach(this)
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                webViewClient = LocalAssetsOnlyClient(
                    assetLoader = assetLoader,
                    onDocumentChanged = nativeBridge::resetDocument,
                )
                loadUrl(WEB_ENTRY_URL)
            }
        },
    )
}

private class LocalAssetsOnlyClient(
    private val assetLoader: WebViewAssetLoader,
    private val onDocumentChanged: () -> Unit,
) : WebViewClientCompat() {

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        onDocumentChanged()
        super.onPageStarted(view, url, favicon)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return !request.url.isTrustedEntry()
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        return if (request.url.isTrustedAsset()) {
            assetLoader.shouldInterceptRequest(request.url)
        } else {
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Blocked",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0)),
            )
        }
    }
}

private fun Uri.hasTrustedOrigin(): Boolean {
    return scheme == "https" &&
        host == APP_ASSET_HOST &&
        userInfo == null &&
        port == -1
}

private fun Uri.isTrustedAsset(): Boolean {
    return hasTrustedOrigin() && encodedPath?.startsWith(WEB_ROOT_PATH) == true
}

private fun Uri.isTrustedEntry(): Boolean {
    return hasTrustedOrigin() && encodedPath == "${WEB_ROOT_PATH}index.html"
}
