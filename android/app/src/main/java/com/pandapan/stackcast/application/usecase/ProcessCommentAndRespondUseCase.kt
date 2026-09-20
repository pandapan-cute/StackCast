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
    private val lipSyncCalculator: LipSyncCalculator = LipSyncCalculator(),
    private val logger: PipelineLogger = NoOpPipelineLogger
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
        logger.info(TAG, "start comment pipeline: commentId=${comment.id}, author=${comment.author}, textLength=${comment.text.length}")

        val response = generateAiResponse(comment, persona, conversationHistory)
            .mapFailure("LLM応答生成")
            .getOrElse { return Result.failure(it) }
        logger.info(TAG, "ai response generated: commentId=${comment.id}, responseLength=${response.responseText.length}, model=${response.modelName}, executionTimeMs=${response.executionTimeMs}")

        val audio = ttsPort.synthesizeToPcm(response.responseText)
            .mapFailure("TTS音声合成")
            .getOrElse { return Result.failure(it) }
        val audioDurationMs = if (audio.sampleRateHz > 0) {
            audio.samples.size * 1_000L / audio.sampleRateHz
        } else {
            0L
        }
        logger.info(TAG, "tts pcm generated: commentId=${comment.id}, sampleRateHz=${audio.sampleRateHz}, sampleCount=${audio.samples.size}, durationMs=$audioDurationMs")

        val motions = lipSyncCalculator.calculate(audio)
        logger.info(TAG, "lip sync calculated: commentId=${comment.id}, motionCount=${motions.size}, ${summarizeMotions(motions)}")

        audioSink.submit(audio).mapFailure("RTMP音声供給").getOrElse { return Result.failure(it) }
        // 既定経路ではPCMをRTMPへ直接注入するため、端末スピーカー再生は行わない。
        if (!stackChanPort.isConnected()) {
            logger.warn(TAG, "stackchan is not connected: commentId=${comment.id}")
            return Result.failure(PipelineException("BLEモーション送信", "ｽﾀｯｸﾁｬﾝが接続されていません。"))
        }

        logger.info(TAG, "sending lip sync motion to stackchan: commentId=${comment.id}, motionCount=${motions.size}")
        stackChanPort.sendLipSyncMotion(motions)
            .mapFailure("BLEモーション送信")
            .getOrElse { return Result.failure(it) }
        logger.info(TAG, "stackchan send completed: commentId=${comment.id}")

        return Result.success(PipelineResult(response, motions))
    }

    private fun summarizeMotions(motions: List<LipSyncMotion>): String {
        if (motions.isEmpty()) return "lipOpening=min=0.000,max=0.000,avg=0.000"
        val openings = motions.map { it.lipOpening.value }
        val min = openings.minOrNull() ?: 0.0
        val max = openings.maxOrNull() ?: 0.0
        val avg = openings.average()
        val first = openings.firstOrNull() ?: 0.0
        val last = openings.lastOrNull() ?: 0.0
        return "lipOpening=min=${formatDouble(min)},max=${formatDouble(max)},avg=${formatDouble(avg)},first=${formatDouble(first)},last=${formatDouble(last)}"
    }

    private fun formatDouble(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)

    private fun <T> Result<T>.mapFailure(stage: String): Result<T> =
        fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(PipelineException(stage, it.message ?: "処理に失敗しました。", it)) }
        )

    private companion object {
        const val TAG = "StackCastPipeline"
    }
}

/**
 * コメント処理パイプラインのログ出力先。
 */
interface PipelineLogger {
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String)
}

/**
 * ログを出力しない既定実装。
 */
object NoOpPipelineLogger : PipelineLogger {
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String) = Unit
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
