package com.pandapan.stackcast.presentation.streaming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pandapan.stackcast.application.usecase.StartStreamingUseCase
import com.pandapan.stackcast.application.usecase.StopStreamingUseCase
import com.pandapan.stackcast.application.usecase.ProcessCommentAndRespondUseCase
import com.pandapan.stackcast.domain.model.AiResponse
import com.pandapan.stackcast.domain.model.LiveComment
import com.pandapan.stackcast.domain.model.PersonaPrompt
import com.pandapan.stackcast.domain.model.PipelineState
import com.pandapan.stackcast.domain.model.StreamSession
import com.pandapan.stackcast.domain.model.StreamerEvent
import com.pandapan.stackcast.domain.model.StreamingState
import com.pandapan.stackcast.domain.ports.RtmpStreamerPort
import com.pandapan.stackcast.domain.ports.YouTubeChatPort
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 配信中画面 ([StreamingFragment]) の状態を保持する ViewModel。
 *
 * [RtmpStreamerPort] の具体的な実装（実機では [StreamingForegroundService] 経由の
 * `RtmpEncoderEngine`、単体テストでは `MockRtmpEncoderEngine`）には依存せず、
 * [attachStreamer] で注入されたポートを介して配信の開始・停止・イベント購読を行う。
 *
 * 状態は [StreamingState.Idle] → [StreamingState.Starting] → [StreamingState.Streaming] →
 * [StreamingState.Stopping] → [StreamingState.Idle] の順に遷移する。
 * 配信中に予期せぬ切断が発生した場合は、指数バックオフによる簡易的な自動再接続を試みる。
 */
class StreamingViewModel : ViewModel() {

    private val _state = MutableStateFlow<StreamingState>(StreamingState.Idle)

    /** 配信状態の公開ストリーム。 */
    val state: StateFlow<StreamingState> = _state.asStateFlow()

    private var rtmpStreamerPort: RtmpStreamerPort? = null
    private var eventsJob: Job? = null
    private var reconnectJob: Job? = null

    private var lastSession: StreamSession? = null
    private var reconnectAttempt: Int = 0
    private var pipelineJob: Job? = null
    private val _pipelineState = MutableStateFlow<PipelineState>(PipelineState.Idle)

    /** コメント処理パイプラインの状態。 */
    val pipelineState: StateFlow<PipelineState> = _pipelineState.asStateFlow()

    private val _latestComment = MutableStateFlow<LiveComment?>(null)

    /** 最後に受信したコメント。 */
    val latestComment: StateFlow<LiveComment?> = _latestComment.asStateFlow()

    private val _latestResponse = MutableStateFlow<AiResponse?>(null)

    /** 最後に生成したAI応答。 */
    val latestResponse: StateFlow<AiResponse?> = _latestResponse.asStateFlow()

    /**
     * 配信エンジンをこの ViewModel へ接続する。
     *
     * [StreamingForegroundService] とのバインドが完了したタイミングで
     * [StreamingFragment] から呼び出されることを想定している。同一ポートが
     * 既に接続済みの場合は何もしない。
     *
     * @param port 接続するRTMP配信エンジンのポート。
     */
    fun attachStreamer(port: RtmpStreamerPort) {
        if (rtmpStreamerPort === port) return
        rtmpStreamerPort = port
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            port.events.collect { event -> handleEvent(event) }
        }
    }

    /**
     * 配信を開始する。既に開始処理中・配信中の場合は無視される。
     *
     * @param session 配信先URLおよびエンコードパラメータ。
     */
    fun startStreaming(session: StreamSession) {
        val port = rtmpStreamerPort
        if (port == null) {
            _state.value = StreamingState.Error("配信エンジンが初期化されていません。")
            return
        }
        if (_state.value is StreamingState.Starting || _state.value is StreamingState.Streaming) return

        reconnectJob?.cancel()
        reconnectAttempt = 0
        lastSession = session
        _state.value = StreamingState.Starting

        viewModelScope.launch {
            StartStreamingUseCase(port)(session).onFailure { error ->
                _state.value = StreamingState.Error(error.message ?: "配信開始に失敗しました。")
            }
        }
    }

    /** 配信を終了する。ユーザー操作による明示的な終了のため、自動再接続は行わない。 */
    fun stopStreaming() {
        val port = rtmpStreamerPort ?: return
        reconnectJob?.cancel()
        lastSession = null
        reconnectAttempt = 0
        _state.value = StreamingState.Stopping

        viewModelScope.launch {
            StopStreamingUseCase(port)()
                .onSuccess { _state.value = StreamingState.Idle }
                .onFailure { error ->
                    _state.value = StreamingState.Error(error.message ?: "配信停止に失敗しました。")
                }

        }
    }

    /**
     * コメント取得から応答・音声・モーション送信までを開始する。
     *
     * @param chat コメント取得ポート。
     * @param processCommentAndRespond コメント処理ユースケース。
     * @param persona 応答に適用するペルソナ。
     */
    fun startCommentPipeline(
        chat: YouTubeChatPort,
        processCommentAndRespond: ProcessCommentAndRespondUseCase,
        persona: PersonaPrompt
    ) {
        if (pipelineJob?.isActive == true) return
        _pipelineState.value = PipelineState.Listening
        pipelineJob = viewModelScope.launch {
            val history = ArrayDeque<String>()
            try {
                chat.comments().collect { comment ->
                    _latestComment.value = comment
                    _pipelineState.value = PipelineState.Processing(comment)
                    processCommentAndRespond(comment, persona, history.toList())
                        .onSuccess { result ->
                            _latestResponse.value = result.response
                            history.addLast(result.response.responseText)
                            if (history.size > MAX_HISTORY_SIZE) history.removeFirst()
                            _pipelineState.value = PipelineState.Completed(comment, result.response)
                        }
                        .onFailure { error ->
                            val pipelineError = error as? com.pandapan.stackcast.application.usecase.PipelineException
                            _pipelineState.value = PipelineState.Error(
                                comment,
                                pipelineError?.stage ?: "コメント処理",
                                error.message ?: "処理に失敗しました。"
                            )
                        }
                    _pipelineState.value = PipelineState.Listening
                }
            } catch (error: Throwable) {
                if (error !is kotlinx.coroutines.CancellationException) {
                    _pipelineState.value = PipelineState.Error(
                        null,
                        "コメント取得",
                        error.message ?: "コメント取得に失敗しました。"
                    )
                }
            }
        }
    }

    /** コメント監視と処理を停止する。 */
    fun stopCommentPipeline() {
        pipelineJob?.cancel()
        pipelineJob = null
        _pipelineState.value = PipelineState.Idle
    }

    /** 配信エンジンからのイベントを現在の状態へ反映する。 */
    private fun handleEvent(event: StreamerEvent) {
        when (event) {
            is StreamerEvent.ConnectionStarted -> Unit
            StreamerEvent.ConnectionSuccess -> {
                reconnectAttempt = 0
                _state.value = StreamingState.Streaming()
            }
            is StreamerEvent.ConnectionFailed -> handleConnectionLoss(event.reason)
            StreamerEvent.Disconnected -> handleDisconnected()
            is StreamerEvent.AuthError -> _state.value = StreamingState.Error(event.reason)
            is StreamerEvent.BitrateUpdated -> {
                val current = _state.value
                if (current is StreamingState.Streaming) {
                    _state.value = current.copy(bitrateBps = event.bitrateBps)
                }
            }
        }
    }

    /** ユーザー操作による停止処理中は正常終了として扱い、それ以外（配信中の切断）は再接続を試みる。 */
    private fun handleDisconnected() {
        when (_state.value) {
            is StreamingState.Streaming -> handleConnectionLoss("配信サーバーとの接続が切断されました。")
            is StreamingState.Stopping -> _state.value = StreamingState.Idle
            else -> Unit
        }
    }

    /** 配信中の予期しない切断・接続失敗に対し、指数バックオフで自動再接続を試みる。 */
    private fun handleConnectionLoss(reason: String) {
        val session = lastSession
        val port = rtmpStreamerPort
        if (session == null || port == null || reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            _state.value = StreamingState.Error(reason)
            return
        }

        reconnectAttempt += 1
        val backoffMillis = INITIAL_BACKOFF_MILLIS shl (reconnectAttempt - 1)
        _state.value = StreamingState.Starting

        reconnectJob = viewModelScope.launch {
            delay(backoffMillis)
            StartStreamingUseCase(port)(session).onFailure {
                handleConnectionLoss(it.message ?: reason)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        eventsJob?.cancel()
        reconnectJob?.cancel()
        pipelineJob?.cancel()
        pipelineJob?.cancel()
    }

    private companion object {
        /** 自動再接続の最大試行回数。 */
        const val MAX_RECONNECT_ATTEMPTS = 3

        /** 再接続の初期待機時間 (ms)。試行毎に倍加させる (指数バックオフ)。 */
        const val INITIAL_BACKOFF_MILLIS = 2_000L
        const val MAX_HISTORY_SIZE = 10
    }
}
