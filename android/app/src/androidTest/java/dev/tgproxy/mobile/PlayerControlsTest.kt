package dev.tgproxy.mobile

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.longvideo.controls.PlaybackPreferences
import dev.longvideo.controls.VideoControlsView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class PlayerControlsTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val preferences get() = PlaybackPreferences(context)
    private var oldSpeed = 1f
    private var oldAspect = 0
    private var oldVolume = 0
    private val audio get() = context.getSystemService(AudioManager::class.java)

    @Before fun setup() {
        oldSpeed = preferences.speed; oldAspect = preferences.aspect
        oldVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        preferences.speed = 1f; preferences.aspect = 0
        await { (context.applicationContext as TgApplication).engine != null }
    }

    @After fun cleanup() {
        instrument.runOnMainSync { PlaybackService.stopPlayback(context) }
        preferences.speed = oldSpeed; preferences.aspect = oldAspect
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, oldVolume, 0)
    }

    private fun launch() = ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java)
        .putExtra("validation", true).putExtra("title", "控制器验证 · 本地测试片"))

    private fun controls(activity: PlayerActivity): VideoControlsView {
        fun search(view: View): VideoControlsView? {
            if (view is VideoControlsView) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) search(view.getChildAt(i))?.let { return it }
            return null
        }
        return search(activity.window.decorView)!!
    }

    private fun await(timeout: Long = 15000, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeout
        while (!condition()) { if (SystemClock.uptimeMillis() >= until) fail("condition timed out"); Thread.sleep(50) }
    }

    private fun ready(scenario: ActivityScenario<PlayerActivity>) = await {
        var ready = false
        scenario.onActivity { ready = it.testPlayer()?.playbackState == Player.STATE_READY }
        ready
    }

    private fun speed(scenario: ActivityScenario<PlayerActivity>): Float {
        var speed = -1f
        scenario.onActivity { speed = it.testPlayer()?.playbackParameters?.speed ?: -1f }
        return speed
    }

    private fun point(scenario: ActivityScenario<PlayerActivity>, x: Float, y: Float): Pair<Float, Float> {
        var point = 0f to 0f
        scenario.onActivity {
            val view = controls(it)
            val location = IntArray(2); view.getLocationOnScreen(location)
            point = location[0] + view.width * x to location[1] + view.height * y
        }
        return point
    }

    private fun touch(action: Int, point: Pair<Float, Float>, down: Long) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
        instrument.sendPointerSync(event)
        event.recycle()
    }

    private fun tap(point: Pair<Float, Float>) {
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, point, down)
        Thread.sleep(30)
        touch(MotionEvent.ACTION_UP, point, down)
    }

    private fun clickNode(text: String) {
        var target: AccessibilityNodeInfo? = null
        var lastScroll = 0L
        fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable && node.isVisibleToUser) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { child -> scrollable(child)?.let { return it } }
            return null
        }
        // long: ListView 行可能不标记 clickable，但会响应真实触摸；按可见文字的位置验证用户操作。
        await {
            val root = instrument.uiAutomation.rootInActiveWindow
            val nodes = root?.findAccessibilityNodeInfosByText(text).orEmpty()
            target = nodes.firstOrNull { it.isVisibleToUser && it.isEnabled }
            // long: 大字体或横屏下菜单后半段需要滚动，不能把未显示的选项视为功能缺失。
            if (target == null && root != null && SystemClock.uptimeMillis() - lastScroll > 600) {
                scrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                lastScroll = SystemClock.uptimeMillis()
            }
            target != null
        }
        val bounds = Rect()
        target!!.getBoundsInScreen(bounds)
        assertFalse(bounds.isEmpty)
        tap(bounds.exactCenterX() to bounds.exactCenterY())
        instrument.waitForIdleSync()
    }

    @Test fun speedMenuBoostCancelAndBackgroundRestore() {
        launch().use { scenario ->
            ready(scenario)
            scenario.onActivity {
                controls(it).showControls()
                it.findViewById<View>(androidx.media3.ui.R.id.exo_playback_speed).performClick()
            }
            clickNode("1.5")
            await { speed(scenario) == 1.5f }
            assertEquals(1.5f, preferences.speed, 0f)
            val point = point(scenario, 0.25f, 0.3f)
            for (end in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                val down = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, point, down)
                await { speed(scenario) == 2f }
                assertEquals("临时倍速不能覆盖偏好", 1.5f, preferences.speed, 0f)
                touch(end, point, down)
                await { speed(scenario) == 1.5f }
            }
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, point, down)
            await { speed(scenario) == 2f }
            scenario.moveToState(Lifecycle.State.CREATED)
            instrument.runOnMainSync {
                assertEquals(1.5f, PlaybackService.testPlayer()!!.playbackParameters.speed, 0f)
                assertTrue(PlaybackService.testPlayer()!!.isPlaying)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            ready(scenario)
            assertEquals(1.5f, speed(scenario), 0f)
            touch(MotionEvent.ACTION_CANCEL, point, down)
            scenario.recreate()
            ready(scenario)
            assertEquals(1.5f, speed(scenario), 0f)
        }
    }

    @Test fun doubleTapSeekAndLockPreventAccidentalChanges() {
        launch().use { scenario ->
            ready(scenario)
            scenario.onActivity { it.testPlayer()!!.pause(); it.testPlayer()!!.seekTo(15000) }
            fun position(): Long { var value = 0L; scenario.onActivity { value = it.testPlayer()!!.currentPosition }; return value }
            val left = point(scenario, 0.2f, 0.3f)
            val right = point(scenario, 0.8f, 0.3f)
            tap(right); Thread.sleep(70); tap(right)
            await { position() in 24900..25100 }
            Thread.sleep(350)
            tap(left); Thread.sleep(70); tap(left)
            await { position() in 14900..15100 }
            scenario.onActivity {
                controls(it).showControls()
                it.findViewById<View>(dev.longvideo.controls.R.id.pc_lock).performClick()
                assertTrue(controls(it).isLocked)
            }
            Thread.sleep(350)
            tap(right); Thread.sleep(70); tap(right)
            Thread.sleep(400)
            assertTrue(position() in 14900..15100)
            val down = SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN, right, down); Thread.sleep(650)
            assertEquals(1f, speed(scenario), 0f)
            touch(MotionEvent.ACTION_UP, right, down)
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed(); assertFalse(controls(it).isLocked) }
            scenario.onActivity { controls(it).showControls(); it.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd).performClick() }
            await { position() in 24900..25100 }
        }
    }

    @Test fun brightnessVolumeAspectAndRotation() {
        launch().use { scenario ->
            ready(scenario)
            var original = -1f
            scenario.onActivity { original = it.window.attributes.screenBrightness; it.testPlayer()!!.pause() }
            fun swipe(x: Float) {
                val start = point(scenario, x, 0.42f)
                val end = point(scenario, x, 0.18f)
                val down = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, start, down)
                for (i in 1..12) {
                    Thread.sleep(20)
                    touch(MotionEvent.ACTION_MOVE, start.first to start.second + (end.second - start.second) * i / 12, down)
                }
                touch(MotionEvent.ACTION_UP, end, down)
            }
            swipe(0.2f)
            scenario.onActivity { assertTrue(it.window.attributes.screenBrightness > 0) }
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
            swipe(0.8f)
            assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 1)
            scenario.onActivity { controls(it).showControls(); it.findViewById<View>(dev.longvideo.controls.R.id.pc_ratio).performClick() }
            clickNode("4:3")
            await { preferences.aspect == 4 }
            await {
                var aspect = 0f
                scenario.onActivity {
                    val frame = it.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                    aspect = frame.width.toFloat() / frame.height
                }
                kotlin.math.abs(aspect - 4f / 3f) < 0.01f
            }
            scenario.onActivity { controls(it).showControls(); it.findViewById<View>(dev.longvideo.controls.R.id.pc_ratio).performClick() }
            screenshot("player-ratio-menu.png")
            clickNode("16:9")
            await { preferences.aspect == 3 }
            await {
                var aspect = 0f
                scenario.onActivity {
                    val frame = it.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                    aspect = frame.width.toFloat() / frame.height
                }
                kotlin.math.abs(aspect - 16f / 9f) < 0.01f
            }
            scenario.onActivity {
                controls(it).showControls()
            }
            screenshot("player-portrait.png")
            scenario.onActivity { it.findViewById<View>(dev.longvideo.controls.R.id.pc_rotate).performClick() }
            await {
                var landscape = false
                scenario.onActivity { landscape = controls(it).width > controls(it).height }
                landscape
            }
            scenario.onActivity { controls(it).showControls() }
            screenshot("player-landscape.png")
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { assertEquals(original, it.window.attributes.screenBrightness, 0f) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            ready(scenario)
            assertEquals(3, preferences.aspect)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    private fun screenshot(name: String) {
        instrument.waitForIdleSync()
        // long: 布局完成不等于系统旋转和窗口淡出动画已合成，截图等待最终画面再验收。
        Thread.sleep(1000)
        val directory = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
        instrument.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
