package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.infrastructure.tts.MockTtsEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SynthesizeVoiceAndLipSyncUseCase] の単体テスト。
 *
 * [MockTtsEngine] を用いて実機のTTSエンジンに依存せずに、
 * テキスト -> 疑似音声合成 -> リップシンク解析、という一連の処理が
 * 破綻なく完了しタイムスタンプ付きの時系列を生成することを検証する。
 */
class SynthesizeVoiceAndLipSyncUseCaseTest {

    @Test
    fun `MockTtsEngine経由でテキストからLipSyncMotionの時系列が生成される`() = runTest {
        val useCase = SynthesizeVoiceAndLipSyncUseCase(MockTtsEngine())

        val result = useCase("こんにちは、ｽﾀｯｸﾁｬﾝです！")

        assertTrue(result.isSuccess)
        val motions = result.getOrThrow()
        assertTrue("口パクの時系列が1フレーム以上生成されるはず", motions.isNotEmpty())

        // タイムスタンプが古い順 (単調非減少) に並んでいることを検証する。
        for (i in 1 until motions.size) {
            assertTrue(motions[i].timestampMs >= motions[i - 1].timestampMs)
        }

        // 発話区間には、口が一定以上開くフレームが少なくとも1つは含まれるはず。
        assertTrue(motions.any { it.lipOpening.value > 0.2 })
    }

    @Test
    fun `空文字列でも例外を送出せず短い時系列を返す`() = runTest {
        val useCase = SynthesizeVoiceAndLipSyncUseCase(MockTtsEngine())

        val result = useCase("")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isNotEmpty())
    }
}
