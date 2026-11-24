package com.healthyagent.running.ui.settings

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.healthyagent.running.agent.cloud.CloudAgentConfiguration
import kotlinx.coroutines.*

/** 不回填凭据、不进 Web、不存窗口状态；关闭即清除输入。 */
class CloudAgentConfigDialog(private val activity: Activity, private val scope: CoroutineScope,
    private val configuration: CloudAgentConfiguration, private val onClosed: () -> Unit) {
    private fun field(label: String, secret: Boolean = false) = EditText(activity).apply {
        hint = label
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        setSingleLine(true); isSaveEnabled = false; setFreezesText(false)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
    private val origin = field("云服务地址（https://…，不带路径）")
    private val owner = field("云端 ownerId")
    private val device = field("云端 deviceId")
    private val bearer = field("设备凭据 lyd_…（不是模型密钥）", true)
    internal val fields = listOf(origin, owner, device, bearer)
    private var opened = false
    private var previouslySecure = false
    private var job: Job? = null
    internal val dialog = AlertDialog.Builder(activity).setTitle("在线 Agent 私密配置")
        .setMessage("仅填入你信任的云服务及其签发的设备凭据。凭据经 Keystore 加密并排除备份；保存不会联网，也不等于授权发送。更换配置后只显示新配置的在线任务，旧记录保留、不转发。")
        .setView(ScrollView(activity).apply { addView(LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; fields.forEach(::addView) }) })
        .setNegativeButton("取消", null).setPositiveButton("保存配置", null).create()

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
                val values = fields.map { it.text.toString() }
                job = scope.launch {
                    try {
                        withContext(Dispatchers.IO) { configuration.save(values[0], values[1], values[2], values[3]) }
                        dismiss()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (opened) bearer.error = "保存失败，请检查地址、身份与设备凭据格式" }
                }
            }
        }
        try { dialog.show() } catch (_: Exception) { dismiss() }
    }
    fun dismiss() { dialog.dismiss(); finish() }
    private fun finish() {
        if (!opened) return
        opened = false; fields.forEach { it.text.clear() }; job?.cancel()
        if (!previouslySecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onClosed()
    }
}
