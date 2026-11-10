package dev.tgproxy.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ScanService : Service() {
    private var running = false
    private val app get() = application as TgApplication

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            app.cancelScan()
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        val channelID = intent?.getLongExtra("channel", 0L) ?: 0L
        val refresh = intent?.getBooleanExtra("refresh", false) ?: false
        if (channelID <= 0L) { stopSelf(startId); return START_NOT_STICKY }
        app.prepareScan()
        createChannel()
        startForeground(NOTIFICATION_ID, notification(channelID))
        running = true
        app.perform({ core -> app.scanChannel(core, channelID, refresh) }) { value, error ->
            running = false
            app.scanMessage = if (value == "paused") "已暂停，下次从断点继续" else error ?: if (refresh) "新增视频已同步" else "历史视频索引完成"
            app.scanning = false
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "频道索引", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(channelID: Long): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, ScanService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("正在索引 Telegram 频道")
            .setContentText(app.database.channel(channelID)?.optString("title") ?: "频道")
            .setOngoing(true)
            .addAction(0, "暂停", cancel)
            .build()
    }

    // long: Android 对 dataSync 前台服务有运行时限；达到系统限制必须及时停止，游标已由每页事务保存。
    override fun onTimeout(startId: Int, fgsType: Int) {
        app.cancelScan()
        app.scanMessage = "后台索引已达系统时限，请回到频道页继续"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        if (running) app.cancelScan()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "channel-scan"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_CANCEL = "dev.tgproxy.mobile.action.CANCEL_SCAN"
    }
}
