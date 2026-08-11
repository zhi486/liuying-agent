package com.healthyagent.running

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.healthyagent.running.core.pipeline.RunningDataPipeline
import com.healthyagent.running.data.sensor.SensorDataSource
import com.healthyagent.running.ui.running.RunningScreen
import com.healthyagent.running.ui.running.RunningViewModel
import com.healthyagent.running.ui.theme.HealthyAgentTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val sensorDataSource: SensorDataSource by inject()
    private val pipeline: RunningDataPipeline by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            HealthyAgentTheme {
                val viewModel = RunningViewModel(
                    sensorDataSource = sensorDataSource,
                    pipeline = pipeline,
                    runConfig = com.healthyagent.running.core.model.RunConfig(
                        targetPaceSecPerKm = 360.0,
                        targetDistanceMeters = 5000.0,
                        maxHeartRate = 180,
                    ),
                )
                RunningScreen(viewModel = viewModel)
            }
        }
    }
}
