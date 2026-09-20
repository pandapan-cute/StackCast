package com.pandapan.stackcast.domain.model

/**
 * 配信画面 ([com.pandapan.stackcast.presentation.streaming.StreamingViewModel]) が公開する
 * 配信状態のシールドクラス。
 *
 * `Idle` → `Previewing` → `Starting` → `Streaming` → `Stopping` → `Idle` の順で遷移することを基本とし、
 * 各段階でのエラーは `Error` へ遷移する（`Error` からは再度「配信開始」操作で `Starting` へ戻れる）。
 */
sealed class StreamingState {

    /** 配信していない待機状態。 */
    data object Idle : StreamingState()

    /** カメラプレビューのみを表示している状態。 */
    data object Previewing : StreamingState()

    /** 配信開始処理（エンコーダ準備・RTMP接続）を実行中の状態。 */
    data object Starting : StreamingState()

    /**
     * 配信中の状態。
     *
     * @property bitrateBps 直近で計測された実測送出ビットレート (bit/sec)。未計測時は0。
     */
    data class Streaming(val bitrateBps: Long = 0L) : StreamingState()

    /** 配信終了処理（RTMP切断・エンコーダ解放）を実行中の状態。 */
    data object Stopping : StreamingState()

    /**
     * エラーで配信を継続できなくなった状態。
     *
     * @property reason ユーザー向けに表示するエラー理由。
     */
    data class Error(val reason: String) : StreamingState()
}
