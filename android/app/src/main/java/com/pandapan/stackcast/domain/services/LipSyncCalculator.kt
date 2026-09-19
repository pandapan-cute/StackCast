package com.pandapan.stackcast.domain.services

import com.pandapan.stackcast.domain.model.LipOpening
import com.pandapan.stackcast.domain.model.LipSyncMotion
import com.pandapan.stackcast.domain.model.PcmAudioData
import com.pandapan.stackcast.domain.model.ServoAngle
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 16-bit PCM 音声データの音量エンベロープを解析し、口パク ([LipOpening]) と
 * サーボ角 ([ServoAngle]) の時系列 ([LipSyncMotion] のリスト) を算出する純粋Kotlinのドメインサービス。
 *
 * アルゴリズムの流れ:
 * 1. 短時間窓 (既定20ms) ごとにRMS (二乗平均平方根) を算出しエンベロープ化する。
 * 2. 移動平均によるローパスフィルタでエンベロープを平滑化し、フレーム間のガタつきを抑える。
 * 3. RMSをデシベルスケールへ変換し、無音とみなす閾値以下は口を閉じた状態 (0.0) とする。
 * 4. 残りの区間は指定範囲のデシベルを0.0〜1.0へ正規化したうえで、べき関数による
 *    非線形マッピング (`nonlinearExponent`) を適用して [LipOpening] を得る。
 * 5. [LipOpening] を [ServoAngle] へ線形マッピングする。
 *
 * Android SDK には一切依存しないため、JVM単体テストのみで全ロジックを検証できる。
 *
 * @property windowMillis エンベロープ算出に用いる短時間窓の長さ (ミリ秒)。10〜40ms程度を推奨。
 * @property smoothingWindowSize 移動平均フィルタの窓数 (フレーム数)。1を指定すると平滑化を行わない。
 * @property silenceThresholdRms これ未満の正規化RMSを無音とみなし、口を閉じた状態にする閾値。
 * @property minDb 正規化の下限として扱うデシベル値。この値以下は0.0に、0dB (最大振幅) は1.0に対応する。
 * @property nonlinearExponent 正規化後のエンベロープに適用するべき関数の指数。
 *   1.0未満にすると小さな音量変化でも口が開きやすくなり、1.0より大きくすると大きな音量でないと
 *   口が開かなくなる (メリハリのある動きになる)。
 * @property servoAngleRangeDegrees [LipOpening] から [ServoAngle] へ変換する際の角度範囲。
 */
class LipSyncCalculator(
    private val windowMillis: Int = DEFAULT_WINDOW_MILLIS,
    private val smoothingWindowSize: Int = DEFAULT_SMOOTHING_WINDOW_SIZE,
    private val silenceThresholdRms: Double = DEFAULT_SILENCE_THRESHOLD_RMS,
    private val minDb: Double = DEFAULT_MIN_DB,
    private val nonlinearExponent: Double = DEFAULT_NONLINEAR_EXPONENT,
    private val servoAngleRangeDegrees: ClosedFloatingPointRange<Double> = ServoAngle.DEFAULT_RANGE_DEGREES
) {

    /**
     * PCM音声データを解析し、口パク・サーボ角の時系列を算出する。
     *
     * @param pcm 解析対象の16-bit PCM音声データ (モノラル)。
     * @param startTimestampMs 発話開始時刻を基準としたタイムスタンプのオフセット (ミリ秒)。
     *   複数の発話を1つのタイムラインに連結したい場合に使用する。既定は0 (発話開始点を0msとする)。
     * @return 短時間窓ごとに算出された [LipSyncMotion] の時系列リスト (発話開始から古い順)。
     *   入力サンプルが空の場合は空リストを返す。
     */
    fun calculate(pcm: PcmAudioData, startTimestampMs: Long = 0L): List<LipSyncMotion> {
        if (pcm.samples.isEmpty()) return emptyList()

        val samplesPerWindow = max(1, pcm.sampleRateHz * windowMillis / MILLIS_PER_SECOND)
        val rawEnvelope = computeRmsEnvelope(pcm.samples, samplesPerWindow)
        val smoothedEnvelope = applyMovingAverage(rawEnvelope, smoothingWindowSize)

        return smoothedEnvelope.mapIndexed { index, rms ->
            val lipOpening = mapRmsToLipOpening(rms)
            val servoAngle = ServoAngle.fromLipOpening(lipOpening, servoAngleRangeDegrees)
            LipSyncMotion(
                timestampMs = startTimestampMs + index.toLong() * windowMillis,
                lipOpening = lipOpening,
                servoAngle = servoAngle
            )
        }
    }

    /** サンプル列を [samplesPerWindow] ごとの窓に区切り、各窓のRMS (正規化振幅) を算出する。 */
    private fun computeRmsEnvelope(samples: ShortArray, samplesPerWindow: Int): List<Double> {
        val envelope = mutableListOf<Double>()
        var windowStart = 0
        while (windowStart < samples.size) {
            val windowEnd = min(windowStart + samplesPerWindow, samples.size)
            envelope += calculateRms(samples, windowStart, windowEnd)
            windowStart = windowEnd
        }
        return envelope
    }

    /** [start] (含む) から [end] (含まない) までのサンプルについて、RMS (0.0〜1.0に正規化) を算出する。 */
    private fun calculateRms(samples: ShortArray, start: Int, end: Int): Double {
        if (end <= start) return 0.0
        var sumOfSquares = 0.0
        for (i in start until end) {
            val normalized = samples[i] / SHORT_MAX_ABS
            sumOfSquares += normalized * normalized
        }
        return sqrt(sumOfSquares / (end - start))
    }

    /**
     * 単純移動平均によるローパスフィルタを適用し、エンベロープの急激な変化を平滑化する。
     * 各時点で、直前 [windowSize] 個 (自分自身を含む) の平均値を採用する。
     */
    private fun applyMovingAverage(values: List<Double>, windowSize: Int): List<Double> {
        if (windowSize <= 1 || values.size <= 1) return values

        return values.indices.map { index ->
            val rangeStart = max(0, index - windowSize + 1)
            var sum = 0.0
            for (i in rangeStart..index) {
                sum += values[i]
            }
            sum / (index - rangeStart + 1)
        }
    }

    /** 正規化RMS値を、無音判定・デシベル変換・非線形マッピングを経て [LipOpening] へ変換する。 */
    private fun mapRmsToLipOpening(rms: Double): LipOpening {
        if (rms < silenceThresholdRms) {
            return LipOpening.CLOSED
        }

        val db = AMPLITUDE_TO_DB_SCALE * log10(rms.coerceAtLeast(MIN_RMS_FLOOR))
        val normalized = ((db - minDb) / (MAX_DB - minDb)).coerceIn(0.0, 1.0)
        val shaped = normalized.pow(nonlinearExponent)
        return LipOpening.of(shaped)
    }

    companion object {
        /** エンベロープ算出用の短時間窓のデフォルト長 (ミリ秒)。 */
        const val DEFAULT_WINDOW_MILLIS = 20

        /** 移動平均フィルタのデフォルト窓数。 */
        const val DEFAULT_SMOOTHING_WINDOW_SIZE = 3

        /** これ未満の正規化RMSは無音とみなすデフォルト閾値。 */
        const val DEFAULT_SILENCE_THRESHOLD_RMS = 0.02

        /** 正規化の下限として扱うデフォルトのデシベル値。 */
        const val DEFAULT_MIN_DB = -40.0

        /** デフォルトの非線形マッピング指数 (1.0 = 線形)。 */
        const val DEFAULT_NONLINEAR_EXPONENT = 0.7

        private const val MILLIS_PER_SECOND = 1000
        private const val SHORT_MAX_ABS = 32768.0
        private const val AMPLITUDE_TO_DB_SCALE = 20.0
        private const val MAX_DB = 0.0

        /** log10(0) の発散を避けるための、RMSの実質的な下限値。 */
        private const val MIN_RMS_FLOOR = 1e-6
    }
}
