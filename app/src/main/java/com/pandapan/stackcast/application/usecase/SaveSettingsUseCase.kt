package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.domain.model.AppSettings
import com.pandapan.stackcast.domain.ports.SettingsRepositoryPort

/**
 * アプリ設定を永続化するユースケース。
 *
 * [SettingsRepositoryPort] を介して入力された設定値を DataStore 等へ保存する。
 *
 * @property settingsRepositoryPort 設定値の永続化を担うポート。
 */
class SaveSettingsUseCase(
    private val settingsRepositoryPort: SettingsRepositoryPort
) {
    /**
     * アプリ設定を保存する。
     *
     * @param settings 保存対象の設定値。
     */
    suspend operator fun invoke(settings: AppSettings) {
        settingsRepositoryPort.saveSettings(settings)
    }
}
