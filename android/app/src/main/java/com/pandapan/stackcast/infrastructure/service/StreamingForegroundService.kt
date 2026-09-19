package com.pandapan.stackcast.infrastructure.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pandapan.stackcast.MainActivity
import com.pandapan.stackcast.R
import com.pandapan.stackcast.domain.ports.RtmpStreamerPort
import com.pandapan.stackcast.infrastructure.rtmp.RtmpEncoderEngine

/**
 * RTMP配信のライフサイクルを管理するフォアグラウンドサービス。
 *
 * カメラ・マイクの取得からエンコード・RTMP送出までを行う [RtmpEncoderEngine] を
 * 本サービスのライフサイクルで保持することで、[com.pandapan.stackcast.presentation.streaming.StreamingFragment]
 * の画面が破棄されて（画面ロック・ホーム画面遷移等）も配信が継続するようにする。
 *
 * 配信中は [NotificationCompat] による常時通知を表示し `START_STICKY` を返すことで、
 * OSにより不用意にプロセスが終了された場合の復帰確率を高める。
 * ただし、Android 12+ (API 31+) 以降はバックグラウンドからの起動制限やフォアグラウンドサービスの
 * 種別宣言 (`camera`/`microphone`) が必須であることに注意すること（`AndroidManifest.xml` 側で対応済み）。
 */
class StreamingForegroundService : Service() {

    /** Fragment からサービスへバインドするための [Binder] 実装。 */
    inner class LocalBinder : Binder() {
        /** このサービスが保持する [RtmpStreamerPort] を取得する。 */
        fun getService(): StreamingForegroundService = this@StreamingForegroundService
    }

    private val binder = LocalBinder()

    /** サービスの生存期間全体で保持する配信エンジン。バインドしているFragmentが無くても破棄されない。 */
    private var encoderEngine: RtmpEncoderEngine? = null

    /** 画面消灯中もCPUを止めずにエンコード・送出処理を継続するための部分的WakeLock。 */
    private var wakeLock: PowerManager.WakeLock? = null

    /** 配信エンジンのポート。未初期化の場合はここで生成する。 */
    val rtmpStreamerPort: RtmpStreamerPort
        get() = encoderEngine ?: RtmpEncoderEngine(applicationContext).also { encoderEngine = it }

    /** [RtmpEncoderEngine] 固有の操作（プレビューView接続等）を行うための実体アクセサ。 */
    val encoder: RtmpEncoderEngine
        get() = rtmpStreamerPort as RtmpEncoderEngine

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        acquireWakeLock()
        // OSにより強制終了された場合もインテント無しで再生成し、配信継続を試みる。
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        encoderEngine?.release()
        encoderEngine = null
        super.onDestroy()
    }

    /** 画面ロック中もエンコード処理が停止しないよう、部分的WakeLockを取得する。 */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:StreamingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(MAX_WAKE_LOCK_DURATION_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** 通知チャンネルを作成する (Android 8.0 / API 26 以降で必須)。 */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.streaming_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    /** 配信中であることを示す常時通知を表示してフォアグラウンド化する。 */
    private fun startForegroundWithNotification() {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.streaming_notification_title))
            .setContentText(getString(R.string.streaming_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "stackcast_streaming_channel"
        private const val NOTIFICATION_ID = 1001

        /** WakeLockの最大保持時間 (24時間稼働を想定した安全上限。定期的に再取得されるため実運用上は十分な値)。 */
        private const val MAX_WAKE_LOCK_DURATION_MILLIS = 24L * 60L * 60L * 1000L

        /**
         * サービスをフォアグラウンドで起動する。
         *
         * @param context 起動元のコンテキスト。
         */
        fun start(context: Context) {
            val intent = Intent(context, StreamingForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * サービスを停止する。
         *
         * @param context 停止元のコンテキスト。
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, StreamingForegroundService::class.java))
        }
    }
}
