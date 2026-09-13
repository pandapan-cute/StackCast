package com.pandapan.stackcast.presentation.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.pandapan.stackcast.R
import com.pandapan.stackcast.databinding.FragmentSettingsBinding
import com.pandapan.stackcast.domain.model.AppSettings
import com.pandapan.stackcast.domain.model.BleConfig
import com.pandapan.stackcast.domain.model.PersonaPrompt
import com.pandapan.stackcast.domain.model.RtmpConfig
import com.google.android.material.textfield.TextInputEditText
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
            bleConfig = bleConfig
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // メモリリーク防止のため、Fragment の View 破棄時に binding 参照を解放する。
        _binding = null
    }
}
