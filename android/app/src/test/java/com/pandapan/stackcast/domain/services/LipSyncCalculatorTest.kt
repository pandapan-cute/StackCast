package com.pandapan.stackcast.domain.services

import com.pandapan.stackcast.domain.model.PcmAudioData
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LipSyncCalculator] の単体テスト。
 *
 * 合成PCMデータ (無音・単一正弦波・立ち上がり/減衰のある発話風信号・ノイズ混在) を用いて、
 * 口パクタイムラインの算出ロジックが仕様通りに動作することを検証する。
 */
class LipSyncCalculatorTest {

    private val sampleRateHz = 16_000

    @Test
    fun `完全な無音PCMに対しては全フレームで口を閉じた状態(0_0)を返す`() {
        val calculator = LipSyncCalculator()
        val silentPcm = PcmAudioData(ShortArray(sampleRateHz), sampleRateHz) // 1秒間の無音

        val motions = calculator.calculate(silentPcm)

        assertTrue("無音区間には最低1フレームは出力されるべき", motions.isNotEmpty())
        assertTrue(motions.all { it.lipOpening.value == 0.0 })
        assertTrue(motions.all { it.servoAngle.degrees == 0.0 })
    }

    @Test
    fun `単一周波数の正弦波に対しては継続的に口が開いた状態を返す`() {
        val calculator = LipSyncCalculator()
        val sineWave = generateSineWave(
            durationMs = 500,
            sampleRateHz = sampleRateHz,
            frequencyHz = 220.0,
            amplitude = 0.8
        )

        val motions = calculator.calculate(PcmAudioData(sineWave, sampleRateHz))

        assertTrue(motions.isNotEmpty())
        // 立ち上がり・末尾フレームを除く中間フレームは、継続的な音量があるため一定以上口が開いているはず。
        val middleMotions = motions.subList(2, motions.size - 2)
        assertTrue(
            "一定振幅の正弦波では口が十分に開くはず",
            middleMotions.all { it.lipOpening.value > 0.3 }
        )
    }

    @Test
    fun `立ち上がりと減衰のある発話風信号では前後が閉じ中央が開く`() {
        val calculator = LipSyncCalculator()
        val silence = ShortArray(sampleRateHz / 10) // 100ms の無音
        val speech = generateSineWave(
            durationMs = 300,
            sampleRateHz = sampleRateHz,
            frequencyHz = 300.0,
            amplitude = 0.9
        )
        val samples = silence + speech + silence

        val motions = calculator.calculate(PcmAudioData(samples, sampleRateHz))

        val firstFrame = motions.first()
        val lastFrame = motions.last()
        val loudestFrame = motions.maxBy { it.lipOpening.value }

        assertEquals(0.0, firstFrame.lipOpening.value, DELTA)
        assertEquals(0.0, lastFrame.lipOpening.value, DELTA)
        assertTrue(
            "発話区間中央のフレームは前後の無音より大きく口を開くはず",
            loudestFrame.lipOpening.value > 0.3
        )
    }

    @Test
    fun `無音にわずかなノイズが乗っても閾値以下であれば口を閉じたままにする`() {
        val calculator = LipSyncCalculator()
        val random = Random(seed = 42)
        // 振幅の小さい(RMSが閾値未満に収まる)ノイズのみのPCMを生成する。
        val noisySilence = ShortArray(sampleRateHz / 2) { (random.nextInt(-50, 50)).toShort() }

        val motions = calculator.calculate(PcmAudioData(noisySilence, sampleRateHz))

        assertTrue(
            "無音とみなせる程度の微小ノイズでは口を開かないはず",
            motions.all { it.lipOpening.value == 0.0 }
        )
    }

    @Test
    fun `発話信号に微小ノイズが混入しても口の開き具合は大きく変化しない`() {
        val calculator = LipSyncCalculator()
        val random = Random(seed = 7)
        val cleanSpeech = generateSineWave(
            durationMs = 300,
            sampleRateHz = sampleRateHz,
            frequencyHz = 300.0,
            amplitude = 0.8
        )
        val noisySpeech = ShortArray(cleanSpeech.size) { index ->
            (cleanSpeech[index] + random.nextInt(-200, 200)).toShort()
        }

        val cleanMotions = calculator.calculate(PcmAudioData(cleanSpeech, sampleRateHz))
        val noisyMotions = calculator.calculate(PcmAudioData(noisySpeech, sampleRateHz))

        assertEquals(cleanMotions.size, noisyMotions.size)
        for (i in cleanMotions.indices) {
            val diff = kotlin.math.abs(cleanMotions[i].lipOpening.value - noisyMotions[i].lipOpening.value)
            assertTrue(
                "低SNRのノイズ混入では口の開き具合が大きく変動しすぎないはず (index=$i, diff=$diff)",
                diff < 0.2
            )
        }
    }

    @Test
    fun `タイムスタンプは窓長の整数倍で単調増加する`() {
        val calculator = LipSyncCalculator(windowMillis = 20)
        val sineWave = generateSineWave(
            durationMs = 200,
            sampleRateHz = sampleRateHz,
            frequencyHz = 220.0,
            amplitude = 0.5
        )

        val motions = calculator.calculate(PcmAudioData(sineWave, sampleRateHz))

        motions.forEachIndexed { index, motion ->
            assertEquals(index.toLong() * 20, motion.timestampMs)
        }
    }

    /** 指定条件の正弦波PCMサンプルを生成するテストヘルパー。 */
    private fun generateSineWave(
        durationMs: Int,
        sampleRateHz: Int,
        frequencyHz: Double,
        amplitude: Double
    ): ShortArray {
        val sampleCount = sampleRateHz * durationMs / 1000
        return ShortArray(sampleCount) { index ->
            val timeSeconds = index.toDouble() / sampleRateHz
            (amplitude * Short.MAX_VALUE * sin(2.0 * Math.PI * frequencyHz * timeSeconds)).toInt().toShort()
        }
    }

    private companion object {
        const val DELTA = 0.0001
    }
}
