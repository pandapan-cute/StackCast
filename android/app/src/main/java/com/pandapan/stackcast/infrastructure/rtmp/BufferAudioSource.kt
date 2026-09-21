package com.pandapan.stackcast.infrastructure.rtmp

import com.pandapan.stackcast.application.usecase.NoOpPipelineLogger
import com.pandapan.stackcast.application.usecase.PipelineLogger
import com.pandapan.stackcast.domain.model.PcmAudioData
import com.pandapan.stackcast.domain.ports.BgmPlayerPort
import com.pandapan.stackcast.domain.services.AudioMixer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * TTS音声とBGM音声を内部で結合し、RootEncoderの音声エフェクトへ受け渡すバッファ。
 *
 * RootEncoder 2.8.1 から要求されたバイト数ごとに、TTS の発話キューと
 * BGM ループ音源を [AudioMixer] で合成して返す。
 * これにより、端末スピーカーへの外部再生を経由せず、アプリ内部で結合した音声を
 * RTMP 配信の音声トラックへ直接注入できる。
 *
 * @property outputSampleRateHz 配信側の出力サンプリングレート (Hz)。
 * @property outputChannels 配信側の出力チャンネル数 (1: モノラル, 2: ステレオ)。
 * @property ttsVolume TTS音声の音量倍率 (0.0〜1.0)。
 * @property bgmVolume BGM音声の音量倍率 (0.0〜1.0)。
 * @property bgmPlayer BGM供給ポート。null の場合はTTS単独または無音となる。
 * @property audioMixer PCM結合ドメインサービス。
 * @property logger 結合統計および遅延ログの出力先。
 */
class BufferAudioSource(
    private val outputSampleRateHz: Int,
    private val outputChannels: Int,
    var ttsVolume: Float = 1.0f,
    bgmVolume: Float = 0.3f,
    bgmPlayer: BgmPlayerPort? = null,
    private val audioMixer: AudioMixer = AudioMixer(),
    private val logger: PipelineLogger = NoOpPipelineLogger
) {
    var bgmPlayer: BgmPlayerPort? = bgmPlayer
        set(value) {
            field = value
            value?.volume = bgmVolume
        }

    var bgmVolume: Float = bgmVolume
        set(value) {
            field = value
            bgmPlayer?.volume = value
        }

    private val ttsQueue = ConcurrentLinkedQueue<ShortArray>()
    private var pendingTts: ShortArray = ShortArray(0)
    private var pendingOffset: Int = 0
    private var isTtsActive: Boolean = false
    private var lastLogTimeMs: Long = 0L

    init {
        this.bgmPlayer?.volume = this.bgmVolume
    }

    /**
     * TTSで生成されたPCMを出力サンプリングレートへ変換してキューへ追加する。
     *
     * @param audio 16-bit モノラルPCM。
     */
    fun enqueue(audio: PcmAudioData) {
        require(outputChannels > 0) { "出力チャンネル数は正である必要があります。" }
        val resampled = audioMixer.resample(audio.samples, audio.sampleRateHz, outputSampleRateHz)
        ttsQueue.add(resampled)
        val durationMs = if (outputSampleRateHz > 0) (resampled.size * 1000L) / outputSampleRateHz else 0L
        logger.info(
            TAG,
            "TTS音声投入: サンプル数=${audio.samples.size}(${audio.sampleRateHz}Hz) -> 出力換算=${resampled.size}サンプル(${durationMs}ms)"
        )
    }

    /**
     * 指定サイズのPCMを読み出し、TTSとBGMを結合して返す。
     * 不足部分は無音で埋める。
     *
     * @param sizeBytes RootEncoderが要求したバイト数。
     * @return 16-bit PCM バイト列。
     */
    fun read(sizeBytes: Int): ByteArray {
        require(sizeBytes >= 0) { "読み出しサイズは負にできません。" }
        if (sizeBytes == 0) return ByteArray(0)

        val bytesPerFrame = outputChannels * 2
        val framesNeeded = sizeBytes / bytesPerFrame
        if (framesNeeded <= 0) return ByteArray(sizeBytes)

        // BGMサンプルの取得
        val bgmSamples = try {
            bgmPlayer?.readNextSamples(framesNeeded, outputSampleRateHz)
        } catch (e: Throwable) {
            logger.warn(TAG, "BGM読み出し失敗 (無音フォールバック): ${e.message}")
            null
        }

        // TTSサンプルの取得
        val ttsSamples = readTtsSamples(framesNeeded)

        // 状態変化ログ
        val currentlyTtsActive = ttsSamples != null
        if (!isTtsActive && currentlyTtsActive) {
            isTtsActive = true
            logger.info(TAG, "内部音声結合開始: TTS音声の合流を開始しました (BGM再生中=${bgmPlayer?.isPlaying == true})")
        } else if (isTtsActive && !currentlyTtsActive) {
            isTtsActive = false
            logger.info(TAG, "内部音声結合停止: TTS音声の合流が終了しました")
        }

        // 定期的な結合状態・遅延ログ (約3秒間隔)
        val now = System.currentTimeMillis()
        if (now - lastLogTimeMs > STATS_LOG_INTERVAL_MS) {
            lastLogTimeMs = now
            val remainingTtsSamples = (pendingTts.size - pendingOffset) + ttsQueue.sumOf { it.size }
            val estimatedDelayMs = if (outputSampleRateHz > 0) (remainingTtsSamples * 1000L) / outputSampleRateHz else 0L
            logger.info(
                TAG,
                "結合状態: 要求PCM=${sizeBytes}B, 推定遅延=${estimatedDelayMs}ms, TTS待機=${remainingTtsSamples}サンプル, BGM再生中=${bgmPlayer?.isPlaying == true}"
            )
        }

        // 音声結合 (BGMサンプルはbgmPlayer側ですでにbgmVolumeスケーリング済みのため倍率1.0で加算)
        val mixedSamples = try {
            audioMixer.mixSampleArrays(ttsSamples, bgmSamples, ttsVolume, 1.0f)
        } catch (e: Throwable) {
            logger.warn(TAG, "内部音声結合失敗 (個別フォールバック): ${e.message}")
            ttsSamples ?: bgmSamples ?: ShortArray(framesNeeded)
        }

        // 出力バイト配列へ書き出し
        val output = ByteArray(sizeBytes)
        val buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until framesNeeded) {
            val sample = if (i < mixedSamples.size) mixedSamples[i] else 0
            repeat(outputChannels) {
                buffer.putShort(sample)
            }
        }
        return output
    }

    /**
     * キューおよび保留中のTTS音声サンプルを読み出す。
     * TTS音声が存在しない場合は null を返す。
     */
    private fun readTtsSamples(count: Int): ShortArray? {
        if (pendingOffset >= pendingTts.size) {
            val next = ttsQueue.poll() ?: return null
            pendingTts = next
            pendingOffset = 0
        }

        val result = ShortArray(count)
        var filled = 0
        while (filled < count) {
            if (pendingOffset >= pendingTts.size) {
                val next = ttsQueue.poll() ?: break
                pendingTts = next
                pendingOffset = 0
            }
            val toCopy = minOf(count - filled, pendingTts.size - pendingOffset)
            System.arraycopy(pendingTts, pendingOffset, result, filled, toCopy)
            filled += toCopy
            pendingOffset += toCopy
        }

        return if (filled > 0) result else null
    }

    /** 保持中の音声を破棄する。 */
    fun clear() {
        ttsQueue.clear()
        pendingTts = ShortArray(0)
        pendingOffset = 0
        isTtsActive = false
    }

    companion object {
        private const val TAG = "BufferAudioSource"
        private const val STATS_LOG_INTERVAL_MS = 3_000L
    }
}
