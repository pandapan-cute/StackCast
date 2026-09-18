package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.domain.ports.RtmpStreamerPort

/**
 * 実行中のRTMP配信を終了するユースケース。
 *
 * @property rtmpStreamerPort 配信エンジンを抽象化したポート。
 */
class StopStreamingUseCase(
    private val rtmpStreamerPort: RtmpStreamerPort
) {

    /**
     * 配信を終了する。
     *
     * @return 停止処理に成功した場合は [Result.success]。
     */
    suspend operator fun invoke(): Result<Unit> {
        return rtmpStreamerPort.stopStream()
    }
}
