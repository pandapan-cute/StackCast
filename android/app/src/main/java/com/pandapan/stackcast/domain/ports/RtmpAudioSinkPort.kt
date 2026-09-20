package com.pandapan.stackcast.domain.ports

import com.pandapan.stackcast.domain.model.PcmAudioData

/**
 * TTSで生成したPCM音声をRTMP配信側へ供給するポート。
 *
 * RootEncoderのマイク入力を使う実装と、E2E検証用の記録実装を差し替えられる。
 */
interface RtmpAudioSinkPort {
    /** PCM音声を配信側へ渡す。 */
    suspend fun submit(audio: PcmAudioData): Result<Unit>
}
