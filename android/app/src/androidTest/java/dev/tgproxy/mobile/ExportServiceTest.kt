package dev.tgproxy.mobile

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ExportServiceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private class Gate : ExportTransfer(0) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val once = AtomicBoolean(true)
        override fun copy(input: InputStream, output: OutputStream, size: Long, cancellation: ExportCancellation, progress: (Long) -> Unit) {
            super.copy(input, output, size, cancellation) { bytes ->
                progress(bytes)
                if (bytes > 0 && once.getAndSet(false)) { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)); cancellation.check() }
            }
        }
    }
    @Suppress("DEPRECATION")
    private fun serviceRunning() = context.getSystemService(ActivityManager::class.java).getRunningServices(20)
        .any { it.service.className == ExportService::class.java.name }
    private fun power() = ParcelFileDescriptor.AutoCloseInputStream(instrument.uiAutomation.executeShellCommand("dumpsys power")).use { it.bufferedReader().readText() }
    private fun activeLocks(): String {
        val output = power()
        val start = output.indexOf("Wake Locks:"); val end = output.indexOf("Suspend Blockers:", start)
        check(start >= 0 && end > start)
        // long: 系统日志保留已释放锁的历史，判断泄漏必须读取当前活动锁段。
        return output.substring(start, end)
    }

    @Test fun notificationCancelStopsServiceAndReleasesWakeLock() {
        ValidationDownloadFixture("通知取消导出验收").use { fixture ->
            val transfer = Exports::class.java.getDeclaredField("transfer").apply { isAccessible = true }
            val original = transfer.get(fixture.app.exports)
            val gate = Gate()
            transfer.set(fixture.app.exports, gate)
            try {
                ActivityScenario.launch<DownloadsActivity>(Intent(context, DownloadsActivity::class.java).putExtra("complete", true)).use {
                    val queued = CountDownLatch(1); val error = AtomicReference<String?>()
                    fixture.app.exports.enqueue(fixture.source.id, ExportTestProvider.uri(), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) {
                        error.set(it); queued.countDown()
                    }
                    assertTrue(queued.await(15, TimeUnit.SECONDS)); assertNull(error.get())
                    assertTrue(gate.entered.await(15, TimeUnit.SECONDS)); assertTrue(serviceRunning())
                    assertTrue("前台复制应持有自己的唤醒锁", activeLocks().contains("TGProxy:Exports"))
                    val notifications = context.getSystemService(NotificationManager::class.java)
                    awaitValidation { notifications.activeNotifications.any { it.id == 4104 } }
                    // long: 发送实际通知的取消 PendingIntent，使服务收到第二个 startId，覆盖旧编号无法停止的窗口。
                    notifications.activeNotifications.first { it.id == 4104 }.notification.actions.first { it.title.toString() == "取消另存" }.actionIntent.send()
                    awaitValidation { fixture.results().single().state == ExportState.CANCELLING }
                    gate.release.countDown()
                    awaitValidation { !fixture.app.exports.running && !serviceRunning() }
                    assertEquals(ExportState.CANCELLED, fixture.results().single().state)
                    assertFalse("服务停止后不能遗留六小时唤醒锁", activeLocks().contains("TGProxy:Exports"))
                }
            } finally {
                gate.release.countDown()
                context.stopService(Intent(context, ExportService::class.java))
                awaitValidation { !fixture.app.exports.running }
                transfer.set(fixture.app.exports, original)
            }
        }
    }
}
