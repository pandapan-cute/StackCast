package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.domain.model.LipSyncMotion
import com.pandapan.stackcast.domain.ports.StackChanPort

/**
 * リップシンク時系列をｽﾀｯｸﾁｬﾝへ送信するユースケース。
 *
 * @property stackChanPort BLE通信ポート。
 */
class SendLipSyncUseCase(
    private val stackChanPort: StackChanPort
) {
    /**
     * 接続状態を確認してリップシンク時系列を送信する。
     *
     * @param motion 送信対象のモーション時系列。
     * @return 送信結果。
     */
    suspend operator fun invoke(motion: List<LipSyncMotion>): Result<Unit> {
        if (!stackChanPort.isConnected()) {
            return Result.failure(IllegalStateException("ｽﾀｯｸﾁｬﾝが接続されていません。"))
        }
        return stackChanPort.sendLipSyncMotion(motion)
    }
}
