package com.pandapan.stackcast.domain.ports

import com.pandapan.stackcast.domain.model.AvatarExpression
import com.pandapan.stackcast.domain.model.LipSyncMotion
import com.pandapan.stackcast.domain.model.StackChanConnectionState
import kotlinx.coroutines.flow.StateFlow

/**
 * ｽﾀｯｸﾁｬﾝとの通信を抽象化するポート。
 *
 * AndroidのBluetoothGattやスキャンAPIをドメイン・アプリケーション層へ漏らさず、
 * 実機アダプターとテスト用フェイクを差し替え可能にする。
 */
interface StackChanPort {
    /** 現在の接続状態。 */
    val connectionState: StateFlow<StackChanConnectionState>

    /** 接続済みかどうかを返す。 */
    fun isConnected(): Boolean

    /**
     * 指定アドレスのデバイスへ接続する。
     *
     * @param deviceAddress BLE MACアドレス。
     */
    suspend fun connect(deviceAddress: String): Result<Unit>

    /** 接続を切断してリソースを解放する。 */
    suspend fun disconnect(): Result<Unit>

    /**
     * リップシンク時系列をパケット化して送信する。
     *
     * @param motion 送信対象の時系列。
     */
    suspend fun sendLipSyncMotion(motion: List<LipSyncMotion>): Result<Unit>

    /**
     * 表情変更コマンドをパケット化して送信する。
     *
     * @param expression 変更先の表情。
     */
    suspend fun sendExpression(expression: AvatarExpression): Result<Unit>
}
