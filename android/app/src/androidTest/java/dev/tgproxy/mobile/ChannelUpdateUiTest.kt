package dev.tgproxy.mobile

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ChannelUpdateUiTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun buttons(activity: MainActivity) = views(activity.window.decorView).filterIsInstance<ChannelUpdateButton>()
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "validation/channel-update/$name")
        file.parentFile!!.mkdirs()
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }

    @Test fun inlineFeedbackSurvivesRecreationAndPausesWithoutChangingIndex() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as TgApplication
        assumeTrue(!app.busy && !app.scanning)
        val channels = app.database.channels().toString()
        val count = app.database.count()
        val first = app.database.channels().optJSONObject(0)
        assumeTrue("需要已有频道，只读取真实索引，不运行网络扫描", first != null)
        val id = first!!.getLong("channel_id")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var worker: Thread? = null
        app.scanQueue.begin(listOf(ChannelScanTask(id)), all = true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    views(activity.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_channels
                }
                Thread.sleep(1200)
                scenario.onActivity { activity ->
                    val row = buttons(activity).first { it.contentDescription.startsWith("等待更新：") }
                    assertFalse(row.isSpinning)
                    assertFalse(row.isEnabled)
                    assertNull(activity.activeSheetContent)
                }
                // long: 使用生产队列发布内存状态，执行器只等待，不访问 Telegram 或写入真实数据库。
                worker = thread {
                    app.scanQueue.execute { _, report ->
                        report("更新中 · 新增 0 个", count); started.countDown()
                        check(release.await(30, TimeUnit.SECONDS))
                        ChannelScanResult("已是最新", count)
                    }
                }
                assertTrue(started.await(3, TimeUnit.SECONDS))
                Thread.sleep(1200)
                var originalTop = 0
                scenario.onActivity { activity ->
                    val row = buttons(activity).first { it.contentDescription.startsWith("暂停更新：") }
                    assertTrue(row.isSpinning)
                    originalTop = row.top
                    assertTrue(views(activity.window.decorView).filterIsInstance<LinearProgressIndicator>().none { it.isShown })
                    assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().none { it.text == "暂停扫描" })
                }
                capture("running.png")
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity { activity -> assertTrue(buttons(activity).none { it.isSpinning }) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.recreate()
                Thread.sleep(1200)
                scenario.onActivity { activity ->
                    val row = buttons(activity).first { it.contentDescription.startsWith("暂停更新：") }
                    assertTrue(row.isSpinning)
                    assertEquals(originalTop, row.top)
                    row.performClick()
                }
                release.countDown(); worker!!.join(3000)
                Thread.sleep(1200)
                scenario.onActivity { activity ->
                    val row = buttons(activity).first { it.contentDescription.startsWith("继续更新：") }
                    assertFalse(row.isSpinning); assertTrue(row.isEnabled)
                    assertTrue(buttons(activity).any { it.contentDescription == "更新全部频道" })
                }
                capture("paused.png")
                assertTrue(app.scanQueue.begin(listOf(ChannelScanTask(id))))
                app.scanQueue.execute { _, _ -> error("示例网络错误，请重试") }
                Thread.sleep(1200)
                scenario.onActivity { activity ->
                    assertTrue(buttons(activity).first { it.contentDescription.startsWith("重试更新：") }.isEnabled)
                    assertTrue(buttons(activity).none { it.isSpinning })
                }
                capture("failed.png")
            }
        } finally {
            app.scanQueue.cancel(); release.countDown(); worker?.join(3000)
            if (app.scanQueue.snapshot.running) app.scanQueue.execute { _, _ -> ChannelScanResult("") }
            app.scanQueue.clear(); app.prepareScan()
            assertEquals(channels, app.database.channels().toString())
            assertEquals(count, app.database.count())
        }
    }
}
