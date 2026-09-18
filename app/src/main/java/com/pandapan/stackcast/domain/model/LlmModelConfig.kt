package com.pandapan.stackcast.domain.model

/**
 * オンデバイスLLMモデルファイルのダウンロード元設定を表す値オブジェクト。
 *
 * HuggingFace 等、認証が必要な配布元からモデルファイル (.task / .bin) を取得するための
 * URL とアクセストークンを保持する。ドメイン層に属するため Android SDK には依存しない。
 *
 * @property modelUrl ダウンロード対象のモデルファイルURL。
 * @property huggingFaceAccessToken HuggingFace 等のアクセストークン。
 *   `Authorization: Bearer <token>` ヘッダとして送信される。不要な配布元の場合は空文字でよい。
 */
data class LlmModelConfig(
    val modelUrl: String = "",
    val huggingFaceAccessToken: String = ""
)
