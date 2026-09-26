package com.pandapan.stackcast.infrastructure.youtube

import android.util.Log
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
 *
 * @property initialContinuation watch URLがない場合に使う手入力の継続トークン。
 * @property watchUrl 設定時にytcfgを自動取得するYouTube watch URL。
 * @property watchPageLoader watchページからInnertube設定を取得するローダー。
 * @property client HTTP通信クライアント。
 * @property pollIntervalMillis コメント取得間隔。
 * @property maxRetries HTTP通信の最大再試行回数。
 */
class YouTubeChatScraperEngine(
    private val initialContinuation: String,
    private val watchUrl: String = "",
    private val watchPageLoader: YouTubeWatchPageLoader = YouTubeWatchPageLoader(),
    private val client: OkHttpClient = OkHttpClient(),
    private val pollIntervalMillis: Long = 5_000L,
    private val maxRetries: Int = 3
) : YouTubeChatPort {
    override fun comments(): Flow<LiveComment> = flow {
        Log.i(LOG_TAG, "comments() 開始: watchUrl=${watchUrl.isNotBlank()}, continuation=${initialContinuation.isNotBlank()}")
        val pageConfig = if (watchUrl.isBlank()) {
            null
        } else {
            Log.i(LOG_TAG, "watch URL から ytcfg 取得開始: $watchUrl")
            watchPageLoader.load(watchUrl).getOrThrow()
        }
        Log.i(LOG_TAG, "初期設定完了: apiKey=${pageConfig?.apiKey?.isNotBlank() == true}, continuation=${pageConfig?.continuation?.isNotBlank() == true}")
        var continuation = pageConfig?.continuation ?: initialContinuation
        if (continuation.isBlank()) {
            Log.w(LOG_TAG, "continuation が空のため終了")
            throw IOException("継続トークンまたはYouTube Liveのwatch URLを設定してください。")
        }
        val emittedIds = LinkedHashSet<String>()
        while (true) {
            Log.i(LOG_TAG, "コメント取得ポーリング: continuation=${continuation.take(12)}...")
            val result = requestContinuation(continuation, pageConfig)
            result.getOrThrow().let { response ->
                Log.i(LOG_TAG, "コメント取得成功: comments=${response.comments.size}")
                response.comments.filter { emittedIds.add(it.id) }.forEach { emit(it) }
                continuation = response.continuation
            }
            delay(pollIntervalMillis)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun requestContinuation(
        continuation: String,
        pageConfig: YouTubeWatchPageConfig?
    ): Result<ChatResponse> =
        withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            repeat(maxRetries) { attempt ->
                try {
                    Log.i(LOG_TAG, "リクエスト送信: attempt=${attempt + 1}/$maxRetries")
                    val body = JSONObject()
                        .put(
                            "context",
                            pageConfig?.context ?: JSONObject().put(
                                "client",
                                JSONObject().put("clientName", "WEB")
                            )
                        )
                        .put("continuation", continuation)
                        .toString()
                        .toRequestBody(JSON_MEDIA_TYPE)
                    val request = Request.Builder()
                        .url(
                            if (pageConfig == null) INNERTUBE_CHAT_URL
                            else "$INNERTUBE_CHAT_URL?key=${pageConfig.apiKey}"
                        )
                        .post(body)
                        .build()
                    client.newCall(request).execute().use { response ->
                        val responseBody = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            Log.w(LOG_TAG, "HTTP失敗: code=${response.code}")
                            throw IOException(
                                "YouTubeチャット取得失敗: HTTP ${response.code}: " +
                                    sanitizeErrorBody(responseBody)
                            )
                        }
                        Log.i(LOG_TAG, "HTTP成功: code=${response.code}")
                        return@withContext Result.success(parseResponse(responseBody))
                    }
                } catch (error: IOException) {
                    lastError = error
                    Log.w(LOG_TAG, "取得失敗 attempt=${attempt + 1}: ${error.message}")
                    if (attempt + 1 < maxRetries) delay(RETRY_DELAY_MILLIS * (attempt + 1))
                } catch (error: org.json.JSONException) {
                    lastError = error
                    Log.w(LOG_TAG, "JSON解析失敗 attempt=${attempt + 1}: ${error.message}")
                    if (attempt + 1 < maxRetries) delay(RETRY_DELAY_MILLIS * (attempt + 1))
                }
            }
            Log.e(LOG_TAG, "コメント取得失敗: ${lastError?.message}")
            Result.failure(lastError ?: IOException("YouTubeチャット取得に失敗しました。"))
        }

    /**
     * HTTPエラー本文をログ・UI表示用に整形する。
     *
     * YouTubeのエラー本文にはリクエスト値が再掲される場合があるため、
     * continuation等のトークンを除去してから記録する。
     */
    private fun sanitizeErrorBody(body: String): String {
        return body
            .replace(Regex("(?i)continuation\\s*[:=]\\s*\"[^\"]*\""), "continuation:\"[REDACTED]\"")
            .replace(Regex("\\s+"), " ")
            .take(MAX_ERROR_BODY_LENGTH)
            .also { Log.e(LOG_TAG, it) }
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
        Log.i(LOG_TAG, "parseResponse 完了: continuation=${continuation.take(12)}..., parsed=${parsed.size}")
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
        const val MAX_ERROR_BODY_LENGTH = 1000
        const val LOG_TAG = "YouTubeChatScraper"
    }
}
