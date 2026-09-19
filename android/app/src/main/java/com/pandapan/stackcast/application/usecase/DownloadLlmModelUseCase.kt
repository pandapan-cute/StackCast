package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.domain.model.LlmModelConfig
import com.pandapan.stackcast.domain.model.ModelDownloadState
import com.pandapan.stackcast.domain.ports.ModelDownloaderPort
import kotlinx.coroutines.flow.Flow

/**
 * LLMモデルファイルをダウンロードするユースケース。
 *
 * [ModelDownloaderPort] を呼び出すだけの薄いオーケストレーション層だが、
 * プレゼンテーション層がインフラ層の実装 (HTTP通信等) を直接知らずに済むようにするための境界となる。
 *
 * @property modelDownloaderPort モデルファイルのダウンロードを実行するポート。
 */
class DownloadLlmModelUseCase(
    private val modelDownloaderPort: ModelDownloaderPort
) {

    /**
     * モデルファイルのダウンロードを開始する。
     *
     * @param config ダウンロード元URL・アクセストークンを含む設定。
     * @param destinationPath ダウンロードしたファイルを保存する絶対パス。
     * @return ダウンロードの進捗・完了・失敗を表す [ModelDownloadState] を発行し続ける [Flow]。
     */
    operator fun invoke(config: LlmModelConfig, destinationPath: String): Flow<ModelDownloadState> {
        return modelDownloaderPort.download(config, destinationPath)
    }
}
