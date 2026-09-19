package com.pandapan.stackcast.infrastructure.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.pandapan.stackcast.domain.ports.LlmInferencePort
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MediaPipe GenAI LLM Inference API (`com.google.mediapipe:tasks-genai`) を用いた
 * オンデバイスLLM推論エンジン。[LlmInferencePort] の実機実装。
 *
 * `.task` / `.bin` 形式のローカルモデルファイルを遅延読み込みし、[LlmInference] で
 * モデル自体をロードしたうえで、応答の多様性 (topK / temperature / randomSeed) を
 * 制御可能な [LlmInferenceSession] を介してテキスト応答を生成する。
 * モデルファイルが端末に配置されていない場合や推論エンジンの初期化に失敗した場合でも
 * アプリをクラッシュさせず、[Result.failure] を返す。
 *
 * @property context モデル読み込みに使用するアプリケーションコンテキスト。
 * @property modelPath 端末上に配置されたローカルモデルファイル (.task / .bin) の絶対パス。
 * @property maxTokens モデルが扱える最大トークン数 (入力+出力)。
 * @property topK 各生成ステップで考慮する候補トークン数。
 * @property temperature 応答生成のランダム性 (値が大きいほど多様な応答になる)。
 * @property randomSeed 応答生成に用いる乱数シード。
 */
class MediaPipeLlmInferenceEngine(
    private val context: Context,
    private val modelPath: String,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val topK: Int = DEFAULT_TOP_K,
    private val temperature: Float = DEFAULT_TEMPERATURE,
    private val randomSeed: Int = DEFAULT_RANDOM_SEED
) : LlmInferencePort {

    /** モデル本体を保持する [LlmInference] インスタンス。読み込み完了後に非nullとなる。 */
    @Volatile
    private var llmInference: LlmInference? = null

    /** 生成パラメータ (topK / temperature / randomSeed) を保持する推論セッション。 */
    @Volatile
    private var llmInferenceSession: LlmInferenceSession? = null

    override suspend fun generateResponse(prompt: String): Result<String> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val session = ensureSessionLoaded()
                    ?: throw IllegalStateException(
                        "LLMモデルの読み込みに失敗しました。モデルファイルの配置を確認してください: $modelPath"
                    )
                // LlmInferenceSession はクエリ文字列を追加してから応答を生成する逐次APIのため、
                // プロンプト全体を1つのクエリチャンクとして投入する。
                // ブロッキング呼び出しのため IO ディスパッチャ上で実行する。
                session.addQueryChunk(prompt)
                session.generateResponse()
            }
        }
    }

    override fun isModelLoaded(): Boolean = llmInference != null && llmInferenceSession != null

    /**
     * モデルおよび推論セッションが未読み込みであれば読み込みを行い、
     * 読み込み済みの [LlmInferenceSession] インスタンスを返す。
     * 読み込みに失敗した場合は null を返し、呼び出し元でエラーとして扱う。
     */
    private fun ensureSessionLoaded(): LlmInferenceSession? {
        llmInferenceSession?.let { return it }

        return synchronized(this) {
            llmInferenceSession ?: runCatching {
                check(File(modelPath).exists()) { "モデルファイルが見つかりません: $modelPath" }

                // LlmInferenceOptions ではモデルパスや最大トークン数など、モデル自体の設定を行う。
                val inferenceOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(maxTokens)
                    .setMaxTopK(topK)
                    .build()

                val inference = LlmInference.createFromOptions(context, inferenceOptions)
                llmInference = inference

                // LlmInferenceSessionOptions では topK / temperature / randomSeed など
                // 応答生成ごとのパラメータを設定する。
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(topK)
                    .setTemperature(temperature)
                    .setRandomSeed(randomSeed)
                    .build()

                LlmInferenceSession.createFromOptions(inference, sessionOptions)
            }.onSuccess { session ->
                llmInferenceSession = session
            }.onFailure {
                // モデル読み込みに失敗した場合、中途半端に確保されたリソースを解放しておく。
                llmInference?.close()
                llmInference = null
            }.getOrNull()
        }
    }

    /**
     * ネイティブリソースを解放する。`StreamingForegroundService` 終了時など、
     * このエンジンが不要になったタイミングで必ず呼び出すこと。
     */
    fun close() {
        synchronized(this) {
            llmInferenceSession?.close()
            llmInferenceSession = null
            llmInference?.close()
            llmInference = null
        }
    }

    private companion object {
        const val DEFAULT_MAX_TOKENS = 512
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_TEMPERATURE = 0.8f
        const val DEFAULT_RANDOM_SEED = 0
    }
}
