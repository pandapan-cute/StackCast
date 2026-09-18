package com.pandapan.stackcast.presentation.streaming

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.pandapan.stackcast.domain.model.LipSyncMotion
import kotlin.math.roundToLong

/**
 * [LipSyncMotion] の時系列を簡易的に可視化するデバッグ用オーバーレイビュー。
 *
 * 口の開き具合 (0.0〜1.0) を縦棒の高さとして描画するとともに、Logcatへも出力することで、
 * 実機のｽﾀｯｸﾁｬﾝ (BLEサーボ) 連携を実装する前段階でリップシンク解析結果を確認できるようにする。
 * 本ビューはあくまで開発・デバッグ用の可視化手段であり、本番UIへの組み込みは必須ではない。
 */
class LipSyncOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint().apply {
        color = Color.parseColor(BAR_COLOR_HEX)
        isAntiAlias = true
    }

    private var currentLipOpening: Float = 0f
    private var playbackAnimator: ValueAnimator? = null

    /**
     * 口パクの時系列 ([LipSyncMotion] のリスト) を、各フレームのタイムスタンプ通りの
     * タイミングでアニメーション再生し、都度 [Log.d] へ出力する。
     *
     * @param motions [com.pandapan.stackcast.domain.services.LipSyncCalculator] が算出した時系列。
     */
    fun playMotions(motions: List<LipSyncMotion>) {
        playbackAnimator?.cancel()
        if (motions.isEmpty()) {
            setLipOpening(0f)
            return
        }

        val totalDurationMs = motions.last().timestampMs.coerceAtLeast(1L)
        playbackAnimator = ValueAnimator.ofFloat(0f, totalDurationMs.toFloat()).apply {
            duration = totalDurationMs
            addUpdateListener { animator ->
                val elapsedMs = (animator.animatedValue as Float).roundToLong()
                val currentFrame = motions.lastOrNull { it.timestampMs <= elapsedMs } ?: motions.first()
                setLipOpening(currentFrame.lipOpening.value.toFloat())
                Log.d(
                    TAG,
                    "timestampMs=${currentFrame.timestampMs} " +
                        "lipOpening=${currentFrame.lipOpening.value} " +
                        "servoAngle=${currentFrame.servoAngle.degrees}"
                )
            }
            start()
        }
    }

    /** 口の開き具合を直接設定し、再描画する。 */
    private fun setLipOpening(value: Float) {
        currentLipOpening = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barHeight = height * currentLipOpening
        canvas.drawRect(0f, height - barHeight, width.toFloat(), height.toFloat(), barPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        playbackAnimator?.cancel()
    }

    private companion object {
        const val TAG = "LipSyncOverlayView"
        const val BAR_COLOR_HEX = "#FF4081"
    }
}
