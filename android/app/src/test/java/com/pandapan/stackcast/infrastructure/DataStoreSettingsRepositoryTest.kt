package com.pandapan.stackcast.infrastructure

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.pandapan.stackcast.domain.model.AppSettings
import com.pandapan.stackcast.domain.model.BleConfig
import com.pandapan.stackcast.domain.model.LlmModelConfig
import com.pandapan.stackcast.domain.model.PersonaPrompt
import com.pandapan.stackcast.domain.model.RtmpConfig
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [DataStoreSettingsRepository] の単体テスト。
 *
 * Android 実機・エミュレータを介さずに JVM 上で動作する
 * [PreferenceDataStoreFactory] を用いて一時ファイルベースの DataStore を生成し、
 * 設定値の保存・読み込みがアプリ再起動 (プロセス再生成) を跨いでも保持されることを検証する。
 */
class DataStoreSettingsRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var preferencesFile: File

    @Before
    fun setUp() {
        preferencesFile = File(temporaryFolder.root, "test_settings.preferences_pb")
    }

    /**
     * テストごとに独立した DataStore を生成し、[DataStoreSettingsRepository] を組み立てる。
     * DataStore のコルーチンスコープには [TestScope.backgroundScope] を用いることで、
     * テスト対象コルーチンが `runTest` の終了時に確実にキャンセルされるようにする。
     */
    private fun TestScope.createRepository(): DataStoreSettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { preferencesFile }
        )
        return DataStoreSettingsRepository(dataStore)
    }

    @Test
    fun `未保存の状態ではデフォルト値が読み込まれる`() = runTest {
        val repository = createRepository()

        val settings = repository.observeSettings().first()

        assertEquals(AppSettings(), settings)
    }

    @Test
    fun `保存した設定値が読み込み時に復元される`() = runTest {
        val repository = createRepository()
        val expected = AppSettings(
            rtmpConfig = RtmpConfig(
                rtmpUrl = "rtmp://a.rtmp.youtube.com/live2",
                streamKey = "test-stream-key",
                resolution = "1280x720",
                frameRate = 60,
                bitrateKbps = 6000
            ),
            personaPrompt = PersonaPrompt(
                systemPrompt = "あなたは元気なAIキャラクターです。",
                characterSetting = "明るく前向きな性格。",
                endingRule = "〜ですぱん",
                ngWords = listOf("暴力", "差別")
            ),
            bleConfig = BleConfig(
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                deviceName = "StackChan-01",
                autoReconnect = false
            ),
            llmModelConfig = LlmModelConfig(
                modelUrl = "https://huggingface.co/example/model.task",
                huggingFaceAccessToken = "hf_dummy_token"
            )
        )

        repository.saveSettings(expected)
        val actual = repository.observeSettings().first()

        assertEquals(expected, actual)
    }
}
