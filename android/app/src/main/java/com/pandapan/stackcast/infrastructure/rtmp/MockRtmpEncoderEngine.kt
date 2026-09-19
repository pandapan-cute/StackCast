package com.pandapan.stackcast.infrastructure.rtmp

import com.pandapan.stackcast.domain.model.StreamSession
import com.pandapan.stackcast.domain.model.StreamerEvent
import com.pandapan.stackcast.domain.ports.RtmpStreamerPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 実際のカメラ・ネットワーク送出を一切行わない、テスト・開発用の [RtmpStreamerPort] モック実装。
 *
 * 実機や実サーバーが無くても [com.pandapan.stackcast.presentation.streaming.StreamingViewModel] の
 * 状態遷移 (`Idle` → `Starting` → `Streaming` → `Stopping` → `Idle`) を検証できるよう、
 * 一定時間後に接続成功イベントを発行するだけの簡易的な疑似実装を提供する。
 * JVM単体テストから直接利用されるため、`android.util.Log` 等のAndroid依存APIは呼び出さない。
 *
 * @property connectDelayMillis 接続開始から接続成功イベントを発行するまでの疑似遅延時間 (ms)。
 * @property logger 動作確認用の出力先。既定では標準出力へ出力するが、テストからは差し替え可能。
 */
class MockRtmpEncoderEngine(
    private val connectDelayMillis: Long = 100L,
    private val logger: (String) -> Unit = ::println
) : RtmpStreamerPort {

    private val _events = MutableSharedFlow<StreamerEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val events: SharedFlow<StreamerEvent> = _events.asSharedFlow()

    private var streaming = false
    private var previewing = false

    override fun isReady(): Boolean = true

    override fun isStreaming(): Boolean = streaming

    override fun startPreview(): Result<Unit> = runCatching {
        previewing = true
        logger("[MockRtmpEncoderEngine] プレビューを開始しました。")
    }

    override fun stopPreview(): Result<Unit> = runCatching {
        previewing = false
        logger("[MockRtmpEncoderEngine] プレビューを停止しました。")
    }

    override suspend fun startStream(session: StreamSession): Result<Unit> = runCatching {
        logger("[MockRtmpEncoderEngine] 配信開始要求: ${session.fullRtmpUrl}")
        _events.emit(StreamerEvent.ConnectionStarted(session.fullRtmpUrl))
        // 実エンジンの接続処理を模した疑似遅延。
        delay(connectDelayMillis)
        streaming = true
        _events.emit(StreamerEvent.ConnectionSuccess)
        _events.emit(StreamerEvent.BitrateUpdated(session.videoBitrateBps.toLong()))
        logger("[MockRtmpEncoderEngine] 配信を開始しました。")
    }

    override suspend fun stopStream(): Result<Unit> = runCatching {
        streaming = false
        _events.emit(StreamerEvent.Disconnected)
        logger("[MockRtmpEncoderEngine] 配信を停止しました。")
    }

    override fun release() {
        streaming = false
        previewing = false
        logger("[MockRtmpEncoderEngine] リソースを解放しました。")
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 16
    }
}
