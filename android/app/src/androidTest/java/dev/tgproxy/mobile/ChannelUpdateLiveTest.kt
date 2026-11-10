package dev.tgproxy.mobile

import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** long: 必须显式开启，真实更新会追加视频索引；不执行历史扫描、移除或重新登录。 */
@RunWith(AndroidJUnit4::class)
class ChannelUpdateLiveTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun toolbarUpdatesAllAndSingleButtonStillWorksAfterRecreation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTelegram") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TgApplication
        val readyDeadline = System.currentTimeMillis() + 20000
        while (!app.mediaReady() && System.currentTimeMillis() < readyDeadline) Thread.sleep(200)
        assertTrue("真实账号未就绪", app.mediaReady())
        assertFalse(app.busy)
        val channels = app.database.channels()
        assertTrue(channels.length() > 0)
        val ids = (0 until channels.length()).map { channels.getJSONObject(it).getLong("channel_id") }
        val count = app.database.count()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                views(activity.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_channels
            }
            scenario.recreate()
            Thread.sleep(1500)
            val beforeAll = app.scanQueue.snapshot.runID
            scenario.onActivity { activity ->
                views(activity.window.decorView).first { it.contentDescription == "更新全部频道" }.performClick()
            }
            awaitComplete(app, beforeAll)
            assertTrue(app.scanQueue.snapshot.all)
            assertEquals(ids, app.scanQueue.snapshot.tasks.map { it.channelID })
            assertTrue(app.scanQueue.snapshot.entries.filterKeys { it in ids }.values.all { it.phase == ScanPhase.DONE })
            Thread.sleep(1200)
            scenario.recreate()
            Thread.sleep(1500)
            val beforeSingle = app.scanQueue.snapshot.runID
            scenario.onActivity { activity ->
                views(activity.window.decorView).first { it.contentDescription?.toString()?.startsWith("更新频道：") == true }.performClick()
            }
            awaitComplete(app, beforeSingle)
            assertFalse(app.scanQueue.snapshot.all)
            assertEquals(listOf(ChannelScanTask(ids.first())), app.scanQueue.snapshot.tasks)
            assertEquals(ScanPhase.DONE, app.scanQueue.snapshot.entries[ids.first()]!!.phase)
            assertTrue("更新不应移除已有视频", app.database.count() >= count)
            assertEquals(ids.size, app.database.channels().length())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "live_update_channels=${ids.size} videos_before=$count videos_after=${app.database.count()}\n")
            })
        }
    }

    private fun awaitComplete(app: TgApplication, previous: Long) {
        val deadline = System.currentTimeMillis() + 130000
        while (System.currentTimeMillis() < deadline) {
            val state = app.scanQueue.snapshot
            if (state.runID > previous && !state.running && !app.busy) return
            Thread.sleep(200)
        }
        app.cancelScan()
        fail("更新未在时限内完成，已请求暂停")
    }
}
