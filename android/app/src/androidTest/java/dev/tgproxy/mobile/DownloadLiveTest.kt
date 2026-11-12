package dev.tgproxy.mobile

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** long: 显式启用才从真实频道取样，验证后仅删除本用例创建的下载，不改频道索引。 */
@RunWith(AndroidJUnit4::class)
class DownloadLiveTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(seconds: Int = 60, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000
        while (!condition()) { if (System.currentTimeMillis() > end) fail("真实下载等待超时"); Thread.sleep(100) }
    }
    @Test fun detailStartsForegroundDownloadAndResumeKeepsPartial() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTelegram") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as TgApplication
        await { app.downloads.ready && app.mediaReady() }
        val before = app.database.count()
        val rows = app.database.videos(limit = 1000)
        val video = (0 until rows.length()).map(rows::getJSONObject)
            .filter { it.optLong("size") > 0 && app.downloads.store.find(app.database.ownerID(), it) == null }
            .minByOrNull { it.optLong("size") }
        assumeTrue("需要尚未下载的真实资源", video != null)
        var id: String? = null
        try {
            ActivityScenario.launch<VideoDetailActivity>(Intent(context, VideoDetailActivity::class.java).putExtra("key", video!!.getString("key"))).use { scenario ->
                await { var found = false; scenario.onActivity { activity -> found = views(activity.window.decorView).any { it.contentDescription?.toString()?.startsWith("下载视频：") == true } }; found }
                scenario.onActivity { activity -> views(activity.window.decorView).first { it.contentDescription?.toString()?.startsWith("下载视频：") == true }.performClick() }
                await { app.downloads.store.find(app.database.ownerID(), video) != null }
                id = app.downloads.store.find(app.database.ownerID(), video)!!.id
                await {
                    val item = app.downloads.store.get(id!!)!!
                    assertFalse(item.error, item.state == DownloadState.FAILED)
                    item.bytes >= 65536 || item.state == DownloadState.COMPLETE
                }
                val paused = CountDownLatch(1)
                app.downloads.pause(id!!) { assertNull(it); paused.countDown() }
                assertTrue(paused.await(5, TimeUnit.SECONDS))
                Thread.sleep(1000)
                val first = app.downloads.store.get(id!!)!!
                if (first.state != DownloadState.COMPLETE) {
                    val resumed = CountDownLatch(1)
                    app.downloads.resume(id!!) { assertNull(it); resumed.countDown() }
                    assertTrue(resumed.await(5, TimeUnit.SECONDS))
                    await {
                        val item = app.downloads.store.get(id!!)!!
                        assertFalse(item.error, item.state == DownloadState.FAILED)
                        item.bytes > first.bytes || item.state == DownloadState.COMPLETE
                    }
                }
                val latest = app.downloads.store.get(id!!)!!
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "live_download_bytes=${latest.bytes} total=${latest.size} state=${latest.state}\n")
                })
            }
        } finally {
            id?.let { task ->
                val removed = CountDownLatch(1)
                app.downloads.remove(task) { removed.countDown() }
                assertTrue(removed.await(5, TimeUnit.SECONDS))
                await(10) { app.downloads.store.get(task) == null }
            }
            assertEquals(before, app.database.count())
        }
    }
}
