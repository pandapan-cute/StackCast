package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.domain.model.AiResponse
import com.pandapan.stackcast.domain.model.LiveComment
import com.pandapan.stackcast.domain.model.PersonaPrompt
import com.pandapan.stackcast.domain.ports.LlmInferencePort
import com.pandapan.stackcast.domain.services.PersonaPromptBuilder

/**
 * ライブコメントに対するAI応答を生成するユースケース。
 *
 * [PersonaPromptBuilder] でペルソナ設定を反映したプロンプトを構築し、
 * [LlmInferencePort] で実際の推論を実行、生成されたテキストへ語尾ルールを適用して
 * [AiResponse] として返却する一連の処理をオーケストレーションする。
 *
 * @property llmInferencePort LLM推論を実行するポート (MediaPipe実装 / モック実装のいずれでも可)。
 * @property modelName 応答生成に使用したモデルの名称 (例: "gemma-2b-it-cpu-int4", "mock")。
 * @property personaPromptBuilder プロンプト構築・語尾ルール適用を行うドメインサービス。
 */
class GenerateAiResponseUseCase(
    private val llmInferencePort: LlmInferencePort,
    private val modelName: String,
    private val personaPromptBuilder: PersonaPromptBuilder = PersonaPromptBuilder()
) {

    /**
     * ライブコメントに対するAI応答を生成する。
     *
     * @param comment 応答対象のライブコメント。
     * @param persona 適用するキャラクターのペルソナ設定。
     * @param conversationHistory 直前までの会話履歴 (古い順)。
     * @return 成功時は [AiResponse] を含む [Result.success]、
     *   LLM推論に失敗した場合は [Result.failure] を返す。
     */
    suspend operator fun invoke(
        comment: LiveComment,
        persona: PersonaPrompt,
        conversationHistory: List<String> = emptyList()
    ): Result<AiResponse> {
        val prompt = personaPromptBuilder.buildPrompt(persona, comment, conversationHistory)

        val startTimeMs = System.currentTimeMillis()
        val inferenceResult = llmInferencePort.generateResponse(prompt)
        val executionTimeMs = System.currentTimeMillis() - startTimeMs

        return inferenceResult.map { rawResponseText ->
            val finalResponseText = personaPromptBuilder.applyToneEnding(rawResponseText, persona)
            AiResponse(
                commentId = comment.id,
                responseText = finalResponseText,
                modelName = modelName,
                executionTimeMs = executionTimeMs
            )
        }
    }
}
