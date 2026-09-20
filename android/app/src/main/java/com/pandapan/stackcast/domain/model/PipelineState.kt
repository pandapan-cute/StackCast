package com.pandapan.stackcast.domain.model

/**
 * コメント処理パイプラインの現在状態。
 */
sealed class PipelineState {
    /** コメント監視を停止している状態。 */
    data object Idle : PipelineState()

    /** コメント監視を開始した状態。 */
    data object Listening : PipelineState()

    /** 1件のコメントを処理中の状態。 */
    data class Processing(val comment: LiveComment) : PipelineState()

    /** 応答生成・音声化・送信が完了した状態。 */
    data class Completed(val comment: LiveComment, val response: AiResponse) : PipelineState()

    /** パイプラインのいずれかの段階で失敗した状態。 */
    data class Error(val comment: LiveComment?, val stage: String, val reason: String) : PipelineState()
}
