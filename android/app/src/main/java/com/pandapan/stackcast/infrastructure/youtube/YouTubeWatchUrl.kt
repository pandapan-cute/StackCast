package com.pandapan.stackcast.infrastructure.youtube

import java.net.URI

/**
 * YouTube watch URLを扱う補助処理。
 */
object YouTubeWatchUrl {
    /**
     * watch URLから動画IDを取り出す。
     *
     * @param url YouTubeのwatch URLまたは短縮URL。
     * @return 11文字の動画ID。対象外のURLやID不正時はnull。
     */
    fun extractVideoId(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val candidate = when {
            uri.host == "youtu.be" -> uri.path.trim('/').substringBefore('/')
            uri.path == "/watch" -> uri.rawQuery
                ?.split('&')
                ?.mapNotNull { it.split('=', limit = 2).takeIf { pair -> pair.size == 2 } }
                ?.firstOrNull { it[0] == "v" }
                ?.get(1)
            else -> null
        }
        return candidate?.takeIf { it.matches(VIDEO_ID_REGEX) }
    }

    private val VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")
}
