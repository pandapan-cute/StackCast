package com.pandapan.stackcast.domain.services

import com.pandapan.stackcast.domain.model.PcmAudioData
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TTS音声とBGM音声を内部で結合 (ミックス) する純粋Kotlinドメインサービス。
 *
 * Android SDKに依存せず、サンプリングレートの変換 (リサンプリング)、
 * 音量スケーリング、クリッピング防止 (クランプ処理)、および多チャンネル (モノラル/ステレオ)
 * への変換を行う。
 */
class AudioMixer {

    /**
     * TTSの [PcmAudioData] と BGMの [PcmAudioData] を指定の音量比率で結合する。
     *
     * どちらか一方が null または空の場合は、もう一方を単独で音量スケーリング・リサンプリングして返す。
     * 両方とも null または空の場合は、長さ0の [PcmAudioData] を返す。
     *
     * @param ttsAudio コメント応答等のTTS合成音声 (モノラル 16-bit PCM)。
     * @param bgmAudio ループBGM音源 (モノラル 16-bit PCM)。
     * @param ttsVolume TTS音声の音量倍率 (0.0〜1.0、デフォルト: 1.0)。
     * @param bgmVolume BGM音声の音量倍率 (0.0〜1.0、デフォルト: 0.3)。
     * @param outputSampleRateHz 結合後の出力サンプリングレート (Hz、デフォルト: 44,100)。
     * @return 結合後の [PcmAudioData]。
     */
    fun mix(
        ttsAudio: PcmAudioData?,
        bgmAudio: PcmAudioData?,
        ttsVolume: Float = 1.0f,
        bgmVolume: Float = 0.3f,
        outputSampleRateHz: Int = DEFAULT_OUTPUT_SAMPLE_RATE_HZ
    ): PcmAudioData {
        val ttsResampled = ttsAudio?.let { resample(it.samples, it.sampleRateHz, outputSampleRateHz) }
        val bgmResampled = bgmAudio?.let { resample(it.samples, it.sampleRateHz, outputSampleRateHz) }

        val mixedSamples = mixSampleArrays(ttsResampled, bgmResampled, ttsVolume, bgmVolume)
        return PcmAudioData(mixedSamples, outputSampleRateHz)
    }

    /**
     * 2つの 16-bit PCM サンプル配列を指定音量で線形加算し、16-bit範囲へクランプする。
     *
     * 長さが異なる場合は、長い方の配列に合わせて短い方をゼロ (無音) として加算する。
     *
     * @param ttsSamples TTSサンプル列。null の場合は無音。
     * @param bgmSamples BGMサンプル列。null の場合は無音。
     * @param ttsVolume TTS音量 (0.0〜1.0)。
     * @param bgmVolume BGM音量 (0.0〜1.0)。
     * @return 結合された 16-bit PCM サンプル配列。
     */
    fun mixSampleArrays(
        ttsSamples: ShortArray?,
        bgmSamples: ShortArray?,
        ttsVolume: Float,
        bgmVolume: Float
    ): ShortArray {
        val ttsLen = ttsSamples?.size ?: 0
        val bgmLen = bgmSamples?.size ?: 0
        val maxLength = maxOf(ttsLen, bgmLen)

        if (maxLength == 0) return ShortArray(0)

        val output = ShortArray(maxLength)
        for (i in 0 until maxLength) {
            val ttsVal = if (ttsSamples != null && i < ttsLen) ttsSamples[i] * ttsVolume else 0f
            val bgmVal = if (bgmSamples != null && i < bgmLen) bgmSamples[i] * bgmVolume else 0f
            val sum = (ttsVal + bgmVal).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[i] = sum.toShort()
        }
        return output
    }

    /**
     * 線形補間を用いてサンプルレートを変換 (リサンプリング) する。
     *
     * @param samples 入力 16-bit PCM サンプル配列。
     * @param inputRateHz 入力サンプリングレート (Hz)。
     * @param outputRateHz 出力サンプリングレート (Hz)。
     * @return リサンプリング後の 16-bit PCM サンプル配列。
     */
    fun resample(samples: ShortArray, inputRateHz: Int, outputRateHz: Int): ShortArray {
        if (inputRateHz <= 0 || outputRateHz <= 0 || samples.isEmpty()) return ShortArray(0)
        if (inputRateHz == outputRateHz) return samples.copyOf()

        val ratio = outputRateHz.toDouble() / inputRateHz
        val outputCount = (samples.size * ratio).toInt()
        val result = ShortArray(outputCount)

        for (i in 0 until outputCount) {
            val srcPos = i / ratio
            val srcIndex = srcPos.toInt()
            val fraction = (srcPos - srcIndex).toFloat()

            val s0 = samples[minOf(srcIndex, samples.lastIndex)]
            val s1 = samples[minOf(srcIndex + 1, samples.lastIndex)]

            val interpolated = (s0 * (1f - fraction) + s1 * fraction).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            result[i] = interpolated.toShort()
        }
        return result
    }

    /**
     * 16-bit PCM サンプル配列をリトルエンディアンのバイト配列へ変換する。
     *
     * @param samples モノラル 16-bit PCM サンプル配列。
     * @param channels 出力チャンネル数 (1: モノラル, 2: ステレオ)。
     * @return 16-bit PCM バイト配列。
     */
    fun toPcmBytes(samples: ShortArray, channels: Int = 1): ByteArray {
        require(channels > 0) { "チャンネル数は1以上である必要があります: $channels" }
        val buffer = ByteBuffer.allocate(samples.size * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            repeat(channels) {
                buffer.putShort(sample)
            }
        }
        return buffer.array()
    }

    companion object {
        /** 既定の出力サンプリングレート (RTMP配信の標準: 44,100Hz)。 */
        const val DEFAULT_OUTPUT_SAMPLE_RATE_HZ = 44_100
    }
}
