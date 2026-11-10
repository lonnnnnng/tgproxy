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
        if (app.busy || app.scanning) { stopSelf(startId); return START_NOT_STICKY }
        val channelID = intent?.getLongExtra("channel", 0L) ?: 0L
        val refresh = intent?.getBooleanExtra("refresh", false) ?: false
        val recheck = intent?.getBooleanExtra("recheck", false) ?: false
        val all = intent?.getBooleanExtra("all", false) ?: false
        val ids = if (all) app.database.channels().let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it).getLong("channel_id") }
        } else listOf(channelID)
        val mode = if (recheck) ScanMode.RECHECK else if (refresh || all) ScanMode.UPDATE else ScanMode.HISTORY
        val tasks = ids.map { ChannelScanTask(it, mode) }
        if (!app.scanQueue.begin(tasks, all)) { stopSelf(startId); return START_NOT_STICKY }
        // long: 整批只重置一次取消标记，频道切换时必须保留用户刚刚发出的暂停。
        app.prepareScan()
        try {
            createChannel()
            startForeground(NOTIFICATION_ID, notification(if (all) "正在依次更新 ${ids.size} 个频道" else "正在处理所选频道"))
        } catch (_: Exception) {
            app.scanQueue.execute { _, _ -> error("后台任务未能启动，请回到前台重试") }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running = true
        app.perform({ core -> app.executeScanQueue(core); "" }) { _, error ->
            running = false
            // long: 内核未就绪等入口失败也要结束等待态，不能留下永远转动的按钮。
            if (app.scanQueue.snapshot.running) app.scanQueue.execute { _, _ -> kotlin.error(error ?: "更新未启动，请重试") }
            app.scanMessage = error ?: app.scanQueue.snapshot.summary
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

    private fun notification(description: String): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, ScanService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java)
            .putExtra("open_channels", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("正在索引 Telegram 频道")
            .setContentText(description)
            .setContentIntent(open)
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
