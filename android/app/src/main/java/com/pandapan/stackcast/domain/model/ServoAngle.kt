package com.pandapan.stackcast.domain.model

/**
 * ｽﾀｯｸﾁｬﾝの口部サーボの回転角度 (度数法) を表す値オブジェクト。
 *
 * [LipOpening] (0.0〜1.0 に正規化された口の開度) を、実機のサーボが取り得る角度範囲へ
 * 線形マッピングして得られる値であり、常に指定範囲内にクランプされることを保証する。
 *
 * @property degrees サーボの回転角度 (度)。
 */
@ConsistentCopyVisibility
data class ServoAngle private constructor(val degrees: Double) {

    companion object {
        /**
         * サーボ角度のデフォルト可動範囲 (度)。
         * 0度 = 口を完全に閉じた状態のサーボ位置、60度 = 最大開口時のサーボ位置を想定する。
         * 実機のホーン取り付け角度に応じて呼び出し側で任意の範囲を指定できる。
         */
        val DEFAULT_RANGE_DEGREES = 0.0..60.0

        /**
         * 角度を指定範囲内にクランプして [ServoAngle] を生成する。
         *
         * @param degrees サーボの回転角度 (度)。
         * @param range サーボが実際に可動できる角度範囲。
         * @return [range] の範囲内にクランプされた [ServoAngle]。
         */
        fun of(degrees: Double, range: ClosedFloatingPointRange<Double> = DEFAULT_RANGE_DEGREES): ServoAngle {
            return ServoAngle(degrees.coerceIn(range.start, range.endInclusive))
        }

        /**
         * [LipOpening] (0.0〜1.0) を指定角度範囲へ線形マッピングして [ServoAngle] を算出する。
         *
         * @param lipOpening 正規化された口の開度。
         * @param range サーボが実際に可動できる角度範囲。
         * @return [lipOpening] に対応する [ServoAngle]。
         */
        fun fromLipOpening(
            lipOpening: LipOpening,
            range: ClosedFloatingPointRange<Double> = DEFAULT_RANGE_DEGREES
        ): ServoAngle {
            val degrees = range.start + (range.endInclusive - range.start) * lipOpening.value
            return of(degrees, range)
        }
    }
}
