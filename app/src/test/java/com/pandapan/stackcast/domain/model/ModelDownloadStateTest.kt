package com.pandapan.stackcast.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ModelDownloadState] の単体テスト。
 *
 * 特に [ModelDownloadState.InProgress.progressPercent] の計算ロジックが
 * 総バイト数不明時や境界値でも正しく動作することを検証する。
 */
class ModelDownloadStateTest {

    @Test
    fun `総バイト数が既知の場合は正しい進捗率が計算される`() {
        val state = ModelDownloadState.InProgress(downloadedBytes = 50L, totalBytes = 200L)

        assertEquals(25, state.progressPercent)
    }

    @Test
    fun `総バイト数が不明(-1)の場合は進捗率0を返す`() {
        val state = ModelDownloadState.InProgress(downloadedBytes = 50L, totalBytes = -1L)

        assertEquals(0, state.progressPercent)
    }

    @Test
    fun `ダウンロード完了時は進捗率100を返す`() {
        val state = ModelDownloadState.InProgress(downloadedBytes = 200L, totalBytes = 200L)

        assertEquals(100, state.progressPercent)
    }
}
