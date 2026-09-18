package com.pandapan.stackcast.domain.model

/**
 * RTMP配信エンジンから通知される接続イベントを表すシールドクラス。
 *
 * [com.pandapan.stackcast.domain.ports.RtmpStreamerPort] の実装は、内部の配信エンジン
 * （実機では RootEncoder、テストではモック）から受け取ったコールバックをこのイベント型へ
 * 変換したうえで公開する。これにより、プレゼンテーション層は特定のRTMPライブラリに
 * 依存せずイベントを購読できる。
 */
sealed class StreamerEvent {

    /** 接続処理を開始したことを示すイベント。 */
    data class ConnectionStarted(val url: String) : StreamerEvent()

    /** RTMPサーバーへの接続に成功したことを示すイベント。 */
    data object ConnectionSuccess : StreamerEvent()

    /** RTMPサーバーへの接続に失敗したことを示すイベント。 */
    data class ConnectionFailed(val reason: String) : StreamerEvent()

    /** RTMPサーバーとの接続が切断されたことを示すイベント。 */
    data object Disconnected : StreamerEvent()

    /** 認証エラーが発生したことを示すイベント。 */
    data class AuthError(val reason: String) : StreamerEvent()

    /** 現在の実測送出ビットレートが更新されたことを示すイベント。 */
    data class BitrateUpdated(val bitrateBps: Long) : StreamerEvent()
}
