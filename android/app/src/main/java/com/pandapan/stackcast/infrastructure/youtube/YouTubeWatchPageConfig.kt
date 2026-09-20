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
        val apiKey = Regex(""""INNERTUBE_API_KEY"\s*:\s*"([^"]+)"""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?: runCatching { ytcfg.getString("INNERTUBE_API_KEY") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("watchページにINNERTUBE_API_KEYがありません。")
        val context = extractContext(html)
            ?: runCatching { ytcfg.getJSONObject("INNERTUBE_CONTEXT") }.getOrNull()
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

    private fun extractContext(html: String): JSONObject? {
        val marker = """"INNERTUBE_CONTEXT""""
        val keyStart = html.indexOf(marker)
        if (keyStart < 0) return null
        val objectStart = html.indexOf('{', keyStart + marker.length)
        return objectStart.takeIf { it >= 0 }
            ?.let { extractBalancedObject(html, it) }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
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
        // `token`全般を拾うと、チャットとは無関係な認証・再生トークンを
        // 継続トークンとして送信してHTTP 400になるため、対象キーを限定する。
        Regex(""""(?:continuation|continuationToken)"\s*:\s*"([^"]+)"""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
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
                    val child = value.get(key)
                    if (key in keys && child is String && child.isNotBlank()) return child
                    findStringForKeys(child, keys)?.let { return it }
                }
            }
            is org.json.JSONArray -> {
                for (index in 0 until value.length()) {
                    findStringForKeys(value.get(index), keys)?.let { return it }
                }
            }
        }
        return null
    }
}
