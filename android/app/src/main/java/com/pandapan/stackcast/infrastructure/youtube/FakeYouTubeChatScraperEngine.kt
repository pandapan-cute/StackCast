package com.pandapan.stackcast.infrastructure.youtube

import com.pandapan.stackcast.domain.model.LiveComment
import com.pandapan.stackcast.domain.ports.YouTubeChatPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 開発画面とE2Eテストで利用するモックYouTubeチャット取得エンジン。
 *
 * 実際のネットワークへ接続せず、固定コメントを定期的に発行する。
 */
class FakeYouTubeChatScraperEngine(
    private val intervalMillis: Long = 3_000L,
    private val messages: List<String> = DEFAULT_MESSAGES
) : YouTubeChatPort {
    override fun comments(): Flow<LiveComment> = flow {
        require(messages.isNotEmpty()) { "モックコメントが設定されていません。" }
        var index = 0
        while (true) {
            emit(
                LiveComment(
                    id = "mock-$index",
                    author = "モック視聴者",
                    text = messages[index % messages.size],
                    timestamp = System.currentTimeMillis()
                )
            )
            index += 1
            delay(intervalMillis)
        }
    }

    private companion object {
        val DEFAULT_MESSAGES = listOf("こんにちは！", "今日の配信も楽しみです", "元気ですか？")
    }
}
