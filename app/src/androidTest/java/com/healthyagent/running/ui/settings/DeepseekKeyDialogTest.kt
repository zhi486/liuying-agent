package com.healthyagent.running.ui.settings

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.MainActivity
import com.healthyagent.running.coordinator.CommandOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 仅填测试标记并使用假保存；真机执行前仍须确认没有活动跑步。 */
@RunWith(AndroidJUnit4::class)
class DeepseekKeyDialogTest {
    @Test fun lateDismissCallbackCannotClearTheNextWindowsInputOrSecureFlag() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var next: DeepseekKeyDialog
            lateinit var scope: CoroutineScope
            var closes = 0
            var originalFlags = 0
            scenario.onActivity { activity ->
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                originalFlags = activity.window.attributes.flags
                val previous = DeepseekKeyDialog(activity, scope, { true }, { CommandOutcome.Accepted }, { closes++ })
                previous.show()
                previous.input.setText("test-only-previous")
                previous.dismiss()
                assertEquals(1, closes)
                next = DeepseekKeyDialog(activity, scope, { true }, { CommandOutcome.Accepted }, {})
                next.show()
                next.input.setText("test-only-next")
            }
            // 分开的主线程回调让前一窗口的 OnDismiss Handler 有机会执行。
            scenario.onActivity { activity ->
                try {
                    assertEquals(1, closes)
                    assertTrue(next.dialog.isShowing)
                    assertEquals("test-only-next", next.input.text.toString())
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    next.dismiss()
                    assertEquals("", next.input.text.toString())
                } finally {
                    next.dismiss()
                    activity.window.attributes = activity.window.attributes.apply { flags = originalFlags }
                    scope.cancel()
                }
            }
        }
    }

    @Test fun masksInputAndClearsItOnDismissWhileRestoringWindowFlags() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val originalFlags = activity.window.attributes.flags
                try {
                    for (secureInitially in listOf(false, true)) {
                        if (secureInitially) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                        var closed = false
                        val prompt = DeepseekKeyDialog(activity, scope, { true }, { error("取消不得保存") }, { closed = true })
                        prompt.show()
                        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                        assertTrue(prompt.dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                        assertFalse(prompt.input.isSaveEnabled)
                        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, prompt.input.inputType and InputType.TYPE_MASK_VARIATION)
                        assertTrue(prompt.input.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
                        assertTrue(prompt.input.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
                        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, prompt.input.importantForAutofill)
                        assertEquals("", prompt.input.text.toString())
                        prompt.input.setText("test-only-not-saved")
                        prompt.dismiss()
                        assertTrue(closed)
                        assertEquals("", prompt.input.text.toString())
                        assertEquals(secureInitially, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    }
                } finally {
                    activity.window.attributes = activity.window.attributes.apply { flags = originalFlags }
                    scope.cancel()
                }
            }
        }
    }

    @Test fun successfulFakeSaveClosesAndClearsWithoutReturningInput() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var scope: CoroutineScope
            lateinit var prompt: DeepseekKeyDialog
            var saves = 0
            scenario.onActivity { activity ->
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                prompt = DeepseekKeyDialog(activity, scope, { true }, {
                    assertEquals("test-only-fake-save", it)
                    saves++
                    CommandOutcome.Accepted
                }, {})
                prompt.show()
                prompt.input.setText("test-only-fake-save")
            }
            // OnShow 安装保存监听器后再点击，不能在 show 的同一调用栈抢先触发。
            scenario.onActivity {
                try {
                    prompt.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertEquals(1, saves)
                    assertEquals("", prompt.input.text.toString())
                    assertFalse(prompt.dialog.isShowing)
                } finally {
                    prompt.dismiss()
                    scope.cancel()
                }
            }
        }
    }
}
