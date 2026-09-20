package com.pandapan.stackcast.infrastructure.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * watch URLとytcfg抽出処理を検証する。
 */
class YouTubeWatchPageParserTest {
    @Test
    fun `ytcfgと初回継続トークンをHTMLから抽出できる`() {
        val html = """
            <script>
            ytcfg.set({"INNERTUBE_API_KEY":"test-key","INNERTUBE_CONTEXT":{"client":{"clientName":"WEB"}}});
            var ytInitialData = {"continuation":"initial-token"};
            </script>
        """.trimIndent()

        val result = YouTubeWatchPageParser.parse(html)

        assertEquals("test-key", result.apiKey)
        assertEquals("WEB", result.context.getJSONObject("client").getString("clientName"))
        assertEquals("initial-token", result.continuation)
    }

    @Test
    fun `watch URLから動画IDを抽出できる`() {
        assertEquals(
            "dQw4w9WgXcQ",
            YouTubeWatchUrl.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
    }
}
