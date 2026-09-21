package com.pandapan.stackcast.domain.services

import com.pandapan.stackcast.domain.model.PcmAudioData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [AudioMixer] の単体テスト。
 *
 * TTSとBGMのPCM結合、リサンプリング、音量スケーリング、クランプ処理、
 * およびバイト変換の正当性を検証する。
 */
class AudioMixerTest {

    private val mixer = AudioMixer()

    @Test
    fun mix_bothSourcesAvailable_combinesAndScalesCorrectly() {
        // TTS: 1000, 2000 (44100Hz)
        val ttsSamples = shortArrayOf(1000, 2000)
        val tts = PcmAudioData(ttsSamples, 44100)

        // BGM: 400, 600 (44100Hz)
        val bgmSamples = shortArrayOf(400, 600)
        val bgm = PcmAudioData(bgmSamples, 44100)

        // ttsVolume = 1.0, bgmVolume = 0.5
        // 期待値: 1000 * 1.0 + 400 * 0.5 = 1200
        //        2000 * 1.0 + 600 * 0.5 = 2300
        val mixed = mixer.mix(tts, bgm, ttsVolume = 1.0f, bgmVolume = 0.5f, outputSampleRateHz = 44100)

        assertEquals(44100, mixed.sampleRateHz)
        assertEquals(2, mixed.samples.size)
        assertEquals(1200.toShort(), mixed.samples[0])
        assertEquals(2300.toShort(), mixed.samples[1])
    }

    @Test
    fun mix_overflows_clampsToShortRange() {
        val tts = PcmAudioData(shortArrayOf(30000, -30000), 44100)
        val bgm = PcmAudioData(shortArrayOf(20000, -20000), 44100)

        val mixed = mixer.mix(tts, bgm, ttsVolume = 1.0f, bgmVolume = 1.0f, outputSampleRateHz = 44100)

        assertEquals(Short.MAX_VALUE, mixed.samples[0])
        assertEquals(Short.MIN_VALUE, mixed.samples[1])
    }

    @Test
    fun mix_onlyTts_returnsScaledTts() {
        val tts = PcmAudioData(shortArrayOf(1000, 2000), 44100)
        val mixed = mixer.mix(tts, null, ttsVolume = 0.5f, outputSampleRateHz = 44100)

        assertEquals(2, mixed.samples.size)
        assertEquals(500.toShort(), mixed.samples[0])
        assertEquals(1000.toShort(), mixed.samples[1])
    }

    @Test
    fun mix_onlyBgm_returnsScaledBgm() {
        val bgm = PcmAudioData(shortArrayOf(1000, 2000), 44100)
        val mixed = mixer.mix(null, bgm, bgmVolume = 0.3f, outputSampleRateHz = 44100)

        assertEquals(2, mixed.samples.size)
        assertEquals(300.toShort(), mixed.samples[0])
        assertEquals(600.toShort(), mixed.samples[1])
    }

    @Test
    fun mix_differentLengths_padsShortSourceWithSilence() {
        val tts = PcmAudioData(shortArrayOf(1000), 44100)
        val bgm = PcmAudioData(shortArrayOf(200, 400, 600), 44100)

        val mixed = mixer.mix(tts, bgm, ttsVolume = 1.0f, bgmVolume = 1.0f, outputSampleRateHz = 44100)

        assertEquals(3, mixed.samples.size)
        assertEquals(1200.toShort(), mixed.samples[0])
        assertEquals(400.toShort(), mixed.samples[1])
        assertEquals(600.toShort(), mixed.samples[2])
    }

    @Test
    fun resample_sameRate_returnsIdenticalCopy() {
        val original = shortArrayOf(100, 200, 300)
        val resampled = mixer.resample(original, 44100, 44100)

        assertArrayEquals(original, resampled)
    }

    @Test
    fun resample_upsampleAndDownsample_preservesProportions() {
        // 1000サンプル (16000Hz) を 44100Hz へアップサンプリング
        val samples = ShortArray(1600) { (it % 1000).toShort() }
        val upsampled = mixer.resample(samples, 16000, 44100)

        val expectedSize = (1600 * (44100.0 / 16000.0)).toInt()
        assertEquals(expectedSize, upsampled.size)

        // ダウンサンプリング
        val downsampled = mixer.resample(upsampled, 44100, 16000)
        assertEquals(1600, downsampled.size)
    }

    @Test
    fun toPcmBytes_stereoInterleaved_createsCorrectByteArray() {
        val samples = shortArrayOf(100, -200)
        val bytes = mixer.toPcmBytes(samples, channels = 2)

        // 2 samples * 2 channels * 2 bytes = 8 bytes
        assertEquals(8, bytes.size)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(100.toShort(), buffer.short) // L
        assertEquals(100.toShort(), buffer.short) // R
        assertEquals((-200).toShort(), buffer.short) // L
        assertEquals((-200).toShort(), buffer.short) // R
    }
}
