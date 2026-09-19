package com.pandapan.stackcast.domain.ports

import com.pandapan.stackcast.domain.model.LlmModelConfig
import com.pandapan.stackcast.domain.model.ModelDownloadState
import kotlinx.coroutines.flow.Flow

/**
 * LLMモデルファイルのダウンロードを担うポート (インターフェース)。
 *
 * ドメイン層・アプリケーション層はこのポートにのみ依存し、
 * 実際のHTTP通信・ファイルI/O等の実装詳細はインフラ層のアダプタークラスに委ねる (DIP)。
 */
interface ModelDownloaderPort {

    /**
     * 指定された設定に基づきモデルファイルをダウンロードし、進捗状態を [Flow] として発行する。
     *
     * @param config ダウンロード元URL・アクセストークンを含む設定。
     * @param destinationPath ダウンロードしたファイルを保存する絶対パス。
     * @return ダウンロードの進捗・完了・失敗を表す [ModelDownloadState] を発行し続ける [Flow]。
     */
    fun download(config: LlmModelConfig, destinationPath: String): Flow<ModelDownloadState>
}
