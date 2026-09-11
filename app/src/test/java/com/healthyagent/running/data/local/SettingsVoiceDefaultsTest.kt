package com.healthyagent.running.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SettingsVoiceDefaultsTest {
    @Test fun `DeepSeek开关不继承旧LLM开关且只写新键`() = runTest {
        val oldKey = booleanPreferencesKey("llm_enabled")
        val newKey = booleanPreferencesKey("deepseek_companion_enabled")
        val store = MemoryStore(preferencesOf(oldKey to true))
        val repository = SettingsDataStore(store)
        assertFalse(repository.current().llmEnabled)
        repository.update(SettingsPatch(llmEnabled = true))
        assertEquals(true, store.data.value[newKey])
        repository.update(SettingsPatch(llmEnabled = false))
        assertEquals(true, store.data.value[oldKey])
        assertEquals(false, store.data.value[newKey])
    }

    @Test fun `空偏好与默认快照不提供私人声线`() = runTest {
        assertTrue(SettingsSnapshot().fishReferenceId.isEmpty())
        assertTrue(SettingsDataStore(MemoryStore()).current().fishReferenceId.isEmpty())
    }

    @Test fun `仅保存Key不会自动补声线且无关更新仍保持未配置`() = runTest {
        val repository = SettingsDataStore(MemoryStore(preferencesOf(
            stringPreferencesKey("fish_api_key") to "fixture-key",
        )))
        assertTrue(repository.current().fishReferenceId.isEmpty())
        repository.update(SettingsPatch(maxHeartRate = 190))
        assertTrue(repository.current().fishReferenceId.isEmpty())
        assertEquals("fixture-key", repository.current().fishApiKey)
    }

    @Test fun `已持久化自定义声线在读取和无关更新后保留`() = runTest {
        val repository = SettingsDataStore(MemoryStore(preferencesOf(
            stringPreferencesKey("fish_reference_id") to "fixture-reference",
        )))
        assertEquals("fixture-reference", repository.current().fishReferenceId)
        repository.update(SettingsPatch(ttsEnabled = false))
        assertEquals("fixture-reference", repository.current().fishReferenceId)
    }

    private class MemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }
}
