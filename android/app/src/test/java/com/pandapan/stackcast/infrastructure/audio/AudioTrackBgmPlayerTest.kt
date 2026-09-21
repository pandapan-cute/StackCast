package com.pandapan.stackcast.infrastructure.audio

import com.pandapan.stackcast.domain.model.PcmAudioData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AudioTrackBgmPlayer] の単体テスト。
 *
 * BGMの再生・停止・一時停止、ループ動作、および音量制御の正当性を検証する。
 */
class AudioTrackBgmPlayerTest {

    private val sampleData = PcmAudioData(
        samples = shortArrayOf(1000, 2000, 3000, 4000),
        sampleRateHz = 44100
    )

    @Test
    fun readNextSamples_whenStopped_returnsSilence() {
        val player = AudioTrackBgmPlayer(sampleData)

        assertFalse(player.isPlaying)
        val samples = player.readNextSamples(4, 44100)

        assertEquals(4, samples.size)
        assertTrue(samples.all { it == 0.toShort() })
    }

    @Test
    fun readNextSamples_whenPlaying_returnsScaledSamples() {
        val player = AudioTrackBgmPlayer(sampleData)
        player.volume = 1.0f
        player.play()

        assertTrue(player.isPlaying)
        val samples = player.readNextSamples(4, 44100)

        assertEquals(4, samples.size)
        assertEquals(1000.toShort(), samples[0])
        assertEquals(2000.toShort(), samples[1])
        assertEquals(3000.toShort(), samples[2])
        assertEquals(4000.toShort(), samples[3])
    }

    @Test
    fun readNextSamples_looping_cyclesSamplesContinuously() {
        val player = AudioTrackBgmPlayer(sampleData)
        player.volume = 1.0f
        player.isLooping = true
        player.play()

        // 音源長4を超える8サンプル要求 -> 2ループ巡回すること
        val samples = player.readNextSamples(8, 44100)

        assertEquals(8, samples.size)
        assertEquals(1000.toShort(), samples[0])
        assertEquals(2000.toShort(), samples[1])
        assertEquals(3000.toShort(), samples[2])
        assertEquals(4000.toShort(), samples[3])
        assertEquals(1000.toShort(), samples[4])
        assertEquals(2000.toShort(), samples[5])
        assertEquals(3000.toShort(), samples[6])
        assertEquals(4000.toShort(), samples[7])
    }

    @Test
    fun stop_resetsPlayheadPosition() {
        val player = AudioTrackBgmPlayer(sampleData)
        player.volume = 1.0f
        player.play()

        // 2サンプル消費
        player.readNextSamples(2, 44100)
        player.stop()

        assertFalse(player.isPlaying)

        // 再開時は先頭から再生されること
        player.play()
        val samples = player.readNextSamples(2, 44100)
        assertEquals(1000.toShort(), samples[0])
        assertEquals(2000.toShort(), samples[1])
    }

    @Test
    fun volume_adjustsSampleAmplitude() {
        val player = AudioTrackBgmPlayer(sampleData)
        player.volume = 0.5f
        player.play()

        val samples = player.readNextSamples(4, 44100)
        assertEquals(500.toShort(), samples[0])
        assertEquals(1000.toShort(), samples[1])
        assertEquals(1500.toShort(), samples[2])
        assertEquals(2000.toShort(), samples[3])
    }
}
