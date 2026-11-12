package dev.tgproxy.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class DownloadService : Service() {
    private val downloads get() = (application as TgApplication).downloads
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val tick = object : Runnable {
        override fun run() {
            getSystemService(NotificationManager::class.java).notify(ID, notification())
            handler.postDelayed(this, 1500)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == PAUSE) {
            downloads.serviceStopped(); finished = true
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "视频下载", NotificationManager.IMPORTANCE_LOW))
        runCatching { startForeground(ID, notification()) }.onFailure {
            downloads.failedToStart(); stopSelf(); return START_NOT_STICKY
        }
        finished = false
        // long: 用户主动下载期间保持 CPU 可写入断点；系统时限、暂停和服务结束都释放锁。
        if (wakeLock?.isHeld != true) wakeLock = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "TGProxy:Downloads").apply { acquire(6 * 60 * 60 * 1000L) }
        downloads.start { finished = true; handler.removeCallbacks(tick); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        handler.removeCallbacks(tick); handler.post(tick)
        return START_NOT_STICKY
    }
    private fun notification(): android.app.Notification {
        val pause = PendingIntent.getService(this, 21, Intent(this, DownloadService::class.java).setAction(PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 22, Intent(this, DownloadsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val current = downloads.records.firstOrNull { it.state == DownloadState.RUNNING }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_download)
            .setContentTitle(current?.name ?: "视频下载")
            .setContentText(current?.let { "${MediaPresentation.size(it.bytes)} / ${MediaPresentation.size(it.size)}" } ?: "正在准备下载…")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .apply { if (current != null) setProgress(100, (current.bytes * 100 / current.size.coerceAtLeast(1)).toInt(), false) }
            .addAction(0, "全部暂停", pause).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        downloads.serviceStopped(); finished = true
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        if (!finished) downloads.serviceStopped()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL = "video-download"
        private const val ID = 4103
        private const val PAUSE = "dev.tgproxy.mobile.PAUSE_DOWNLOADS"
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
                .onFailure { (context.applicationContext as TgApplication).downloads.failedToStart() }
        }
    }
}
