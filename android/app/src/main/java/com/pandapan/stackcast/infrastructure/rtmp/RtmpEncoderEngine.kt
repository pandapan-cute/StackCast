package com.pandapan.stackcast.infrastructure.rtmp

import android.content.Context
import android.util.Log
import android.view.View
import androidx.core.view.doOnLayout
import com.pandapan.stackcast.domain.model.StreamSession
import com.pandapan.stackcast.domain.model.StreamerEvent
import com.pandapan.stackcast.domain.model.PcmAudioData
import com.pandapan.stackcast.domain.ports.BgmPlayerPort
import com.pandapan.stackcast.domain.ports.RtmpAudioSinkPort
import com.pandapan.stackcast.domain.ports.RtmpStreamerPort
import com.pandapan.stackcast.infrastructure.audio.AudioTrackBgmPlayer
import com.pandapan.stackcast.infrastructure.logging.AndroidPipelineLogger
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.gl.render.filters.ViewFilterRender
import com.pedro.encoder.utils.gl.TranslateTo
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

    /** BGMプレイヤー。 */
    val bgmPlayer: BgmPlayerPort = AudioTrackBgmPlayer(context.applicationContext)

    /** TTS PCMおよびBGMを結合して配信音声へ渡す内部バッファ。 */
    val audioSource = BufferAudioSource(
        outputSampleRateHz = AUDIO_SAMPLE_RATE_HZ,
        outputChannels = AUDIO_CHANNELS,
        bgmPlayer = bgmPlayer,
        logger = AndroidPipelineLogger()
    )

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
            Log.i(TAG, "RTMP接続開始: $url")
            _events.tryEmit(StreamerEvent.ConnectionStarted(url))
        }

        override fun onConnectionSuccess() {
            Log.i(TAG, "RTMP接続成功")
            _events.tryEmit(StreamerEvent.ConnectionSuccess)
        }

        override fun onConnectionFailed(reason: String) {
            Log.w(TAG, "RTMP接続失敗: $reason")
            _events.tryEmit(StreamerEvent.ConnectionFailed(reason))
        }

        override fun onDisconnect() {
            Log.w(TAG, "RTMP切断")
            _events.tryEmit(StreamerEvent.Disconnected)
        }

        override fun onAuthError() {
            Log.w(TAG, "RTMP認証エラー")
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
        Log.i(TAG, "RtmpEncoderEngine 初期化")
        // RootEncoderの公開APIでPCMを差し替えられる唯一の接続点を使い、
        // マイク由来のバイト列をTTS+BGM結合バッファへ置換する。AudioTrack/speakは経由しない。
        rtmpCamera.setCustomAudioEffect(object : com.pedro.encoder.input.audio.CustomAudioEffect() {
            override fun process(pcmBuffer: ByteArray): ByteArray = audioSource.read(pcmBuffer.size)
        })
    }

    /**
     * TTSおよびBGMの内部音声音量を設定する。
     *
     * @param ttsVolume TTS音量倍率 (0.0〜1.0)。
     * @param bgmVolume BGM音量倍率 (0.0〜1.0)。
     */
    fun setAudioVolumes(ttsVolume: Float, bgmVolume: Float) {
        audioSource.ttsVolume = ttsVolume
        audioSource.bgmVolume = bgmVolume
        bgmPlayer.volume = bgmVolume
    }

    override fun isReady(): Boolean = true

    override fun isStreaming(): Boolean = rtmpCamera.isStreaming

    override fun startPreview(): Result<Unit> = runCatching {
        Log.i(TAG, "startPreview 呼び出し")
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
        Log.i(TAG, "stopPreview 呼び出し")
        // 配信中はプレビュー停止に伴うカメラクローズを避けるため無視する。
        if (rtmpCamera.isOnPreview && !rtmpCamera.isStreaming) {
            rtmpCamera.stopPreview()
        }
    }

    override suspend fun startStream(session: StreamSession): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Log.i(TAG, "startStream 開始: url=${session.fullRtmpUrl}, ${session.videoWidth}x${session.videoHeight}, fps=${session.fps}")
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

            // prepareVideo が解像度/FPS変更に伴って GL 描画系を作り直す場合があるため、
            // エンコーダ準備後かつ配信開始前に字幕フィルタを現在の GL に再接続する。
            withContext(Dispatchers.Main.immediate) {
                subtitleView?.let { view ->
                    if (view.width > 0 && view.height > 0) {
                        installSubtitleFilter(view)
                    } else {
                        Log.w(TAG, "字幕Viewのレイアウト未完了のため、配信開始前のフィルタ再接続を見送り")
                    }
                }
            }

            rtmpCamera.startStream(session.fullRtmpUrl)
            bgmPlayer.play()
            Log.i(TAG, "startStream 完了: BGM再生開始")
            Unit
        }
    }

    override suspend fun stopStream(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            Log.i(TAG, "stopStream 開始")
            bgmPlayer.stop()
            if (rtmpCamera.isStreaming) {
                rtmpCamera.stopStream()
            }
            Log.i(TAG, "stopStream 完了")
            Unit
        }
    }

    /**
     * TTSで生成したPCMを配信音声キューへ追加する。
     *
     * @param audio 16-bitモノラルPCM。
     * @return キュー投入結果。
     */
    override suspend fun submit(audio: PcmAudioData): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            Log.i(TAG, "PCM投入: samples=${audio.samples.size}, sampleRate=${audio.sampleRateHz}")
            audioSource.enqueue(audio)
        }
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
        Log.i(TAG, "attachPreviewView: ${openGlView.javaClass.simpleName}")
        rtmpCamera.replaceView(openGlView)
        restoreSubtitleFilterWhenLaidOut()
    }

    /** 字幕テロップを配信映像フレームに焼き込むための OpenGL フィルタ。 */
    private var subtitleFilter: ViewFilterRender? = null

    /** 字幕フィルタの再生成時に使う字幕View。 */
    private var subtitleView: View? = null

    /**
     * 字幕テロップの [View] を配信映像フレームへ焼き込むフィルタとして接続する。
     *
     * RootEncoder の [ViewFilterRender] を使用し、カメラプレビューおよび
     * RTMP配信映像の下端へリアルタイムにテロップを合成する。
     *
     * @param view 字幕テロップのコンテナView (例: layout_comment_overlay)。
     */
    fun attachSubtitleView(view: View) {
        Log.i(TAG, "attachSubtitleView request: ${view.javaClass.simpleName}, id=${view.id}, measured=${view.width}x${view.height}")
        subtitleView = view
        restoreSubtitleFilterWhenLaidOut()
    }

    /** レイアウト完了後に字幕Viewを現在のGL描画系へ登録する。 */
    private fun restoreSubtitleFilterWhenLaidOut() {
        val view = subtitleView ?: return
        view.doOnLayout { laidOutView ->
            if (subtitleView !== laidOutView) return@doOnLayout
            if (laidOutView.width <= 0 || laidOutView.height <= 0) {
                Log.w(TAG, "字幕Viewのサイズが不正のためフィルタを登録できません: ${laidOutView.width}x${laidOutView.height}")
                return@doOnLayout
            }
            installSubtitleFilter(laidOutView)
        }
    }

    /** 字幕フィルタを作り直し、現在の RootEncoder GL 描画系へ登録する。 */
    private fun installSubtitleFilter(view: View) {
        subtitleFilter?.let { previousFilter ->
            runCatching { rtmpCamera.glInterface.removeFilter(previousFilter) }
            runCatching { previousFilter.release() }
        }
        val filter = ViewFilterRender().apply {
            this.view = view
            // 配信映像（16:9）の下部に配置
            setPosition(TranslateTo.BOTTOM)
        }
        subtitleFilter = filter
        rtmpCamera.glInterface.setFilter(filter)
        Log.i(TAG, "字幕フィルタをGLへ登録: view=${view.width}x${view.height}")
    }

    /**
     * 字幕テロップの映像焼き込みフィルタを解除する。
     */
    fun detachSubtitleView() {
        Log.i(TAG, "detachSubtitleView")
        subtitleFilter?.let {
            runCatching { rtmpCamera.glInterface.removeFilter(it) }
            runCatching { it.release() }
        }
        subtitleFilter = null
        subtitleView = null
    }

    /**
     * プレビュー描画先をUIから切り離し、バックグラウンドレンダリングへ戻す。
     *
     * 画面が破棄された後もフォアグラウンドサービス上で配信を継続するために、
     * [com.pandapan.stackcast.presentation.streaming.StreamingFragment] の `onStop` 相当の
     * タイミングで呼び出すことを想定している。
     */
    fun detachPreviewView() {
        Log.i(TAG, "detachPreviewView")
        rtmpCamera.replaceView(context.applicationContext)
        restoreSubtitleFilterWhenLaidOut()
    }

    override fun release() {
        Log.i(TAG, "release 呼び出し")
        detachSubtitleView()
        audioSource.clear()
        bgmPlayer.release()
        runCatching { if (rtmpCamera.isStreaming) rtmpCamera.stopStream() }
        runCatching { if (rtmpCamera.isOnPreview) rtmpCamera.stopPreview() }
    }

    private companion object {
        const val TAG = "RtmpEncoderEngine"
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
