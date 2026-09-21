package com.pandapan.stackcast.infrastructure.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.pandapan.stackcast.R
import com.pandapan.stackcast.domain.model.PcmAudioData
import com.pandapan.stackcast.domain.ports.BgmPlayerPort
import com.pandapan.stackcast.infrastructure.tts.WavPcmReader
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [BgmPlayerPort] の実機実装。
 *
 * `android/app/src/main/res/raw/m06_piano_short_bpm80.wav` からBGMのPCMデータをロードし、
 * 内部音声結合用のサンプル供給 ([readNextSamples])、およびループ再生・停止・音量制御を提供する。
 * また、外部スピーカーでの音源確認 ([playToSpeaker]) にも対応する。
 *
 * @param initialPcmData テスト等で直接注入するBGM PCMデータ。
 * @param context rawリソースからの読み込みに使用するコンテキスト。
 */
class AudioTrackBgmPlayer(
    initialPcmData: PcmAudioData? = null,
    private val context: Context? = null
) : BgmPlayerPort {

    /** コンテキストからBGM音源 (m06_piano_short_bpm80.wav) を読み込む簡易コンストラクタ。 */
    constructor(context: Context) : this(null, context)

    /** 事前ロード済みPCMデータを使用するテスト用コンストラクタ。 */
    constructor(pcmData: PcmAudioData) : this(pcmData, null)

    @Volatile
    private var pcmData: PcmAudioData? = initialPcmData

    @Volatile
    override var isPlaying: Boolean = false
        private set

    @Volatile
    override var volume: Float = DEFAULT_BGM_VOLUME
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
            activeSpeakerTrack?.setVolume(field)
        }

    @Volatile
    override var isLooping: Boolean = true

    /** 現在の再生位置 (ネイティブサンプリングレート基準のサンプル位置)。 */
    private var playheadPosition: Double = 0.0

    /** スピーカー再生用の [AudioTrack] (外部スピーカー確認時のみ使用)。 */
    private var activeSpeakerTrack: AudioTrack? = null

    init {
        if (pcmData == null && context != null) {
            loadFromRawResource()
        }
    }

    override fun play() {
        ensureLoaded()
        isPlaying = true
    }

    override fun stop() {
        isPlaying = false
        playheadPosition = 0.0
        stopSpeakerTrack()
    }

    override fun pause() {
        isPlaying = false
        activeSpeakerTrack?.pause()
    }

    override fun getPcmData(): PcmAudioData? {
        ensureLoaded()
        return pcmData
    }

    /**
     * 要求されたサンプル数のPCMサンプル列を生成して返す。
     *
     * 要求サンプルレートと音源サンプルレートが異なる場合は線形補間によってリサンプリングし、
     * [volume] を乗算して返す。
     */
    @Synchronized
    override fun readNextSamples(sampleCount: Int, targetSampleRateHz: Int): ShortArray {
        if (!isPlaying || sampleCount <= 0) {
            return ShortArray(sampleCount)
        }

        val data = pcmData ?: return ShortArray(sampleCount)
        val sourceSamples = data.samples
        if (sourceSamples.isEmpty()) return ShortArray(sampleCount)

        val output = ShortArray(sampleCount)
        val step = data.sampleRateHz.toDouble() / targetSampleRateHz
        val sourceCount = sourceSamples.size

        for (i in 0 until sampleCount) {
            val srcIndex = playheadPosition.toInt()
            val fraction = (playheadPosition - srcIndex).toFloat()

            val s0 = sourceSamples[srcIndex % sourceCount]
            val s1 = sourceSamples[(srcIndex + 1) % sourceCount]

            val interpolated = (s0 * (1f - fraction) + s1 * fraction) * volume
            output[i] = interpolated.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            playheadPosition += step
            if (playheadPosition >= sourceCount) {
                if (isLooping) {
                    playheadPosition %= sourceCount
                } else {
                    isPlaying = false
                    break
                }
            }
        }

        return output
    }

    /**
     * 外部スピーカー確認用として、BGMを端末スピーカーから再生する。
     *
     * @return 再生開始に成功した場合は [Result.success]。
     */
    fun playToSpeaker(): Result<Unit> = runCatching {
        ensureLoaded()
        val data = pcmData ?: throw IllegalStateException("BGM音源がロードされていません。")
        stopSpeakerTrack()

        val sampleRate = data.sampleRateHz
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, data.samples.size * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.setVolume(volume)
        track.play()

        val byteBuffer = ByteBuffer.allocate(data.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in data.samples) {
            byteBuffer.putShort(sample)
        }
        val bytes = byteBuffer.array()

        track.write(bytes, 0, bytes.size)
        activeSpeakerTrack = track
        isPlaying = true
    }

    private fun stopSpeakerTrack() {
        activeSpeakerTrack?.let { track ->
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            }
        }
        activeSpeakerTrack = null
    }

    override fun release() {
        stop()
        pcmData = null
    }

    private fun ensureLoaded() {
        if (pcmData == null && context != null) {
            loadFromRawResource()
        }
    }

    private fun loadFromRawResource() {
        val ctx = context ?: return
        try {
            ctx.resources.openRawResource(R.raw.m06_piano_short_bpm80).use { inputStream ->
                pcmData = WavPcmReader.read(inputStream, "m06_piano_short_bpm80.wav")
            }
        } catch (e: IOException) {
            pcmData = null
        }
    }

    companion object {
        /** BGMの既定音量 (主音声TTSの邪魔にならないよう0.3)。 */
        const val DEFAULT_BGM_VOLUME = 0.3f
    }
}
