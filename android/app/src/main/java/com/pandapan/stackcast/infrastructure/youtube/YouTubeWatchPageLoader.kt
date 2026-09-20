package com.pandapan.stackcast.infrastructure.youtube

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * YouTube watchページを取得し、Innertube設定へ変換するローダー。
 *
 * @property client HTTP通信クライアント。
 */
class YouTubeWatchPageLoader(
    private val client: OkHttpClient = OkHttpClient()
) {
    /**
     * watch URLからytcfgを取得する。
     *
     * @param watchUrl YouTubeのwatch URL。
     */
    suspend fun load(watchUrl: String): Result<YouTubeWatchPageConfig> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = java.net.URI(watchUrl)
                require(uri.scheme == "https" && uri.host in setOf("www.youtube.com", "youtube.com", "m.youtube.com")) {
                    "YouTube watch URLを入力してください。"
                }
                require(YouTubeWatchUrl.extractVideoId(watchUrl) != null) {
                    "YouTube watch URLに動画IDがありません。"
                }
                val request = Request.Builder().url(watchUrl).header("Accept-Language", "ja-JP,ja;q=0.9").build()
                client.newCall(request).execute().use { response ->
                    val html = response.body?.string().orEmpty()
                    check(response.isSuccessful) { "YouTube watchページ取得失敗: HTTP ${response.code}" }
                    check(html.isNotBlank()) { "YouTube watchページが空です。" }
                    YouTubeWatchPageParser.parse(html)
                }
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(IOException(it.message ?: "watchページ取得に失敗しました。", it)) }
            )
        }
}
