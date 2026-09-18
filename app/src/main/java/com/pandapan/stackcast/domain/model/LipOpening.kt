package com.pandapan.stackcast.domain.model

/**
 * 口の開き具合 (0.0=完全に閉じた状態 〜 1.0=最大開口) を表す値オブジェクト。
 *
 * [LipSyncCalculator][com.pandapan.stackcast.domain.services.LipSyncCalculator] が
 * 音声のエンベロープ (音量変化) から算出する正規化済みの値であり、
 * 常に 0.0〜1.0 の範囲にクランプされることを保証する。
 *
 * @property value 口の開き具合 (0.0〜1.0)。
 */
@ConsistentCopyVisibility
data class LipOpening private constructor(val value: Double) {

    companion object {
        /** 完全に口を閉じた状態。 */
        val CLOSED = LipOpening(0.0)

        /** 最大まで口を開いた状態。 */
        val OPEN = LipOpening(1.0)

        /**
         * 任意の値から [LipOpening] を生成する。
         *
         * 0.0〜1.0 の範囲外の値が渡された場合は自動的にクランプする。
         *
         * @param value 口の開き具合を表す値 (範囲外でも可)。
         * @return 0.0〜1.0 にクランプされた [LipOpening]。
         */
        fun of(value: Double): LipOpening = LipOpening(value.coerceIn(0.0, 1.0))
    }
}
