package com.pandapan.stackcast.infrastructure.ble

import com.pandapan.stackcast.application.usecase.SendLipSyncUseCase
import com.pandapan.stackcast.domain.model.LipOpening
import com.pandapan.stackcast.domain.model.LipSyncMotion
import com.pandapan.stackcast.domain.model.LipSyncMotionPacketCodec
import com.pandapan.stackcast.domain.model.ServoAngle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * フェイクBLEアダプターとMTU分割の単体テスト。
 */
class FakeStackChanAdapterTest {
    /** 未接続時は送信ユースケースが失敗することを確認する。 */
    @Test
    fun sendWithoutConnectionFails() = runTest {
        val adapter = FakeStackChanAdapter()
        val result = SendLipSyncUseCase(adapter)(sampleMotion())

        assertTrue(result.isFailure)
        assertTrue(adapter.sentPackets.isEmpty())
    }

    /** 接続後にモーションが送信され、パケットヘッダーが付与されることを確認する。 */
    @Test
    fun connectedMotionIsPacketizedAndSent() = runTest {
        val adapter = FakeStackChanAdapter()
        adapter.connect("AA:BB:CC:DD:EE:FF")
        val result = SendLipSyncUseCase(adapter)(sampleMotion())

        assertTrue(result.isSuccess)
        assertEquals(1, adapter.sentPackets.size)
        assertEquals('S'.code.toByte(), adapter.sentPackets.single()[0])
        assertEquals('C'.code.toByte(), adapter.sentPackets.single()[1])
    }

    /** 標準MTUより長い時系列が複数パケットへ分割されることを確認する。 */
    @Test
    fun longMotionIsSplitByMtu() {
        val motions = (0 until 20).map { index ->
            LipSyncMotion(
                timestampMs = index * 20L,
                lipOpening = LipOpening.of(index / 20.0),
                servoAngle = ServoAngle.fromLipOpening(LipOpening.of(index / 20.0))
            )
        }

        val packets = LipSyncMotionPacketCodec.encode(motions)

        assertFalse(packets.isEmpty())
        assertTrue(packets.size > 1)
        assertTrue(packets.all { it.size <= LipSyncMotionPacketCodec.DEFAULT_MTU - 3 })
    }

    /** テスト用の短いモーション時系列を生成する。 */
    private fun sampleMotion(): List<LipSyncMotion> {
        return listOf(
            LipSyncMotion(0L, LipOpening.CLOSED, ServoAngle.fromLipOpening(LipOpening.CLOSED)),
            LipSyncMotion(100L, LipOpening.OPEN, ServoAngle.fromLipOpening(LipOpening.OPEN))
        )
    }
}
