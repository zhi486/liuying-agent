package com.healthyagent.running.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthyagent.running.core.model.CoachTone
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDataStoreTest {
    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        file = File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        file.delete()
    }

    @Test
    fun current和原子多字段更新都能完成() = runBlocking {
        val repository = SettingsDataStore(dataStore)

        val initial = withTimeout(1_000) { repository.current() }
        assertEquals(SettingsDataStore.DEFAULT_MAX_HR, initial.maxHeartRate)

        val nextEmission = async(start = CoroutineStart.UNDISPATCHED) {
            repository.settings.drop(1).first()
        }
        repository.update(
            SettingsPatch(
                targetPaceSecPerKm = NullableSettingUpdate.Set(330.0),
                targetDistanceKm = NullableSettingUpdate.Clear,
                maxHeartRate = 192,
                ttsEnabled = false,
                coachTone = CoachTone.DIRECT,
                themePreference = ThemePreference.NIGHT,
                motionPreference = MotionPreference.REDUCED,
            ),
        )

        val updated = withTimeout(1_000) { nextEmission.await() }
        assertEquals(330.0, updated.targetPaceSecPerKm)
        assertEquals(null, updated.targetDistanceKm)
        assertEquals(192, updated.maxHeartRate)
        assertEquals(false, updated.ttsEnabled)
        assertEquals(CoachTone.DIRECT, updated.coachTone)
        assertEquals(ThemePreference.NIGHT, updated.themePreference)
        assertEquals(MotionPreference.REDUCED, updated.motionPreference)
    }
}
