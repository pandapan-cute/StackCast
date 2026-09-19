package com.pandapan.stackcast.domain.model

/**
 * ｽﾀｯｸﾁｬﾝとのBLE接続状態を表す。
 */
sealed interface StackChanConnectionState {
    /** 未接続状態。 */
    data object Disconnected : StackChanConnectionState

    /** 接続処理中の状態。 */
    data object Connecting : StackChanConnectionState

    /** サービス探索とMTU交渉が完了した接続状態。 */
    data class Connected(val mtu: Int) : StackChanConnectionState

    /** 接続または通信で発生したエラー状態。 */
    data class Error(val message: String) : StackChanConnectionState
}
