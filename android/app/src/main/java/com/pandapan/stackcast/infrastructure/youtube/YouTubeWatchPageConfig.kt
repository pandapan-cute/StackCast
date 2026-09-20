package com.pandapan.stackcast.infrastructure.youtube

import org.json.JSONObject

/**
 * YouTube watchページから抽出したInnertube接続情報。
 *
 * @property apiKey YouTube WEBクライアントのAPIキー。
 * @property context Innertubeリクエストにそのまま使用するクライアントコンテキスト。
 * @property continuation 初回チャット取得に使う継続トークン。
 */
data class YouTubeWatchPageConfig(
    val apiKey: String,
    val context: JSONObject,
    val continuation: String
)

/**
 * watchページHTMLからytcfgとチャット継続トークンを抽出する純粋なパーサー。
 */
object YouTubeWatchPageParser {
    /**
     * HTMLからInnertube設定を抽出する。
     *
     * @param html YouTube watchページのHTML。
     * @return 抽出結果。必要な値がない場合は明示的な例外を返す。
     */
    fun parse(html: String): YouTubeWatchPageConfig {
        val ytcfg = extractYtCfg(html)
            ?: throw IllegalArgumentException("watchページからytcfgを解析できませんでした。")
        val apiKey = ytcfg.optString("INNERTUBE_API_KEY")
            .takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("watchページにINNERTUBE_API_KEYがありません。")
        val context = ytcfg.optJSONObject("INNERTUBE_CONTEXT")
            ?: throw IllegalArgumentException("watchページにINNERTUBE_CONTEXTがありません。")
        val continuation = findContinuation(html)
            ?: throw IllegalArgumentException("watchページにチャット継続トークンがありません。")
        return YouTubeWatchPageConfig(apiKey, context, continuation)
    }

    private fun extractYtCfg(html: String): JSONObject? {
        val markers = listOf("ytcfg.set(", "ytcfg.set && ytcfg.set(")
        markers.forEach { marker ->
            var start = html.indexOf(marker)
            while (start >= 0) {
                val jsonStart = html.indexOf('{', start + marker.length)
                if (jsonStart >= 0) {
                    extractBalancedObject(html, jsonStart)?.let { raw ->
                        runCatching { JSONObject(raw) }.getOrNull()?.let { return it }
                    }
                }
                start = html.indexOf(marker, start + marker.length)
            }
        }
        return null
    }

    private fun extractBalancedObject(text: String, start: Int): String? {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
                continue
            }
            when (character) {
                '"' -> quoted = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun findContinuation(html: String): String? {
        val root = extractInitialData(html) ?: return null
        return findStringForKeys(root, setOf("continuation", "continuationToken"))
    }

    private fun extractInitialData(html: String): JSONObject? {
        val markers = listOf("ytInitialData = ", "var ytInitialData = ", "window[\"ytInitialData\"] = ")
        val marker = markers.firstOrNull { html.contains(it) } ?: return null
        val start = html.indexOf(marker)
        if (start < 0) return null
        val jsonStart = html.indexOf('{', start + marker.length)
        return jsonStart.takeIf { it >= 0 }
            ?.let { extractBalancedObject(html, it) }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    private fun findStringForKeys(value: Any?, keys: Set<String>): String? {
        when (value) {
            is JSONObject -> {
                value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (key in keys && child is String && child.isNotBlank()) return child
                    findStringForKeys(child, keys)?.let { return it }
                }
            }
            is org.json.JSONArray -> {
                for (index in 0 until value.length()) {
                    findStringForKeys(value.opt(index), keys)?.let { return it }
                }
            }
        }
        return null
    }
}
