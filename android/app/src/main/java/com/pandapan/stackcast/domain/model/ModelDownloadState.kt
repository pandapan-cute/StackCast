package com.pandapan.stackcast.domain.model

/**
 * LLMモデルファイルのダウンロード進捗状態を表すドメインモデル。
 *
 * ダウンロード開始前から完了・失敗までの一連の状態遷移を表現する。
 * ドメイン層に属するため Android SDK には依存しない純粋 Kotlin の sealed class として定義する。
 */
sealed class ModelDownloadState {

    /** ダウンロード未実施の初期状態。 */
    data object Idle : ModelDownloadState()

    /**
     * ダウンロード実行中の状態。
     *
     * @property downloadedBytes これまでにダウンロード済みのバイト数。
     * @property totalBytes ダウンロード対象ファイルの総バイト数。サーバーが返却しない場合は -1。
     */
    data class InProgress(
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : ModelDownloadState() {

        /** 進捗率 (0〜100)。総バイト数が不明な場合は 0 を返す。 */
        val progressPercent: Int
            get() = if (totalBytes > 0) {
                ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            } else {
                0
            }
    }

    /**
     * ダウンロードが正常に完了した状態。
     *
     * @property filePath 保存先モデルファイルの絶対パス。
     */
    data class Completed(val filePath: String) : ModelDownloadState()

    /**
     * ダウンロードに失敗した状態。
     *
     * @property message 失敗理由を表すメッセージ (ユーザー表示可能な文言)。
     */
    data class Failed(val message: String) : ModelDownloadState()
}
