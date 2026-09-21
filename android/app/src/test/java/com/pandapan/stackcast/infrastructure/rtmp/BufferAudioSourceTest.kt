package com.pandapan.stackcast.infrastructure.rtmp

import com.pandapan.stackcast.domain.model.PcmAudioData
import com.pandapan.stackcast.domain.ports.BgmPlayerPort
import com.pandapan.stackcast.infrastructure.audio.AudioTrackBgmPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [BufferAudioSource] の単体テスト。
 *
 * TTSとBGMの内部音声結合、ステレオPCM変換、フォールバック動作を検証する。
 */
class BufferAudioSourceTest {

    @Test
    fun read_bothTtsAndBgmActive_combinesIntoStereoBytes() {
        val bgmData = PcmAudioData(shortArrayOf(200, 400), 44100)
        val bgmPlayer = AudioTrackBgmPlayer(bgmData).apply {
            volume = 0.5f
            play()
        }

        val audioSource = BufferAudioSource(
            outputSampleRateHz = 44100,
            outputChannels = 2,
            ttsVolume = 1.0f,
            bgmVolume = 0.5f,
            bgmPlayer = bgmPlayer
        )

        // TTS: 1000, 2000
        val tts = PcmAudioData(shortArrayOf(1000, 2000), 44100)
        audioSource.enqueue(tts)

        // 2 frames * 2 channels * 2 bytes = 8 bytes
        val bytes = audioSource.read(8)
        assertEquals(8, bytes.size)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // frame 0: 1000 * 1.0 + 200 * 0.5 = 1100 (L, R)
        assertEquals(1100.toShort(), buffer.short)
        assertEquals(1100.toShort(), buffer.short)

        // frame 1: 2000 * 1.0 + 400 * 0.5 = 2200 (L, R)
        assertEquals(2200.toShort(), buffer.short)
        assertEquals(2200.toShort(), buffer.short)
    }

    @Test
    fun read_onlyBgmActive_outputsBgmInStereo() {
        val bgmData = PcmAudioData(shortArrayOf(500), 44100)
        val bgmPlayer = AudioTrackBgmPlayer(bgmData).apply {
            volume = 1.0f
            play()
        }

        val audioSource = BufferAudioSource(
            outputSampleRateHz = 44100,
            outputChannels = 2,
            bgmPlayer = bgmPlayer
        )

        // 1 frame * 2 channels * 2 bytes = 4 bytes
        val bytes = audioSource.read(4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // bgmVolume = 0.3f (default) -> 500 * 0.3 = 150
        assertEquals(150.toShort(), buffer.short)
        assertEquals(150.toShort(), buffer.short)
    }

    @Test
    fun read_bgmFails_fallsBackToTtsWithoutCrashing() {
        // 例外をスローするBGMモック
        val faultyBgmPlayer = object : BgmPlayerPort {
            override fun play() = Unit
            override fun stop() = Unit
            override fun pause() = Unit
            override val isPlaying: Boolean = true
            override var volume: Float = 0.3f
            override var isLooping: Boolean = true
            override fun getPcmData(): PcmAudioData? = null
            override fun readNextSamples(sampleCount: Int, targetSampleRateHz: Int): ShortArray {
                throw RuntimeException("BGMデコード異常")
            }
            override fun release() = Unit
        }

        val audioSource = BufferAudioSource(
            outputSampleRateHz = 44100,
            outputChannels = 2,
            bgmPlayer = faultyBgmPlayer
        )

        val tts = PcmAudioData(shortArrayOf(800), 44100)
        audioSource.enqueue(tts)

        // BGMが失敗しても例外にならず、TTS単独でフォールバック再生されること
        val bytes = audioSource.read(4)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(800.toShort(), buffer.short)
        assertEquals(800.toShort(), buffer.short)
    }

    @Test
    fun read_bothEmpty_outputsSilence() {
        val audioSource = BufferAudioSource(
            outputSampleRateHz = 44100,
            outputChannels = 2,
            bgmPlayer = null
        )

        val bytes = audioSource.read(8)
        assertTrue(bytes.all { it == 0.toByte() })
    }
}
