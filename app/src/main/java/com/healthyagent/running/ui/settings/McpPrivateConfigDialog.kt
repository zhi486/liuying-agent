package com.healthyagent.running.ui.settings

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.healthyagent.running.agent.mcp.McpConfigurationRequest
import com.healthyagent.running.agent.mcp.McpManagementService
import kotlinx.coroutines.*

/** 插件密钥只留在原生安全窗口，默认不回填，也不把空输入解释为匿名授权。 */
class McpPrivateConfigDialog(private val activity: Activity, private val scope: CoroutineScope,
    private val service: McpManagementService, private val request: McpConfigurationRequest, private val onClosed: () -> Unit) {
    private val bearer = EditText(activity).apply {
        hint = "新的插件 Bearer 密钥（不是云设备或模型密钥）"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(true); isSaveEnabled = false; setFreezesText(false)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
    private val anonymous = CheckBox(activity).apply {
        text = "此服务无需密钥，明确保存为匿名连接"
        isSaveEnabled = false
        setOnCheckedChangeListener { _, checked -> bearer.isEnabled = !checked; if (checked) bearer.text.clear() }
    }
    private var opened = false
    private var previouslySecure = false
    private var job: Job? = null
    private val dialog = AlertDialog.Builder(activity).setTitle("插件私密配置")
        .setMessage("目标服务：${request.endpoint}\n密钥经 Keystore 加密并排除备份，不进入网页或云端。保存前仅向已配置的云服务核对版本，不连接插件、不启用工具。已有密钥不回填；取消不会修改。")
        .setView(ScrollView(activity).apply { addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0); addView(bearer); addView(anonymous)
        }) }).setNegativeButton("取消", null).setPositiveButton("保存到手机", null).create()
    fun show() {
        if (opened || activity.isFinishing || activity.isDestroyed) return
        previouslySecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        opened = true
        dialog.setOnDismissListener { finish() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!opened || job?.isActive == true) return@setOnClickListener
                val input = bearer.text.toString()
                val withoutKey = anonymous.isChecked
                if (!withoutKey && input.isBlank()) { bearer.error = "请输入新密钥，或明确选择匿名连接"; return@setOnClickListener }
                job = scope.launch {
                    try { service.saveConfiguration(request, input, withoutKey); dismiss() }
                    catch (_: TimeoutCancellationException) { if (opened) bearer.error = "核对超时，未确认保存，请关闭后重新读取配置" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (opened) bearer.error = "保存未确认，请核对服务版本和密钥格式；不会自动重试" }
                }
            }
        }
        try { dialog.show() } catch (_: Exception) { dismiss() }
    }
    fun dismiss() { dialog.dismiss(); finish() }
    private fun finish() {
        if (!opened) return
        opened = false; bearer.text.clear(); job?.cancel()
        if (!previouslySecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        service.closeConfiguration(request.token); onClosed()
    }
}
