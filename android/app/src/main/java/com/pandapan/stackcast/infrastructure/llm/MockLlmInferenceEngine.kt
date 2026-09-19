package com.pandapan.stackcast.infrastructure.llm

import com.pandapan.stackcast.domain.ports.LlmInferencePort
import kotlinx.coroutines.delay

/**
 * 単体テストやモデルファイル未配置時に使用するダミーのLLM推論エンジン。
 *
 * 実際のモデルを読み込まず、与えられたプロンプトに対して即座に擬似的な応答テキストを返す。
 * `MediaPipeLlmInferenceEngine` と同一の [LlmInferencePort] を実装しているため、
 * アプリケーション層・プレゼンテーション層は実装の差し替えを意識せずに利用できる。
 *
 * @property simulatedDelayMillis 疑似的な推論時間として挿入する遅延 (ミリ秒)。
 * @property responseFactory プロンプト文字列から擬似応答テキストを生成する関数。
 *   テストごとに応答内容を差し替えたい場合はこの関数を注入する。
 */
class MockLlmInferenceEngine(
    private val simulatedDelayMillis: Long = DEFAULT_SIMULATED_DELAY_MILLIS,
    private val responseFactory: (String) -> String = ::defaultResponse
) : LlmInferencePort {

    override suspend fun generateResponse(prompt: String): Result<String> {
        if (simulatedDelayMillis > 0) {
            delay(simulatedDelayMillis)
        }
        return Result.success(responseFactory(prompt))
    }

    /** モックエンジンは常にモデル読み込み済み（＝即応答可能）として振る舞う。 */
    override fun isModelLoaded(): Boolean = true

    companion object {
        private const val DEFAULT_SIMULATED_DELAY_MILLIS = 10L

        /** デフォルトの擬似応答テキストを生成する。 */
        private fun defaultResponse(@Suppress("UNUSED_PARAMETER") prompt: String): String {
            return "(モック応答) コメントありがとうございます！"
        }
    }
}
