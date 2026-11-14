package dev.tgproxy.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.longvideo.controls.PlaybackPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlaybackQueueTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val app get() = context.applicationContext as TgApplication
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 15000
        while (!check()) { if (System.currentTimeMillis() > until) fail("播放队列检查超时"); Thread.sleep(30) }
    }
    private fun service() = PlaybackService::class.java.getDeclaredField("active").apply { isAccessible = true }.get(null) as PlaybackService
    private fun state(check: (Player) -> Boolean): Boolean {
        var value = false
        instrument.runOnMainSync { value = PlaybackService.testPlayer()?.let(check) == true }
        return value
    }
    private fun withFixtures(channel: Long, count: Int, check: (List<DownloadRecord>) -> Unit) {
        await { app.downloads.ready }
        val before = app.downloads.store.list().size
        val preferences = PlaybackPreferences(context); val continuous = preferences.continuous
        val bytes = context.assets.open("validation.mp4").use { it.readBytes() }
        val items = (1..count).map { id ->
            app.downloads.store.add(Long.MAX_VALUE, JSONObject().put("key", "${channel}_$id").put("channel_id", channel).put("message_id", id)
                .put("document_id", channel + id).put("name", "边界回归0$id.mp4").put("date", id).put("size", bytes.size).put("mime", "video/mp4")).also {
                app.downloads.store.file(it.id).writeBytes(bytes); app.downloads.store.update(it.id, DownloadState.COMPLETE, bytes.size.toLong())
            }
        }
        try {
            preferences.continuous = false; app.downloads.refresh()
            await { items.all { app.downloads.record(it.id)?.state == DownloadState.COMPLETE } }
            check(items)
        } finally {
            instrument.runOnMainSync { PlaybackService.stopPlayback(context) }
            preferences.continuous = continuous
            items.forEach { app.downloads.store.removeFiles(it.id); app.downloads.store.delete(it.id) }
            app.downloads.refresh(); assertEquals(before, app.downloads.store.list().size)
        }
    }
    private class GateExecutor(private val delegate: ExecutorService) : AbstractExecutorService() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        private val once = AtomicBoolean(true)
        override fun execute(command: Runnable) = delegate.execute {
            if (once.getAndSet(false)) { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
            command.run()
        }
        override fun shutdown() { release.countDown(); delegate.shutdown() }
        override fun shutdownNow(): MutableList<Runnable> { release.countDown(); return delegate.shutdownNow() }
        override fun isShutdown() = delegate.isShutdown
        override fun isTerminated() = delegate.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = delegate.awaitTermination(timeout, unit)
    }
    @Test fun nextPreviousContinuousAndBackgroundRebindFollowCurrentMedia() {
        await { app.downloads.ready }
        val preferences = PlaybackPreferences(context)
        val previousContinuous = preferences.continuous
        val before = app.downloads.store.list().size
        val bytes = context.assets.open("validation.mp4").use { it.readBytes() }
        val items = (1..3).map { id ->
            val row = JSONObject().put("key", "990000500_" + id).put("channel_id", 990000500).put("message_id", id)
                .put("document_id", id).put("name", "选片验证0" + id + ".mp4").put("date", id).put("size", bytes.size).put("mime", "video/mp4")
            app.downloads.store.add(Long.MAX_VALUE, row).also { item ->
                app.downloads.store.file(item.id).writeBytes(bytes); app.downloads.store.update(item.id, DownloadState.COMPLETE, bytes.size.toLong())
            }
        }
        try {
            preferences.continuous = false
            val revision = app.downloads.revision; app.downloads.refresh(); await { app.downloads.revision != revision }
            ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java).putExtra("key", "download:" + items[0].id)
                .putExtra("queue_offline", true).putExtra("queue_sort", "name")).use { scenario ->
                await { var ready = false; scenario.onActivity { activity ->
                    ready = PlaybackService.testPlayer()?.isPlaying == true && views(activity.window.decorView).firstOrNull { it.id == dev.longvideo.controls.R.id.pc_next }?.isEnabled == true
                }; ready }
                scenario.onActivity { activity -> views(activity.window.decorView).first { it.id == dev.longvideo.controls.R.id.pc_next }.performClick() }
                await { var current = false; scenario.onActivity { activity ->
                    current = PlaybackService.testPlayer()?.let { it.currentMediaItem?.mediaId == "download:" + items[1].id && it.isPlaying } == true &&
                        views(activity.window.decorView).first { it.id == dev.longvideo.controls.R.id.pc_previous }.isEnabled
                }; current }
                scenario.onActivity { activity -> views(activity.window.decorView).first { it.id == dev.longvideo.controls.R.id.pc_previous }.performClick() }
                await { var current = false; scenario.onActivity { current = PlaybackService.testPlayer()?.let { player -> player.currentMediaItem?.mediaId == "download:" + items[0].id && player.isPlaying } == true }; current }
                scenario.onActivity {
                    preferences.continuous = true; PlaybackService.applyContinuousPreference()
                    val player = PlaybackService.testPlayer()!!
                    assertTrue(player.duration > 1000); player.seekTo(player.duration - 150); player.play()
                }
                await { var current = false; scenario.onActivity { current = PlaybackService.testPlayer()?.let { it.currentMediaItem?.mediaId == "download:" + items[1].id && it.isPlaying } == true }; current }
                scenario.moveToState(Lifecycle.State.CREATED)
                Thread.sleep(300)
                scenario.moveToState(Lifecycle.State.RESUMED)
                await { var current = false; scenario.onActivity { current = it.testPlayer()?.let { player -> player.currentMediaItem?.mediaId == "download:" + items[1].id && player.isPlaying } == true }; current }
                scenario.onActivity { PlaybackService.stopPlayback(it) }
            }
        } finally {
            instrument.runOnMainSync { PlaybackService.stopPlayback(context) }
            preferences.continuous = previousContinuous
            items.forEach { app.downloads.store.removeFiles(it.id); app.downloads.store.delete(it.id) }
            app.downloads.refresh()
            assertEquals(before, app.downloads.store.list().size)
        }
    }

    @Test fun selectionCompletedAfterUnbindDoesNotReopenOldTargetAfterContinuousAdvance() = withFixtures(990000510, 3) { items ->
        ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java).putExtra("key", "download:" + items[0].id)
            .putExtra("queue_offline", true).putExtra("queue_sort", "name")).use { scenario ->
            await { state { it.currentMediaItem?.mediaId == "download:" + items[0].id && it.isPlaying } }
            val service = service()
            val field = PlaybackService::class.java.getDeclaredField("mediaWorker").apply { isAccessible = true }
            val original = field.get(service) as ExecutorService
            val gate = GateExecutor(original)
            var selection: String? = null
            try {
                scenario.onActivity { activity ->
                    field.set(service, gate)
                    // long: 固定解析未完成窗口再退后台，不能依赖线程竞速碰巧丢掉 Activity 回调。
                    PlayerActivity::class.java.getDeclaredMethod("openSelected", String::class.java, String::class.java).apply { isAccessible = true }
                        .invoke(activity, "download:" + items[1].id, "待完成选片")
                    selection = PlayerActivity::class.java.getDeclaredField("pendingSelectionID").apply { isAccessible = true }.get(activity) as String
                }
                assertTrue(gate.entered.await(15, TimeUnit.SECONDS))
                scenario.moveToState(Lifecycle.State.CREATED); gate.release.countDown()
                await { state { it.currentMediaItem?.mediaId == "download:" + items[1].id && it.isPlaying } }
                assertTrue(PlaybackService.selectionFulfilled(selection))
                instrument.runOnMainSync {
                    PlaybackPreferences(context).continuous = true
                    val player = PlaybackService.testPlayer()!!; player.seekTo(player.duration - 150); player.play()
                }
                await { state { it.currentMediaItem?.mediaId == "download:" + items[2].id && it.isPlaying } }
                val generation = PlaybackService::class.java.getDeclaredField("openGeneration").apply { isAccessible = true }
                var before = 0L; instrument.runOnMainSync { before = generation.getLong(service) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                await { var ready = false; scenario.onActivity { ready = it.testPlayer()?.playbackState == Player.STATE_READY }; ready }
                Thread.sleep(500)
                scenario.onActivity {
                    assertEquals("后台切到下一条后应继续当前视频", "download:" + items[2].id, it.testPlayer()!!.currentMediaItem!!.mediaId)
                    assertEquals("重绑不得重发已经兑现的选片", before, generation.getLong(service))
                }
            } finally { gate.release.countDown(); instrument.runOnMainSync { field.set(service, original) } }
        }
    }

    @Test fun endedRangeSourceSwitchesToSameLocalDocumentFromBeginning() = withFixtures(990000520, 1) { items ->
        await { app.engine != null }
        ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java).putExtra("key", "download:" + items[0].id)
            .putExtra("queue_offline", true)).use { scenario ->
            await { state { it.isPlaying } }
            scenario.onActivity {
                val service = service()
                val http = PlaybackService::class.java.getDeclaredField("http").apply { isAccessible = true }.get(service) as DefaultHttpDataSource.Factory
                http.setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${app.engine!!.token()}"))
                val player = PlaybackService.testPlayer()!!
                // long: 用本机 Range 的合成素材模拟在线来源，保留下载的账号/document身份，检查片尾切来源。
                val online = player.currentMediaItem!!.buildUpon().setMediaId(items[0].key).setUri(app.validationMedia()).build()
                player.setMediaItem(online); player.prepare(); player.play()
            }
            await { state { it.currentMediaItem?.mediaId == items[0].key && it.playbackState == Player.STATE_READY } }
            instrument.runOnMainSync { val player = PlaybackService.testPlayer()!!; player.seekTo(player.duration - 150); player.play() }
            await { state { it.playbackState == Player.STATE_ENDED } }
            var pending: com.google.common.util.concurrent.ListenableFuture<SessionResult>? = null
            scenario.onActivity { activity ->
                pending = (activity.testPlayer() as MediaController).sendCustomCommand(PlaybackService.OPEN, Bundle().apply {
                    putString("key", "download:" + items[0].id); putBoolean("queue_offline", true)
                })
            }
            assertEquals(SessionResult.RESULT_SUCCESS, pending!!.get(15, TimeUnit.SECONDS).resultCode)
            await { state { it.currentMediaItem?.mediaId == "download:" + items[0].id && it.isPlaying } }
            instrument.runOnMainSync { assertTrue("已看完同文档切本机来源应从头播放", PlaybackService.testPlayer()!!.currentPosition < 1000) }
        }
    }
}
