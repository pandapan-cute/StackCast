package com.pandapan.stackcast.domain.model

/**
 * RTMP配信を開始するために必要な、実行時パラメータ一式を表す値オブジェクト。
 *
 * [RtmpConfig] が「設定画面で永続化されるユーザー入力値」であるのに対し、
 * [StreamSession] は「実際に [com.pandapan.stackcast.domain.ports.RtmpStreamerPort] へ
 * 引き渡すためのエンコードパラメータ（解像度・FPS・ビットレート等をInt/Boolean型で正規化した値）」
 * を保持する。ドメイン層に属するため Android SDK には一切依存しない。
 *
 * @property rtmpUrl 配信先の RTMP サーバー URL (例: rtmp://a.rtmp.youtube.com/live2)。
 * @property streamKey 配信先が発行するストリームキー。空文字の場合は [rtmpUrl] のみで接続する。
 * @property videoWidth 映像の横幅 (px)。
 * @property videoHeight 映像の縦幅 (px)。
 * @property fps 映像のフレームレート。
 * @property videoBitrateBps 映像ビットレート (bit/sec)。
 * @property audioSampleRateHz 音声サンプルレート (Hz)。
 * @property audioBitrateBps 音声ビットレート (bit/sec)。
 * @property audioStereo true の場合ステレオ、false の場合モノラルで収音する。
 */
data class StreamSession(
    val rtmpUrl: String,
    val streamKey: String = "",
    val videoWidth: Int = DEFAULT_VIDEO_WIDTH,
    val videoHeight: Int = DEFAULT_VIDEO_HEIGHT,
    val fps: Int = DEFAULT_FPS,
    val videoBitrateBps: Int = DEFAULT_VIDEO_BITRATE_BPS,
    val audioSampleRateHz: Int = DEFAULT_AUDIO_SAMPLE_RATE_HZ,
    val audioBitrateBps: Int = DEFAULT_AUDIO_BITRATE_BPS,
    val audioStereo: Boolean = true
) {
    init {
        require(rtmpUrl.isNotBlank()) { "rtmpUrlは空にできません。" }
        require(videoWidth > 0 && videoHeight > 0) { "映像解像度は正の値である必要があります。" }
        require(fps > 0) { "FPSは正の値である必要があります。" }
        require(videoBitrateBps > 0) { "映像ビットレートは正の値である必要があります。" }
        require(audioSampleRateHz > 0) { "音声サンプルレートは正の値である必要があります。" }
        require(audioBitrateBps > 0) { "音声ビットレートは正の値である必要があります。" }
    }

    /**
     * ストリームキーを連結した実際の接続先URL。
     *
     * RTMPは慣習的に `rtmp://ホスト/アプリ名/ストリームキー` の形式で接続するため、
     * [streamKey] が指定されている場合は [rtmpUrl] の末尾へ `/` 区切りで連結する。
     */
    val fullRtmpUrl: String
        get() = if (streamKey.isBlank()) {
            rtmpUrl
        } else {
            rtmpUrl.trimEnd('/') + "/" + streamKey
        }

    companion object {
        /** 映像横幅の初期値 (720p)。 */
        const val DEFAULT_VIDEO_WIDTH: Int = 1280

        /** 映像縦幅の初期値 (720p)。 */
        const val DEFAULT_VIDEO_HEIGHT: Int = 720

        /** FPSの初期値。 */
        const val DEFAULT_FPS: Int = 30

        /** 映像ビットレートの初期値 (2.5Mbps)。 */
        const val DEFAULT_VIDEO_BITRATE_BPS: Int = 2_500_000

        /** 音声サンプルレートの初期値 (44.1kHz)。 */
        const val DEFAULT_AUDIO_SAMPLE_RATE_HZ: Int = 44_100

        /** 音声ビットレートの初期値 (128kbps)。 */
        const val DEFAULT_AUDIO_BITRATE_BPS: Int = 128_000

        /**
         * 設定画面で永続化されている [RtmpConfig] から [StreamSession] を生成する。
         *
         * [RtmpConfig.resolution] は `"1920x1080"` のような `"幅x高さ"` 形式の文字列のため、
         * パース失敗時は [DEFAULT_VIDEO_WIDTH] / [DEFAULT_VIDEO_HEIGHT] にフォールバックする。
         *
         * @param config 設定画面で保持している配信設定。
         * @return 生成された [StreamSession]。
         */
        fun fromRtmpConfig(config: RtmpConfig): StreamSession {
            val (width, height) = parseResolution(config.resolution)
            return StreamSession(
                rtmpUrl = config.rtmpUrl,
                streamKey = config.streamKey,
                videoWidth = width,
                videoHeight = height,
                fps = if (config.frameRate > 0) config.frameRate else DEFAULT_FPS,
                videoBitrateBps = if (config.bitrateKbps > 0) config.bitrateKbps * 1000 else DEFAULT_VIDEO_BITRATE_BPS
            )
        }

        /** `"幅x高さ"` 形式の解像度文字列を (幅, 高さ) のペアへ変換する。パース失敗時は初期解像度を返す。 */
        private fun parseResolution(resolution: String): Pair<Int, Int> {
            val parts = resolution.lowercase().split("x")
            val width = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val height = parts.getOrNull(1)?.trim()?.toIntOrNull()
            return if (width != null && height != null && width > 0 && height > 0) {
                width to height
            } else {
                DEFAULT_VIDEO_WIDTH to DEFAULT_VIDEO_HEIGHT
            }
        }
    }
}
