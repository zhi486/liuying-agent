package com.healthyagent.running.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import com.healthyagent.running.data.local.NullableSettingUpdate
import com.healthyagent.running.data.local.SettingsDataStore
import com.healthyagent.running.data.local.SettingsPatch
import com.healthyagent.running.data.local.SettingsRepository
import com.healthyagent.running.data.local.SettingsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeartRateAlertViewModelTest {
    @Test
    fun `旧保存回显不能覆盖后来未保存的输入`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = SettingsDataStore(MemoryStore())
        val viewModel = SettingsViewModel(store)
        try {
            runCurrent()
            viewModel.setHrAlertThresholdBpm("170")
            viewModel.setHrAlertThresholdBpm("17")
            runCurrent()
            assertEquals(170, store.current().hrAlertThresholdBpm)
            assertEquals("17", viewModel.hrAlertThresholdBpm.value)
            assertNotNull(viewModel.hrAlertError.value)
            viewModel.setHrAlertRepeatIntervalSec("180")
            viewModel.setHrAlertRepeatIntervalSec("18")
            runCurrent()
            assertEquals(180, store.current().hrAlertRepeatIntervalSec)
            assertEquals("18", viewModel.hrAlertRepeatIntervalSec.value)
            assertEquals("17", viewModel.hrAlertThresholdBpm.value)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `另一字段成功不能覆盖失败输入或掩盖错误`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val backing = SettingsDataStore(MemoryStore())
        var rejectThreshold = true
        val store = object : SettingsRepository {
            override val settings = backing.settings
            override suspend fun current() = backing.current()
            override suspend fun update(patch: SettingsPatch) {
                if (rejectThreshold && patch.hrAlertThresholdBpm != NullableSettingUpdate.Unchanged) error("测试阈值保存失败")
                backing.update(patch)
            }
        }
        val viewModel = SettingsViewModel(store)
        try {
            runCurrent()
            viewModel.setHrAlertThresholdBpm("170")
            runCurrent()
            assertEquals("心率警戒设置保存失败，请重试", viewModel.hrAlertError.value)
            viewModel.setHrAlertRepeatIntervalSec("180")
            runCurrent()
            assertEquals(180, store.current().hrAlertRepeatIntervalSec)
            assertNull(store.current().hrAlertThresholdBpm)
            assertEquals("170", viewModel.hrAlertThresholdBpm.value)
            assertEquals("心率警戒设置保存失败，请重试", viewModel.hrAlertError.value)
            rejectThreshold = false
            viewModel.setHrAlertThresholdBpm("170")
            runCurrent()
            assertEquals(170, store.current().hrAlertThresholdBpm)
            assertNull(viewModel.hrAlertError.value)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `原生入口读取已保存值并可修改和清空`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = SettingsDataStore(MemoryStore())
        store.update(SettingsPatch(hrAlertThresholdBpm = NullableSettingUpdate.Set(170), hrAlertRepeatIntervalSec = 180))
        val viewModel = SettingsViewModel(store)
        try {
            runCurrent()
            assertEquals("170", viewModel.hrAlertThresholdBpm.value)
            assertEquals("180", viewModel.hrAlertRepeatIntervalSec.value)
            viewModel.setHrAlertThresholdBpm("175")
            viewModel.setHrAlertRepeatIntervalSec("150")
            runCurrent()
            assertEquals(175, store.current().hrAlertThresholdBpm)
            assertEquals(150, store.current().hrAlertRepeatIntervalSec)
            viewModel.setHrAlertThresholdBpm("")
            runCurrent()
            assertNull(store.current().hrAlertThresholdBpm)
            assertEquals(150, store.current().hrAlertRepeatIntervalSec)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `原生入口拒绝非法值并显示错误而非保存成功`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = SettingsDataStore(MemoryStore())
        val viewModel = SettingsViewModel(store)
        try {
            runCurrent()
            for (input in listOf("79", "241", "170.5")) {
                viewModel.setHrAlertThresholdBpm(input)
                runCurrent()
                assertNull(store.current().hrAlertThresholdBpm)
                assertNotNull(viewModel.hrAlertError.value)
            }
            for (input in listOf("29", "301", "", "120.5")) {
                viewModel.setHrAlertRepeatIntervalSec(input)
                runCurrent()
                assertEquals(120, store.current().hrAlertRepeatIntervalSec)
                assertNotNull(viewModel.hrAlertError.value)
            }
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `保存失败提示稳定错误并保留输入供重试`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = object : SettingsRepository {
            override val settings = MutableStateFlow(SettingsSnapshot())
            override suspend fun current() = settings.value
            override suspend fun update(patch: SettingsPatch) { error("测试存储不可用") }
        }
        val viewModel = SettingsViewModel(store)
        try {
            runCurrent()
            viewModel.setHrAlertThresholdBpm("170")
            runCurrent()
            assertEquals("170", viewModel.hrAlertThresholdBpm.value)
            assertEquals("心率警戒设置保存失败，请重试", viewModel.hrAlertError.value)
            assertNull(store.current().hrAlertThresholdBpm)
        } finally {
            viewModel.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
