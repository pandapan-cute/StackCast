package com.pandapan.stackcast.infrastructure

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pandapan.stackcast.domain.model.AppSettings
import com.pandapan.stackcast.domain.model.BleConfig
import com.pandapan.stackcast.domain.model.PersonaPrompt
import com.pandapan.stackcast.domain.model.RtmpConfig
import com.pandapan.stackcast.domain.ports.SettingsRepositoryPort
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Jetpack DataStore (Preferences) を用いた [SettingsRepositoryPort] の実装。
 *
 * 配信 / AI(ペルソナ) / BLE の各設定値をキーごとに Preferences ファイルへ書き込み・読み出しする。
 * ドメイン層は本クラスの詳細を知らず、[SettingsRepositoryPort] インターフェース越しにのみ利用する。
 *
 * @property dataStore 設定値を保存する Preferences DataStore。
 *   本番環境では [settingsDataStore] (Context 拡張) から取得したインスタンスを渡す。
 *   テスト時には [androidx.datastore.preferences.core.PreferenceDataStoreFactory] で
 *   生成した一時ファイル用のインスタンスを注入できる。
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>
) : SettingsRepositoryPort {

    override fun observeSettings(): Flow<AppSettings> {
        return dataStore.data
            .catch { throwable ->
                // ファイル破損等の IOException 発生時はクラッシュさせず、空の設定として扱う。
                if (throwable is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw throwable
                }
            }
            .map { preferences -> preferences.toAppSettings() }
    }

    override suspend fun saveSettings(settings: AppSettings) {
        dataStore.edit { preferences ->
            preferences[KEY_RTMP_URL] = settings.rtmpConfig.rtmpUrl
            preferences[KEY_STREAM_KEY] = settings.rtmpConfig.streamKey
            preferences[KEY_RESOLUTION] = settings.rtmpConfig.resolution
            preferences[KEY_FRAME_RATE] = settings.rtmpConfig.frameRate
            preferences[KEY_BITRATE_KBPS] = settings.rtmpConfig.bitrateKbps

            preferences[KEY_SYSTEM_PROMPT] = settings.personaPrompt.systemPrompt
            preferences[KEY_CHARACTER_SETTING] = settings.personaPrompt.characterSetting
            preferences[KEY_ENDING_RULE] = settings.personaPrompt.endingRule
            preferences[KEY_NG_WORDS] = settings.personaPrompt.ngWords.joinToString(NG_WORDS_DELIMITER)

            preferences[KEY_BLE_DEVICE_ADDRESS] = settings.bleConfig.deviceAddress
            preferences[KEY_BLE_DEVICE_NAME] = settings.bleConfig.deviceName
            preferences[KEY_BLE_AUTO_RECONNECT] = settings.bleConfig.autoReconnect
        }
    }

    /** Preferences の生データを [AppSettings] ドメインモデルへ変換する。 */
    private fun Preferences.toAppSettings(): AppSettings {
        val rtmpConfig = RtmpConfig(
            rtmpUrl = this[KEY_RTMP_URL] ?: "",
            streamKey = this[KEY_STREAM_KEY] ?: "",
            resolution = this[KEY_RESOLUTION] ?: RtmpConfig.DEFAULT_RESOLUTION,
            frameRate = this[KEY_FRAME_RATE] ?: RtmpConfig.DEFAULT_FRAME_RATE,
            bitrateKbps = this[KEY_BITRATE_KBPS] ?: RtmpConfig.DEFAULT_BITRATE_KBPS
        )

        val ngWordsRaw = this[KEY_NG_WORDS] ?: ""
        val personaPrompt = PersonaPrompt(
            systemPrompt = this[KEY_SYSTEM_PROMPT] ?: "",
            characterSetting = this[KEY_CHARACTER_SETTING] ?: "",
            endingRule = this[KEY_ENDING_RULE] ?: "",
            ngWords = if (ngWordsRaw.isBlank()) {
                emptyList()
            } else {
                ngWordsRaw.split(NG_WORDS_DELIMITER).map { it.trim() }.filter { it.isNotEmpty() }
            }
        )

        val bleConfig = BleConfig(
            deviceAddress = this[KEY_BLE_DEVICE_ADDRESS] ?: "",
            deviceName = this[KEY_BLE_DEVICE_NAME] ?: "",
            autoReconnect = this[KEY_BLE_AUTO_RECONNECT] ?: true
        )

        return AppSettings(
            rtmpConfig = rtmpConfig,
            personaPrompt = personaPrompt,
            bleConfig = bleConfig
        )
    }

    private companion object {
        /** NGワードリストを1つの文字列として保存する際の区切り文字。 */
        const val NG_WORDS_DELIMITER = ","

        val KEY_RTMP_URL = stringPreferencesKey("rtmp_url")
        val KEY_STREAM_KEY = stringPreferencesKey("stream_key")
        val KEY_RESOLUTION = stringPreferencesKey("resolution")
        val KEY_FRAME_RATE = intPreferencesKey("frame_rate")
        val KEY_BITRATE_KBPS = intPreferencesKey("bitrate_kbps")

        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val KEY_CHARACTER_SETTING = stringPreferencesKey("character_setting")
        val KEY_ENDING_RULE = stringPreferencesKey("ending_rule")
        val KEY_NG_WORDS = stringPreferencesKey("ng_words")

        val KEY_BLE_DEVICE_ADDRESS = stringPreferencesKey("ble_device_address")
        val KEY_BLE_DEVICE_NAME = stringPreferencesKey("ble_device_name")
        val KEY_BLE_AUTO_RECONNECT = booleanPreferencesKey("ble_auto_reconnect")
    }
}
