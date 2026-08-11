package com.healthyagent.running.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 用户设置持久化管理器 — DataStore Preferences 封装
 *
 * 提供类型安全的读写接口 + Flow 实时订阅。
 */
class SettingsDataStore(private val context: Context) {

    // ── 读 ──

    val targetPaceSecPerKm: Flow<Double?>
        get() = context.dataStore.data.map { it[KEY_TARGET_PACE] }

    val targetDistanceKm: Flow<Double?>
        get() = context.dataStore.data.map { it[KEY_TARGET_DISTANCE] }

    val maxHeartRate: Flow<Int>
        get() = context.dataStore.data.map { it[KEY_MAX_HR] ?: DEFAULT_MAX_HR }

    val ttsEnabled: Flow<Boolean>
        get() = context.dataStore.data.map { it[KEY_TTS_ENABLED] ?: true }

    val ttsMinIntervalSec: Flow<Int>
        get() = context.dataStore.data.map { it[KEY_TTS_INTERVAL] ?: DEFAULT_TTS_INTERVAL }

    val llmEnabled: Flow<Boolean>
        get() = context.dataStore.data.map { it[KEY_LLM_ENABLED] ?: false }

    val mockScenario: Flow<String>
        get() = context.dataStore.data.map { it[KEY_MOCK_SCENARIO] ?: "EASY_RUN" }

    val fishApiKey: Flow<String>
        get() = context.dataStore.data.map { it[KEY_FISH_API_KEY] ?: "" }

    val fishReferenceId: Flow<String>
        get() = context.dataStore.data.map {
            it[KEY_FISH_REFERENCE_ID] ?: DEFAULT_FISH_REFERENCE_ID
        }

    val llmApiKey: Flow<String>
        get() = context.dataStore.data.map { it[KEY_LLM_API_KEY] ?: "" }

    // ── 写（suspend） ──

    suspend fun setTargetPace(secPerKm: Double?) {
        context.dataStore.edit { prefs ->
            if (secPerKm != null) prefs[KEY_TARGET_PACE] = secPerKm
            else prefs.remove(KEY_TARGET_PACE)
        }
    }

    suspend fun setTargetDistance(km: Double?) {
        context.dataStore.edit { prefs ->
            if (km != null) prefs[KEY_TARGET_DISTANCE] = km
            else prefs.remove(KEY_TARGET_DISTANCE)
        }
    }

    suspend fun setMaxHeartRate(hr: Int) {
        context.dataStore.edit { it[KEY_MAX_HR] = hr }
    }

    suspend fun setTtsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_TTS_ENABLED] = enabled }
    }

    suspend fun setTtsMinInterval(sec: Int) {
        context.dataStore.edit { it[KEY_TTS_INTERVAL] = sec }
    }

    suspend fun setLlmEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_LLM_ENABLED] = enabled }
    }

    suspend fun setMockScenario(scenario: String) {
        context.dataStore.edit { it[KEY_MOCK_SCENARIO] = scenario }
    }

    suspend fun setFishApiKey(key: String) {
        context.dataStore.edit { it[KEY_FISH_API_KEY] = key }
    }

    suspend fun setFishReferenceId(refId: String) {
        context.dataStore.edit { it[KEY_FISH_REFERENCE_ID] = refId }
    }

    suspend fun setLlmApiKey(key: String) {
        context.dataStore.edit { it[KEY_LLM_API_KEY] = key }
    }

    // ── 全量读取（用于初始化 RunConfig） ──

    suspend fun loadAll(): SettingsSnapshot {
        val prefs = context.dataStore.data
        var snapshot = SettingsSnapshot()
        // 收集第一条（当前值）作为快照
        prefs.collect { p ->
            snapshot = SettingsSnapshot(
                targetPaceSecPerKm = p[KEY_TARGET_PACE],
                targetDistanceKm = p[KEY_TARGET_DISTANCE],
                maxHeartRate = p[KEY_MAX_HR] ?: DEFAULT_MAX_HR,
                ttsEnabled = p[KEY_TTS_ENABLED] ?: true,
                ttsMinIntervalSec = p[KEY_TTS_INTERVAL] ?: DEFAULT_TTS_INTERVAL,
                llmEnabled = p[KEY_LLM_ENABLED] ?: false,
                mockScenario = p[KEY_MOCK_SCENARIO] ?: "EASY_RUN",
                fishApiKey = p[KEY_FISH_API_KEY] ?: "",
                fishReferenceId = p[KEY_FISH_REFERENCE_ID] ?: DEFAULT_FISH_REFERENCE_ID,
                llmApiKey = p[KEY_LLM_API_KEY] ?: "",
            )
            return@collect // 只取第一条
        }
        return snapshot
    }

    companion object {
        private val KEY_TARGET_PACE = doublePreferencesKey("target_pace")
        private val KEY_TARGET_DISTANCE = doublePreferencesKey("target_distance")
        private val KEY_MAX_HR = intPreferencesKey("max_hr")
        private val KEY_TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        private val KEY_TTS_INTERVAL = intPreferencesKey("tts_interval")
        private val KEY_LLM_ENABLED = booleanPreferencesKey("llm_enabled")
        private val KEY_MOCK_SCENARIO = stringPreferencesKey("mock_scenario")
        private val KEY_FISH_API_KEY = stringPreferencesKey("fish_api_key")
        private val KEY_FISH_REFERENCE_ID = stringPreferencesKey("fish_reference_id")
        private val KEY_LLM_API_KEY = stringPreferencesKey("llm_api_key")

        const val DEFAULT_MAX_HR = 180
        const val DEFAULT_TTS_INTERVAL = 4
        /** LIUYING 声音克隆参考 ID（来自 chatbot 项目） */
        const val DEFAULT_FISH_REFERENCE_ID = "79b9954c15f24deeab2319ec5f44f3b0"
    }
}

/**
 * 设置快照 — 用于一次性读取所有设置（初始化 RunConfig 时使用）
 */
data class SettingsSnapshot(
    val targetPaceSecPerKm: Double? = null,
    val targetDistanceKm: Double? = null,
    val maxHeartRate: Int = SettingsDataStore.DEFAULT_MAX_HR,
    val ttsEnabled: Boolean = true,
    val ttsMinIntervalSec: Int = SettingsDataStore.DEFAULT_TTS_INTERVAL,
    val llmEnabled: Boolean = false,
    val mockScenario: String = "EASY_RUN",
    val fishApiKey: String = "",
    val fishReferenceId: String = SettingsDataStore.DEFAULT_FISH_REFERENCE_ID,
    val llmApiKey: String = "",
)
