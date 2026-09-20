package com.pandapan.stackcast.infrastructure.rtmp

import com.pandapan.stackcast.domain.model.PcmAudioData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * TTSのPCMをRootEncoderの音声エフェクトへ受け渡すバッファ。
 *
 * RootEncoder 2.8.1には外部PCMを直接渡す公開メソッドがないため、
 * [com.pedro.encoder.input.audio.CustomAudioEffect]から要求されたバイト数ごとに
 * このバッファを読み出す。これにより端末スピーカーを経由せず音声エンコーダへ注入できる。
 *
 * @property outputSampleRateHz 配信側の出力サンプルレート。
 * @property outputChannels 配信側の出力チャンネル数。
 * @property volume 注入音声の倍率。
 */
class BufferAudioSource(
    private val outputSampleRateHz: Int,
    private val outputChannels: Int,
    private val volume: Float = 1f
) {
    private val chunks = ConcurrentLinkedQueue<ByteArray>()
    private var pending = ByteArray(0)

    /**
     * PCMを出力形式へ変換してキューへ追加する。
     *
     * @param audio 16-bitモノラルPCM。
     */
    fun enqueue(audio: PcmAudioData) {
        require(outputChannels > 0) { "出力チャンネル数は正である必要があります。" }
        chunks.add(convert(audio))
    }

    /**
     * 指定サイズのPCMを読み出し、不足部分は無音で埋める。
     *
     * @param sizeBytes RootEncoderが要求したバイト数。
     * @return 16-bit PCMバイト列。
     */
    fun read(sizeBytes: Int): ByteArray {
        require(sizeBytes >= 0) { "読み出しサイズは負にできません。" }
        val output = ByteArray(sizeBytes)
        var offset = 0
        while (offset < sizeBytes) {
            if (pending.isEmpty()) pending = chunks.poll() ?: break
            val count = minOf(sizeBytes - offset, pending.size)
            pending.copyInto(output, offset, 0, count)
            offset += count
            pending = if (count == pending.size) ByteArray(0) else pending.copyOfRange(count, pending.size)
        }
        return output
    }

    /** 保持中の音声を破棄する。 */
    fun clear() {
        chunks.clear()
        pending = ByteArray(0)
    }

    private fun convert(audio: PcmAudioData): ByteArray {
        val ratio = outputSampleRateHz.toDouble() / audio.sampleRateHz
        val outputSamples = (audio.samples.size * ratio).toInt()
        val bytes = ByteBuffer.allocate(outputSamples * outputChannels * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(outputSamples) { index ->
            val sourceIndex = minOf((index / ratio).toInt(), audio.samples.lastIndex)
            val sample = (audio.samples[sourceIndex] * volume).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            repeat(outputChannels) { bytes.putShort(sample) }
        }
        return bytes.array()
    }
}
