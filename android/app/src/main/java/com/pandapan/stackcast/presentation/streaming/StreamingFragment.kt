package com.pandapan.stackcast.presentation.streaming

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.pandapan.stackcast.R
import com.pandapan.stackcast.databinding.FragmentStreamingBinding
import com.pandapan.stackcast.domain.model.StreamSession
import com.pandapan.stackcast.domain.model.StreamingState
import com.pandapan.stackcast.domain.model.PipelineState
import com.pandapan.stackcast.domain.ports.StackChanPort
import com.pandapan.stackcast.application.usecase.GenerateAiResponseUseCase
import com.pandapan.stackcast.application.usecase.ProcessCommentAndRespondUseCase
import com.pandapan.stackcast.infrastructure.DataStoreSettingsRepository
import com.pandapan.stackcast.infrastructure.ble.FakeStackChanAdapter
import com.pandapan.stackcast.infrastructure.llm.MockLlmInferenceEngine
import com.pandapan.stackcast.infrastructure.llm.MediaPipeLlmInferenceEngine
import com.pandapan.stackcast.infrastructure.llm.llmModelFile
import com.pandapan.stackcast.infrastructure.logging.AndroidPipelineLogger
import com.pandapan.stackcast.infrastructure.rtmp.RecordingRtmpAudioSink
import com.pandapan.stackcast.infrastructure.service.StreamingForegroundService
import com.pandapan.stackcast.infrastructure.settingsDataStore
import com.pandapan.stackcast.infrastructure.tts.MockTtsEngine
import com.pandapan.stackcast.infrastructure.tts.AndroidTtsEngine
import com.pandapan.stackcast.infrastructure.youtube.FakeYouTubeChatScraperEngine
import com.pandapan.stackcast.infrastructure.youtube.YouTubeChatScraperEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 配信中画面。
 *
 * カメラプレビュー ([com.pedro.library.view.OpenGlView]) の表示、配信状態のオーバーレイ表示、
 * および [StreamingForegroundService] とのバインドを担当する。
 * カメラ・マイクの権限が許可されている場合、画面表示と同時にプレビューだけを開始し、
 * 「配信開始」ボタンでRTMP配信を開始する。「配信終了」ボタン押下、または画面破棄時にサービスとの結びつきを解除する
 * （配信自体はフォアグラウンドサービスにより継続される）。
 */
class StreamingFragment : Fragment() {

    private var _binding: FragmentStreamingBinding? = null

    /** onCreateView 〜 onDestroyView の間のみ有効な ViewBinding 参照。 */
    private val binding get() = _binding!!

    private val viewModel: StreamingViewModel by viewModels()

    private var streamingService: StreamingForegroundService? = null
    private var isServiceBound = false
    private var serviceBindingContext: Context? = null
    private var liveLlmEngine: MediaPipeLlmInferenceEngine? = null
    private var liveTtsEngine: AndroidTtsEngine? = null
    private var pendingSession: StreamSession? = null
    private var pendingSettings: com.pandapan.stackcast.domain.model.AppSettings? = null

    /** カメラ・マイクの実行時権限をまとめて要求するためのランチャー。 */
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startServiceAndBind()
        } else {
            Toast.makeText(requireContext(), R.string.streaming_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "StreamingForegroundService 接続成功")
            val binder = service as? StreamingForegroundService.LocalBinder ?: return
            val boundService = binder.getService()
            streamingService = boundService
            viewModel.attachStreamer(boundService.rtmpStreamerPort)
            _binding?.cameraPreviewView?.let { boundService.encoder.attachPreviewView(it) }
            _binding?.layoutCommentOverlay?.let { boundService.encoder.attachSubtitleView(it) }
            viewModel.startPreview()
            loadStreamingSettings()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "StreamingForegroundService 切断")
            streamingService = null
            isServiceBound = false
            serviceBindingContext = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStreamingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeStreamingState()

        binding.btnStartStream.setOnClickListener {
            val session = pendingSession
            val settings = pendingSettings
            if (session == null || settings == null) {
                Toast.makeText(requireContext(), "配信設定を読み込んでいます。", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.startStreaming(session)
                viewLifecycleOwner.lifecycleScope.launch {
                    startCommentPipeline(settings)
                }
            }
        }

        binding.btnStopStream.setOnClickListener {
            viewModel.stopStreaming()
            // 配信結果サマリー画面へ遷移する。実際の配信終了はサービス側で非同期に完了する。
            findNavController().navigate(R.id.action_streaming_to_summary)
        }

        requestCameraAndMicPermissions()
    }

    override fun onStop() {
        super.onStop()
        // 画面がバックグラウンドへ回っても配信自体はサービス側で継続するため、
        // プレビュー描画先のみ切り離してバックグラウンドレンダリングへ戻す。
        streamingService?.encoder?.detachPreviewView()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Log.i(TAG, "onDestroyView: subtitle detach / unbind start")
        streamingService?.encoder?.detachSubtitleView()
        val boundContext = serviceBindingContext
        if (isServiceBound && boundContext != null) {
            runCatching {
                boundContext.unbindService(serviceConnection)
            }.onFailure {
                // 二重解除やサービス側の切断後でも画面破棄時に落ちないようにする。
                android.util.Log.w("StreamingFragment", "サービス解除に失敗: ${it.message}")
            }
        }
        isServiceBound = false
        serviceBindingContext = null
        liveLlmEngine?.close()
        liveTtsEngine?.close()
        // メモリリーク防止のため、Fragment の View 破棄時に binding 参照を解放する。
        _binding = null
    }

    /** カメラ・マイクの実行時権限を要求する。既に許可済みであれば即座にサービスを起動する。 */
    private fun requestCameraAndMicPermissions() {
        val permissions = arrayOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            startServiceAndBind()
        } else {
            requestPermissionsLauncher.launch(permissions)
        }
    }

    /** [StreamingForegroundService] を起動し、バインドする。 */
    private fun startServiceAndBind() {
        val context = requireContext().applicationContext
        if (isServiceBound) return
        Log.i(TAG, "StreamingForegroundService 起動/バインド開始")
        StreamingForegroundService.start(context)
        val bound = context.bindService(
            Intent(context, StreamingForegroundService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        Log.i(TAG, "bindService 結果=$bound")
        isServiceBound = bound
        serviceBindingContext = if (bound) context else null
    }

    /** 設定画面で保存された配信設定を読み込み、明示的な開始操作に備える。 */
    private fun loadStreamingSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            val repository = DataStoreSettingsRepository(requireContext().applicationContext.settingsDataStore)
            val settings = repository.observeSettings().first()
            if (settings.rtmpConfig.isValid()) {
                pendingSession = StreamSession.fromRtmpConfig(settings.rtmpConfig)
                pendingSettings = settings
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.streaming_status_error, "配信設定が未入力です。設定画面を確認してください。"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** 設定されたLive/Mockモードでコメント処理パイプラインを開始する。 */
    private suspend fun startCommentPipeline(settings: com.pandapan.stackcast.domain.model.AppSettings) {
        Log.i(TAG, "コメントパイプライン開始: mock=${settings.youtubeMockMode}, watchUrl=${settings.youtubeWatchUrl.isNotBlank()}")
        val stackChan: StackChanPort = if (settings.youtubeMockMode) {
            FakeStackChanAdapter().also { it.connect("mock") }
        } else {
            com.pandapan.stackcast.infrastructure.ble.BleStackChanAdapter(
                requireContext(),
                autoReconnect = settings.bleConfig.autoReconnect
            ).also {
                it.connect(settings.bleConfig.deviceAddress)
            }
        }
        val chat = if (settings.youtubeMockMode) {
            FakeYouTubeChatScraperEngine()
        } else {
            YouTubeChatScraperEngine(
                initialContinuation = settings.youtubeContinuation,
                watchUrl = settings.youtubeWatchUrl
            )
        }
        val llm = if (settings.youtubeMockMode) {
            MockLlmInferenceEngine()
        } else {
            MediaPipeLlmInferenceEngine(requireContext(), requireContext().llmModelFile().absolutePath)
                .also { liveLlmEngine = it }
        }
        val tts = if (settings.youtubeMockMode) {
            MockTtsEngine()
        } else {
            AndroidTtsEngine(requireContext()).also { liveTtsEngine = it }
        }
        val process = ProcessCommentAndRespondUseCase(
            generateAiResponse = GenerateAiResponseUseCase(llm, if (settings.youtubeMockMode) "mock" else "local"),
            ttsPort = tts,
            audioSink = streamingService?.encoder ?: RecordingRtmpAudioSink(),
            stackChanPort = stackChan,
            logger = AndroidPipelineLogger()
        )
        Log.i(TAG, "コメントパイプラインの依存関係を準備完了: chat=${chat.javaClass.simpleName}, llm=${llm.javaClass.simpleName}, tts=${tts.javaClass.simpleName}")
        viewModel.startCommentPipeline(chat, process, settings.personaPrompt)
    }

    /** [StreamingViewModel.state] を監視し、画面下部のステータス表示へ反映する。 */
    private fun observeStreamingState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    _binding?.tvStreamingStatus?.text = renderState(state)
                    _binding?.btnStartStream?.visibility =
                        if (state is StreamingState.Previewing || state is StreamingState.Error) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                    _binding?.btnStopStream?.visibility =
                        if (state is StreamingState.Streaming || state is StreamingState.Starting) {
                            View.VISIBLE
                        } else {
                            View.GONE
                        }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pipelineState.collect { state ->
                    renderPipelineState(state)
                }
            }
        }
    }

    /** コメント処理パイプラインの進行状態をオーバーレイへ反映する。 */
    private fun renderPipelineState(state: PipelineState) {
        when (state) {
            PipelineState.Idle -> binding.tvPipelineStatus.setText(R.string.streaming_pipeline_idle)
            PipelineState.Listening -> binding.tvPipelineStatus.setText(R.string.streaming_pipeline_listening)
            is PipelineState.Processing -> binding.tvPipelineStatus.text =
                getString(R.string.streaming_pipeline_processing, state.comment.text)
            is PipelineState.Completed -> binding.tvPipelineStatus.setText(R.string.streaming_pipeline_listening)
            is PipelineState.Error -> binding.tvPipelineStatus.text =
                getString(R.string.streaming_pipeline_error, "${state.stage}: ${state.reason}")
        }
        viewModel.latestComment.value?.let {
            binding.tvLatestComment.text = getString(R.string.streaming_latest_comment, "${it.author}: ${it.text}")
        }
        viewModel.latestResponse.value?.let {
            binding.tvLatestResponse.text = getString(R.string.streaming_latest_response, it.responseText)
        }
        renderSubtitleOverlay(state)
    }

    /**
     * 画面下部の字幕テロップ (HUD オーバーレイ) を更新する。
     *
     * 画面上の表示だけでなく、[RtmpEncoderEngine.attachSubtitleView] を介して
     * 配信映像フレーム（YouTube側）へもリアルタイムに焼き込まれる。
     *
     * @param state 現在のコメント処理パイプライン状態。
     */
    private fun renderSubtitleOverlay(state: PipelineState) {
        val currentBinding = _binding ?: return
        when (state) {
            PipelineState.Idle -> {
                currentBinding.tvCommentAuthor.text = ""
                currentBinding.tvCommentText.setText(R.string.streaming_overlay_listening)
                currentBinding.tvReplyStatus.visibility = View.GONE
                currentBinding.tvReplyText.text = ""
            }
            is PipelineState.Processing -> {
                currentBinding.tvCommentAuthor.text = "@${state.comment.author}: "
                currentBinding.tvCommentText.text = state.comment.text
                currentBinding.tvReplyStatus.visibility = View.VISIBLE
                currentBinding.tvReplyStatus.setText(R.string.streaming_overlay_thinking)
                currentBinding.tvReplyText.text = ""
            }
            is PipelineState.Completed -> {
                currentBinding.tvCommentAuthor.text = "@${state.comment.author}: "
                currentBinding.tvCommentText.text = state.comment.text
                currentBinding.tvReplyStatus.visibility = View.GONE
                currentBinding.tvReplyText.text = state.response.responseText
            }
            is PipelineState.Error -> {
                val comment = state.comment ?: viewModel.latestComment.value
                if (comment != null) {
                    currentBinding.tvCommentAuthor.text = "@${comment.author}: "
                    currentBinding.tvCommentText.text = comment.text
                }
                currentBinding.tvReplyStatus.visibility = View.VISIBLE
                currentBinding.tvReplyStatus.text = "⚠️ ${state.stage}失敗"
            }
            PipelineState.Listening -> {
                val latestComment = viewModel.latestComment.value
                val latestResponse = viewModel.latestResponse.value
                currentBinding.tvReplyStatus.visibility = View.GONE
                if (latestComment != null) {
                    currentBinding.tvCommentAuthor.text = "@${latestComment.author}: "
                    currentBinding.tvCommentText.text = latestComment.text
                    currentBinding.tvReplyText.text = latestResponse?.responseText.orEmpty()
                } else {
                    currentBinding.tvCommentAuthor.text = ""
                    currentBinding.tvCommentText.setText(R.string.streaming_overlay_listening)
                    currentBinding.tvReplyText.text = ""
                }
            }
        }
        currentBinding.layoutCommentOverlay.postInvalidate()
    }

    /** [StreamingState] を画面表示用の文字列へ変換する。 */
    private fun renderState(state: StreamingState): String = when (state) {
        StreamingState.Idle -> getString(R.string.streaming_status_idle)
        StreamingState.Previewing -> getString(R.string.streaming_status_preview)
        StreamingState.Starting -> getString(R.string.streaming_status_starting)
        is StreamingState.Streaming -> getString(R.string.streaming_status_streaming, state.bitrateBps / 1000)
        StreamingState.Stopping -> getString(R.string.streaming_status_stopping)
        is StreamingState.Error -> getString(R.string.streaming_status_error, state.reason)
    }

    private companion object {
        const val TAG = "StreamingFragment"
    }
}
