package com.pandapan.stackcast.infrastructure.rtmp

import com.pandapan.stackcast.domain.model.PcmAudioData
import com.pandapan.stackcast.domain.ports.RtmpAudioSinkPort

/**
 * 開発・統合テスト用のRTMP音声供給実装。
 *
 * PCMを記録し、実機の外部音声入力APIが接続されるまでパイプラインを成立させる。
 */
class RecordingRtmpAudioSink : RtmpAudioSinkPort {
    /** 供給された音声履歴。 */
    val submittedAudio: MutableList<PcmAudioData> = mutableListOf()

    override suspend fun submit(audio: PcmAudioData): Result<Unit> = runCatching {
        submittedAudio += audio
    }
}
