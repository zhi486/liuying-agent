package com.healthyagent.running.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import com.healthyagent.running.core.model.effectiveHrAlertThresholdBpm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class HeartRateAlertSettingsTest {
    @Test
    fun `旧偏好保持最大心率并使用自动门槛与120秒默认间隔`() = runTest {
        val store = SettingsDataStore(MemoryStore(preferencesOf(intPreferencesKey("max_hr") to 180)))
        val snapshot = store.current()
        assertNull(snapshot.hrAlertThresholdBpm)
        assertEquals(120, snapshot.hrAlertRepeatIntervalSec)
        assertEquals(162, snapshot.toRunConfig().effectiveHrAlertThresholdBpm())
        assertEquals(180, snapshot.maxHeartRate)
    }

    @Test
    fun `手动值持久化且清空恢复自动不改变重复间隔`() = runTest {
        val memory = MemoryStore()
        val store = SettingsDataStore(memory)
        store.update(SettingsPatch(hrAlertThresholdBpm = NullableSettingUpdate.Set(170)))
        store.update(SettingsPatch(maxHeartRate = 200, hrAlertRepeatIntervalSec = 180))
        assertEquals(170, SettingsDataStore(memory).current().toRunConfig().effectiveHrAlertThresholdBpm())
        store.update(SettingsPatch(hrAlertThresholdBpm = NullableSettingUpdate.Clear))
        assertNull(store.current().hrAlertThresholdBpm)
        assertEquals(180, store.current().toRunConfig().effectiveHrAlertThresholdBpm())
        assertEquals(180, store.current().toRunConfig().hrAlertRepeatIntervalSec)
    }

    @Test
    fun `合法边界保存且无关偏好更新不改警戒设置`() = runTest {
        val store = SettingsDataStore(MemoryStore())
        for ((threshold, interval) in listOf(80 to 30, 240 to 300)) {
            store.update(SettingsPatch(hrAlertThresholdBpm = NullableSettingUpdate.Set(threshold), hrAlertRepeatIntervalSec = interval))
            store.update(SettingsPatch(ttsEnabled = false))
            assertEquals(threshold, store.current().hrAlertThresholdBpm)
            assertEquals(interval, store.current().hrAlertRepeatIntervalSec)
        }
    }

    @Test
    fun `非法警戒值使整个补丁拒绝且已有值不变`() = runTest {
        val store = SettingsDataStore(MemoryStore())
        for (threshold in listOf(79, 241)) {
            val previous = store.current()
            try {
                store.update(SettingsPatch(maxHeartRate = 200, hrAlertThresholdBpm = NullableSettingUpdate.Set(threshold)))
                fail("非法阈值不能保存")
            } catch (_: IllegalArgumentException) {
                assertEquals(previous, store.current())
            }
        }
    }

    @Test
    fun `非法重复间隔不能静默截断或部分保存`() = runTest {
        val store = SettingsDataStore(MemoryStore())
        for (interval in listOf(29, 301)) {
            val previous = store.current()
            try {
                store.update(SettingsPatch(maxHeartRate = 200, hrAlertRepeatIntervalSec = interval))
                fail("非法间隔不能保存")
            } catch (_: IllegalArgumentException) {
                assertEquals(previous, store.current())
            }
        }
    }

    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
