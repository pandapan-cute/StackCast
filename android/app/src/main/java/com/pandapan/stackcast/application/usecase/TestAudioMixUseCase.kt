package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.domain.model.PcmAudioData
import com.pandapan.stackcast.domain.ports.BgmPlayerPort
import com.pandapan.stackcast.domain.ports.RtmpAudioSinkPort
import com.pandapan.stackcast.domain.ports.TtsPort
import com.pandapan.stackcast.domain.services.AudioMixer
import com.pandapan.stackcast.infrastructure.audio.SpeakerAudioPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TTS音声とBGM音声を内部で結合し、動作検証を行うユースケース。
 *
 * 設定画面の「テスト用音声を再生」ボタンから呼び出され、
 * `これはテスト用音声です。` をTTSで合成し、BGM音源と内部結合して
 * [RtmpAudioSinkPort] やスピーカー出力 ([SpeakerAudioPlayer]) へ送る。
 *
 * @property ttsPort TTS合成ポート。
 * @property bgmPlayer BGM内部再生ポート。
 * @property audioMixer PCM結合ドメインサービス。
 * @property audioSink 内部音声の供給先 (任意)。
 * @property speakerPlayer 外部スピーカー再生プレイヤー (任意)。
 * @property logger パイプラインログ出力先。
 */
class TestAudioMixUseCase(
    private val ttsPort: TtsPort,
    private val bgmPlayer: BgmPlayerPort,
    private val audioMixer: AudioMixer = AudioMixer(),
    private val audioSink: RtmpAudioSinkPort? = null,
    private val speakerPlayer: SpeakerAudioPlayer? = null,
    private val logger: PipelineLogger = NoOpPipelineLogger
) {

    /**
     * テスト用音声結合を実行する。
     *
     * @param testText 合成対象テキスト (デフォルト: "これはテスト用音声です。")。
     * @param playToSpeaker 外部スピーカーでの確認を行うかどうか。
     * @param ttsVolume TTS音量倍率 (0.0〜1.0)。
     * @param bgmVolume BGM音量倍率 (0.0〜1.0)。
     * @return 結合結果情報。
     */
    suspend operator fun invoke(
        testText: String = DEFAULT_TEST_TEXT,
        playToSpeaker: Boolean = false,
        ttsVolume: Float = 1.0f,
        bgmVolume: Float = 0.3f
    ): Result<AudioMixTestResult> = withContext(Dispatchers.Default) {
        runCatching {
            val startTime = System.currentTimeMillis()
            logger.info(TAG, "音声結合テスト開始: text=\"$testText\", playToSpeaker=$playToSpeaker")

            // 1. TTS側の内部音声生成
            val ttsAudio = ttsPort.synthesizeToPcm(testText)
                .getOrElse { throw PipelineException("TTS音声合成", it.message ?: "TTS合成に失敗しました。", it) }

            val ttsDurationMs = if (ttsAudio.sampleRateHz > 0) {
                (ttsAudio.samples.size * 1000L) / ttsAudio.sampleRateHz
            } else {
                0L
            }
            logger.info(TAG, "TTS生成完了: ${ttsAudio.samples.size}サンプル (${ttsAudio.sampleRateHz}Hz, ${ttsDurationMs}ms)")

            // 2. BGM側の内部音声生成
            val outputRate = AudioMixer.DEFAULT_OUTPUT_SAMPLE_RATE_HZ
            val durationWithPaddingMs = ttsDurationMs + 500L
            val requiredBgmSamples = ((durationWithPaddingMs * outputRate) / 1000L).toInt().coerceAtLeast(outputRate)

            bgmPlayer.volume = bgmVolume
            bgmPlayer.play()
            val bgmSamples = bgmPlayer.readNextSamples(requiredBgmSamples, outputRate)
            logger.info(TAG, "BGMサンプル取得: ${bgmSamples.size}サンプル (レート: ${outputRate}Hz)")

            // 3. 2つのPCMを内部で結合
            val resampledTts = audioMixer.resample(ttsAudio.samples, ttsAudio.sampleRateHz, outputRate)
            val mixedSamples = audioMixer.mixSampleArrays(
                ttsSamples = resampledTts,
                bgmSamples = bgmSamples,
                ttsVolume = ttsVolume,
                bgmVolume = 1.0f
            )
            val mixedAudio = PcmAudioData(mixedSamples, outputRate)
            val mixedBytes = mixedAudio.samples.size * 2

            // 配信側 (AudioSink) へ送信
            audioSink?.submit(mixedAudio)
            logger.info(TAG, "内部結合完了: 結合サンプル数=${mixedAudio.samples.size}, バイト数=${mixedBytes}B")

            // 4. 外部スピーカー確認 (有効時のみ)
            if (playToSpeaker && speakerPlayer != null) {
                logger.info(TAG, "外部スピーカー再生開始: ${mixedAudio.samples.size}サンプル")
                speakerPlayer.play(mixedAudio).getOrThrow()
                logger.info(TAG, "外部スピーカー再生完了")
            }

            val processingTimeMs = System.currentTimeMillis() - startTime
            val summary = buildString {
                append("結合成功: TTS(${ttsDurationMs}ms, ${ttsAudio.samples.size}サンプル)")
                append(" + BGM(${bgmSamples.size}サンプル)")
                append(" -> 結合PCM: ${mixedBytes}B")
                append(" (処理時間: ${processingTimeMs}ms")
                if (playToSpeaker) append(", スピーカー出力: 完了")
                append(")")
            }

            AudioMixTestResult(
                ttsSampleCount = ttsAudio.samples.size,
                ttsDurationMs = ttsDurationMs,
                bgmSampleCount = bgmSamples.size,
                mixedSampleCount = mixedAudio.samples.size,
                mixedBytes = mixedBytes,
                processingTimeMs = processingTimeMs,
                playedToSpeaker = playToSpeaker,
                summary = summary
            )
        }
    }

    companion object {
        const val DEFAULT_TEST_TEXT = "これはテスト用音声です。"
        private const val TAG = "TestAudioMixUseCase"
    }
}

/**
 * 音声結合テストの結果情報。
 *
 * @property ttsSampleCount TTSのサンプル数。
 * @property ttsDurationMs TTSの再生時間 (ミリ秒)。
 * @property bgmSampleCount BGMのサンプル数。
 * @property mixedSampleCount 結合後の総サンプル数。
 * @property mixedBytes 結合後のPCMバイト数。
 * @property processingTimeMs 合成・結合処理にかかった時間 (ミリ秒)。
 * @property playedToSpeaker 外部スピーカーで再生されたかどうか。
 * @property summary 結果の要約テキスト。
 */
data class AudioMixTestResult(
    val ttsSampleCount: Int,
    val ttsDurationMs: Long,
    val bgmSampleCount: Int,
    val mixedSampleCount: Int,
    val mixedBytes: Int,
    val processingTimeMs: Long,
    val playedToSpeaker: Boolean,
    val summary: String
)
