package com.pandapan.stackcast.presentation.settings

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.pandapan.stackcast.R
import com.pandapan.stackcast.databinding.FragmentSettingsBinding
import com.pandapan.stackcast.domain.model.AppSettings
import com.pandapan.stackcast.domain.model.AvatarExpression
import com.pandapan.stackcast.domain.model.BleConfig
import com.pandapan.stackcast.domain.model.LipOpening
import com.pandapan.stackcast.domain.model.LipSyncMotion
import com.pandapan.stackcast.domain.model.LlmModelConfig
import com.pandapan.stackcast.domain.model.ModelDownloadState
import com.pandapan.stackcast.domain.model.PersonaPrompt
import com.pandapan.stackcast.domain.model.RtmpConfig
import com.pandapan.stackcast.domain.model.ServoAngle
import com.pandapan.stackcast.infrastructure.ble.BleStackChanAdapter
import com.pandapan.stackcast.application.usecase.SendExpressionUseCase
import com.pandapan.stackcast.application.usecase.SendLipSyncUseCase
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 設定画面。
 *
 * 配信 (RTMP)、AI (ペルソナ)、BLE (ｽﾀｯｸﾁｬﾝ接続) の設定をまとめて管理する。
 * [SettingsViewModel] が保持する [AppSettings] を DataStore と双方向に同期し、
 * 入力値はアプリ再起動後も保持される。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null

    /** onCreateView 〜 onDestroyView の間のみ有効な ViewBinding 参照。 */
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModelFactory(requireContext().applicationContext)
    }

    private val bleAdapter by lazy {
        BleStackChanAdapter(requireContext().applicationContext)
    }

    /** テスト送信ボタンを押すたびに切り替える表情のインデックス。 */
    private var testExpressionIndex = 0

    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            bleAdapter.startScan()
            binding.tvBleStatus.setText(R.string.settings_ble_status_scanning)
        } else {
            binding.tvBleStatus.setText(R.string.settings_ble_no_permission)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        observeUiState()
        observeDownloadState()
        observeBleState()
        setupButtons()
    }

    /**
     * [SettingsViewModel.uiState] を監視し、DataStore から読み込まれた設定値をフォームへ反映する。
     */
    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { settings ->
                    bindFormFields(settings)
                }
            }
        }
    }

    /**
     * [SettingsViewModel.downloadState] を監視し、LLMモデルのダウンロード進捗をUIへ反映する。
     */
    private fun observeDownloadState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.downloadState.collect { state ->
                    renderDownloadState(state)
                }
            }
        }
    }

    /** 保存ボタン・戻るボタン・システムバックの各操作を設定する。 */
    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            viewModel.saveSettings(collectFormFields())
            Toast.makeText(
                requireContext(),
                getString(R.string.settings_save_success),
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.btnDownloadModel.setOnClickListener {
            viewModel.downloadLlmModel(collectLlmModelConfig())
        }

        binding.btnBleScan.setOnClickListener {
            requestBlePermissionsAndScan()
        }

        binding.btnBleConnect.setOnClickListener {
            val address = binding.etBleDeviceAddress.text?.toString().orEmpty()
            viewLifecycleOwner.lifecycleScope.launch {
                val result = bleAdapter.connect(address)
                result.exceptionOrNull()?.let { error ->
                    Toast.makeText(requireContext(), error.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnBleSendTest.setOnClickListener {
            // テスト送信ごとに表情をサイクル切り替え (HAPPY -> DOUBT -> ANGRY -> SAD -> SLEEPY -> NEUTRAL)
            val expressions = listOf(
                AvatarExpression.HAPPY,
                AvatarExpression.DOUBT,
                AvatarExpression.ANGRY,
                AvatarExpression.SAD,
                AvatarExpression.SLEEPY,
                AvatarExpression.NEUTRAL
            )
            val targetExpression = expressions[testExpressionIndex % expressions.size]
            testExpressionIndex++

            val testMotion = listOf(
                LipSyncMotion(0L, LipOpening.CLOSED, ServoAngle.fromLipOpening(LipOpening.CLOSED)),
                LipSyncMotion(120L, LipOpening.OPEN, ServoAngle.fromLipOpening(LipOpening.OPEN)),
                LipSyncMotion(240L, LipOpening.CLOSED, ServoAngle.fromLipOpening(LipOpening.CLOSED))
            )
            viewLifecycleOwner.lifecycleScope.launch {
                // 表情の変更と口パクモーションを送信
                val exprResult = SendExpressionUseCase(bleAdapter)(targetExpression)
                val motionResult = SendLipSyncUseCase(bleAdapter)(testMotion)

                if (exprResult.isSuccess && motionResult.isSuccess) {
                    val message = "${getString(R.string.settings_ble_test_success)}: 表情「${targetExpression.displayName}」"
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                } else {
                    val error = exprResult.exceptionOrNull() ?: motionResult.exceptionOrNull()
                    Toast.makeText(
                        requireContext(),
                        error?.message.orEmpty(),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        binding.btnBack.setOnClickListener {
            // 戻るボタン: ダッシュボード画面へ遷移する。
            findNavController().navigateUp()
        }

        // システムバック操作でもダッシュボードへ戻れるようにコールバックを登録する。
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    findNavController().navigateUp()
                }
            }
        )
    }

    /** BLE接続状態とスキャン結果を画面へ反映する。 */
    private fun observeBleState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    bleAdapter.connectionState.collect { state ->
                        when (state) {
                            is com.pandapan.stackcast.domain.model.StackChanConnectionState.Disconnected ->
                                binding.tvBleStatus.setText(R.string.settings_ble_status_disconnected)
                            is com.pandapan.stackcast.domain.model.StackChanConnectionState.Connecting ->
                                binding.tvBleStatus.setText(R.string.settings_ble_status_connecting)
                            is com.pandapan.stackcast.domain.model.StackChanConnectionState.Connected ->
                                binding.tvBleStatus.text = getString(
                                    R.string.settings_ble_status_connected,
                                    state.mtu
                                )
                            is com.pandapan.stackcast.domain.model.StackChanConnectionState.Error ->
                                binding.tvBleStatus.text = getString(
                                    R.string.settings_ble_status_error,
                                    state.message
                                )
                        }
                    }
                }
                launch {
                    bleAdapter.scanResults.collect { results ->
                        binding.tvBleScanResults.text = if (results.isEmpty()) {
                            getString(R.string.settings_ble_scan_idle)
                        } else {
                            results.joinToString("\n") { result ->
                                "${result.name.ifBlank { "名称なし" }}\n${result.address}"
                            }
                        }
                        results.firstOrNull()?.let { result ->
                            binding.etBleDeviceAddress.setTextIfChanged(result.address)
                            binding.etBleDeviceName.setTextIfChanged(result.name)
                        }
                    }
                }
            }
        }
    }

    /** Androidバージョンに応じたBLE権限を要求してスキャンを開始する。 */
    private fun requestBlePermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        blePermissionLauncher.launch(permissions)
    }

    /** ダウンロード進捗状態をプログレスバー・ステータステキストへ反映する。 */
    private fun renderDownloadState(state: ModelDownloadState) {
        with(binding) {
            when (state) {
                is ModelDownloadState.Idle -> {
                    progressDownloadModel.progress = 0
                    tvDownloadStatus.text = getString(R.string.settings_download_status_idle)
                }

                is ModelDownloadState.InProgress -> {
                    progressDownloadModel.progress = state.progressPercent
                    tvDownloadStatus.text = getString(
                        R.string.settings_download_status_in_progress,
                        state.progressPercent,
                        formatBytes(state.downloadedBytes),
                        if (state.totalBytes > 0) formatBytes(state.totalBytes) else "?"
                    )
                }

                is ModelDownloadState.Completed -> {
                    progressDownloadModel.progress = 100
                    tvDownloadStatus.text = getString(
                        R.string.settings_download_status_completed,
                        state.filePath
                    )
                }

                is ModelDownloadState.Failed -> {
                    tvDownloadStatus.text = getString(
                        R.string.settings_download_status_failed,
                        state.message
                    )
                }
            }
        }
    }

    /** バイト数を "12.3MB" のような読みやすい表記へ変換する。 */
    private fun formatBytes(bytes: Long): String {
        val megaBytes = bytes / (1024.0 * 1024.0)
        return String.format(Locale.getDefault(), "%.1fMB", megaBytes)
    }

    /** [AppSettings] の値を各フォーム入力欄へ反映する。 */
    private fun bindFormFields(settings: AppSettings) {
        with(binding) {
            etRtmpUrl.setTextIfChanged(settings.rtmpConfig.rtmpUrl)
            etStreamKey.setTextIfChanged(settings.rtmpConfig.streamKey)
            etResolution.setTextIfChanged(settings.rtmpConfig.resolution)
            etFrameRate.setTextIfChanged(settings.rtmpConfig.frameRate.toString())
            etBitrate.setTextIfChanged(settings.rtmpConfig.bitrateKbps.toString())

            etSystemPrompt.setTextIfChanged(settings.personaPrompt.systemPrompt)
            etCharacterSetting.setTextIfChanged(settings.personaPrompt.characterSetting)
            etEndingRule.setTextIfChanged(settings.personaPrompt.endingRule)
            etNgWords.setTextIfChanged(settings.personaPrompt.ngWords.joinToString(", "))

            etBleDeviceAddress.setTextIfChanged(settings.bleConfig.deviceAddress)
            etBleDeviceName.setTextIfChanged(settings.bleConfig.deviceName)
            switchBleAutoReconnect.isChecked = settings.bleConfig.autoReconnect

            etLlmModelUrl.setTextIfChanged(settings.llmModelConfig.modelUrl)
            etLlmHfToken.setTextIfChanged(settings.llmModelConfig.huggingFaceAccessToken)
            switchYoutubeMock.isChecked = settings.youtubeMockMode
            etYoutubeContinuation.setTextIfChanged(settings.youtubeContinuation)
        }
    }

    /**
     * 編集中のカーソル位置がリセットされないよう、現在値と異なる場合のみ setText する拡張関数。
     */
    private fun TextInputEditText.setTextIfChanged(value: String) {
        if (text?.toString() != value) {
            setText(value)
        }
    }

    /** 現在のフォーム入力値から [AppSettings] を組み立てる。 */
    private fun collectFormFields(): AppSettings {
        val rtmpConfig = RtmpConfig(
            rtmpUrl = binding.etRtmpUrl.text?.toString().orEmpty(),
            streamKey = binding.etStreamKey.text?.toString().orEmpty(),
            resolution = binding.etResolution.text?.toString()
                .takeUnless { it.isNullOrBlank() } ?: RtmpConfig.DEFAULT_RESOLUTION,
            frameRate = binding.etFrameRate.text?.toString()?.toIntOrNull()
                ?: RtmpConfig.DEFAULT_FRAME_RATE,
            bitrateKbps = binding.etBitrate.text?.toString()?.toIntOrNull()
                ?: RtmpConfig.DEFAULT_BITRATE_KBPS
        )

        val ngWords = binding.etNgWords.text?.toString().orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val personaPrompt = PersonaPrompt(
            systemPrompt = binding.etSystemPrompt.text?.toString().orEmpty(),
            characterSetting = binding.etCharacterSetting.text?.toString().orEmpty(),
            endingRule = binding.etEndingRule.text?.toString().orEmpty(),
            ngWords = ngWords
        )

        val bleConfig = BleConfig(
            deviceAddress = binding.etBleDeviceAddress.text?.toString().orEmpty(),
            deviceName = binding.etBleDeviceName.text?.toString().orEmpty(),
            autoReconnect = binding.switchBleAutoReconnect.isChecked
        )

        return AppSettings(
            rtmpConfig = rtmpConfig,
            personaPrompt = personaPrompt,
            bleConfig = bleConfig,
            llmModelConfig = collectLlmModelConfig(),
            youtubeMockMode = binding.switchYoutubeMock.isChecked,
            youtubeContinuation = binding.etYoutubeContinuation.text?.toString().orEmpty()
        )
    }

    /** LLMモデルダウンロード関連のフォーム入力値から [LlmModelConfig] を組み立てる。 */
    private fun collectLlmModelConfig(): LlmModelConfig {
        return LlmModelConfig(
            modelUrl = binding.etLlmModelUrl.text?.toString().orEmpty(),
            huggingFaceAccessToken = binding.etLlmHfToken.text?.toString().orEmpty()
        )
    }

    override fun onDestroyView() {
        bleAdapter.stopScan()
        bleAdapter.close()
        super.onDestroyView()
        // メモリリーク防止のため、Fragment の View 破棄時に binding 参照を解放する。
        _binding = null
    }
}
