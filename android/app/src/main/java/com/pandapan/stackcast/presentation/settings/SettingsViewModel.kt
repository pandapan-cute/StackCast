package com.pandapan.stackcast.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pandapan.stackcast.application.usecase.DownloadLlmModelUseCase
import com.pandapan.stackcast.application.usecase.LoadSettingsUseCase
import com.pandapan.stackcast.application.usecase.SaveSettingsUseCase
import com.pandapan.stackcast.domain.model.AppSettings
import com.pandapan.stackcast.domain.model.LlmModelConfig
import com.pandapan.stackcast.domain.model.ModelDownloadState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 設定画面 ([SettingsFragment]) の状態を保持する ViewModel。
 *
 * DataStore に永続化された [AppSettings] を [LoadSettingsUseCase] 経由で監視し、
 * [uiState] として Fragment へ公開する。フォーム入力値の保存は [saveSettings] を通じて
 * [SaveSettingsUseCase] に委譲し、具体的な永続化手段には一切依存しない。
 * また、[downloadLlmModel] を通じてLLMモデルファイルのダウンロードを開始し、
 * その進捗状態を [downloadState] として公開する。
 *
 * @property loadSettingsUseCase 永続化された設定値を読み込むユースケース。
 * @property saveSettingsUseCase 設定値を永続化するユースケース。
 * @property downloadLlmModelUseCase LLMモデルファイルをダウンロードするユースケース。
 * @property modelDestinationPath ダウンロードしたモデルファイルの保存先絶対パス。
 */
class SettingsViewModel(
    private val loadSettingsUseCase: LoadSettingsUseCase,
    private val saveSettingsUseCase: SaveSettingsUseCase,
    private val downloadLlmModelUseCase: DownloadLlmModelUseCase,
    private val modelDestinationPath: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppSettings())

    /** 設定画面のフォームへ反映するための現在の設定値。 */
    val uiState: StateFlow<AppSettings> = _uiState.asStateFlow()

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)

    /** LLMモデルファイルのダウンロード進捗状態。 */
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    init {
        restoreDownloadState()
        viewModelScope.launch {
            loadSettingsUseCase().collect { settings ->
                _uiState.value = settings
            }
        }
    }

    /** 保存済みのモデルファイルを確認し、再起動後もダウンロード完了状態を復元する。 */
    private fun restoreDownloadState() {
        val modelFile = File(modelDestinationPath)
        if (modelFile.isFile && modelFile.length() > 0L) {
            _downloadState.value = ModelDownloadState.Completed(modelFile.absolutePath)
        }
    }

    /**
     * フォームで入力された設定値を永続化する。
     *
     * @param settings 保存対象の設定値。
     */
    fun saveSettings(settings: AppSettings) {
        viewModelScope.launch {
            saveSettingsUseCase(settings)
        }
    }

    /**
     * 指定されたLLMモデル設定に基づき、モデルファイルのダウンロードを開始する。
     *
     * ダウンロードの進捗は [downloadState] を通じて随時更新される。
     *
     * @param config ダウンロード元URL・アクセストークンを含む設定。
     */
    fun downloadLlmModel(config: LlmModelConfig) {
        viewModelScope.launch {
            downloadLlmModelUseCase(config, modelDestinationPath).collect { state ->
                _downloadState.value = state
            }
        }
    }
}
