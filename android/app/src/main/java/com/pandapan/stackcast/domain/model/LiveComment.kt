package com.pandapan.stackcast.domain.model

/**
 * 視聴者から受信したライブコメントを表すエンティティ。
 *
 * YouTube Live 等から取得したコメント1件分の情報を保持する。
 * ドメイン層に属するため Android SDK には一切依存しない。
 *
 * @property id コメントを一意に識別するID。
 * @property author コメント投稿者の表示名。
 * @property text コメント本文。
 * @property timestamp コメントを受信した時刻 (エポックミリ秒)。
 */
data class LiveComment(
    val id: String,
    val author: String,
    val text: String,
    val timestamp: Long
)
