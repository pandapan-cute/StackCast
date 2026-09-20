package com.pandapan.stackcast.application.usecase

import com.pandapan.stackcast.domain.model.AiResponse
import com.pandapan.stackcast.domain.model.LiveComment
import com.pandapan.stackcast.domain.model.LipSyncMotion
import com.pandapan.stackcast.domain.model.PersonaPrompt
import com.pandapan.stackcast.domain.ports.RtmpAudioSinkPort
import com.pandapan.stackcast.domain.ports.StackChanPort
import com.pandapan.stackcast.domain.ports.TtsPort
import com.pandapan.stackcast.domain.services.LipSyncCalculator

/**
 * コメント1件をAI応答・音声化・RTMP供給・StackChan送信まで処理するユースケース。
 *
 * 各段階を個別に検証できるよう、失敗は段階名付きの [Result.failure] として返す。
 */
class ProcessCommentAndRespondUseCase(
    private val generateAiResponse: GenerateAiResponseUseCase,
    private val ttsPort: TtsPort,
    private val audioSink: RtmpAudioSinkPort,
    private val stackChanPort: StackChanPort,
    private val lipSyncCalculator: LipSyncCalculator = LipSyncCalculator()
) {
    /**
     * 指定コメントをパイプラインへ通す。
     *
     * @param comment 対象コメント。
     * @param persona 応答に適用するペルソナ。
     * @param conversationHistory 直前の応答履歴。
     */
    suspend operator fun invoke(
        comment: LiveComment,
        persona: PersonaPrompt,
        conversationHistory: List<String> = emptyList()
    ): Result<PipelineResult> {
        val response = generateAiResponse(comment, persona, conversationHistory)
            .mapFailure("LLM応答生成")
            .getOrElse { return Result.failure(it) }
        val audio = ttsPort.synthesizeToPcm(response.responseText)
            .mapFailure("TTS音声合成")
            .getOrElse { return Result.failure(it) }
        val motions = lipSyncCalculator.calculate(audio)
        audioSink.submit(audio).mapFailure("RTMP音声供給").getOrElse { return Result.failure(it) }
        ttsPort.speak(response.responseText)
            .mapFailure("TTS再生")
            .getOrElse { return Result.failure(it) }
        if (!stackChanPort.isConnected()) {
            return Result.failure(PipelineException("BLEモーション送信", "ｽﾀｯｸﾁｬﾝが接続されていません。"))
        }
        stackChanPort.sendLipSyncMotion(motions)
            .mapFailure("BLEモーション送信")
            .getOrElse { return Result.failure(it) }
        return Result.success(PipelineResult(response, motions))
    }

    private fun <T> Result<T>.mapFailure(stage: String): Result<T> =
        fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(PipelineException(stage, it.message ?: "処理に失敗しました。", it)) }
        )
}

/**
 * コメント処理の段階を含むパイプライン例外。
 */
class PipelineException(
    val stage: String,
    message: String,
    cause: Throwable? = null
) : IllegalStateException("$stage: $message", cause)

/**
 * 1件分のパイプライン処理結果。
 */
data class PipelineResult(
    val response: AiResponse,
    val motions: List<LipSyncMotion>
)
