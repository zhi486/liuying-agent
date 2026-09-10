package com.healthyagent.running.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.healthyagent.running.core.model.CoachTone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** DataStore Preferences 的原子设置仓库。 */
class SettingsDataStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    constructor(context: Context) : this(context.settingsDataStore)

    override val settings: Flow<SettingsSnapshot> = dataStore.data.map(::toSnapshot)

    val targetPaceSecPerKm: Flow<Double?> = settings.map { it.targetPaceSecPerKm }
    val targetDistanceKm: Flow<Double?> = settings.map { it.targetDistanceKm }
    val maxHeartRate: Flow<Int> = settings.map { it.maxHeartRate }
    val ttsEnabled: Flow<Boolean> = settings.map { it.ttsEnabled }
    val ttsMinIntervalSec: Flow<Int> = settings.map { it.ttsMinIntervalSec }
    val llmEnabled: Flow<Boolean> = settings.map { it.llmEnabled }
    val mockScenario: Flow<String> = settings.map { it.mockScenario }
    val fishApiKey: Flow<String> = settings.map { it.fishApiKey }
    val fishReferenceId: Flow<String> = settings.map { it.fishReferenceId }
    val llmApiKey: Flow<String> = settings.map { it.llmApiKey }

    override suspend fun current(): SettingsSnapshot = toSnapshot(dataStore.data.first())

    override suspend fun update(patch: SettingsPatch) {
        val errors = patch.validate()
        require(errors.isEmpty()) { "设置更新无效：${errors.joinToString()}" }

        dataStore.edit { preferences ->
            when (val value = patch.targetPaceSecPerKm) {
                NullableSettingUpdate.Unchanged -> Unit
                NullableSettingUpdate.Clear -> preferences.remove(KEY_TARGET_PACE)
                is NullableSettingUpdate.Set -> preferences[KEY_TARGET_PACE] = value.value
            }
            when (val value = patch.targetDistanceKm) {
                NullableSettingUpdate.Unchanged -> Unit
                NullableSettingUpdate.Clear -> preferences.remove(KEY_TARGET_DISTANCE)
                is NullableSettingUpdate.Set -> preferences[KEY_TARGET_DISTANCE] = value.value
            }
            patch.maxHeartRate?.let { preferences[KEY_MAX_HR] = it }
            patch.ttsEnabled?.let { preferences[KEY_TTS_ENABLED] = it }
            patch.ttsMinIntervalSec?.let { preferences[KEY_TTS_INTERVAL] = it }
            patch.llmEnabled?.let { preferences[KEY_LLM_ENABLED] = it }
            patch.mockScenario?.let { preferences[KEY_MOCK_SCENARIO] = it }
            patch.coachTone?.let { preferences[KEY_COACH_TONE] = it.name }
            patch.fishApiKey?.let { preferences[KEY_FISH_API_KEY] = it }
            patch.fishReferenceId?.let { preferences[KEY_FISH_REFERENCE_ID] = it }
            patch.llmApiKey?.let { preferences[KEY_LLM_API_KEY] = it }
            patch.themePreference?.let { preferences[KEY_THEME_PREFERENCE] = it.name }
            patch.motionPreference?.let { preferences[KEY_MOTION_PREFERENCE] = it.name }
        }
    }

    @Deprecated("使用 current()")
    suspend fun loadAll(): SettingsSnapshot = current()

    suspend fun setTargetPace(secPerKm: Double?) = update(
        SettingsPatch(
            targetPaceSecPerKm = secPerKm?.let { NullableSettingUpdate.Set(it) }
                ?: NullableSettingUpdate.Clear,
        ),
    )

    suspend fun setTargetDistance(km: Double?) = update(
        SettingsPatch(
            targetDistanceKm = km?.let { NullableSettingUpdate.Set(it) }
                ?: NullableSettingUpdate.Clear,
        ),
    )

    suspend fun setMaxHeartRate(hr: Int) = update(SettingsPatch(maxHeartRate = hr))

    suspend fun setTtsEnabled(enabled: Boolean) = update(SettingsPatch(ttsEnabled = enabled))

    suspend fun setTtsMinInterval(sec: Int) = update(SettingsPatch(ttsMinIntervalSec = sec))

    suspend fun setLlmEnabled(enabled: Boolean) = update(SettingsPatch(llmEnabled = enabled))

    suspend fun setMockScenario(scenario: String) = update(SettingsPatch(mockScenario = scenario))

    suspend fun setFishApiKey(key: String) = update(SettingsPatch(fishApiKey = key))

    suspend fun setFishReferenceId(refId: String) = update(SettingsPatch(fishReferenceId = refId))

    suspend fun setLlmApiKey(key: String) = update(SettingsPatch(llmApiKey = key))

    private fun toSnapshot(preferences: Preferences): SettingsSnapshot = SettingsSnapshot(
        targetPaceSecPerKm = preferences[KEY_TARGET_PACE],
        targetDistanceKm = preferences[KEY_TARGET_DISTANCE],
        maxHeartRate = preferences[KEY_MAX_HR] ?: DEFAULT_MAX_HR,
        ttsEnabled = preferences[KEY_TTS_ENABLED] ?: true,
        ttsMinIntervalSec = preferences[KEY_TTS_INTERVAL] ?: DEFAULT_TTS_INTERVAL,
        llmEnabled = preferences[KEY_LLM_ENABLED] ?: false,
        mockScenario = preferences[KEY_MOCK_SCENARIO] ?: "EASY_RUN",
        coachTone = preferences[KEY_COACH_TONE].toEnumOrDefault(CoachTone.BALANCED),
        fishApiKey = preferences[KEY_FISH_API_KEY] ?: "",
        fishReferenceId = preferences[KEY_FISH_REFERENCE_ID] ?: DEFAULT_FISH_REFERENCE_ID,
        llmApiKey = preferences[KEY_LLM_API_KEY] ?: "",
        themePreference = preferences[KEY_THEME_PREFERENCE].toEnumOrDefault(ThemePreference.SYSTEM),
        motionPreference = preferences[KEY_MOTION_PREFERENCE].toEnumOrDefault(MotionPreference.SYSTEM),
    )

    companion object {
        private val KEY_TARGET_PACE = doublePreferencesKey("target_pace")
        private val KEY_TARGET_DISTANCE = doublePreferencesKey("target_distance")
        private val KEY_MAX_HR = intPreferencesKey("max_hr")
        private val KEY_TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        private val KEY_TTS_INTERVAL = intPreferencesKey("tts_interval")
        private val KEY_LLM_ENABLED = booleanPreferencesKey("llm_enabled")
        private val KEY_MOCK_SCENARIO = stringPreferencesKey("mock_scenario")
        private val KEY_COACH_TONE = stringPreferencesKey("coach_tone")
        private val KEY_FISH_API_KEY = stringPreferencesKey("fish_api_key")
        private val KEY_FISH_REFERENCE_ID = stringPreferencesKey("fish_reference_id")
        private val KEY_LLM_API_KEY = stringPreferencesKey("llm_api_key")
        private val KEY_THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        private val KEY_MOTION_PREFERENCE = stringPreferencesKey("motion_preference")

        const val DEFAULT_MAX_HR = 180
        const val DEFAULT_TTS_INTERVAL = 4

        /** 声线仅从用户显式保存的私有设置读取。 */
        const val DEFAULT_FISH_REFERENCE_ID = ""
    }
}

private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T =
    this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default
