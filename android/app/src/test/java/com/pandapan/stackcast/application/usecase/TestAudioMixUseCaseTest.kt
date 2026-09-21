package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.domain.model.PcmAudioData
import com.pandapan.stackcast.domain.ports.TtsPort
import com.pandapan.stackcast.domain.services.AudioMixer
import com.pandapan.stackcast.infrastructure.audio.AudioTrackBgmPlayer
import com.pandapan.stackcast.infrastructure.rtmp.RecordingRtmpAudioSink
import com.pandapan.stackcast.infrastructure.tts.MockTtsEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TestAudioMixUseCase] の単体テスト。
 *
 * 設定画面のテストボタン押下時の、TTS合成 -> BGMサンプル取得 -> PCM結合 -> AudioSink供給
 * のエンドツーエンドフローを検証する。
 */
class TestAudioMixUseCaseTest {

    private val bgmData = PcmAudioData(
        samples = ShortArray(44100) { 300 }, // 1秒間のBGM
        sampleRateHz = 44100
    )

    @Test
    fun invoke_successfulMix_submitsToAudioSinkAndReturnsResult() = runBlocking {
        val tts = MockTtsEngine()
        val bgmPlayer = AudioTrackBgmPlayer(bgmData)
        val audioSink = RecordingRtmpAudioSink()

        val useCase = TestAudioMixUseCase(
            ttsPort = tts,
            bgmPlayer = bgmPlayer,
            audioMixer = AudioMixer(),
            audioSink = audioSink
        )

        val result = useCase(testText = "これはテスト用音声です。", playToSpeaker = false)

        assertTrue(result.isSuccess)
        val testResult = result.getOrThrow()

        assertTrue(testResult.ttsSampleCount > 0)
        assertTrue(testResult.bgmSampleCount > 0)
        assertTrue(testResult.mixedSampleCount > 0)
        assertEquals(testResult.mixedSampleCount * 2, testResult.mixedBytes)
        assertEquals(false, testResult.playedToSpeaker)
        assertTrue(testResult.summary.contains("結合成功"))

        // AudioSink へ結合PCMが供給されていること
        assertEquals(1, audioSink.submittedAudio.size)
        val submitted = audioSink.submittedAudio.first()
        assertEquals(testResult.mixedSampleCount, submitted.samples.size)
    }

    @Test
    fun invoke_ttsFails_returnsFailureResult() = runBlocking {
        val failingTts = object : TtsPort {
            override suspend fun synthesizeToPcm(text: String): Result<PcmAudioData> =
                Result.failure(IllegalStateException("TTSエンジン未初期化"))
            override suspend fun speak(text: String): Result<Unit> = Result.failure(IllegalStateException("speak未実装"))
            override fun isReady(): Boolean = false
        }

        val bgmPlayer = AudioTrackBgmPlayer(bgmData)
        val useCase = TestAudioMixUseCase(
            ttsPort = failingTts,
            bgmPlayer = bgmPlayer
        )

        val result = useCase()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PipelineException)
    }
}
