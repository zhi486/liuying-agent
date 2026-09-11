package com.healthyagent.running.ui.settings

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.healthyagent.running.coordinator.CommandOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 只持有当前窗口的输入；不回填、不进入可恢复状态，也不传给 Web。 */
class DeepseekKeyDialog(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val isCurrent: () -> Boolean,
    private val onSave: suspend (String) -> CommandOutcome,
    private val onClosed: () -> Unit,
) {
    internal val input = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine(true)
        isSaveEnabled = false
        setFreezesText(false)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        hint = "输入新的 DeepSeek 密钥"
    }
    private var saveJob: Job? = null
    private var previouslySecure = false
    private var opened = false
    internal val dialog: AlertDialog = AlertDialog.Builder(activity)
        .setTitle("DeepSeek 私密配置")
        .setMessage("密钥仅保存在本机私有目录，并排除系统备份；保存不代表连接成功，不会自动开启 AI 或试听。")
        .setView(input)
        .setNegativeButton("取消", null)
        .setPositiveButton("保存", null)
        .create()

    fun show() {
        if (opened) return
        if (!isCurrent() || activity.isFinishing || activity.isDestroyed) {
            input.text.clear()
            onClosed()
            return
        }
        previouslySecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { finishClose() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!isCurrent()) {
                    dismiss()
                    return@setOnClickListener
                }
                if (saveJob?.isActive == true) return@setOnClickListener
                saveJob = scope.launch {
                    try {
                        val result = onSave(input.text.toString())
                        if (result is CommandOutcome.Accepted) dismiss()
                        else if (isCurrent()) input.error = "保存失败，请检查输入或稍后重试"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (isCurrent()) input.error = "保存失败，请稍后重试"
                    }
                }
            }
        }
        try {
            opened = true
            dialog.show()
        } catch (_: Exception) {
            dismiss()
        }
    }

    fun dismiss() {
        input.text.clear()
        if (opened) {
            dialog.dismiss()
            // Android 的 OnDismiss 通过 Handler 迟到；主动关闭必须同步且只清理一次。
            finishClose()
        }
    }

    private fun finishClose() {
        if (!opened) return
        opened = false
        input.text.clear()
        saveJob?.cancel()
        if (!previouslySecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onClosed()
    }
}
