package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.domain.model.LiveComment
import com.pandapan.stackcast.domain.model.PersonaPrompt
import com.pandapan.stackcast.domain.ports.LlmInferencePort
import com.pandapan.stackcast.infrastructure.llm.MockLlmInferenceEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GenerateAiResponseUseCase] の単体テスト。
 *
 * [MockLlmInferenceEngine] を用いることで、実モデル (MediaPipe GenAI) や
 * 端末上のモデルファイルが無い環境でもAI応答生成パイプライン全体の疎通を検証できる。
 */
class GenerateAiResponseUseCaseTest {

    private val persona = PersonaPrompt(
        systemPrompt = "あなたは視聴者に優しく寄り添うAIキャラクターです。",
        characterSetting = "明るく元気な性格。",
        endingRule = "のだ",
        ngWords = listOf("暴力")
    )

    private val comment = LiveComment(
        id = "comment-1",
        author = "視聴者A",
        text = "こんにちは！",
        timestamp = 1_700_000_000_000L
    )

    @Test
    fun `モックエンジン経由でペルソナの語尾ルールが反映されたAI応答が生成される`() = runTest {
        val mockEngine = MockLlmInferenceEngine(
            simulatedDelayMillis = 0,
            responseFactory = { "コメントありがとうございます。" }
        )
        val useCase = GenerateAiResponseUseCase(
            llmInferencePort = mockEngine,
            modelName = "mock"
        )

        val result = useCase(comment, persona)

        assertTrue(result.isSuccess)
        val aiResponse = result.getOrThrow()
        assertEquals(comment.id, aiResponse.commentId)
        assertEquals("mock", aiResponse.modelName)
        assertEquals("コメントありがとうございますのだ。", aiResponse.responseText)
    }

    @Test
    fun `LLM推論が失敗した場合はResult_failureが返る`() = runTest {
        val failingEngine = object : LlmInferencePort {
            override suspend fun generateResponse(prompt: String): Result<String> {
                return Result.failure(IllegalStateException("モデル未読み込み"))
            }

            override fun isModelLoaded(): Boolean = false
        }
        val useCase = GenerateAiResponseUseCase(
            llmInferencePort = failingEngine,
            modelName = "mock"
        )

        val result = useCase(comment, persona)

        assertTrue(result.isFailure)
    }
}
