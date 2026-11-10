package dev.tgproxy.mobile

import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import android.widget.EditText
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatDelegate
import android.content.res.Configuration
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun accessibilityNodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(accessibilityNodes(it)) }
    }

    private fun views(activity: MainActivity) = descendants(activity.findViewById(android.R.id.content))

    private fun click(activity: MainActivity, label: String) {
        val button = views(activity).filterIsInstance<MaterialButton>().firstOrNull { it.text.toString() == label }
        assertNotNull("找不到按钮：$label", button)
        assertTrue("按钮不可操作：$label", button!!.isEnabled)
        button.performClick()
    }

    private fun assertInput(activity: MainActivity, label: String) {
        val input = views(activity).filterIsInstance<TextInputLayout>().firstOrNull { it.hint.toString() == label }
        assertNotNull("输入框未创建：$label", input)
        assertNotNull("编辑控件未添加：$label", input!!.editText)
    }

    @Test fun homeConnectChannelAndCredentialsRenderWithoutCrash() {
        // long: 此用例只操作页面，不发验证码、不登录、不清除用户数据，覆盖本次闪退的实际点击链路。
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TgApplication
        val deadline = System.currentTimeMillis() + 10000
        while (app.engine == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertNotNull("内核未就绪", app.engine)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                views(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_account
                val phase = org.json.JSONObject(app.engine!!.status()).optString("state")
                if (phase in listOf("ready", "connecting", "offline", "expired")) {
                    views(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_channels
                    assertInput(activity, "频道链接 / 用户名 / ID")
                    return@onActivity
                }
                assertInput(activity, "手机号（含国家区号）")
                click(activity, "API ID / Hash（可选）")
                assertInput(activity, "API ID")
                assertInput(activity, "API Hash")
                views(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_channels
                assertInput(activity, "频道链接 / 用户名 / ID")
                views(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_account
                assertInput(activity, "手机号（含国家区号）")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                views(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_channels
                assertInput(activity, "频道链接 / 用户名 / ID")
            }
        }
    }
    private fun input(activity: MainActivity, label: String): EditText = views(activity)
        .filterIsInstance<TextInputLayout>().first { it.hint.toString() == label }.editText!!

    @Test fun channelDraftSurvivesNavigationAndRecreation() {
        // long: 未提交链接应跨页面和旋转保留，但用例不添加频道或改写真实索引。
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val navigation = views(activity).filterIsInstance<BottomNavigationView>().single()
                navigation.selectedItemId = R.id.nav_channels
                input(activity, "频道链接 / 用户名 / ID").setText("https://t.me/example_draft")
                navigation.selectedItemId = R.id.nav_account
                navigation.selectedItemId = R.id.nav_channels
                assertEquals("https://t.me/example_draft", input(activity, "频道链接 / 用户名 / ID").text.toString())
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("https://t.me/example_draft", input(activity, "频道链接 / 用户名 / ID").text.toString())
            }
        }
    }

    @Test fun loginDraftSurvivesExpansionPollingAndRecreation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TgApplication
        val deadline = System.currentTimeMillis() + 10000
        while (app.engine == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assumeTrue("已授权设备保留现有账号，登录草稿在未授权安装中验证",
            app.engine?.let { org.json.JSONObject(it.status()).optString("state") } in listOf("signed_out", "starting"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                views(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_account
                val phone = input(activity, "手机号（含国家区号）")
                phone.setText("+12345678900")
                phone.requestFocus()
                click(activity, "API ID / Hash（可选）")
                assertSame(phone, input(activity, "手机号（含国家区号）"))
                input(activity, "API ID").setText("12345")
                input(activity, "API Hash").setText("0123456789abcdef0123456789abcdef")
                click(activity, "隐藏 API 凭证")
                click(activity, "API ID / Hash（可选）")
            }
            Thread.sleep(1300)
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("+12345678900", input(activity, "手机号（含国家区号）").text.toString())
                assertEquals("12345", input(activity, "API ID").text.toString())
                assertEquals("0123456789abcdef0123456789abcdef", input(activity, "API Hash").text.toString())
                assertTrue(!input(activity, "API Hash").isSaveEnabled)
                assertTrue(input(activity, "API Hash").transformationMethod is android.text.method.PasswordTransformationMethod)
                val nav = views(activity).filterIsInstance<BottomNavigationView>().single()
                nav.selectedItemId = R.id.nav_channels
                nav.selectedItemId = R.id.nav_account
                assertEquals("12345", input(activity, "API ID").text.toString())
            }
        }
    }

    @Test fun librarySearchAndScrollSurviveNavigation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TgApplication
        assumeTrue("需要已索引的媒体库", app.database.count() > 50)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1500)
            scenario.onActivity { activity ->
                views(activity).filterIsInstance<ScrollView>().single().scrollTo(0, 800)
                input(activity, "搜索视频名称").setText("no_matching_title_qa")
            }
            Thread.sleep(500)
            scenario.onActivity { activity ->
                assertEquals("修改搜索条件后应回到结果顶部", 0, views(activity).filterIsInstance<ScrollView>().single().scrollY)
                click(activity, "清除搜索条件")
                assertEquals("", input(activity, "搜索视频名称").text.toString())
            }
            Thread.sleep(500)
            scenario.onActivity { activity ->
                val scroll = views(activity).filterIsInstance<ScrollView>().single()
                scroll.scrollTo(0, 800)
                views(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_channels
                views(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_videos
            }
            Thread.sleep(500)
            scenario.onActivity { activity ->
                assertEquals(800, views(activity).filterIsInstance<ScrollView>().single().scrollY)
                click(activity, "下一页")
            }
            Thread.sleep(500)
            scenario.onActivity { activity ->
                assertEquals(0, views(activity).filterIsInstance<ScrollView>().single().scrollY)
                click(activity, "上一页")
            }
        }
    }

    @Test fun videoSearchAndFiltersStayPinnedWhileResultsScroll() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as TgApplication
        assumeTrue("需要已索引的媒体库", app.database.count() > 50)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1200)
            scenario.onActivity { activity ->
                val search = input(activity, "搜索视频名称")
                val start = android.graphics.Rect()
                assertTrue(search.getGlobalVisibleRect(start))
                val filters = views(activity).filterIsInstance<com.google.android.material.button.MaterialButtonToggleGroup>().single()
                val filtersStart = android.graphics.Rect()
                assertTrue(filters.getGlobalVisibleRect(filtersStart))
                val listScroll = views(activity).filterIsInstance<ScrollView>().single()
                listScroll.scrollTo(0, 800)
                val after = android.graphics.Rect()
                assertTrue(search.getGlobalVisibleRect(after))
                assertEquals("搜索栏应始终固定在列表上方", start.top, after.top)
                val filtersAfter = android.graphics.Rect()
                assertTrue(filters.getGlobalVisibleRect(filtersAfter))
                assertEquals("筛选栏不应随结果列表滚动", filtersStart.top, filtersAfter.top)
                assertTrue("结果列表未滚动", listScroll.scrollY > 0)
            }
        }
    }

    @Test fun appearanceSelectionChangesThemeAndKeepsDestination() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val preferences = instrumentation.targetContext.getSharedPreferences("appearance", 0)
        val original = preferences.getInt("theme", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for ((label, mode) in listOf("浅色" to AppCompatDelegate.MODE_NIGHT_NO, "深色" to AppCompatDelegate.MODE_NIGHT_YES)) {
                    scenario.onActivity { activity ->
                        views(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_account
                        views(activity).first { it.contentDescription?.toString()?.startsWith("外观，") == true }.performClick()
                    }
                    instrumentation.waitForIdleSync()
                    val automation = instrumentation.uiAutomation
                    automation.serviceInfo = automation.serviceInfo.apply {
                        flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    }
                    var option: AccessibilityNodeInfo? = null
                    val deadline = System.currentTimeMillis() + 5000
                    while (option == null && System.currentTimeMillis() < deadline) {
                        option = automation.windows.asSequence().mapNotNull { it.root }
                            .flatMap(::accessibilityNodes).firstOrNull { it.text?.toString() == label }
                        if (option == null) Thread.sleep(100)
                    }
                    assertNotNull("主题选项未显示", option)
                    val bounds = android.graphics.Rect()
                    option!!.getBoundsInScreen(bounds)
                    val now = android.os.SystemClock.uptimeMillis()
                    // long: RadioButton 文本节点不单独处理 ACTION_CLICK，按实际可见坐标触发整行选项。
                    instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now, now,
                        android.view.MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0))
                    instrumentation.sendPointerSync(android.view.MotionEvent.obtain(now, now + 50,
                        android.view.MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0))
                    Thread.sleep(1000)
                    scenario.onActivity { activity ->
                        assertEquals(mode, preferences.getInt("theme", 0))
                        assertEquals(if (mode == AppCompatDelegate.MODE_NIGHT_YES) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
                            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                        assertEquals(R.id.nav_account, views(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId)
                    }
                }
            }
        } finally {
            // long: 主题回归只改变被测应用，并在完成后恢复用户原有外观偏好。
            preferences.edit().putInt("theme", original).commit()
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(original) }
        }
    }

}
