package com.pandapan.stackcast.domain.model

import kotlin.math.roundToInt

/**
 * 音声再生タイムライン上のある一時点における、口パク動作の状態を表すエンティティ。
 *
 * [com.pandapan.stackcast.domain.services.LipSyncCalculator] は、合成音声のPCMデータを
 * 短時間窓ごとに解析し、この [LipSyncMotion] を時系列リスト (`List<LipSyncMotion>`) として出力する。
 * リスト全体が1つの発話における口パク・サーボ駆動のタイムラインを構成する。
 *
 * @property timestampMs 発話開始 (0ms) を基準とした、この時点の経過時間 (ミリ秒)。
 * @property lipOpening この時点における口の開き具合。
 * @property servoAngle この時点における口部サーボの目標角度。
 */
data class LipSyncMotion(
    val timestampMs: Long,
    val lipOpening: LipOpening,
    val servoAngle: ServoAngle
)

/**
 * ｽﾀｯｸﾁｬﾝ向けリップシンクパケットを生成する純粋Kotlinのコーデック。
 *
 * パケット形式は、先頭から `SC`、バージョン、シーケンス番号、点数、
 * そして各点のタイムスタンプ・口の開度・サーボ角度をリトルエンディアンで格納する。
 * ATTの予約領域を除いたMTU内に収まるよう、時系列を複数パケットへ分割する。
 */
object LipSyncMotionPacketCodec {
    /** パケットヘッダーのバイト数。 */
    const val HEADER_SIZE = 6

    /** 1モーション点のシリアライズ後サイズ。 */
    const val POINT_SIZE = 7

    /** BLEの標準MTU。 */
    const val DEFAULT_MTU = 23

    /**
     * モーション時系列をMTUに収まるパケットへ分割する。
     *
     * @param motions 送信対象のモーション時系列。
     * @param mtu ネゴシエーション済みのATT MTU。
     * @param initialSequence 最初のパケットに割り当てるシーケンス番号。
     * @return BLE characteristicへ書き込むバイト列のリスト。
     */
    fun encode(
        motions: List<LipSyncMotion>,
        mtu: Int = DEFAULT_MTU,
        initialSequence: Int = 0
    ): List<ByteArray> {
        require(mtu > HEADER_SIZE + POINT_SIZE) { "MTUが小さすぎます: $mtu" }
        if (motions.isEmpty()) return emptyList()

        val maxPoints = (mtu - 3 - HEADER_SIZE) / POINT_SIZE
        require(maxPoints > 0) { "MTUにモーション点を格納できません: $mtu" }

        return motions.chunked(maxPoints).mapIndexed { packetIndex, chunk ->
            val packet = ByteArray(HEADER_SIZE + chunk.size * POINT_SIZE)
            packet[0] = 'S'.code.toByte()
            packet[1] = 'C'.code.toByte()
            packet[2] = 1
            putUnsignedShort(packet, 3, (initialSequence + packetIndex) and 0xffff)
            packet[5] = chunk.size.toByte()

            chunk.forEachIndexed { index, motion ->
                val offset = HEADER_SIZE + index * POINT_SIZE
                putUnsignedInt(packet, offset, motion.timestampMs.coerceIn(0, 0xffffffffL))
                packet[offset + 4] = (motion.lipOpening.value * 255.0)
                    .toInt()
                    .coerceIn(0, 255)
                    .toByte()
                putSignedShort(packet, offset + 5, motion.servoAngle.degrees.roundToInt())
            }
            packet
        }
    }

    /** 16ビット符号なし整数をリトルエンディアンで格納する。 */
    private fun putUnsignedShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
    }

    /** 32ビット符号なし整数をリトルエンディアンで格納する。 */
    private fun putUnsignedInt(target: ByteArray, offset: Int, value: Long) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = ((value ushr 8) and 0xff).toByte()
        target[offset + 2] = ((value ushr 16) and 0xff).toByte()
        target[offset + 3] = ((value ushr 24) and 0xff).toByte()
    }

    /** 16ビット符号付き整数をリトルエンディアンで格納する。 */
    private fun putSignedShort(target: ByteArray, offset: Int, value: Int) {
        putUnsignedShort(target, offset, value and 0xffff)
    }
}
