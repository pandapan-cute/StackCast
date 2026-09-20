package com.pandapan.stackcast.domain.model

/**
 * ｽﾀｯｸﾁｬﾝ (M5Avatar) が表現可能な表情の種類を表す列挙型。
 *
 * @property id ファームウェアとのBLE通信パケットで送受信される表情識別子。
 * @property displayName 画面表示用の日本語名称。
 */
enum class AvatarExpression(val id: Int, val displayName: String) {
    /** 通常（ニュートラル）の表情。 */
    NEUTRAL(0, "通常"),

    /** 笑顔・嬉しい表情。 */
    HAPPY(1, "笑顔"),

    /** 怒り・不満の表情。 */
    ANGRY(2, "怒り"),

    /** 悲しい・困った表情。 */
    SAD(3, "悲しみ"),

    /** 疑問・小首をかしげる表情。 */
    DOUBT(4, "疑問"),

    /** 眠い・うとうとした表情。 */
    SLEEPY(5, "眠気");

    companion object {
        /**
         * 表情IDに対応する [AvatarExpression] を取得する。未定義の場合は [NEUTRAL] を返す。
         *
         * @param id 表情ID。
         * @return 対応する表情。
         */
        fun fromId(id: Int): AvatarExpression {
            return entries.firstOrNull { it.id == id } ?: NEUTRAL
        }
    }
}
