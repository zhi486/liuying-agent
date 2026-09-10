package com.healthyagent.running.data.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.healthyagent.running.ui.theme.HealthyAgentTheme

/** Health Connect 权限页要求的本地隐私用途说明。 */
class HealthPermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HealthyAgentTheme {
                HealthPermissionsRationale(onClose = ::finish)
            }
        }
    }
}

@Composable
private fun HealthPermissionsRationale(onClose: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 56.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = "Health Connect 数据说明",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "流萤仅在你完成跑步后，将本次跑步的时间、距离和可选 GPS 路线写入 Health Connect。流萤不会读取这些健康数据，也不会把它们上传到服务器。你可以随时在系统的 Health Connect 设置中撤销权限或删除记录。",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onClose) {
                Text("知道了")
            }
        }
    }
}
