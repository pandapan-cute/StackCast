package com.pandapan.stackcast.presentation.streaming

import com.pandapan.stackcast.domain.model.StreamSession
import com.pandapan.stackcast.domain.model.StreamingState
import com.pandapan.stackcast.infrastructure.rtmp.MockRtmpEncoderEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [StreamingViewModel] の状態遷移を検証する単体テスト。
 *
 * 実際のカメラ・ネットワークを用いず [MockRtmpEncoderEngine] を接続することで、
 * `Idle` → `Starting` → `Streaming` → `Stopping` → `Idle` という配信状態の
 * 遷移フローを [com.pandapan.stackcast.infrastructure.rtmp.RtmpEncoderEngine] を
 * 実機なしで検証できる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingIntegrationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // ViewModel が内部で使用する viewModelScope は Dispatchers.Main に依存するため、
        // テスト用のディスパッチャへ差し替える。
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val testSession = StreamSession(rtmpUrl = "rtmp://example.com/live", streamKey = "test-key")

    @Test
    fun `配信エンジン未接続の場合はエラー状態になる`() = runTest {
        val viewModel = StreamingViewModel()

        viewModel.startStreaming(testSession)

        assertTrue(viewModel.state.value is StreamingState.Error)
    }

    @Test
    fun `startStreamingでIdleからStarting経てStreamingへ遷移する`() = runTest {
        val viewModel = StreamingViewModel()
        val engine = MockRtmpEncoderEngine(connectDelayMillis = 50L, logger = {})
        viewModel.attachStreamer(engine)

        assertEquals(StreamingState.Idle, viewModel.state.value)

        viewModel.startStreaming(testSession)
        assertTrue(
            "配信開始直後はStartingになるはず",
            viewModel.state.value is StreamingState.Starting
        )

        testDispatcher.scheduler.advanceUntilIdle()

        val streamingState = viewModel.state.value
        assertTrue("接続成功後はStreamingになるはず", streamingState is StreamingState.Streaming)
    }

    @Test
    fun `stopStreamingでStreamingからStopping経てIdleへ遷移する`() = runTest {
        val viewModel = StreamingViewModel()
        val engine = MockRtmpEncoderEngine(connectDelayMillis = 50L, logger = {})
        viewModel.attachStreamer(engine)

        viewModel.startStreaming(testSession)
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is StreamingState.Streaming)

        viewModel.stopStreaming()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(StreamingState.Idle, viewModel.state.value)
        assertTrue(!engine.isStreaming())
    }

    @Test
    fun `ビットレート更新イベントがStreaming状態へ反映される`() = runTest {
        val viewModel = StreamingViewModel()
        val engine = MockRtmpEncoderEngine(connectDelayMillis = 10L, logger = {})
        viewModel.attachStreamer(engine)

        viewModel.startStreaming(testSession)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is StreamingState.Streaming)
        assertEquals(testSession.videoBitrateBps.toLong(), (state as StreamingState.Streaming).bitrateBps)
    }
}
