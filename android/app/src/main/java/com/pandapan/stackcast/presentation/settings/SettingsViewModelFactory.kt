package com.pandapan.stackcast.presentation.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pandapan.stackcast.application.usecase.DownloadLlmModelUseCase
import com.pandapan.stackcast.application.usecase.LoadSettingsUseCase
import com.pandapan.stackcast.application.usecase.SaveSettingsUseCase
import com.pandapan.stackcast.infrastructure.DataStoreSettingsRepository
import com.pandapan.stackcast.infrastructure.llm.HttpLlmModelDownloader
import com.pandapan.stackcast.infrastructure.llm.llmModelFile
import com.pandapan.stackcast.infrastructure.settingsDataStore

/**
 * [SettingsViewModel] を組み立てるための [ViewModelProvider.Factory]。
 *
 * DI フレームワークを導入していない Phase 2 時点では、
 * このファクトリが Infrastructure 層 ([DataStoreSettingsRepository]) と
 * Application 層 (ユースケース群) の配線を担う。
 *
 * @property appContext DataStore の生成、およびモデルファイル保存先パスの解決に使用する
 *   アプリケーションコンテキスト。
 */
class SettingsViewModelFactory(
    private val appContext: Context
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val repository = DataStoreSettingsRepository(appContext.settingsDataStore)
        val loadSettingsUseCase = LoadSettingsUseCase(repository)
        val saveSettingsUseCase = SaveSettingsUseCase(repository)
        val downloadLlmModelUseCase = DownloadLlmModelUseCase(HttpLlmModelDownloader())
        val modelDestinationPath = appContext.llmModelFile().absolutePath
        return SettingsViewModel(
            loadSettingsUseCase,
            saveSettingsUseCase,
            downloadLlmModelUseCase,
            modelDestinationPath
        ) as T
    }
}

