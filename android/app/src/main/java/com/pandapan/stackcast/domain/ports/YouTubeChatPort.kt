package com.pandapan.stackcast.domain.ports

import com.pandapan.stackcast.domain.model.LiveComment
import kotlinx.coroutines.flow.Flow

/**
 * YouTube Live チャット取得を抽象化するポート。
 *
 * 実機の Innertube 実装と開発用フェイクを差し替え可能にする。
 */
interface YouTubeChatPort {
    /** コメントを重複排除済みの時系列として発行する。購読解除で取得処理も終了する。 */
    fun comments(): Flow<LiveComment>
}
