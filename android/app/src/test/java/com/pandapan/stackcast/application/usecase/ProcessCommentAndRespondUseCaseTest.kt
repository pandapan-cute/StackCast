package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.application.usecase.GenerateAiResponseUseCase
import com.pandapan.stackcast.domain.model.PersonaPrompt
import com.pandapan.stackcast.domain.ports.RtmpAudioSinkPort
import com.pandapan.stackcast.infrastructure.ble.FakeStackChanAdapter
import com.pandapan.stackcast.infrastructure.llm.MockLlmInferenceEngine
import com.pandapan.stackcast.infrastructure.rtmp.RecordingRtmpAudioSink
import com.pandapan.stackcast.infrastructure.tts.MockTtsEngine
import com.pandapan.stackcast.infrastructure.youtube.FakeYouTubeChatScraperEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * モック依存でコメント処理パイプライン全体を検証する。
 */
class ProcessCommentAndRespondUseCaseTest {
    @Test
    fun `モックコメントが応答 音声 RTMP BLEまで処理される`() = runTest {
        val stackChan = FakeStackChanAdapter()
        stackChan.connect("mock")
        val audioSink = RecordingRtmpAudioSink()
        val process = ProcessCommentAndRespondUseCase(
            generateAiResponse = GenerateAiResponseUseCase(MockLlmInferenceEngine(0L), "mock"),
            ttsPort = MockTtsEngine(millisPerCharacter = 1),
            audioSink = audioSink,
            stackChanPort = stackChan
        )
        val comment = FakeYouTubeChatScraperEngine(intervalMillis = 0L).comments().first()

        val result = process(comment, PersonaPrompt())

        assertTrue(result.isSuccess)
        assertEquals(comment.id, result.getOrThrow().response.commentId)
        assertEquals(1, audioSink.submittedAudio.size)
        assertTrue(stackChan.sentPackets.isNotEmpty())
    }

    @Test
    fun `BLE未接続時はモーション送信段階のエラーになる`() = runTest {
        val process = ProcessCommentAndRespondUseCase(
            generateAiResponse = GenerateAiResponseUseCase(MockLlmInferenceEngine(0L), "mock"),
            ttsPort = MockTtsEngine(millisPerCharacter = 1),
            audioSink = object : RtmpAudioSinkPort {
                override suspend fun submit(
                    audio: com.pandapan.stackcast.domain.model.PcmAudioData
                ): Result<Unit> = Result.success(Unit)
            },
            stackChanPort = FakeStackChanAdapter()
        )

        val error = process(
            FakeYouTubeChatScraperEngine(intervalMillis = 0L).comments().first(),
            PersonaPrompt()
        ).exceptionOrNull()

        assertTrue(error is PipelineException)
        assertEquals("BLEモーション送信", (error as PipelineException).stage)
    }
}
