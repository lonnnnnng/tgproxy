package dev.tgproxy.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import dev.longvideo.controls.VideoControlsView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class NativeUiTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun targets(view: View) {
        val minimum = (48 * view.resources.displayMetrics.density).toInt()
        assertTrue("操作宽度不足48dp", view.width >= minimum)
        assertTrue("操作高度不足48dp", view.height >= minimum)
    }
    private fun fitsText(view: TextView) {
        val textLayout = view.layout ?: error("文字尚未布局")
        assertTrue("文字被固定高度裁切", view.height - view.compoundPaddingTop - view.compoundPaddingBottom >= textLayout.height)
    }
    private fun capture(name: String) {
        instrument.waitForIdleSync(); Thread.sleep(300)
        val mode = InstrumentationRegistry.getArguments().getString("uiMode", "normal")
        val directory = File(context.getExternalFilesDir(null), "validation/optimization-ui").apply { mkdirs() }
        instrument.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, "$name-$mode.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }

    @Test fun searchFiltersAndChannelControlsHaveFullTargets() {
        val app = context.applicationContext as TgApplication
        assumeTrue("需要保留的本机索引", app.database.count() > 50)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitValidation {
                var loaded = false
                scenario.onActivity { loaded = views(it.window.decorView).any { view -> view.contentDescription == "搜索视频名称" && view.height > 0 } }
                loaded
            }
            scenario.onActivity { activity ->
                views(activity.window.decorView).filterIsInstance<EditText>().first { it.contentDescription == "搜索视频名称" }.setText("no_matching_optimization_fixture")
            }
            awaitValidation {
                var loaded = false
                scenario.onActivity { loaded = views(it.window.decorView).filterIsInstance<MaterialButton>().any { button -> button.text == "清除搜索条件" && button.height > 0 } }
                loaded
            }
            scenario.onActivity { activity ->
                val all = views(activity.window.decorView)
                listOf("清空搜索", "视频排序").forEach { label -> targets(all.first { it.contentDescription == label }) }
                all.filterIsInstance<MaterialButton>().filter { it.text in listOf("全部视频", "最近播放", "全部频道") }.forEach { targets(it); fitsText(it) }
                fitsText(all.filterIsInstance<EditText>().first { it.contentDescription == "搜索视频名称" })
            }
            capture("library")
            scenario.onActivity { views(it.window.decorView).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_channels }
            awaitValidation {
                var loaded = false
                scenario.onActivity { loaded = views(it.window.decorView).filterIsInstance<ChannelUpdateButton>().size > 1 }
                loaded
            }
            scenario.onActivity { activity ->
                val all = views(activity.window.decorView)
                all.filterIsInstance<ChannelUpdateButton>().take(2).forEach(::targets)
                targets(all.first { it.contentDescription?.toString()?.endsWith("更多操作") == true })
            }
        }
    }

    @Test fun playerTextAndNavigationFitActualWindow() {
        val app = context.applicationContext as TgApplication
        awaitValidation { app.engine != null }
        try {
            ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java).putExtra("validation", true).putExtra("title", "大字体播放器验证")).use { scenario ->
                awaitValidation {
                    var loaded = false
                    scenario.onActivity { loaded = it.testPlayer()?.playbackState == Player.STATE_READY }
                    loaded
                }
                scenario.onActivity { activity ->
                    val controls = views(activity.window.decorView).filterIsInstance<VideoControlsView>().single()
                    controls.title = "大字体与窄窗口 · 播放控制验证"; controls.source = "合成验证来源"
                    controls.showControls(); activity.testPlayer()!!.pause()
                }
                instrument.waitForIdleSync(); Thread.sleep(300)
                scenario.onActivity { activity ->
                    val ids = listOf(dev.longvideo.controls.R.id.pc_title, dev.longvideo.controls.R.id.pc_source,
                        androidx.media3.ui.R.id.exo_position, androidx.media3.ui.R.id.exo_duration)
                    ids.forEach { fitsText(activity.findViewById(it)) }
                    val center = activity.findViewById<View>(androidx.media3.ui.R.id.exo_center_controls)
                    val rect = Rect(); assertTrue(center.getGlobalVisibleRect(rect))
                    assertEquals("切集与播放控件不能越过窗口边缘", center.width, rect.width())
                    for (id in listOf(dev.longvideo.controls.R.id.pc_previous, dev.longvideo.controls.R.id.pc_next,
                        androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_play_pause, androidx.media3.ui.R.id.exo_ffwd)) targets(activity.findViewById(id))
                }
                capture("player")
                scenario.onActivity { views(it.window.decorView).filterIsInstance<VideoControlsView>().single().setLocked(true) }
                instrument.waitForIdleSync()
                scenario.onActivity { activity ->
                    val unlock = views(activity.window.decorView).filterIsInstance<TextView>().first { it.text == context.getString(dev.longvideo.controls.R.string.pc_unlock) }
                    targets(unlock); fitsText(unlock)
                }
            }
        } finally { instrument.runOnMainSync { PlaybackService.stopPlayback(context) } }
    }

    @Test fun downloadsAndExportResultStayReadableAfterRecreation() {
        ValidationDownloadFixture("长文件名与大字体导出状态验证").use { fixture ->
            fixture.failedResult()
            ActivityScenario.launch<DownloadsActivity>(Intent(context, DownloadsActivity::class.java).putExtra("complete", true)).use { scenario ->
                awaitValidation {
                    var loaded = false
                    scenario.onActivity { loaded = views(it.window.decorView).any { view -> view.contentDescription == "下载选项：${fixture.filename}" && view.height > 0 } }
                    loaded
                }
                scenario.recreate()
                awaitValidation {
                    var loaded = false
                    scenario.onActivity { loaded = views(it.window.decorView).filterIsInstance<MaterialButton>().any { button -> button.text == "重新选位置" && button.height > 0 } }
                    loaded
                }
                scenario.onActivity { activity ->
                    val all = views(activity.window.decorView)
                    targets(all.first { it.contentDescription == "下载选项：${fixture.filename}" })
                    all.filterIsInstance<MaterialButton>().filter { it.isShown && it.height > 0 }.forEach { targets(it); fitsText(it) }
                    assertTrue(all.filterIsInstance<TextView>().any { it.text.toString().contains("验证副本未完成") })
                }
                capture("downloads")
            }
        }
    }

    @Test fun loginDoneUsesValidationAndDisabledSubmissionWithoutChangingAccount() {
        val app = context.applicationContext as TgApplication
        awaitValidation { app.engine != null && !app.busy }
        val before = app.database.count()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val bodyField = MainActivity::class.java.getDeclaredField("body").apply { isAccessible = true }
                val body = bodyField.get(activity) as LinearLayout
                val render = MainActivity::class.java.getDeclaredMethod("loginPage", String::class.java).apply { isAccessible = true }
                // long: 只渲染暂时的表单检查空输入与禁用状态，不提交验证码/密码，也不清真实授权。
                for ((phase, label) in listOf("code" to "确认验证码", "password" to "完成登录")) {
                    body.removeAllViews(); render.invoke(activity, phase)
                    val all = views(body)
                    val field = all.filterIsInstance<EditText>().single()
                    val submit = all.filterIsInstance<MaterialButton>().first { it.text == label }
                    assertEquals(EditorInfo.IME_ACTION_DONE, field.imeOptions)
                    field.onEditorAction(EditorInfo.IME_ACTION_DONE); assertNotNull(field.error); assertFalse(app.busy)
                    submit.isEnabled = false; field.setText(if (phase == "code") "12345" else " test password ")
                    val draft = field.text.toString(); field.onEditorAction(EditorInfo.IME_ACTION_DONE)
                    assertEquals(draft, field.text.toString()); assertFalse(app.busy)
                    field.text.clear()
                }
            }
        }
        assertEquals(before, app.database.count())
    }
}
