package com.pandapan.stackcast.infrastructure.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.pandapan.stackcast.domain.ports.LlmInferencePort
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * ネイティブ推論を1件ずつ実行するための排他制御。
     *
     * MediaPipeのセッションは並列利用せず、コメントごとに生成・破棄する。
     */
    private val inferenceMutex = Mutex()

    override suspend fun generateResponse(prompt: String): Result<String> {
        return inferenceMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val inference = ensureInferenceLoaded()
                        ?: throw IllegalStateException(
                            "LLMモデルの読み込みに失敗しました。モデルファイルの配置を確認してください: $modelPath"
                        )
                    // セッションを使い回すと前回のクエリがネイティブ側へ蓄積するため、
                    // コメント1件につき新しいセッションを作り、処理完了後に必ず解放する。
                    val session = createSession(inference)
                    try {
                        session.addQueryChunk(prompt)
                        session.generateResponse()
                    } finally {
                        session.close()
                    }
                }
            }
        }
    }

    override fun isModelLoaded(): Boolean = llmInference != null

    /**
     * 推論モデルが未読み込みであれば読み込みを行い、モデル本体を返す。
     * 読み込みに失敗した場合は null を返し、呼び出し元でエラーとして扱う。
     */
    private fun ensureInferenceLoaded(): LlmInference? {
        llmInference?.let { return it }

        return synchronized(this) {
            llmInference ?: runCatching {
                check(File(modelPath).exists()) { "モデルファイルが見つかりません: $modelPath" }

                // LlmInferenceOptions ではモデルパスや最大トークン数など、モデル自体の設定を行う。
                val inferenceOptions = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(maxTokens)
                    .setMaxTopK(topK)
                    .build()

                val inference = LlmInference.createFromOptions(context, inferenceOptions)
                llmInference = inference
                inference
            }.onFailure {
                // モデル読み込みに失敗した場合、中途半端に確保されたリソースを解放しておく。
                llmInference?.close()
                llmInference = null
            }.getOrNull()
        }
    }

    /**
     * 1回のコメント処理専用の推論セッションを生成する。
     *
     * @param inference 再利用するモデル本体。
     * @return 新しく生成されたセッション。
     */
    private fun createSession(inference: LlmInference): LlmInferenceSession {
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(topK)
            .setTemperature(temperature)
            .setRandomSeed(randomSeed)
            .build()
        return LlmInferenceSession.createFromOptions(inference, sessionOptions)
    }

    /**
     * ネイティブリソースを解放する。`StreamingForegroundService` 終了時など、
     * このエンジンが不要になったタイミングで必ず呼び出すこと。
     */
    fun close() {
        synchronized(this) {
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
