package com.pandapan.stackcast.infrastructure.logging

import android.util.Log
import com.pandapan.stackcast.application.usecase.PipelineLogger

/**
 * コメント処理パイプラインのログを Logcat へ出力する実装。
 */
class AndroidPipelineLogger : PipelineLogger {
    override fun info(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun warn(tag: String, message: String) {
        Log.w(tag, message)
    }
}