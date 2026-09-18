package com.pandapan.stackcast.infrastructure.tts

import com.pandapan.stackcast.domain.model.PcmAudioData
import com.pandapan.stackcast.domain.ports.TtsPort
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * 単体テストや実機TTSエンジン未準備時に用いるダミーの [TtsPort] 実装。
 *
 * 実際の音声合成は行わず、入力テキストの文字数に応じた長さの疑似PCM音声データ
 * (正弦波によるバースト音を文字ごとに配置したもの) を即座に生成して返す。
 * これにより、実機のTTSエンジンやネイティブ音声合成ライブラリに依存せずに
 * [com.pandapan.stackcast.domain.services.LipSyncCalculator] や
 * [com.pandapan.stackcast.application.usecase.SynthesizeVoiceAndLipSyncUseCase] の
 * 動作を検証できる。
 *
 * @property sampleRateHz 生成する疑似PCM音声データのサンプリングレート (Hz)。
 * @property millisPerCharacter 疑似音声において1文字あたりに割り当てるバースト音の長さ (ミリ秒)。
 * @property amplitude 生成するバースト音の振幅 (0.0〜1.0)。
 * @property toneFrequencyHz バースト音に用いる正弦波の周波数 (Hz)。
 */
class MockTtsEngine(
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    private val millisPerCharacter: Int = DEFAULT_MILLIS_PER_CHARACTER,
    private val amplitude: Double = DEFAULT_AMPLITUDE,
    private val toneFrequencyHz: Double = DEFAULT_TONE_FREQUENCY_HZ
) : TtsPort {

    override suspend fun synthesizeToPcm(text: String): Result<PcmAudioData> {
        return Result.success(generatePseudoSpeechPcm(text))
    }

    override suspend fun speak(text: String): Result<Unit> {
        // 実際の再生は行わず、疑似音声の長さ分だけ待機して「再生完了」を模擬する。
        val durationMs = estimateDurationMillis(text)
        delay(durationMs.toLong())
        return Result.success(Unit)
    }

    override fun isReady(): Boolean = true

    /**
     * 文字数に応じた疑似音声の長さ (ミリ秒) を見積もる。
     *
     * 発話がない (空文字列) 場合でも最低限の無音区間を確保する。
     */
    private fun estimateDurationMillis(text: String): Int {
        return max(MIN_DURATION_MILLIS, text.length * millisPerCharacter)
    }

    /**
     * 入力テキストの文字ごとに正弦波のバースト音を配置し、文字間には短い無音区間を挟んだ
     * 疑似的な発話PCMデータを生成する。実際の音素・発音とは一切対応しない、あくまで
     * リップシンク解析パイプラインの動作確認用のダミーデータである。
     */
    private fun generatePseudoSpeechPcm(text: String): PcmAudioData {
        if (text.isBlank()) {
            // 発話内容が空の場合は、最低限の無音PCMを返す。
            val silentSampleCount = sampleRateHz * MIN_DURATION_MILLIS / MILLIS_PER_SECOND
            return PcmAudioData(ShortArray(silentSampleCount), sampleRateHz)
        }

        val samplesPerCharacter = sampleRateHz * millisPerCharacter / MILLIS_PER_SECOND
        val samplesPerGap = samplesPerCharacter / GAP_RATIO_DIVISOR
        val samplesPerBurst = samplesPerCharacter - samplesPerGap

        val samples = ArrayList<Short>(text.length * samplesPerCharacter)
        for (charIndex in text.indices) {
            for (sampleIndex in 0 until samplesPerBurst) {
                val timeSeconds = sampleIndex.toDouble() / sampleRateHz
                // 文字の先頭・末尾で振幅が滑らかに立ち上がり/減衰するよう三角窓を掛ける。
                val envelope = triangularWindow(sampleIndex, samplesPerBurst)
                val value = amplitude * envelope * sin(2.0 * Math.PI * toneFrequencyHz * timeSeconds)
                samples += (value * Short.MAX_VALUE).toInt().toShort()
            }
            repeat(samplesPerGap) { samples += 0 }
        }

        return PcmAudioData(samples.toShortArray(), sampleRateHz)
    }

    /** バースト音の先頭・末尾を滑らかにするための三角窓関数 (0.0〜1.0)。 */
    private fun triangularWindow(sampleIndex: Int, totalSamples: Int): Double {
        if (totalSamples <= 1) return 1.0
        val position = sampleIndex.toDouble() / (totalSamples - 1)
        return 1.0 - kotlin.math.abs(position - 0.5) * 2.0
    }

    private fun max(a: Int, b: Int): Int = if (a > b) a else b

    private companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_MILLIS_PER_CHARACTER = 120
        const val DEFAULT_AMPLITUDE = 0.6
        const val DEFAULT_TONE_FREQUENCY_HZ = 220.0
        const val MIN_DURATION_MILLIS = 200
        const val MILLIS_PER_SECOND = 1000
        /** 1文字あたりの区間のうち、末尾に挟む無音区間の割合 (1/N)。 */
        const val GAP_RATIO_DIVISOR = 6
    }
}
