package dev.tgproxy.mobile

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RealSafExportTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun clickText(label: String) {
        var target: AccessibilityNodeInfo? = null
        awaitValidation {
            target = instrument.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(label)
                ?.firstOrNull { it.text?.toString() == label && it.isVisibleToUser && it.isEnabled }
            target != null
        }
        if (target!!.isClickable && target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val bounds = Rect(); target!!.getBoundsInScreen(bounds)
        val now = SystemClock.uptimeMillis()
        // long: UiAutomation 不替事件补触摸来源，明确标记触屏才能正确命中系统选择器的文件行。
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertTrue(instrument.uiAutomation.injectInputEvent(down, true))
            assertTrue(instrument.uiAutomation.injectInputEvent(up, true))
        } finally { down.recycle(); up.recycle() }
    }
    @Test fun systemPickerExportsExactFileAndResultSurvivesRecreation() {
        assumeTrue("只有明确执行真机系统选择器验收时运行", InstrumentationRegistry.getArguments().getString("validateSaf") == "true")
        ValidationDownloadFixture("TGProxy-导出验收").use { fixture ->
            ActivityScenario.launch<DownloadsActivity>(Intent(context, DownloadsActivity::class.java).putExtra("complete", true)).use { scenario ->
                awaitValidation {
                    var loaded = false
                    scenario.onActivity { loaded = views(it.window.decorView).any { view -> view.contentDescription == "下载选项：${fixture.filename}" } }
                    loaded
                }
                scenario.onActivity { activity -> views(activity.window.decorView).first { it.contentDescription == "下载选项：${fixture.filename}" }.performClick() }
                clickText("另存到手机目录")
                awaitValidation {
                    instrument.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(fixture.filename.removeSuffix(".mp4"))?.any { it.isVisibleToUser } == true
                }
                // long: 同一 UIAutomation 操作真实系统选择器，避免外部 uiautomator 抢占无障碍连接。
                clickText("Download")
                awaitValidation {
                    instrument.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Download")?.any { node ->
                        val location = Rect(); node.getBoundsInScreen(location)
                        node.text?.toString() == "Download" && location.top < context.resources.displayMetrics.heightPixels / 4
                    } == true
                }
                clickText("保存")
                instrument.sendStatus(0, Bundle().apply { putString("saf", "已通过真实系统文件选择器提交保存") })
                awaitValidation(30000) { fixture.app.exports.latest?.let { it.download == fixture.source.id && !ExportPresentation.pending(it) } == true }
                val result = fixture.results().single()
                assertEquals(result.error, ExportState.COMPLETE, result.state)
                assertTrue("本机文件提供器应授予持久访问权", result.persisted)
                assertArrayEquals(fixture.data, context.contentResolver.openInputStream(android.net.Uri.parse(result.uri))!!.use { it.readBytes() })
                scenario.recreate()
                awaitValidation {
                    var visible = false
                    scenario.onActivity { visible = views(it.window.decorView).filterIsInstance<TextView>().any { view -> view.text.toString().startsWith("已另存") } }
                    visible
                }
                val directory = File(context.getExternalFilesDir(null), "validation/optimization-ui").apply { mkdirs() }
                instrument.uiAutomation.takeScreenshot().let { bitmap ->
                    File(directory, "export-success.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                }
            }
        }
    }
}
