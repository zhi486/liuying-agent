package com.healthyagent.running.ui.settings

import android.text.InputType
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.MainActivity
import com.healthyagent.running.agent.cloud.CloudAgentConfiguration
import com.healthyagent.running.agent.cloud.CloudDeviceCredentials
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudAgentConfigDialogTest {
    @Test fun 私密窗口禁止截图回填和自动填充关闭清空输入且不保存() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val file = File(activity.noBackupFilesDir, "cloud-dialog-${UUID.randomUUID()}/identity.json")
                val configuration = CloudAgentConfiguration(file, CloudDeviceCredentials.create(activity))
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val oldFlags = activity.window.attributes.flags
                var closed = 0
                val prompt = CloudAgentConfigDialog(activity, scope, configuration) { closed++ }
                try {
                    prompt.show()
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    assertTrue(prompt.dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    prompt.fields.forEach { field ->
                        assertFalse(field.isSaveEnabled)
                        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, field.importantForAutofill)
                        assertEquals("", field.text.toString())
                        field.setText("仅合成测试")
                    }
                    assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, prompt.fields.last().inputType and InputType.TYPE_MASK_VARIATION)
                    prompt.dismiss(); prompt.dismiss()
                    assertEquals(1, closed)
                    assertEquals(oldFlags and WindowManager.LayoutParams.FLAG_SECURE, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                    assertTrue(prompt.fields.all { it.text.isEmpty() })
                    assertFalse(file.exists())
                } finally { prompt.dismiss(); scope.cancel() }
            }
        }
    }
}
