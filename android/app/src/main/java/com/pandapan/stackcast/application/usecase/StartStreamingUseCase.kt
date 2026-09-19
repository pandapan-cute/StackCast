package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.domain.model.StreamSession
import com.pandapan.stackcast.domain.ports.RtmpStreamerPort

/**
 * 指定された配信セッション設定でRTMP配信を開始するユースケース。
 *
 * [RtmpStreamerPort] の具体的な実装（RootEncoderベースの実機実装かモックか）には依存せず、
 * ポート経由で配信開始を要求するだけの薄いオーケストレーション層として振る舞う。
 *
 * @property rtmpStreamerPort 配信エンジンを抽象化したポート。
 */
class StartStreamingUseCase(
    private val rtmpStreamerPort: RtmpStreamerPort
) {

    /**
     * 配信を開始する。
     *
     * @param session 配信先URLおよびエンコードパラメータ。
     * @return 開始要求に成功した場合は [Result.success]。
     */
    suspend operator fun invoke(session: StreamSession): Result<Unit> {
        return rtmpStreamerPort.startStream(session)
    }
}
