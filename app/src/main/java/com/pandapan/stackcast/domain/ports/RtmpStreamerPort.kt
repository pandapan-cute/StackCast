package com.pandapan.stackcast.domain.ports

import com.pandapan.stackcast.domain.model.StreamSession
import com.pandapan.stackcast.domain.model.StreamerEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * カメラ映像・マイク音声をエンコードし RTMP サーバーへ送出するエンジンを抽象化するポート。
 *
 * 実装はインフラ層 ([com.pandapan.stackcast.infrastructure.rtmp] 配下) に置き、
 * 実機では RootEncoder ライブラリ (`RtmpCamera2`) を用いた実装、
 * 単体テストではネットワーク送出を行わないモック実装を用いる。
 * ドメイン層に属するため Android SDK には一切依存しない。
 */
interface RtmpStreamerPort {

    /** 接続状態・ビットレート更新などのイベントを通知するストリーム。 */
    val events: SharedFlow<StreamerEvent>

    /** カメラ・マイクの初期化が完了し、プレビュー/配信を開始できる状態かどうか。 */
    fun isReady(): Boolean

    /** 現在RTMPサーバーへ配信中かどうか。 */
    fun isStreaming(): Boolean

    /**
     * カメラプレビューを開始する。配信を伴わず、映像確認のみを行いたい場合に用いる。
     *
     * @return 開始に成功した場合は [Result.success]。カメラ初期化失敗時は [Result.failure]。
     */
    fun startPreview(): Result<Unit>

    /** カメラプレビューを停止する。配信中の場合は無視される。 */
    fun stopPreview(): Result<Unit>

    /**
     * 指定された [StreamSession] のパラメータで映像/音声エンコーダを準備し、RTMPサーバーへ接続を開始する。
     *
     * 接続の成否や切断は [events] を通じて非同期に通知される。
     *
     * @param session 配信先URLおよびエンコードパラメータ。
     * @return エンコーダ準備・接続要求の送出に成功した場合は [Result.success]。
     */
    suspend fun startStream(session: StreamSession): Result<Unit>

    /**
     * 配信を終了し、RTMP接続を切断する。
     *
     * @return 正常に停止処理を実行できた場合は [Result.success]。
     */
    suspend fun stopStream(): Result<Unit>

    /** カメラ・マイク・エンコーダ等のネイティブリソースを解放する。エンジン破棄時に必ず呼び出すこと。 */
    fun release()
}
