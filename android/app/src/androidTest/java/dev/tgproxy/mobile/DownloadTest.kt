package dev.tgproxy.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import dev.tgproxy.core.mobilecore.Mobilecore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DownloadTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val app get() = context.applicationContext as TgApplication
    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (!condition()) { if (System.currentTimeMillis() > until) fail("下载检查超时"); Thread.sleep(50) }
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun video(size: Long, key: String = "900000000_1") = JSONObject().put("key", key).put("name", "下载验证片.mp4")
        .put("channel_id", 900000000).put("message_id", 1).put("size", size).put("mime", "video/mp4").put("document_id", 700)
    private fun capture(name: String) {
        val file = File(context.getExternalFilesDir(null), "validation/download/$name")
        file.parentFile!!.mkdirs()
        instrument.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }

    @Test fun localRangeDownloadPersistsAndRecoversAfterStoreReopen() {
        val name = "download-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, "download-test-${UUID.randomUUID()}")
        directory.mkdirs()
        val fixture = File(directory, "fixture.mp4")
        context.assets.open("validation.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        // long: 此 Engine 使用空会话，仅提供本机测试文件，不读取或修改现有 Telegram 授权。
        val core = Mobilecore.newEngine(0, "", "")
        val source = DownloadSource(core.mountValidationMedia(fixture.absolutePath), core.token())
        var id = ""
        try {
            DownloadStore(context, name, directory).use { store ->
                val item = store.add(1, video(fixture.length())); id = item.id
                assertEquals(id, store.add(1, video(fixture.length())).id)
                val cancel = DownloadCancellation()
                try {
                    DownloadTransfer(65536).copy(source, store.file(id, true), item.size, cancel) {
                        store.update(id, DownloadState.RUNNING, it)
                        if (it >= 65536) cancel.cancel()
                    }; fail("取消未停止")
                } catch (_: DownloadPaused) { }
                assertTrue(store.file(id, true).length() in 1 until item.size)
            }
            DownloadStore(context, name, directory).use { store ->
                store.recover()
                val item = store.get(id)!!
                assertEquals(DownloadState.PAUSED, item.state)
                assertEquals(store.file(id, true).length(), item.bytes)
                DownloadTransfer(65536).copy(source, store.file(id, true), item.size, DownloadCancellation()) {}
                assertArrayEquals(fixture.readBytes(), store.file(id, true).readBytes())
                assertTrue(store.file(id, true).renameTo(store.file(id)))
                // long: 模拟改名成功但数据库提交前中断，重建时识别完整文件，而非重复下载。
                store.recover()
                assertEquals(DownloadState.COMPLETE, store.get(id)!!.state)
                assertNotNull(store.completed(id))
                store.file(id).delete(); store.recover()
                assertEquals(DownloadState.FAILED, store.get(id)!!.state)
            }
        } finally { core.close(); context.deleteDatabase(name); directory.deleteRecursively() }
    }

    @Test fun queueContinuesAfterFailureAndDeletesOnlyRequestedDownload() {
        val name = "download-queue-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, "download-queue-${UUID.randomUUID()}")
        directory.mkdirs()
        val fixture = File(directory, "fixture.mp4")
        context.assets.open("validation.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val core = Mobilecore.newEngine(0, "", "")
        val source = DownloadSource(core.mountValidationMedia(fixture.absolutePath), core.token())
        val store = DownloadStore(context, name, directory)
        val downloads = Downloads(app, store) { item -> if (item.key == "900000000_1") error("fake failure") else source }
        try {
            await { downloads.ready }
            val first = store.add(1, video(fixture.length()))
            val second = store.add(1, video(fixture.length(), "900000000_2"))
            val finished = CountDownLatch(1)
            downloads.start { finished.countDown() }
            assertTrue(finished.await(15, TimeUnit.SECONDS))
            assertEquals(DownloadState.FAILED, store.get(first.id)!!.state)
            assertEquals(DownloadState.COMPLETE, store.get(second.id)!!.state)
            assertArrayEquals(fixture.readBytes(), store.completed(second.id)!!.readBytes())
            val deleted = CountDownLatch(1)
            downloads.remove(first.id) { assertNull(it); deleted.countDown() }
            assertTrue(deleted.await(3, TimeUnit.SECONDS))
            assertNull(store.get(first.id)); assertNotNull(store.completed(second.id))
        } finally { downloads.shutdown(); core.close(); store.close(); context.deleteDatabase(name); directory.deleteRecursively() }
    }

    @Test fun myDownloadsAndDetailEntryRenderAndCompletedFilePlaysLocally() {
        await { app.downloads.ready }
        val before = app.database.channels().toString(); val count = app.database.count()
        val bytes = context.assets.open("validation.mp4").use { it.readBytes() }
        val item = app.downloads.store.add(Long.MAX_VALUE, video(bytes.size.toLong()))
        try {
            app.downloads.store.file(item.id).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            app.downloads.store.update(item.id, DownloadState.COMPLETE, bytes.size.toLong())
            app.downloads.refresh()
            await { app.downloads.records.any { it.id == item.id } }
            val monitor = instrument.addMonitor(DownloadsActivity::class.java.name, null, false)
            try {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        views(activity.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_account
                        views(activity.window.decorView).first { it.contentDescription?.toString()?.startsWith("下载管理，") == true }.performClick()
                    }
                    val screen = monitor.waitForActivityWithTimeout(5000) as? DownloadsActivity
                    assertNotNull(screen)
                    await { var focused = false; instrument.runOnMainSync { focused = screen!!.hasWindowFocus() }; focused }
                    instrument.runOnMainSync {
                        views(screen!!.window.decorView).filterIsInstance<TextView>().first { it.text.toString().startsWith("已完成 ") }.performClick()
                    }
                    instrument.waitForIdleSync()
                    await { var visible = false; instrument.runOnMainSync { visible = views(screen!!.window.decorView).filterIsInstance<TextView>().any { it.text == "下载验证片.mp4" && it.isShown } }; visible }
                    capture("completed.png")
                    instrument.runOnMainSync { screen!!.finish() }
                }
            } finally { instrument.removeMonitor(monitor) }
            ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java).putExtra("key", "download:${item.id}")).use { scenario ->
                await {
                    var playing = false
                    scenario.onActivity { playing = PlaybackService.testPlayer()?.isPlaying == true }
                    playing
                }
                scenario.onActivity {
                    val player = PlaybackService.testPlayer()!!
                    assertEquals("本机下载 · 离线播放", player.mediaMetadata.artist.toString())
                    player.seekTo(3000)
                }
                await { var ready = false; scenario.onActivity { ready = PlaybackService.testPlayer()?.let { it.playbackState == Player.STATE_READY && it.currentPosition >= 3000 } == true }; ready }
                scenario.onActivity { PlaybackService.stopPlayback(it) }
            }
            val actual = app.database.videos(limit = 1).optJSONObject(0)
            if (actual != null) ActivityScenario.launch<VideoDetailActivity>(Intent(context, VideoDetailActivity::class.java).putExtra("key", actual.getString("key"))).use { scenario ->
                await { var present = false; scenario.onActivity { activity -> present = views(activity.window.decorView).any { it.contentDescription?.toString()?.startsWith("下载视频：") == true } }; present }
                capture("detail.png")
            }
            assertEquals(before, app.database.channels().toString()); assertEquals(count, app.database.count())
        } finally {
            instrument.runOnMainSync { PlaybackService.stopPlayback(context) }
            app.downloads.store.removeFiles(item.id); app.downloads.store.delete(item.id); app.downloads.refresh()
        }
    }
}
