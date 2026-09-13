package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.domain.model.AppSettings
import com.pandapan.stackcast.domain.ports.SettingsRepositoryPort
import kotlinx.coroutines.flow.Flow

/**
 * 永続化されているアプリ設定を読み込むユースケース。
 *
 * [SettingsRepositoryPort] を介して DataStore 等の永続化層から設定値を取得する。
 * プレゼンテーション層 (ViewModel) はこのユースケースを通じてのみ設定を取得し、
 * 具体的な永続化実装には依存しない。
 *
 * @property settingsRepositoryPort 設定値の永続化を担うポート。
 */
class LoadSettingsUseCase(
    private val settingsRepositoryPort: SettingsRepositoryPort
) {
    /**
     * 保存済みのアプリ設定を監視する [Flow] を取得する。
     *
     * @return 現在の [AppSettings] を発行し続ける [Flow]。
     */
    operator fun invoke(): Flow<AppSettings> = settingsRepositoryPort.observeSettings()
}
