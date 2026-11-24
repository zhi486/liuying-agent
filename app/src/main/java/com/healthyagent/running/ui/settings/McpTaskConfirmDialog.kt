package com.healthyagent.running.ui.settings

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.healthyagent.running.agent.mcp.McpTaskConfirmation

/** 真实工具参数只在原生安全窗口展示；取消、超时、旧窗口点击都不是批准。 */
class McpTaskConfirmDialog(
    private val activity: Activity, private val confirmation: McpTaskConfirmation,
    private val request: McpTaskConfirmation.Request, private val night: Boolean,
    private val onClosed: () -> Unit,
) {
    internal val dialog = Dialog(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val invocation = request.invocation
    private var finished = false
    private var shown = false
    private val background = Color.parseColor(if (night) "#151c16" else "#ffffff")
    private val textColor = Color.parseColor(if (night) "#f0f6ef" else "#202a22")
    private val muted = Color.parseColor(if (night) "#b6c8b6" else "#5d6c60")
    private val accent = Color.parseColor(if (night) "#a9e86b" else "#147a58")
    private val surface = Color.parseColor(if (night) "#28372b" else "#eff8f3")
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20).toFloat() }
    private fun label(value: String, size: Float = 16f, color: Int = textColor) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); setTextIsSelectable(true)
        isSaveEnabled = false; setPadding(0, dp(8), 0, dp(8))
    }
    internal val arguments = label(invocation.argumentsJson, 15f).apply {
        typeface = Typeface.MONOSPACE; setHorizontallyScrolling(false)
        contentDescription = "完整调用参数，JSON 格式"; tag = "mcp-arguments"
    }
    private fun action(title: String, primary: Boolean, approve: Boolean) = Button(activity).apply {
        text = title; textSize = 16f; isAllCaps = false; minHeight = dp(48)
        setTextColor(if (primary) Color.parseColor(if (night) "#1b341f" else "#ffffff") else textColor)
        background = RippleDrawable(ColorStateList.valueOf(muted and 0x33ffffff), rounded(if (primary) accent else surface), null)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener {
            val valid = confirmation.requesting.value === request && System.currentTimeMillis() < invocation.expiresAtMillis
            confirmation.respond(request, approve && valid); dismiss()
        }
    }
    internal val approve = action("允许本次调用", true, true)
    internal val deny = action("不允许", false, false)
    private val expiry = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= invocation.expiresAtMillis || confirmation.requesting.value !== request) dismiss()
            else if (!finished) handler.postDelayed(this, 100)
        }
    }
    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(this@McpTaskConfirmDialog.background)
            setPadding(dp(20), dp(12), dp(20), dp(20))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        root.addView(label("确认插件调用", 21f).apply { setTypeface(typeface, Typeface.BOLD); androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
        root.addView(ScrollView(activity).apply {
            isFillViewport = true
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("此操作由插件执行。请核对完整参数；不确定时选择不允许。", color = muted))
                addView(label("服务：${invocation.scope.endpoint}\n工具：${invocation.name}"))
                addView(label("完整调用参数", color = muted)); addView(arguments)
                addView(label("仅本次调用有效，窗口过期或离开前台会关闭；不会自动重试。", 14f, muted))
            })
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(deny, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        root.addView(approve, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { finish() }
    }
    fun show() {
        if (shown || finished || activity.isFinishing || activity.isDestroyed || confirmation.requesting.value !== request) { dismiss(); return }
        shown = true
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        try {
            dialog.show()
            val screen = activity.resources.displayMetrics
            dialog.window?.setLayout(minOf(screen.widthPixels - dp(32), dp(480)), (screen.heightPixels * 0.86).toInt())
            deny.requestFocus()
            handler.post(expiry)
        } catch (_: Exception) { dismiss() }
    }
    fun dismiss() { dialog.dismiss(); finish() }
    private fun finish() {
        if (finished) return
        finished = true; handler.removeCallbacks(expiry)
        confirmation.respond(request, false)
        arguments.text = ""
        onClosed()
    }
}
