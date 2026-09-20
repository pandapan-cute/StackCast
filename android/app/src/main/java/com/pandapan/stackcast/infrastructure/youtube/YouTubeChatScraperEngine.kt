package com.pandapan.stackcast.infrastructure.youtube

import com.pandapan.stackcast.domain.model.LiveComment
import com.pandapan.stackcast.domain.ports.YouTubeChatPort
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * YouTube Live の Innertube チャットをポーリングする取得エンジン。
 *
 * 継続トークンを保持して5秒間隔で取得し、受信済みIDを排除して発行する。
 * API仕様変更時はレスポンス変換だけを差し替えられるよう、ポート境界を維持する。
 */
class YouTubeChatScraperEngine(
    private val initialContinuation: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val pollIntervalMillis: Long = 5_000L,
    private val maxRetries: Int = 3
) : YouTubeChatPort {
    override fun comments(): Flow<LiveComment> = flow {
        var continuation = initialContinuation
        val emittedIds = LinkedHashSet<String>()
        while (true) {
            val result = requestContinuation(continuation)
            result.getOrThrow().let { response ->
                response.comments.filter { emittedIds.add(it.id) }.forEach { emit(it) }
                continuation = response.continuation
            }
            delay(pollIntervalMillis)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun requestContinuation(continuation: String): Result<ChatResponse> =
        withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            repeat(maxRetries) { attempt ->
                try {
                    val body = JSONObject()
                        .put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB")))
                        .put("continuation", continuation)
                        .toString()
                        .toRequestBody(JSON_MEDIA_TYPE)
                    val request = Request.Builder()
                        .url(INNERTUBE_CHAT_URL)
                        .post(body)
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("YouTubeチャット取得失敗: HTTP ${response.code}")
                        return@withContext Result.success(parseResponse(response.body?.string().orEmpty()))
                    }
                } catch (error: IOException) {
                    lastError = error
                    if (attempt + 1 < maxRetries) delay(RETRY_DELAY_MILLIS * (attempt + 1))
                } catch (error: org.json.JSONException) {
                    lastError = error
                    if (attempt + 1 < maxRetries) delay(RETRY_DELAY_MILLIS * (attempt + 1))
                }
            }
            Result.failure(lastError ?: IOException("YouTubeチャット取得に失敗しました。"))
        }

    private fun parseResponse(body: String): ChatResponse {
        val root = JSONObject(body)
        val continuation = findContinuation(root)
            ?: root.optString("continuation", "").takeIf { it.isNotBlank() }
            ?: throw IOException("YouTubeチャットの継続トークンがありません。")
        val parsed = buildList {
            collectMessageRenderers(root).forEach { item ->
                val message = item.optJSONObject("message") ?: JSONObject()
                val text = message.optJSONArray("runs")?.let { runs ->
                    buildString {
                        for (index in 0 until runs.length()) {
                            append(runs.optJSONObject(index)?.optString("text").orEmpty())
                        }
                    }
                } ?: message.optString("simpleText")
                if (!text.isNullOrBlank()) {
                    add(
                        LiveComment(
                            id = item.optString("id").ifBlank { "youtube-${size}" },
                            author = item.optJSONObject("authorName")?.optString("simpleText")
                                ?: "匿名",
                            text = text,
                            timestamp = item.optString("timestampUsec").toLongOrNull()?.div(1_000L)
                                ?: System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        return ChatResponse(continuation, parsed)
    }

    /** Innertubeレスポンスの入れ子構造から次回継続トークンを探す。 */
    private fun findContinuation(value: Any?): String? {
        when (value) {
            is JSONObject -> {
                value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (key == "continuation" && child is String && child.isNotBlank()) return child
                    findContinuation(child)?.let { return it }
                }
            }
            is org.json.JSONArray -> {
                for (index in 0 until value.length()) {
                    findContinuation(value.opt(index))?.let { return it }
                }
            }
        }
        return null
    }

    /** チャット本文を含むRendererをレスポンス全体から収集する。 */
    private fun collectMessageRenderers(value: Any?): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        when (value) {
            is JSONObject -> {
                value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (key == "liveChatTextMessageRenderer" && child is JSONObject) {
                        result += child
                    } else {
                        result += collectMessageRenderers(child)
                    }
                }
            }
            is org.json.JSONArray -> {
                for (index in 0 until value.length()) {
                    result += collectMessageRenderers(value.opt(index))
                }
            }
        }
        return result
    }

    private data class ChatResponse(val continuation: String, val comments: List<LiveComment>)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        const val INNERTUBE_CHAT_URL = "https://www.youtube.com/youtubei/v1/live_chat/get_live_chat"
        const val RETRY_DELAY_MILLIS = 500L
    }
}
