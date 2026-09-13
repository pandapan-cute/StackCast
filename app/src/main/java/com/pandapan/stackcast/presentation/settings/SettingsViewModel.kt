package com.pandapan.stackcast.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pandapan.stackcast.application.usecase.LoadSettingsUseCase
import com.pandapan.stackcast.application.usecase.SaveSettingsUseCase
import com.pandapan.stackcast.domain.model.AppSettings
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
 *
 * @property loadSettingsUseCase 永続化された設定値を読み込むユースケース。
 * @property saveSettingsUseCase 設定値を永続化するユースケース。
 */
class SettingsViewModel(
    private val loadSettingsUseCase: LoadSettingsUseCase,
    private val saveSettingsUseCase: SaveSettingsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppSettings())

    /** 設定画面のフォームへ反映するための現在の設定値。 */
    val uiState: StateFlow<AppSettings> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            loadSettingsUseCase().collect { settings ->
                _uiState.value = settings
            }
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
}
