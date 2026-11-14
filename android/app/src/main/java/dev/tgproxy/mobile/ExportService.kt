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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class ExportService : Service() {
    private val exports get() = (application as TgApplication).exports
    private val handler = Handler(Looper.getMainLooper())
    private var lease = 0L
    private var finished = false
    private var latestStartID = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private val tick = object : Runnable {
        override fun run() { getSystemService(NotificationManager::class.java).notify(ID, notification()); handler.postDelayed(this, 1000) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartID = startId
        if (intent?.action == CANCEL) {
            intent.getStringExtra("export")?.let { exports.cancel(it) }
            if (!exports.running) stopSelf(startId)
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "视频另存", NotificationManager.IMPORTANCE_LOW))
        runCatching { startForeground(ID, notification()) }.onFailure { exports.failedToStart(); stopSelf(startId); return START_NOT_STICKY }
        finished = false
        // long: 用户主动另存期间保持复制可运行，系统时限或服务结束后释放，不能承诺后台无限持续。
        if (wakeLock?.isHeld != true) wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TGProxy:Exports").apply { acquire(6 * 60 * 60 * 1000L) }
        lease = exports.start {
            finished = true; handler.removeCallbacks(tick); releaseWakeLock()
            // long: 通知取消也是一次新启动，空闲回调用最新编号结束服务，不能让旧编号留下唤醒锁。
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(latestStartID)
        }
        handler.removeCallbacks(tick); handler.post(tick)
        return START_NOT_STICKY
    }
    private fun notification(): android.app.Notification {
        val item = exports.latest
        val open = PendingIntent.getActivity(this, 31, Intent(this, DownloadsActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 32, Intent(this, ExportService::class.java).setAction(CANCEL).putExtra("export", item?.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_download).setContentTitle("另存视频")
            .setContentText(item?.let { it.name + " · " + MediaPresentation.size(it.bytes) + " / " + MediaPresentation.size(it.size) } ?: "正在准备另存…")
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).apply {
                if (item != null) setProgress(100, (item.bytes.toDouble() / item.size.coerceAtLeast(1) * 100).toInt().coerceIn(0, 100), false)
            }.addAction(0, "取消另存", cancel).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        exports.serviceStopped(lease, "系统已停止后台另存，请重新选择位置"); finished = true
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null); releaseWakeLock()
        if (!finished) exports.serviceStopped(lease)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    companion object {
        private const val CHANNEL = "video-export"
        private const val ID = 4104
        private const val CANCEL = "dev.tgproxy.mobile.CANCEL_EXPORT"
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java)) }
                .onFailure { (context.applicationContext as TgApplication).exports.failedToStart() }
        }
    }
}
