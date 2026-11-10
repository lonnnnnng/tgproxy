package dev.tgproxy.mobile

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val app get() = application as TgApplication
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var navigation: BottomNavigationView
    private lateinit var connection: TextView
    private lateinit var progress: ProgressBar
    private var destination = R.id.nav_videos
    private var lastPhase = ""
    private var lastBusy = false
    private var status = JSONObject()
    private var scanStatus: TextView? = null
    private var buttons = mutableListOf<MaterialButton>()
    private var search = ""
    private var page = 0
    private var videosContainer: LinearLayout? = null
    private var phone = ""
    private var channelFilter = 0L
    private var recent = false
    private var pendingScan: Pair<Long, Boolean>? = null
    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        pendingScan?.let { launchScan(it.first, it.second) }; pendingScan = null
    }

    private val poll = object : Runnable {
        override fun run() {
            status = app.engine?.let { JSONObject(it.status()) } ?: JSONObject()
            val phase = status.optString("state", "starting")
            connection.text = when (phase) {
                "ready" -> "已连接 Telegram · 本机播放"
                "code" -> "等待验证码"
                "password" -> "等待两步验证"
                "connecting" -> "正在连接 Telegram…"
                "offline" -> "连接中断 · 正在重连"
                "expired" -> "登录已失效"
                "signed_out" -> "尚未登录 Telegram"
                else -> app.startupError ?: "正在准备播放内核…"
            }
            progress.visibility = if (app.busy) View.VISIBLE else View.INVISIBLE
            scanStatus?.text = app.scanMessage
            if (destination == R.id.nav_account && phase != lastPhase && !app.busy) render()
            if (app.busy != lastBusy) {
                buttons.forEach { it.isEnabled = !app.busy }
                if (lastBusy && !app.busy && destination == R.id.nav_channels) render()
                lastBusy = app.busy
            }
            lastPhase = phase
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        destination = savedInstanceState?.getInt("destination", R.id.nav_videos) ?: R.id.nav_videos
        phone = savedInstanceState?.getString("phone") ?: ""
        channelFilter = savedInstanceState?.getLong("channelFilter") ?: 0L
        recent = savedInstanceState?.getBoolean("recent") ?: false
        search = savedInstanceState?.getString("search") ?: ""
        page = savedInstanceState?.getInt("page") ?: 0
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            if (::navigation.isInitialized) navigation.visibility = if (insets.isVisible(WindowInsetsCompat.Type.ime())) View.GONE else View.VISIBLE
            insets
        }
        root.addView(MaterialToolbar(this).apply { title = "TGProxy" }, LinearLayout.LayoutParams(-1, dp(56)))
        connection = text("正在准备播放内核…", 14).apply {
            setPadding(dp(24), dp(4), dp(24), dp(12))
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(connection)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = View.INVISIBLE }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(24)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        navigation = BottomNavigationView(this).apply {
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
            menu.add(0, R.id.nav_videos, 0, "视频").setIcon(R.drawable.ic_video)
            menu.add(0, R.id.nav_channels, 1, "频道").setIcon(R.drawable.ic_channels)
            menu.add(0, R.id.nav_account, 2, "账号").setIcon(R.drawable.ic_account)
            selectedItemId = destination
            setOnItemSelectedListener { destination = it.itemId; render(); true }
        }
        root.addView(navigation)
        render()
    }

    override fun onStart() { super.onStart(); render(); handler.post(poll) }
    override fun onStop() { handler.removeCallbacks(poll); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("destination", destination); outState.putString("phone", phone); outState.putLong("channelFilter", channelFilter); outState.putBoolean("recent", recent); outState.putString("search", search); outState.putInt("page", page); super.onSaveInstanceState(outState) }

    private fun render() {
        if (!::body.isInitialized) return
        body.removeAllViews(); buttons.clear(); scanStatus = null; videosContainer = null
        when (destination) { R.id.nav_videos -> videosPage(); R.id.nav_channels -> channelsPage(); R.id.nav_account -> accountPage() }
    }

    private fun videosPage() {
        heading("视频库")
        val channels = app.database.channels()
        paragraph("${channels.length()} 个频道 · ${app.database.count()} 个视频")
        if (channels.length() == 0) {
            paragraph("把 Telegram 频道加入视频库，内容会直接在这台手机上索引和播放。")
            action(if (status.optBoolean("connected")) "添加频道" else "连接 Telegram") {
                navigation.selectedItemId = if (status.optBoolean("connected")) R.id.nav_channels else R.id.nav_account
            }
            return
        }
        val query = input("搜索视频名称", InputType.TYPE_CLASS_TEXT).apply { setText(search) }
        query.imeOptions = EditorInfo.IME_ACTION_SEARCH
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                search = query.text.toString().trim(); page = 0; showVideos()
                androidx.core.view.WindowInsetsControllerCompat(window, root).hide(WindowInsetsCompat.Type.ime())
                true
            } else false
        }
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(filters, rowParams())
        filters.addView(button(app.database.channel(channelFilter)?.takeIf { channelFilter != 0L }?.optString("title") ?: "全部频道", true) {
            val labels = arrayOf("全部频道") + (0 until channels.length()).map { channels.getJSONObject(it).getString("title") }
            MaterialAlertDialogBuilder(this).setTitle("按频道查看").setItems(labels) { _, index ->
                search = query.text.toString().trim()
                channelFilter = if (index == 0) 0 else channels.getJSONObject(index - 1).getLong("channel_id")
                page = 0; render()
            }.show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        filters.addView(button(if (recent) "最近播放 · 已选" else "最近播放", true) {
            search = query.text.toString().trim(); recent = !recent; page = 0; render()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (PlaybackService.currentKey != null) action("返回当前播放", true) { startActivity(Intent(this, PlayerActivity::class.java)) }
        videosContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(videosContainer)
        showVideos()
    }

    private fun showVideos() {
        val target = videosContainer ?: return
        buttons.removeAll { it.parent === target }
        target.removeAllViews()
        val rows = app.database.videos(search, page * 50, 51, channelFilter, recent)
        if (rows.length() == 0) target.addView(text("没有匹配的视频，可以换个关键词或重新扫描频道。", 16))
        for (i in 0 until minOf(rows.length(), 50)) {
            val item = rows.getJSONObject(i)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(16), 0, dp(16))
                minimumHeight = dp(72)
                isClickable = true; isFocusable = true
                val typed = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
                setBackgroundResource(typed.resourceId)
                contentDescription = "播放：${item.optString("name")}"
                setOnClickListener {
                    if (app.engine?.let { JSONObject(it.status()).optBoolean("connected") } != true) { message("请先连接 Telegram"); return@setOnClickListener }
                    startActivity(Intent(this@MainActivity, PlayerActivity::class.java).putExtra("key", item.getString("key")).putExtra("title", item.getString("name")))
                }
            }
            row.addView(text(item.getString("name"), 16).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(text("${item.optLong("size") / (1024 * 1024)} MiB · " + (app.database.channel(item.optLong("channel_id"))?.optString("title") ?: "频道视频"), 13).apply {
                setPadding(0, dp(6), 0, 0); setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            target.addView(row)
            target.addView(View(this).apply { setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant)) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
        if (page > 0) target.addView(button("上一页", true) { page--; showVideos() })
        if (rows.length() > 50) target.addView(button("下一页", true) { page++; showVideos() })
    }

    private fun channelsPage() {
        heading("频道")
        paragraph("从已加入的 Telegram 频道选择，也可以输入公开链接或频道 ID。移除仅删除本机索引。")
        action("从 Telegram 选择") {
            app.perform({ it.listChannels() }) { value, error ->
                if (isDestroyed) return@perform
                if (error != null) { message(error); return@perform }
                val channels = JSONArray(value)
                if (channels.length() == 0) { message("未找到已加入的频道或超级群组"); return@perform }
                val labels = Array(channels.length()) { channels.getJSONObject(it).getString("title") }
                val selected = BooleanArray(channels.length())
                MaterialAlertDialogBuilder(this).setTitle("添加频道")
                    .setMultiChoiceItems(labels, selected) { _, index, checked -> selected[index] = checked }
                    .setNegativeButton("取消", null).setPositiveButton("添加") { _, _ ->
                        app.perform({ _ ->
                            for (i in selected.indices) if (selected[i]) app.database.addChannel(channels.getJSONObject(i))
                            ""
                        }) { _, failure -> if (!isDestroyed) { render(); message(failure ?: "已添加，选择频道开始扫描") } }
                    }.show()
            }
        }
        val reference = input("频道用户名 / 链接 / ID", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        action("添加频道", true) {
            val ref = reference.text.toString().trim()
            if (ref.isEmpty()) { reference.error = "请输入频道"; return@action }
            app.perform({ core -> core.findChannel(ref).also { app.database.addChannel(JSONObject(it)) } }) { _, error ->
                if (!isDestroyed) { render(); message(error ?: "频道已添加，可以开始扫描") }
            }
        }
        space(16)
        scanStatus = paragraph(app.scanMessage)
        // long: 扫描任务持续占用工作队列，但暂停入口必须始终可用。
        body.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "暂停扫描"; minHeight = dp(48)
            setOnClickListener { app.cancelScan(); message("正在暂停，已完成的页面会保留") }
        }, rowParams())
        val channels = app.database.channels()
        if (channels.length() == 0) paragraph("还没有频道。添加后可以扫描历史视频和检查新内容。")
        for (i in 0 until channels.length()) {
            val channel = channels.getJSONObject(i)
            val id = channel.getLong("channel_id")
            space(24)
            body.addView(text(channel.getString("title"), 20))
            val updated = channel.optLong("updated")
            val time = if (updated > 0L) android.text.format.DateUtils.getRelativeTimeSpanString(updated).toString() else "尚未扫描"
            paragraph("${app.database.count(id)} 个视频 · $time" + if (channel.optBoolean("history_done")) " · 历史已完成" else " · 历史可续扫")
            action("浏览视频", true) { channelFilter = id; page = 0; recent = false; navigation.selectedItemId = R.id.nav_videos }
            action(if (channel.optLong("refresh_offset") > 0) "继续同步新增" else "检查新增", true) { beginScan(id, true) }
            if (!channel.optBoolean("history_done")) action(if (channel.optLong("history_offset") > 0) "继续扫描历史" else "扫描历史视频") { beginScan(id, false) }
            action("移除频道", true) {
                MaterialAlertDialogBuilder(this).setTitle("移除 ${channel.getString("title")}？")
                    .setMessage("删除该频道在本机的索引和播放记录，不会退出 Telegram 频道。")
                    .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                        if (PlaybackService.currentKey?.let { app.database.video(it)?.optLong("channel_id") } == id) PlaybackService.stopPlayback(this)
                        app.perform({ _ -> app.database.removeChannel(id); "" }) { _, error ->
                            if (!isDestroyed) { if (channelFilter == id) channelFilter = 0; render(); error?.let(::message) }
                        }
                    }.show()
            }
        }
    }

    private fun beginScan(id: Long, refresh: Boolean) {
        if (app.busy || app.scanning) { message("请等待当前操作结束"); return }
        if (!status.optBoolean("connected")) { message("请先连接 Telegram"); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingScan = id to refresh
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else launchScan(id, refresh)
    }

    private fun launchScan(id: Long, refresh: Boolean) {
        androidx.core.content.ContextCompat.startForegroundService(this,
            Intent(this, ScanService::class.java).putExtra("channel", id).putExtra("refresh", refresh))
    }

    private fun accountPage() {
        val phase = status.optString("state", "signed_out")
        heading(if (phase in listOf("ready", "connecting", "offline", "expired")) "Telegram 账号" else "连接你的 Telegram")
        if (phase in listOf("ready", "connecting", "offline", "expired")) {
            paragraph(when (phase) { "ready" -> "授权已保存在这台手机上。现在可以添加频道，或者回到视频列表。"; "expired" -> "授权已失效，请退出本机账号后重新登录。"; else -> "正在恢复 Telegram 连接，请确认手机能够访问 Telegram。" })
            action("管理频道") { navigation.selectedItemId = R.id.nav_channels }
            action("退出本机账号", true) { confirmLogout() }
        } else {
            paragraph("通过验证码授权本机访问频道。已有 Telegram 应用中也可能收到验证码。")
            when (phase) {
                "code" -> {
                    val code = input("验证码", InputType.TYPE_CLASS_NUMBER)
                    action("确认验证码") { val value = code.text.toString(); code.text?.clear(); login { it.signIn(value) } }
                    action("重新发送验证码", true) { if (phone.isBlank()) { message("返回后重新填写手机号"); lastPhase = ""; status.put("state", "signed_out"); render() } else login { it.sendCode(phone) } }
                }
                "password" -> {
                    val password = input("两步验证密码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                    action("完成登录") { val value = password.text.toString(); password.text?.clear(); login { it.submitPassword(value) } }
                }
                else -> {
                    val number = input("手机号（含国家区号）", InputType.TYPE_CLASS_PHONE).apply { setText(phone) }
                    val saved = app.credentials()
                    var id: TextInputEditText? = null
                    var hash: TextInputEditText? = null
                    action("API 凭证（可选）", true) {
                        if (id != null) return@action
                        val insertion = body.indexOfChild(body.findViewWithTag<View>("send-code"))
                        val originalCount = body.childCount
                        paragraph("两项都留空使用预设值；填写时需要成对提供。凭证仅加密保存在这台手机上。")
                        id = input("API ID", InputType.TYPE_CLASS_NUMBER).apply { if (saved.optLong("api_id") > 0) setText(saved.getLong("api_id").toString()) }
                        hash = input("API Hash", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { setText(saved.optString("api_hash")) }
                        // long: 可选项展开在提交按钮前，用户按从上到下的顺序填写，不会错过自有凭证设置。
                        val added = (originalCount until body.childCount).map { body.getChildAt(it) }
                        added.forEachIndexed { index, child -> body.removeView(child); body.addView(child, insertion + index) }
                    }
                    action("发送验证码") {
                        phone = number.text.toString().trim()
                        if (!phone.startsWith("+") || phone.length !in 7..18) { number.error = "请输入带国家区号的手机号"; return@action }
                        if (id == null) { login { it.sendCode(phone) }; return@action }
                        val rawID = id!!.text.toString().trim()
                        val rawHash = hash!!.text.toString().trim()
                        val appID = if (rawID.isEmpty()) 0L else rawID.toLongOrNull()
                        if (appID == null || (rawID.isEmpty() != rawHash.isEmpty()) || (rawID.isNotEmpty() && (appID !in 1..2147483647 || !rawHash.matches(Regex("[0-9a-fA-F]{32}"))))) {
                            message("请同时填写有效的 API ID 和 32 位 API Hash，或将两项都留空")
                            return@action
                        }
                        app.configure(appID, rawHash) { failure ->
                            if (!isDestroyed) { if (failure != null) message(failure) else login { it.sendCode(phone) } }
                        }
                    }
                    body.getChildAt(body.childCount - 1).tag = "send-code"
                }
            }
        }
        space(20)
        action("播放本地测试片", true) { playValidation() }
        paragraph("TGProxy ${BuildConfig.VERSION_NAME} · ARM64\n多频道索引、本机播放与后台控制。")
        if (app.startupError != null) action("重置本机账号", true) { confirmLogout() }
    }

    private fun login(operation: (dev.tgproxy.core.mobilecore.Engine) -> String) {
        buttons.forEach { it.isEnabled = false }
        app.perform(operation) { value, error ->
            if (isDestroyed) return@perform
            if (error != null) { buttons.forEach { it.isEnabled = true }; message(error) }
            else { status.put("state", value); lastPhase = value ?: ""; render() }
        }
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this).setTitle("退出本机账号？")
            .setMessage("清除这台手机保存的授权、频道索引和播放记录。Telegram 账号及频道消息不会被删除。")
            .setNegativeButton("取消", null).setPositiveButton("退出") { _, _ ->
                app.logout { error -> if (!isDestroyed) { if (error != null) message(error) else { status = JSONObject().put("state", "signed_out"); render() } } }
            }.show()
    }

    private fun playValidation() {
        if (app.engine == null) { message(app.startupError ?: "内核仍在准备，请稍后重试"); return }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("validation", true).putExtra("title", "本地播放测试 · 30 秒"))
    }

    private fun heading(value: String) { body.addView(text(value, 26).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium) }); space(12) }
    private fun paragraph(value: String): TextView = text(value, 15).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant)); setLineSpacing(dp(4).toFloat(), 1f)
        body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
    }
    private fun input(label: String, type: Int): TextInputEditText {
        val box = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = label; isHintEnabled = true }
        val field = TextInputEditText(box.context).apply {
            inputType = type; setSingleLine(true); minHeight = dp(56)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            if (type and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) { isSaveEnabled = false }
        }
        // long: TextInputLayout 会把输入框移入内部容器并读取 LinearLayout 的边距，通用 LayoutParams 会导致页面创建时崩溃。
        box.addView(field, LinearLayout.LayoutParams(-1, -2))
        body.addView(box, rowParams())
        return field
    }
    private fun action(label: String, outlined: Boolean = false, action: () -> Unit) { body.addView(button(label, outlined, action), rowParams()) }
    private fun button(label: String, outlined: Boolean, action: () -> Unit): MaterialButton = MaterialButton(this, null,
        if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle).apply {
        text = label; minHeight = dp(48); isAllCaps = false; isEnabled = !app.busy
        setOnClickListener { action() }; buttons.add(this)
    }
    private fun rowParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8) }
    private fun text(value: String, size: Int) = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color(com.google.android.material.R.attr.colorOnSurface)) }
    private fun space(size: Int) { body.addView(View(this), LinearLayout.LayoutParams(1, dp(size))) }
    private fun message(value: String) { Snackbar.make(root, value, Snackbar.LENGTH_LONG).setAnchorView(navigation).show() }
    private fun color(attr: Int) = MaterialColors.getColor(this, attr, "TGProxy")
    private fun dp(size: Int) = (size * resources.displayMetrics.density).toInt()
}
