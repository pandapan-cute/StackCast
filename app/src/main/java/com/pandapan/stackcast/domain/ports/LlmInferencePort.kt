package com.pandapan.stackcast.domain.ports

/**
 * LLM (大規模言語モデル) 推論を担うポート (インターフェース)。
 *
 * ドメイン層・アプリケーション層はこのポートにのみ依存し、
 * オンデバイス推論 (MediaPipe GenAI) やクラウドAPI等、具体的な推論手段の実装詳細は
 * インフラ層のアダプタークラスに委ねる (DIP)。
 */
interface LlmInferencePort {

    /**
     * 与えられたプロンプトからAI応答テキストを生成する。
     *
     * @param prompt LLMへ入力する単一テキストプロンプト。
     * @return 成功時は生成されたテキストを含む [Result.success]、
     *   モデル未読み込みや推論エラー時は [Result.failure] を返す。呼び出し元は例外を送出されないため
     *   クラッシュせずにエラーハンドリングできる。
     */
    suspend fun generateResponse(prompt: String): Result<String>

    /**
     * 推論モデルが読み込み済みで、即座に推論可能な状態かどうかを返す。
     *
     * @return モデルが読み込まれ推論可能であれば true。
     */
    fun isModelLoaded(): Boolean
}
