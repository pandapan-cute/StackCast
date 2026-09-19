package com.pandapan.stackcast.infrastructure.ble

import com.pandapan.stackcast.domain.model.LipSyncMotion
import com.pandapan.stackcast.domain.model.LipSyncMotionPacketCodec
import com.pandapan.stackcast.domain.model.StackChanConnectionState
import com.pandapan.stackcast.domain.ports.StackChanPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * JVMテストと画面開発用のｽﾀｯｸﾁｬﾝ通信フェイク。
 *
 * 実際の無線送信は行わず、MTU分割後のパケットを [sentPackets] に記録する。
 */
class FakeStackChanAdapter : StackChanPort {
    private val _connectionState =
        MutableStateFlow<StackChanConnectionState>(StackChanConnectionState.Disconnected)

    /** 直近の接続状態。 */
    override val connectionState: StateFlow<StackChanConnectionState> = _connectionState.asStateFlow()

    /** フェイクが記録した送信パケット。 */
    val sentPackets: MutableList<ByteArray> = mutableListOf()

    override fun isConnected(): Boolean = _connectionState.value is StackChanConnectionState.Connected

    override suspend fun connect(deviceAddress: String): Result<Unit> {
        if (deviceAddress.isBlank()) {
            return Result.failure(IllegalArgumentException("BLEアドレスが空です。"))
        }
        _connectionState.value = StackChanConnectionState.Connected(LipSyncMotionPacketCodec.DEFAULT_MTU)
        return Result.success(Unit)
    }

    override suspend fun disconnect(): Result<Unit> {
        _connectionState.value = StackChanConnectionState.Disconnected
        return Result.success(Unit)
    }

    override suspend fun sendLipSyncMotion(motion: List<LipSyncMotion>): Result<Unit> {
        if (!isConnected()) {
            return Result.failure(IllegalStateException("未接続です。"))
        }
        sentPackets += LipSyncMotionPacketCodec.encode(motion)
        return Result.success(Unit)
    }
}
