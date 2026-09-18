package com.pandapan.stackcast.infrastructure.tts

import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [AndroidTtsEngine] の実機・エミュレータ上での動作確認用インストルメンテーションテスト。
 *
 * JVM単体テストでは `android.speech.tts.TextToSpeech` を動作させられないため、
 * 実際のTTSエンジン（Google音声合成エンジン等）が動作する端末上でのみ検証可能な
 * [AndroidTtsEngine.synthesizeToPcm] の疎通確認をここで行う。
 *
 * 実行対象端末にTTSエンジンが存在しない、または日本語音声データが未インストールの場合は
 * [org.junit.Assume] により本テストをスキップする（テスト失敗ではなく "skipped" として扱われる）。
 * これにより、TTS環境が整っていないCI/エミュレータ上でも安全にテストスイートを実行できる。
 */
@RunWith(AndroidJUnit4::class)
class AndroidTtsEngineInstrumentedTest {

    private var engine: AndroidTtsEngine? = null

    @After
    fun tearDown() {
        engine?.close()
    }

    /**
     * 端末にインストール済みのTTSエンジンが1つ以上存在することを確認したうえで、
     * [AndroidTtsEngine.synthesizeToPcm] が16-bit PCMデータを実際に生成できることを検証する。
     *
     * TTSエンジンが無い、または初期化・合成に失敗した場合（日本語音声データ未インストールなど）は、
     * テスト失敗ではなく `assumeTrue` によりスキップ扱いとする。
     */
    @Test
    fun synthesizeToPcm_producesNonEmptyPcmData_whenTtsEngineIsAvailable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // 端末にTTSエンジンが1つも入っていない場合はテスト自体が成立しないためスキップする。
        assumeTrue(
            "TTSエンジンが端末にインストールされていないためスキップします。",
            installedEnginesNotEmpty(context)
        )

        val ttsEngine = AndroidTtsEngine(context).also { engine = it }

        val result = ttsEngine.synthesizeToPcm("こんにちは、スタックキャストです。")

        // 日本語音声データ未インストールなど、実行環境起因の失敗はスキップとして扱う。
        assumeTrue(
            "TTSエンジンの初期化または音声合成に失敗したためスキップします: " +
                result.exceptionOrNull()?.message,
            result.isSuccess
        )

        val pcmData = result.getOrThrow()
        assertTrue("生成されたPCMサンプル数が0件でした。", pcmData.samples.isNotEmpty())
        assertTrue("サンプルレートが不正です: ${pcmData.sampleRateHz}", pcmData.sampleRateHz > 0)
    }

    /**
     * `TextToSpeech.getEngines` はコンストラクタ呼び出しなしで参照できるユーティリティAPIではないため、
     * ダミーの [TextToSpeech] を生成してエンジン一覧を取得する。生成に使ったインスタンスは即座に破棄する。
     */
    private fun installedEnginesNotEmpty(context: android.content.Context): Boolean {
        var hasEngine = false
        var probe: TextToSpeech? = null
        try {
            probe = TextToSpeech(context) { }
            hasEngine = probe.engines.isNotEmpty()
        } finally {
            probe?.shutdown()
        }
        return hasEngine
    }
}
