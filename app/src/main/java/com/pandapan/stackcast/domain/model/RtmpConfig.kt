package com.pandapan.stackcast.domain.model

/**
 * 配信 (RTMP) 設定を表す値オブジェクト。
 *
 * YouTube Live 等の RTMP エンドポイントへ配信する際に必要な接続先情報と、
 * 映像エンコード条件（解像度・フレームレート・ビットレート）を保持する不変オブジェクト。
 * ドメイン層に属するため Android SDK には一切依存しない。
 *
 * @property rtmpUrl 配信先の RTMP サーバー URL (例: rtmp://a.rtmp.youtube.com/live2)。
 * @property streamKey 配信先が発行するストリームキー。
 * @property resolution 映像解像度 (例: "1920x1080")。
 * @property frameRate 映像フレームレート (fps)。
 * @property bitrateKbps 映像ビットレート (kbps)。
 */
data class RtmpConfig(
    val rtmpUrl: String = "",
    val streamKey: String = "",
    val resolution: String = DEFAULT_RESOLUTION,
    val frameRate: Int = DEFAULT_FRAME_RATE,
    val bitrateKbps: Int = DEFAULT_BITRATE_KBPS
) {
    /**
     * 配信を開始できる最低限の設定が揃っているかどうかを検証する。
     *
     * @return RTMP URL とストリームキーが入力済みで、フレームレート・ビットレートが正の値であれば true。
     */
    fun isValid(): Boolean {
        return rtmpUrl.isNotBlank() &&
            streamKey.isNotBlank() &&
            frameRate > 0 &&
            bitrateKbps > 0
    }

    companion object {
        /** 解像度の初期値 (フルHD)。 */
        const val DEFAULT_RESOLUTION: String = "1920x1080"

        /** フレームレートの初期値 (fps)。 */
        const val DEFAULT_FRAME_RATE: Int = 30

        /** ビットレートの初期値 (kbps)。 */
        const val DEFAULT_BITRATE_KBPS: Int = 4500
    }
}
