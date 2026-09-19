package com.pandapan.stackcast.domain.model

/**
 * AI (LLM) が生成した返答データを表すエンティティ。
 *
 * どのコメントに対する返答か、実際に生成されたテキスト、使用したモデル名、
 * 生成にかかった時間を保持する。ドメイン層に属するため Android SDK には依存しない。
 *
 * @property commentId 応答対象となった [LiveComment.id]。
 * @property responseText 生成されたAI応答テキスト（ペルソナの語尾・口調ルール適用済み）。
 * @property modelName 推論に使用したモデル名 (例: "gemma-2b-it-cpu-int4", "mock")。
 * @property executionTimeMs 推論の生成にかかった時間 (ミリ秒)。
 */
data class AiResponse(
    val commentId: String,
    val responseText: String,
    val modelName: String,
    val executionTimeMs: Long
)
