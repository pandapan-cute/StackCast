package com.pandapan.stackcast.domain.ports

import com.pandapan.stackcast.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow

/**
 * アプリ設定 (配信 / AI / BLE) の永続化を担うリポジトリのポート (インターフェース)。
 *
 * ドメイン層・アプリケーション層はこのポートにのみ依存し、
 * 実際の永続化手段 (Jetpack DataStore 等) はインフラ層の実装クラスに委ねる (DIP)。
 */
interface SettingsRepositoryPort {

    /**
     * 保存されているアプリ設定の変更を継続的に監視する。
     *
     * @return 現在の [AppSettings] を発行し続ける [Flow]。未設定の場合は初期値を発行する。
     */
    fun observeSettings(): Flow<AppSettings>

    /**
     * アプリ設定を永続化する。
     *
     * @param settings 保存対象の設定値。
     */
    suspend fun saveSettings(settings: AppSettings)
}
