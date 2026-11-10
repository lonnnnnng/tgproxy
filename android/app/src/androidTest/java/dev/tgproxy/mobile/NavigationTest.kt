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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

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
                    assertInput(activity, "频道用户名 / 链接 / ID")
                    return@onActivity
                }
                assertInput(activity, "手机号（含国家区号）")
                click(activity, "API 凭证（可选）")
                assertInput(activity, "API ID")
                assertInput(activity, "API Hash")
                views(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_channels
                assertInput(activity, "频道用户名 / 链接 / ID")
                views(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_account
                assertInput(activity, "手机号（含国家区号）")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                views(activity).filterIsInstance<BottomNavigationView>().single().selectedItemId = R.id.nav_channels
                assertInput(activity, "频道用户名 / 链接 / ID")
            }
        }
    }
}
