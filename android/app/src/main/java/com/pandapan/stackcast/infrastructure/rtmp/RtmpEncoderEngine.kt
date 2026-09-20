package com.pandapan.stackcast.infrastructure.rtmp

import android.content.Context
import com.pandapan.stackcast.domain.model.StreamSession
import com.pandapan.stackcast.domain.model.StreamerEvent
import com.pandapan.stackcast.domain.model.PcmAudioData
import com.pandapan.stackcast.domain.ports.RtmpAudioSinkPort
import com.pandapan.stackcast.domain.ports.RtmpStreamerPort
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpCamera2
import com.pedro.library.view.OpenGlView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * RootEncoder ライブラリ (`RtmpCamera2`) を用いた [RtmpStreamerPort] の実機実装。
 *
 * カメラ・マイクの取得からH.264/AACエンコード、RTMP送出まで [RtmpCamera2] 内部で完結するため、
 * 本クラスは主にライフサイクル制御とイベント変換（[ConnectChecker] → [StreamerEvent]）を担う。
 *
 * [context] にはアプリケーションコンテキストを渡し、`RtmpCamera2(context, connectChecker)` の
 * 「バックグラウンドモード」で生成する。これにより、UI（[OpenGlView]）が破棄された状態
 * （画面ロック中や別画面遷移中）でもカメラ取得・エンコード・配信を継続できる。
 * プレビュー表示が必要な間だけ [attachPreviewView] で実際の [OpenGlView] を接続し、
 * 不要になったら [detachPreviewView] でバックグラウンドレンダリングへ戻す。
 *
 * このエンジンは [com.pandapan.stackcast.infrastructure.service.StreamingForegroundService] が
 * 生成・保持し、配信中は常にサービスと同じライフサイクルで存在し続けることを想定している。
 *
 * @property context カメラ・マイクの初期化に使用するアプリケーションコンテキスト。
 */
class RtmpEncoderEngine(
    private val context: Context
) : RtmpStreamerPort, RtmpAudioSinkPort {

    /** TTS PCMを配信音声へ渡す内部バッファ。 */
    private val audioSource = BufferAudioSource(AUDIO_SAMPLE_RATE_HZ, AUDIO_CHANNELS)

    private val _events = MutableSharedFlow<StreamerEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val events: SharedFlow<StreamerEvent> = _events.asSharedFlow()

    /**
     * [RtmpCamera2] へ委譲する [ConnectChecker] 実装。
     *
     * RootEncoder からのコールバックは呼び出しスレッドが不定であるため、
     * [MutableSharedFlow.tryEmit] を用いてノンブロッキングにイベントへ変換する。
     */
    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) {
            _events.tryEmit(StreamerEvent.ConnectionStarted(url))
        }

        override fun onConnectionSuccess() {
            _events.tryEmit(StreamerEvent.ConnectionSuccess)
        }

        override fun onConnectionFailed(reason: String) {
            _events.tryEmit(StreamerEvent.ConnectionFailed(reason))
        }

        override fun onDisconnect() {
            _events.tryEmit(StreamerEvent.Disconnected)
        }

        override fun onAuthError() {
            _events.tryEmit(StreamerEvent.AuthError("認証に失敗しました。"))
        }

        override fun onAuthSuccess() = Unit

        override fun onNewBitrate(bitrate: Long) {
            _events.tryEmit(StreamerEvent.BitrateUpdated(bitrate))
        }
    }

    /** カメラ・マイク・エンコーダ・RTMP送出を統括する RootEncoder のコア実装。 */
    private val rtmpCamera: RtmpCamera2 = RtmpCamera2(context.applicationContext, connectChecker)

    init {
        // RootEncoderの公開APIでPCMを差し替えられる唯一の接続点を使い、
        // マイク由来のバイト列をTTSバッファへ置換する。AudioTrack/speakは経由しない。
        rtmpCamera.setCustomAudioEffect(object : com.pedro.encoder.input.audio.CustomAudioEffect() {
            override fun process(input: ByteArray): ByteArray = audioSource.read(input.size)
        })
    }

    override fun isReady(): Boolean = true

    override fun isStreaming(): Boolean = rtmpCamera.isStreaming

    override fun startPreview(): Result<Unit> = runCatching {
        if (!rtmpCamera.isOnPreview) {
            check(
                rtmpCamera.prepareVideo(
                    PREVIEW_WIDTH,
                    PREVIEW_HEIGHT,
                    PREVIEW_FPS,
                    PREVIEW_BITRATE_BPS,
                    VIDEO_ROTATION_DEGREES
                )
            ) { "プレビュー用カメラの初期化に失敗しました。" }
            rtmpCamera.startPreview()
        }
    }

    override fun stopPreview(): Result<Unit> = runCatching {
        // 配信中はプレビュー停止に伴うカメラクローズを避けるため無視する。
        if (rtmpCamera.isOnPreview && !rtmpCamera.isStreaming) {
            rtmpCamera.stopPreview()
        }
    }

    override suspend fun startStream(session: StreamSession): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(!rtmpCamera.isStreaming) { "既に配信中です。停止してから再開してください。" }

            val prepared = rtmpCamera.prepareVideo(
                session.videoWidth,
                session.videoHeight,
                session.fps,
                session.videoBitrateBps,
                VIDEO_ROTATION_DEGREES
            ) && rtmpCamera.prepareAudio(
                session.audioBitrateBps,
                session.audioSampleRateHz,
                session.audioStereo
            )
            check(prepared) { "映像/音声エンコーダの初期化に失敗しました。端末がサポートしていない可能性があります。" }

            rtmpCamera.startStream(session.fullRtmpUrl)
        }
    }

    override suspend fun stopStream(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (rtmpCamera.isStreaming) {
                rtmpCamera.stopStream()
            }
        }
    }

    /**
     * TTSで生成したPCMを配信音声キューへ追加する。
     *
     * @param audio 16-bitモノラルPCM。
     * @return キュー投入結果。
     */
    override suspend fun submit(audio: PcmAudioData): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching { audioSource.enqueue(audio) }
    }

    /**
     * UI側で生成した [OpenGlView] をプレビュー描画先として接続する。
     *
     * 配信中に呼び出しても配信は継続されたまま描画先のみ切り替わる（RootEncoderの
     * `replaceView` 機構による）。[com.pandapan.stackcast.presentation.streaming.StreamingFragment]
     * の `onStart` 相当のタイミングで呼び出すことを想定している。
     *
     * @param openGlView プレビューを表示する RootEncoder 提供のカスタムView。
     */
    fun attachPreviewView(openGlView: OpenGlView) {
        rtmpCamera.replaceView(openGlView)
    }

    /**
     * プレビュー描画先をUIから切り離し、バックグラウンドレンダリングへ戻す。
     *
     * 画面が破棄された後もフォアグラウンドサービス上で配信を継続するために、
     * [com.pandapan.stackcast.presentation.streaming.StreamingFragment] の `onStop` 相当の
     * タイミングで呼び出すことを想定している。
     */
    fun detachPreviewView() {
        rtmpCamera.replaceView(context.applicationContext)
    }

    override fun release() {
        audioSource.clear()
        runCatching { if (rtmpCamera.isStreaming) rtmpCamera.stopStream() }
        runCatching { if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview() }
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 16

        /** 映像の回転角。スマートフォンを横向きに構えて配信する運用を前提に0度（回転無し）とする。 */
        const val VIDEO_ROTATION_DEGREES = 0

        /** プレビュー用の16:9推奨解像度。 */
        const val PREVIEW_WIDTH = 1280
        const val PREVIEW_HEIGHT = 720
        const val PREVIEW_FPS = 30
        const val PREVIEW_BITRATE_BPS = 2_500_000
        const val AUDIO_SAMPLE_RATE_HZ = 44_100
        const val AUDIO_CHANNELS = 2
    }
}
