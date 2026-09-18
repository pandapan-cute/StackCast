package com.pandapan.stackcast.domain.model

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
