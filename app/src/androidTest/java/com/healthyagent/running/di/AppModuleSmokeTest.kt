package com.healthyagent.running.di

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.HealthyAgentApp
import com.healthyagent.running.agent.memory.MemoryService
import com.healthyagent.running.agent.cloud.CloudAgentService
import com.healthyagent.running.coordinator.AppCoordinator
import com.healthyagent.running.core.engine.RuleEngine
import com.healthyagent.running.data.sensor.SensorDataSource
import com.healthyagent.running.diagnostics.DiagnosticLogger
import com.healthyagent.running.speech.AudioFocusController
import com.healthyagent.running.speech.TtsEngine
import com.healthyagent.running.speech.VoicePolicyTtsEngine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class AppModuleSmokeTest {
    @Test
    fun productionGraphResolvesAndInfrastructureIsSingleton() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        assertTrue(application is HealthyAgentApp)
        val koin = GlobalContext.get()

        assertNotNull(koin.get<AppCoordinator>())
        assertSame(koin.get<MemoryService>(), koin.get<MemoryService>())
        assertSame(koin.get<CloudAgentService>(), koin.get<CloudAgentService>())
        assertTrue(koin.get<TtsEngine>() is VoicePolicyTtsEngine)
        assertNotNull(koin.get<SensorDataSource>())
        assertNotNull(koin.get<RuleEngine>())
        assertSame(koin.get<DiagnosticLogger>(), koin.get<DiagnosticLogger>())
        assertSame(koin.get<AudioFocusController>(), koin.get<AudioFocusController>())
    }
}
