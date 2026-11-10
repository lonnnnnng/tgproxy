package dev.tgproxy.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val app get() = application as TgApplication
    private val handler = Handler(Looper.getMainLooper())
    private val queryWorker = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var navigation: BottomNavigationView
    private lateinit var scroll: ScrollView
    private lateinit var videoHeader: LinearLayout
    private lateinit var miniPlayer: LinearLayout
    private var lastPlayingKey: String? = null
    private var scanCard: View? = null
    private var scanPause: View? = null
    private var codeDraft = ""
    private var passwordDraft = ""
    private var credentialEdited = false
    private var loginFormPhase = ""
    private var channelDraft = ""
    private val scrollPositions = mutableMapOf<Int, Int>()
    private var renderedDestination = 0
    private var pendingScrollRestore: Int? = null
    private lateinit var progress: LinearProgressIndicator
    private var destination = R.id.nav_videos
    private var lastPhase = ""
    private var lastBusy = false
    private var hasStarted = false
    private var status = JSONObject()
    private var scanStatus: TextView? = null
    private var scanIndicator: LinearProgressIndicator? = null
    private val buttons = mutableListOf<MaterialButton>()
    private var search = ""
    private var searchField: TextInputEditText? = null
    private var page = 0
    private var channelFilter = 0L
    private var recent = false
    private var videoRequest = 0
    private var videosContainer: LinearLayout? = null
    private var channelFilterButton: MaterialButton? = null
    private var channelListContainer: LinearLayout? = null
    private var channelRequest = 0
    private var phone = ""
    private var credentialsExpanded = false
    private var credentialsLoaded = false
    private var credentialDraftId = ""
    private var credentialDraftHash = ""
    private var pendingScan: Pair<Long, Boolean>? = null
    private var searchRunnable: Runnable? = null
    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {
        pendingScan?.let { launchScan(it.first, it.second) }
        pendingScan = null
    }

    private val poll = object : Runnable {
        override fun run() {
            status = app.engine?.let { JSONObject(it.status()) } ?: JSONObject()
            val phase = status.optString("state", "starting")
            toolbar.subtitle = phaseLabel(phase)
            toolbar.setSubtitleTextColor(when (phase) {
                "ready" -> color(com.google.android.material.R.attr.colorSecondary)
                "expired" -> color(com.google.android.material.R.attr.colorError)
                else -> color(com.google.android.material.R.attr.colorOnSurfaceVariant)
            })
            progress.visibility = if (app.busy) View.VISIBLE else View.INVISIBLE
            scanStatus?.text = app.scanMessage
            val hasScanStatus = app.scanning || app.scanMessage != "索引任务等待开始"
            scanCard?.visibility = if (hasScanStatus) View.VISIBLE else View.GONE
            scanPause?.visibility = if (app.scanning) View.VISIBLE else View.GONE
            scanPause?.isEnabled = true
            scanIndicator?.visibility = if (app.scanning) View.VISIBLE else View.GONE
            val signedOutForm = phase in setOf("signed_out", "starting")
            if (destination == R.id.nav_account && phase != lastPhase && !app.busy &&
                !(signedOutForm && loginFormPhase == "phone")) render()
            if (destination == R.id.nav_videos && phase != lastPhase && !app.busy) {
                if (lastPhase == "starting" || phase == "signed_out" || (lastPhase == "connecting" && phase == "ready")) render()
            }
            if (PlaybackService.currentKey != lastPlayingKey) updateMiniPlayer()
            if (app.busy != lastBusy) {
                buttons.forEach { it.isEnabled = !app.busy }
                if (lastBusy && !app.busy && destination == R.id.nav_channels) channelListContainer?.let(::loadChannels)
                lastBusy = app.busy
            }
            lastPhase = phase
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        destination = savedInstanceState?.getInt("destination", R.id.nav_videos) ?: R.id.nav_videos
        scrollPositions[destination] = savedInstanceState?.getInt("scroll", 0) ?: 0
        phone = savedInstanceState?.getString("phone") ?: ""
        channelDraft = savedInstanceState?.getString("channelDraft") ?: ""
        channelFilter = savedInstanceState?.getLong("channelFilter") ?: 0L
        recent = savedInstanceState?.getBoolean("recent") ?: false
        search = savedInstanceState?.getString("search") ?: ""
        page = savedInstanceState?.getInt("page") ?: 0
        (lastCustomNonConfigurationInstance as? LoginDraft)?.let {
            credentialsExpanded = it.expanded; credentialsLoaded = it.loaded
            credentialDraftId = it.apiId; credentialDraftHash = it.hash; credentialEdited = it.edited
            codeDraft = it.code; passwordDraft = it.password; channelDraft = it.channel
        }
        status = app.engine?.let { JSONObject(it.status()) } ?: JSONObject()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            if (::navigation.isInitialized) {
                navigation.visibility = if (insets.isVisible(WindowInsetsCompat.Type.ime())) View.GONE else View.VISIBLE
            }
            insets
        }

        toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
            setTitleTextColor(color(com.google.android.material.R.attr.colorOnSurface))
            setSubtitleTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setTitleTextAppearance(context, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            setSubtitleTextAppearance(context, com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            setContentInsetsRelative(dp(20), dp(12))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        toolbar.setOnMenuItemClickListener { item -> onToolbarAction(item) }

        progress = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            visibility = View.INVISIBLE
            trackThickness = dp(2)
            trackColor = android.graphics.Color.TRANSPARENT
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))
        // long: 扫描属于整个应用的任务，浏览视频或切到账号时也必须能看到进度并暂停。
        root.addView(scanPanel(), LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(20); marginEnd = dp(20); bottomMargin = dp(8)
        })

        // long: 搜索和筛选放在结果滚动区外，浏览到任意位置都能直接调整条件。
        videoHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            visibility = View.GONE
        }
        root.addView(videoHeader, LinearLayout.LayoutParams(-1, -2))

        scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        navigation = BottomNavigationView(this).apply {
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
            menu.add(0, R.id.nav_videos, 0, "视频").setIcon(R.drawable.ic_video)
            menu.add(0, R.id.nav_channels, 1, "频道").setIcon(R.drawable.ic_channels)
            menu.add(0, R.id.nav_account, 2, "账号").setIcon(R.drawable.ic_account)
            selectedItemId = destination
            setOnItemSelectedListener { item ->
                scrollPositions[destination] = scroll.scrollY
                destination = item.itemId; render()
                true
            }
        }
        miniPlayer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(miniPlayer)
        root.addView(navigation)
        render()
        updateMiniPlayer()
    }

    override fun onStart() {
        super.onStart()
        if (hasStarted && destination == R.id.nav_videos) {
            pendingScrollRestore = scrollPositions[destination] ?: 0
            showVideos()
        }
        hasStarted = true
        handler.post(poll)
    }

    override fun onStop() {
        scrollPositions[destination] = scroll.scrollY
        handler.removeCallbacks(poll)
        searchRunnable?.let(handler::removeCallbacks)
        super.onStop()
    }

    override fun onDestroy() {
        queryWorker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // long: 未提交的敏感字段只保留在内存，旋转时不写入系统保存状态或磁盘。
    override fun onRetainCustomNonConfigurationInstance(): Any = LoginDraft(
        credentialsExpanded, credentialsLoaded, credentialDraftId, credentialDraftHash, credentialEdited,
        codeDraft, passwordDraft, channelDraft)

    private data class LoginDraft(val expanded: Boolean, val loaded: Boolean, val apiId: String, val hash: String,
        val edited: Boolean, val code: String, val password: String, val channel: String)

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("destination", destination)
        outState.putString("phone", phone)
        outState.putString("channelDraft", channelDraft)
        outState.putLong("channelFilter", channelFilter)
        outState.putBoolean("recent", recent)
        outState.putString("search", search)
        outState.putInt("page", page)
        if (::scroll.isInitialized) outState.putInt("scroll", scroll.scrollY)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        if (!::body.isInitialized) return
        videoRequest++
        channelRequest++
        searchRunnable?.let(handler::removeCallbacks)
        buttons.clear()
        if (renderedDestination == destination) scrollPositions[destination] = scroll.scrollY
        pendingScrollRestore = scrollPositions[destination] ?: 0
        renderedDestination = destination
        videoHeader.removeAllViews()
        videoHeader.visibility = if (destination == R.id.nav_videos) View.VISIBLE else View.GONE
        body.setPadding(dp(20), if (destination == R.id.nav_videos) 0 else dp(12), dp(20), dp(24))
        videosContainer = null
        channelListContainer = null
        searchField = null
        body.removeAllViews()
        toolbar.title = when (destination) {
            R.id.nav_videos -> "视频库"
            R.id.nav_channels -> "频道管理"
            else -> "Telegram 账号"
        }
        toolbar.subtitle = phaseLabel(status.optString("state", "starting"))
        refreshToolbarMenu()
        when (destination) {
            R.id.nav_videos -> videosPage()
            R.id.nav_channels -> channelsPage()
            R.id.nav_account -> accountPage()
        }
        if (videosContainer == null && channelListContainer == null) restoreScroll()
    }

    private fun restoreScroll() {
        val offset = pendingScrollRestore ?: return
        pendingScrollRestore = null
        // long: 异步列表完成布局后再恢复滚动，否则空容器会把旧位置截断到页面顶部。
        scroll.post { if (!isDestroyed) scroll.scrollTo(0, offset) }
    }

    private fun refreshToolbarMenu() {
        toolbar.menu.clear()
        if (PlaybackService.currentKey != null) {
            toolbar.menu.add(0, MENU_CURRENT_PLAYBACK, 0, "返回正在播放")
                .setIcon(R.drawable.ic_play)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        if (destination == R.id.nav_account) {
            toolbar.menu.add(0, MENU_TEST_MEDIA, 1, "播放本地测试片")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
    }

    private fun onToolbarAction(item: MenuItem): Boolean = when (item.itemId) {
        MENU_CURRENT_PLAYBACK -> { startActivity(Intent(this, PlayerActivity::class.java)); true }
        MENU_TEST_MEDIA -> { playValidation(); true }
        else -> false
    }

    private fun videosPage() {
        val channels = app.database.channels()
        if (channels.length() == 0) {
            videoHeader.visibility = View.GONE
            body.setPadding(dp(20), dp(12), dp(20), dp(24))
            emptyState(
                icon = R.drawable.ic_video,
                title = "还没有频道视频",
                detail = if (status.optBoolean("connected")) "添加一个频道后，扫描到的视频会出现在这里。" else "登录 Telegram 并添加频道，开始建立本机视频库。",
                command = if (status.optBoolean("connected")) "添加频道" else "连接 Telegram",
                action = {
                    navigation.selectedItemId = if (status.optBoolean("connected")) R.id.nav_channels else R.id.nav_account
                }
            )
            return
        }

        val summary = horizontalRow().apply {
            addView(text("正在读取视频库…", 14).apply {
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        }
        videoHeader.addView(summary, rowParams())
        queryWorker.execute {
            val count = app.database.count()
            handler.post {
                if (!isDestroyed && summary.parent != null) {
                    summary.removeAllViews()
                    summary.addView(text("$count 个视频", 16).apply {
                        setTypeface(typeface, Typeface.BOLD)
                    })
                    summary.addView(text("${channels.length()} 个频道", 13).apply {
                        setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(3), 0, 0)
                    })
                }
            }
        }

        val searchBox = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "搜索视频名称"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            setBoxBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerLow))
        }
        val searchInput = TextInputEditText(searchBox.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
            minHeight = dp(52)
            setText(search)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    search = text?.toString()?.trim().orEmpty()
                    page = 0
                    pendingScrollRestore = 0
                    searchRunnable?.let(handler::removeCallbacks)
                    showVideos()
                    androidx.core.view.WindowInsetsControllerCompat(window, root).hide(WindowInsetsCompat.Type.ime())
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    search = s?.toString()?.trim().orEmpty()
                    page = 0
                    // long: 搜索结果是新查询；从旧列表偏移继续会让结果看似缺失。
                    pendingScrollRestore = 0
                    videoRequest++
                    searchRunnable?.let(handler::removeCallbacks)
                    searchRunnable = Runnable { showVideos() }.also { handler.postDelayed(it, 240) }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        searchField = searchInput
        searchBox.addView(searchInput, LinearLayout.LayoutParams(-1, -2))
        videoHeader.addView(searchBox, rowParams())

        val channelsById = buildMap<Long, String> {
            for (index in 0 until channels.length()) {
                val channel = channels.getJSONObject(index)
                put(channel.getLong("channel_id"), channel.optString("title", "Telegram 频道"))
            }
        }
        val filters = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val channelTitle = channelsById[channelFilter] ?: "全部频道"
        channelFilterButton = actionButton(
            label = channelTitle,
            style = ButtonStyle.TEXT,
            icon = R.drawable.ic_filter,
            action = { chooseVideoChannel(channels) }
        ).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL }
        filters.addView(channelFilterButton, LinearLayout.LayoutParams(-1, dp(48)))

        val mode = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val allId = View.generateViewId()
        val recentId = View.generateViewId()
        mode.addView(segmentButton("全部", allId), LinearLayout.LayoutParams(0, dp(44), 1f))
        mode.addView(segmentButton("最近播放", recentId), LinearLayout.LayoutParams(0, dp(44), 1f))
        mode.check(if (recent) recentId else allId)
        mode.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) {
                recent = checkedId == recentId
                page = 0
                pendingScrollRestore = 0
                showVideos()
            }
        }
        filters.addView(mode, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        videoHeader.addView(filters, rowParams(bottom = 8))

        videosContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(videosContainer)
        showVideos(channelsById)
    }

    private fun chooseVideoChannel(channels: JSONArray) {
        val options = Array(channels.length() + 1) { index ->
            if (index == 0) "全部频道"
            else channels.getJSONObject(index - 1).optString("title", "Telegram 频道")
        }
        val selected = if (channelFilter == 0L) 0 else (0 until channels.length())
            .firstOrNull { channels.getJSONObject(it).optLong("channel_id") == channelFilter }?.plus(1) ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle("选择频道")
            .setSingleChoiceItems(options, selected) { dialog, index ->
                channelFilter = if (index == 0) 0 else channels.getJSONObject(index - 1).getLong("channel_id")
                page = 0
                channelFilterButton?.text = options[index]
                pendingScrollRestore = 0
                dialog.dismiss()
                showVideos()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showVideos(channelNames: Map<Long, String>? = null) {
        val target = videosContainer ?: return
        val names = channelNames ?: app.database.channels().let { data ->
            buildMap {
                for (index in 0 until data.length()) {
                    val channel = data.getJSONObject(index)
                    put(channel.getLong("channel_id"), channel.optString("title", "Telegram 频道"))
                }
            }
        }
        val request = ++videoRequest
        buttons.removeAll { it.parent === target }
        target.removeAllViews()
        target.addView(LinearProgressIndicator(this).apply {
            isIndeterminate = true
            trackThickness = dp(2)
            trackColor = android.graphics.Color.TRANSPARENT
        }, LinearLayout.LayoutParams(-1, dp(2)))
        val query = search
        val offset = page * PAGE_SIZE
        val selectedChannel = channelFilter
        val playbackOnly = recent
        queryWorker.execute {
            val rows = runCatching { app.database.videos(query, offset, PAGE_SIZE + 1, selectedChannel, playbackOnly) }
            handler.post {
                if (isDestroyed || request != videoRequest || videosContainer !== target) return@post
                target.removeAllViews()
                val result = rows.getOrNull()
                if (result == null) {
                    target.addView(text("读取视频失败，请稍后重试。", 14).apply {
                        setTextColor(color(com.google.android.material.R.attr.colorError))
                    })
                    return@post
                }
                if (result.length() == 0) {
                    val message = if (playbackOnly) "还没有播放记录" else "没有找到匹配的视频"
                    target.addView(emptyInline(message, if (query.isNotEmpty()) "清除搜索条件" else "管理频道") {
                        if (query.isNotEmpty()) {
                            searchField?.text?.clear()
                        } else navigation.selectedItemId = R.id.nav_channels
                    })
                } else {
                    for (index in 0 until minOf(result.length(), PAGE_SIZE)) {
                        target.addView(videoRow(result.getJSONObject(index), names))
                    }
                    val hasNext = result.length() > PAGE_SIZE
                    if (page > 0 || hasNext) {
                        val pager = horizontalRow().apply {
                            gravity = Gravity.CENTER_VERTICAL
                            addView(actionButton("上一页", ButtonStyle.TEXT, R.drawable.ic_chevron_left) {
                                if (page > 0) { page--; pendingScrollRestore = 0; showVideos(names) }
                            }.apply { buttons.remove(this); isEnabled = page > 0 }, LinearLayout.LayoutParams(0, dp(48), 1f))
                            addView(text("第 ${page + 1} 页", 13).apply {
                                gravity = Gravity.CENTER
                                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                            }, LinearLayout.LayoutParams(dp(72), -2))
                            addView(actionButton("下一页", ButtonStyle.TEXT, R.drawable.ic_chevron_right) {
                                if (hasNext) { page++; pendingScrollRestore = 0; showVideos(names) }
                            }.apply { buttons.remove(this); isEnabled = hasNext }, LinearLayout.LayoutParams(0, dp(48), 1f))
                        }
                        target.addView(pager, rowParams(top = 8, bottom = 0))
                    }
                }
                restoreScroll()
            }
        }
    }

    private fun videoRow(item: JSONObject, channelNames: Map<Long, String>): View {
        val key = item.optString("key")
        val title = item.optString("name", "未命名视频")
        val channel = channelNames[item.optLong("channel_id")] ?: "Telegram 频道"
        val size = formatSize(item.optLong("size"))
        val row = horizontalRow().apply { setPadding(0, dp(11), 0, dp(11)) }
        row.apply {
            val ripple = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            isClickable = true
            isFocusable = true
            contentDescription = "播放：$title，$channel，$size"
            setOnClickListener {
                if (app.engine?.let { JSONObject(it.status()).optBoolean("connected") } != true) {
                    message("先连接 Telegram，再播放视频")
                    return@setOnClickListener
                }
                startActivity(Intent(this@MainActivity, PlayerActivity::class.java)
                    .putExtra("key", key).putExtra("title", title))
            }
        }
        val mark = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(9).toFloat()
                setColor(color(com.google.android.material.R.attr.colorSurfaceContainerLow))
            }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_movie)
                imageTintList = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorPrimary))
                contentDescription = null
            }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        row.addView(mark, LinearLayout.LayoutParams(dp(52), dp(52)).apply { marginEnd = dp(14) })
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
        }
        copy.addView(text(title, 15).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val meta = horizontalRow().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, 0)
        }
        meta.addView(text(channel, 12).apply {
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        meta.addView(text(size, 12).apply {
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            gravity = Gravity.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        copy.addView(meta)
        row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        item.addView(row, LinearLayout.LayoutParams(-1, -2))
        item.addView(View(this).apply {
            setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant))
        }, LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(64) })
        return item.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
    }

    private fun channelsPage() {
        paragraph("选择已加入的 Telegram 频道，或添加公开频道链接。")
        body.addView(actionButton("从 Telegram 选择", ButtonStyle.FILLED, R.drawable.ic_channels) { chooseChannelsFromTelegram() }, rowParams())

        val addRow = horizontalRow().apply { gravity = Gravity.CENTER_VERTICAL }
        val (box, reference) = createInput("频道链接 / 用户名 / ID", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).also {
            it.second.imeOptions = EditorInfo.IME_ACTION_DONE
        }
        reference.setText(channelDraft)
        reference.trackText { channelDraft = it }
        addRow.addView(box, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        addRow.addView(actionButton("添加", ButtonStyle.TONAL, R.drawable.ic_add) {
            val ref = reference.text.toString().trim()
            if (ref.isEmpty()) { reference.error = "输入频道链接、用户名或 ID"; return@actionButton }
            setActionsEnabled(false)
            app.perform({ core -> core.findChannel(ref).also { app.database.addChannel(JSONObject(it)) } }) { _, error ->
                if (isDestroyed) return@perform
                if (error != null) { setActionsEnabled(true); message(error) }
                else { channelDraft = ""; message("频道已加入"); render() }
            }
        }, LinearLayout.LayoutParams(dp(90), dp(54)))
        body.addView(addRow, rowParams())

        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        channelListContainer = listHost
        body.addView(listHost)
        loadChannels(listHost)
    }

    private fun scanPanel(): MaterialCardView {
        val panel = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(color(com.google.android.material.R.attr.colorSecondaryContainer))
            visibility = if (app.scanning || app.scanMessage != "索引任务等待开始") View.VISIBLE else View.GONE
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(10)) }
        val row = horizontalRow().apply { gravity = Gravity.CENTER_VERTICAL }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scanStatus = text(app.scanMessage, 14).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(com.google.android.material.R.attr.colorOnSecondaryContainer))
        }
        labels.addView(scanStatus)
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val pause = imageButton(R.drawable.ic_pause, "暂停扫描") {
            app.cancelScan()
            message("正在暂停，已完成页面会保留")
        }
        pause.isEnabled = true
        pause.visibility = if (app.scanning) View.VISIBLE else View.GONE
        scanPause = pause
        scanCard = panel
        row.addView(pause, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(row)
        scanIndicator = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            trackThickness = dp(2)
            trackColor = android.graphics.Color.TRANSPARENT
            visibility = if (app.scanning) View.VISIBLE else View.GONE
        }
        content.addView(scanIndicator, LinearLayout.LayoutParams(-1, dp(2)).apply { topMargin = dp(7) })
        panel.addView(content)
        return panel
    }

    private fun loadChannels(host: LinearLayout) {
        val request = ++channelRequest
        host.removeAllViews()
        host.addView(LinearProgressIndicator(this).apply { isIndeterminate = true; trackThickness = dp(2) },
            LinearLayout.LayoutParams(-1, dp(2)))
        queryWorker.execute {
            val result = runCatching {
                val channels = app.database.channels()
                channels to app.database.channelCounts()
            }
            handler.post {
                if (isDestroyed || request != channelRequest || channelListContainer !== host) return@post
                host.removeAllViews()
                result.onSuccess { (channels, counts) -> renderChannelCards(host, channels, counts) }
                    .onFailure { host.addView(text("无法读取频道列表，请重新进入此页。", 14)) }
                restoreScroll()
            }
        }
    }

    private fun renderChannelCards(host: LinearLayout, channels: JSONArray, counts: Map<Long, Long>) {
        if (channels.length() == 0) {
            host.addView(emptyInline("还没有添加频道", "从 Telegram 选择") { chooseChannelsFromTelegram() })
            return
        }
        host.addView(text("已加入 ${channels.length()} 个频道", 13).apply {
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(6), 0, dp(8))
        })
        for (index in 0 until channels.length()) {
            val channel = channels.getJSONObject(index)
            val id = channel.getLong("channel_id")
            val historyDone = channel.optBoolean("history_done")
            val refreshPending = channel.optLong("refresh_offset") > 0
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val titleRow = horizontalRow().apply { gravity = Gravity.CENTER_VERTICAL }
            val title = text(channel.optString("title", "Telegram 频道"), 16).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            titleRow.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            titleRow.addView(imageButton(R.drawable.ic_more, "${channel.optString("title")} 更多操作") {
                channelMenu(itAnchor = it, channel = channel)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            content.addView(titleRow)

            val metadata = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(9))
            }
            metadata.addView(text("${counts[id] ?: 0} 个视频", 12).apply {
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
            val stateText = when {
                refreshPending -> "新增同步可续"
                historyDone -> "历史已完成"
                channel.optLong("history_offset") > 0L -> "历史可续扫"
                else -> "尚未扫描"
            }
            metadata.addView(text(stateText, 12).apply {
                setTextColor(color(if (historyDone) com.google.android.material.R.attr.colorSecondary else com.google.android.material.R.attr.colorOnSurfaceVariant))
                gravity = Gravity.END
                setPadding(dp(12), 0, 0, 0)
            })
            content.addView(metadata)
            val updated = channel.optLong("updated")
            if (updated > 0L) content.addView(text("最近扫描 ${android.text.format.DateUtils.getRelativeTimeSpanString(updated)}", 12).apply {
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(4), 0, dp(10))
            })

            val actions = horizontalRow().apply { gravity = Gravity.CENTER_VERTICAL }
            actions.addView(actionButton(if (refreshPending) "继续同步" else "检查新增", ButtonStyle.TONAL, R.drawable.ic_sync) {
                beginScan(id, true)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            if (!historyDone) actions.addView(actionButton(if (channel.optLong("history_offset") > 0L) "继续历史" else "扫描历史", ButtonStyle.TEXT, R.drawable.ic_history) {
                beginScan(id, false)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(actions)
            content.isClickable = true
            content.isFocusable = true
            content.contentDescription = "${channel.optString("title")}，${counts[id] ?: 0} 个视频"
            content.setOnClickListener {
                channelFilter = id
                recent = false
                page = 0
                navigation.selectedItemId = R.id.nav_videos
            }
            host.addView(content, LinearLayout.LayoutParams(-1, -2))
            host.addView(View(this).apply {
                setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant))
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun channelMenu(itAnchor: View, channel: JSONObject) {
        val id = channel.getLong("channel_id")
        if (app.busy) { message("当前操作完成后再试"); return }
        PopupMenu(this, itAnchor).apply {
            menu.add("浏览视频").setOnMenuItemClickListener {
                channelFilter = id; recent = false; page = 0; navigation.selectedItemId = R.id.nav_videos; true
            }
            if (!channel.optBoolean("history_done")) menu.add("扫描历史").setOnMenuItemClickListener { beginScan(id, false); true }
            menu.add("移除频道").setOnMenuItemClickListener {
                confirmRemoveChannel(channel)
                true
            }
            show()
        }
    }

    private fun confirmRemoveChannel(channel: JSONObject) {
        val id = channel.getLong("channel_id")
        MaterialAlertDialogBuilder(this).setTitle("移除 ${channel.optString("title")}")
            .setMessage("只会移除本机的视频索引和播放记录。Telegram 频道成员身份不会改变。")
            .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                if (PlaybackService.currentKey?.let { app.database.video(it)?.optLong("channel_id") } == id) PlaybackService.stopPlayback(this)
                app.perform({ _ -> app.database.removeChannel(id); "" }) { _, error ->
                    if (!isDestroyed) {
                        if (channelFilter == id) channelFilter = 0
                        if (error != null) message(error) else message("频道已从本机移除")
                        render()
                    }
                }
            }.show()
    }

    private fun chooseChannelsFromTelegram() {
        if (!status.optBoolean("connected")) { message("请先连接 Telegram"); return }
        setActionsEnabled(false)
        app.perform({ it.listChannels() }) { value, error ->
            if (isDestroyed) return@perform
            if (error != null) { setActionsEnabled(true); message(error); return@perform }
            val remote = JSONArray(value)
            if (remote.length() == 0) { setActionsEnabled(true); message("当前账号没有可添加的频道"); return@perform }
            val local = app.database.channels()
            val existing = HashSet<Long>().apply { for (i in 0 until local.length()) add(local.getJSONObject(i).getLong("channel_id")) }
            val candidates = (0 until remote.length()).map { remote.getJSONObject(it) }
                .filterNot { existing.contains(it.getLong("channel_id")) }
            if (candidates.isEmpty()) { setActionsEnabled(true); message("当前账号没有尚未添加的频道"); return@perform }
            val labels = Array(candidates.size) { candidates[it].optString("title", "Telegram 频道") }
            val checked = BooleanArray(candidates.size)
            MaterialAlertDialogBuilder(this)
                .setTitle("添加 Telegram 频道")
                .setMultiChoiceItems(labels, checked) { _, index, selected -> checked[index] = selected }
                .setNegativeButton("取消") { _, _ -> setActionsEnabled(true) }
                .setPositiveButton("添加所选") { _, _ ->
                    app.perform({ _ ->
                        for (index in checked.indices) if (checked[index]) app.database.addChannel(candidates[index])
                        ""
                    }) { _, failure ->
                        if (!isDestroyed) {
                            if (failure != null) { setActionsEnabled(true); message(failure) }
                            else { message("频道列表已更新"); render() }
                        }
                    }
                }
                .setOnCancelListener { setActionsEnabled(true) }
                .show()
        }
    }

    private fun beginScan(id: Long, refresh: Boolean) {
        if (app.busy || app.scanning) { message("当前有索引任务正在运行"); return }
        if (!status.optBoolean("connected")) { message("请先连接 Telegram"); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingScan = id to refresh
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else launchScan(id, refresh)
    }

    private fun launchScan(id: Long, refresh: Boolean) {
        ContextCompat.startForegroundService(this,
            Intent(this, ScanService::class.java).putExtra("channel", id).putExtra("refresh", refresh))
    }

    private fun accountPage() {
        val phase = status.optString("state", "signed_out")
        loginFormPhase = phase
        if (phase in AUTHENTICATED_PHASES) {
            connectedAccountPage(phase)
        } else {
            loginPage(phase)
        }
        appearanceSettings()
    }

    private fun connectedAccountPage(phase: String) {
        val connected = phase == "ready"
        val panel = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(color(if (connected) com.google.android.material.R.attr.colorSecondaryContainer else com.google.android.material.R.attr.colorSurfaceContainerLow))
        }
        val content = horizontalRow().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        content.addView(ImageView(this).apply {
            setImageResource(if (connected) R.drawable.ic_check else R.drawable.ic_sync)
            imageTintList = ColorStateList.valueOf(color(if (connected) com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorPrimary))
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(14) })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text(if (connected) "Telegram 已连接" else phaseLabel(phase), 16).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(color(if (connected) com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorOnSurface))
        })
        copy.addView(text(when { connected -> "频道与播放已就绪"; phase == "expired" -> "重新登录原账号，保留本机视频库"; else -> "网络恢复后会自动重连" }, 13).apply {
            setTextColor(color(if (connected) com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(3), 0, 0)
        })
        content.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(content)
        body.addView(panel, rowParams(bottom = 12))
        if (phase == "expired") {
            body.addView(actionButton("重新授权", ButtonStyle.FILLED, R.drawable.ic_refresh) {
                MaterialAlertDialogBuilder(this).setTitle("重新授权原账号")
                    .setMessage("本机频道与播放记录会保留。请登录原 Telegram 账号；如需更换账号，请使用“退出此设备”。")
                    .setNegativeButton("取消", null).setPositiveButton("继续") { _, _ ->
                        val saved = app.credentials()
                        PlaybackService.stopPlayback(this, revokeSession = true)
                        // long: 失效授权只重建连接，不能走会清空媒体库的退出操作。
                        app.configure(saved.optLong("api_id"), saved.optString("api_hash")) { error ->
                            if (!isDestroyed) {
                                if (error != null) message(error)
                                else { status = JSONObject().put("state", "signed_out"); render() }
                            }
                        }
                    }.show()
            }, rowParams())
        }
        actionButton("管理频道", ButtonStyle.FILLED, R.drawable.ic_channels) {
            navigation.selectedItemId = R.id.nav_channels
        }.also { body.addView(it, rowParams()) }
        actionButton("退出此设备", ButtonStyle.TEXT, R.drawable.ic_logout) { confirmLogout() }
            .apply { setTextColor(color(com.google.android.material.R.attr.colorError)); iconTint = ColorStateList.valueOf(currentTextColor) }
            .also { body.addView(it, rowParams()) }
    }

    private fun appearanceSettings() {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(26), 0, 0)
        }
        section.addView(text("偏好设置", 14).apply {
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            ViewCompat.setAccessibilityHeading(this, true)
        })
        val modes = intArrayOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
        val labels = arrayOf("跟随系统", "浅色", "深色")
        val current = getSharedPreferences("appearance", MODE_PRIVATE).getInt("theme", modes[0])
        val choice = modes.indexOf(current).coerceAtLeast(0)
        val row = horizontalRow().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            isClickable = true; isFocusable = true
            contentDescription = "外观，${labels[choice]}"
            val ripple = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener {
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("外观")
                    .setSingleChoiceItems(labels, choice) { dialog, index ->
                        getSharedPreferences("appearance", MODE_PRIVATE).edit().putInt("theme", modes[index]).apply()
                        dialog.dismiss()
                        AppCompatDelegate.setDefaultNightMode(modes[index])
                    }.setNegativeButton("取消", null).show()
            }
        }
        row.addView(text("外观", 16), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text(labels[choice], 14).apply { setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant)) })
        row.addView(ImageView(this).apply { setImageResource(R.drawable.ic_chevron_right); imageTintList = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant)) },
            LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
        section.addView(row)
        section.addView(text("TGProxy ${BuildConfig.VERSION_NAME}", 12).apply {
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(16), 0, dp(4))
        })
        body.addView(section)
    }

    private fun loginPage(phase: String) {
        paragraph(when (phase) {
            "expired" -> "授权已过期。重新连接后可继续使用已保存的频道索引。"
            "offline", "connecting" -> "正在恢复 Telegram 连接。检查网络后通常会自动重连。"
            "code", "password" -> "验证码用于确认 Telegram 账号本人操作。"
            else -> "连接 Telegram 后，即可浏览已加入频道中的视频。"
        })
        when (phase) {
            "code" -> {
                val code = addInput("Telegram 验证码", InputType.TYPE_CLASS_NUMBER).apply {
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    setText(codeDraft); trackText { codeDraft = it }; isSaveEnabled = false
                }
                actionButton("确认验证码", ButtonStyle.FILLED, R.drawable.ic_arrow_right) {
                    val value = code.text.toString().trim()
                    code.text?.clear()
                    setActionsEnabled(false)
                    login { it.signIn(value) }
                }.also { body.addView(it, rowParams()) }
                actionButton("重新发送验证码", ButtonStyle.TEXT) {
                    if (phone.isBlank()) { message("返回填写手机号"); return@actionButton }
                    setActionsEnabled(false)
                    login { it.sendCode(phone) }
                }.also { body.addView(it, rowParams()) }
            }
            "password" -> {
                val password = addInput("两步验证密码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
                    setText(passwordDraft); trackText { passwordDraft = it }
                }
                actionButton("完成登录", ButtonStyle.FILLED, R.drawable.ic_arrow_right) {
                    val value = password.text.toString()
                    password.text?.clear()
                    setActionsEnabled(false)
                    login { it.submitPassword(value) }
                }.also { body.addView(it, rowParams()) }
            }
            else -> phoneLoginForm()
        }
    }

    private fun phoneLoginForm() {
        loginFormPhase = "phone"
        val phoneField = addInput("手机号（含国家区号）", InputType.TYPE_CLASS_PHONE).apply {
            setText(phone)
            imeOptions = EditorInfo.IME_ACTION_NEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { phone = s?.toString().orEmpty() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        if (!credentialsLoaded) {
            val saved = app.credentials()
            credentialDraftId = saved.optLong("api_id").takeIf { it > 0 }?.toString().orEmpty()
            credentialDraftHash = saved.optString("api_hash")
            credentialsLoaded = true
        }
        val credentials = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (credentialsExpanded) View.VISIBLE else View.GONE
        }
        lateinit var toggle: MaterialButton
        toggle = actionButton(
            label = if (credentialsExpanded) "隐藏 API 凭证" else "API ID / Hash（可选）",
            style = ButtonStyle.TEXT,
            icon = if (credentialsExpanded) R.drawable.ic_collapse else R.drawable.ic_expand
        ) {
            // long: 仅展开当前表单区域，保留手机号输入控件、焦点和未提交凭证。
            credentialsExpanded = !credentialsExpanded
            credentials.visibility = if (credentialsExpanded) View.VISIBLE else View.GONE
            toggle.text = if (credentialsExpanded) "隐藏 API 凭证" else "API ID / Hash（可选）"
            toggle.setIconResource(if (credentialsExpanded) R.drawable.ic_collapse else R.drawable.ic_expand)
        }
        body.addView(toggle, rowParams())
        credentials.addView(text("两项留空使用预设值；使用自己的凭证时请同时填写。", 12).apply {
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        val (idBox, idField) = createInput("API ID", InputType.TYPE_CLASS_NUMBER)
        idField.setText(credentialDraftId)
        idField.isSaveEnabled = false
        idField.trackText { credentialDraftId = it; credentialEdited = true }
        credentials.addView(idBox, rowParams())
        val (hashBox, hashField) = createInput("API Hash", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        hashField.setText(credentialDraftHash)
        hashField.trackText { credentialDraftHash = it; credentialEdited = true }
        credentials.addView(hashBox, rowParams())
        body.addView(credentials)

        actionButton("发送验证码", ButtonStyle.FILLED, R.drawable.ic_arrow_right) {
            phone = phoneField.text?.toString()?.trim().orEmpty()
            if (!phone.startsWith("+") || phone.length !in 7..18) {
                phoneField.error = "输入带国家区号的手机号"
                return@actionButton
            }
            if (!credentialEdited) {
                setActionsEnabled(false)
                login { it.sendCode(phone) }
                return@actionButton
            }
            val rawID = credentialDraftId.trim()
            val rawHash = credentialDraftHash.trim()
            val appID = if (rawID.isEmpty()) 0L else rawID.toLongOrNull()
            if (appID == null || (rawID.isEmpty() != rawHash.isEmpty()) ||
                (rawID.isNotEmpty() && (appID !in 1..2147483647 || !rawHash.matches(Regex("[0-9a-fA-F]{32}"))))) {
                message("请填写完整的 API ID 和 32 位 Hash，或将两项都留空")
                return@actionButton
            }
            setActionsEnabled(false)
            app.configure(appID, rawHash) { failure ->
                if (isDestroyed) return@configure
                if (failure != null) { setActionsEnabled(true); message(failure) }
                else login { it.sendCode(phone) }
            }
        }.also { body.addView(it, rowParams(top = 10)) }
        body.addView(text("验证码可能会发到已登录的 Telegram 应用内。", 12).apply {
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(4), dp(6), dp(4), 0)
        })
    }

    private fun login(operation: (dev.tgproxy.core.mobilecore.Engine) -> String) {
        app.perform(operation) { value, error ->
            if (isDestroyed) return@perform
            if (error != null) { setActionsEnabled(true); message(error) }
            else { status.put("state", value); lastPhase = value ?: ""; render() }
        }
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this).setTitle("退出此设备？")
            .setMessage("会清除此设备保存的 Telegram 授权、频道索引和播放记录。Telegram 账号及频道消息不会被删除。")
            .setNegativeButton("取消", null).setPositiveButton("退出") { _, _ ->
                app.logout { error ->
                    if (!isDestroyed) {
                        if (error != null) message(error)
                        else { status = JSONObject().put("state", "signed_out"); credentialsLoaded = false; credentialEdited = false; credentialsExpanded = false; credentialDraftId = ""; credentialDraftHash = ""; codeDraft = ""; passwordDraft = ""; render() }
                    }
                }
            }.show()
    }

    private fun playValidation() {
        if (app.engine == null) { message(app.startupError ?: "内核仍在准备，请稍后重试"); return }
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra("validation", true).putExtra("title", "本地测试视频 · 30 秒"))
    }

    private fun emptyState(icon: Int, title: String, detail: String, command: String, action: () -> Unit) {
        val panel = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(34), dp(24), dp(30))
        }
        val mark = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(color(com.google.android.material.R.attr.colorPrimaryContainer))
            }
            addView(ImageView(this@MainActivity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnPrimaryContainer))
                contentDescription = null
            }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        }
        content.addView(mark, LinearLayout.LayoutParams(dp(62), dp(62)).apply { bottomMargin = dp(18) })
        content.addView(text(title, 18).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        })
        content.addView(text(detail, 14).apply {
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        })
        content.addView(actionButton(command, ButtonStyle.FILLED, R.drawable.ic_arrow_right, action),
            LinearLayout.LayoutParams(-1, dp(48)))
        panel.addView(content)
        body.addView(panel, rowParams(top = 18))
    }

    private fun emptyInline(title: String, command: String, action: () -> Unit): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(28), dp(12), dp(24))
        }
        block.addView(text(title, 14).apply {
            gravity = Gravity.CENTER
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        block.addView(actionButton(command, ButtonStyle.TEXT, R.drawable.ic_refresh, action),
            LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(7) })
        return block
    }

    private fun heading(value: String) {
        body.addView(text(value, 22).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
            ViewCompat.setAccessibilityHeading(this, true)
        }, rowParams(bottom = 6))
    }

    private fun paragraph(value: String): TextView = text(value, 14).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setLineSpacing(dp(3).toFloat(), 1f)
        body.addView(this, rowParams(bottom = 12))
    }

    private fun addInput(label: String, type: Int): TextInputEditText {
        val (box, field) = createInput(label, type)
        body.addView(box, rowParams())
        return field
    }

    private fun createInput(label: String, type: Int): Pair<TextInputLayout, TextInputEditText> {
        val box = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            isHintEnabled = true
        }
        val field = TextInputEditText(box.context).apply {
            setSingleLine(true)
            inputType = type
            minHeight = dp(52)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            if (type and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) {
                // long: 单行设置会替换文字变换器；密码遮蔽必须最后应用，避免凭证切页后变成明文。
                transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                typeface = Typeface.DEFAULT
                isSaveEnabled = false
            }
        }
        // long: TextInputLayout 读取内部 LinearLayout 边距；使用通用 LayoutParams 会在页面创建时崩溃。
        box.addView(field, LinearLayout.LayoutParams(-1, -2))
        return box to field
    }

    private fun actionButton(
        label: String,
        style: ButtonStyle,
        icon: Int? = null,
        action: () -> Unit
    ): MaterialButton = MaterialButton(
        if (style == ButtonStyle.TEXT) ContextThemeWrapper(this, R.style.ThemeOverlay_TGProxy_TextButton) else this,
        null, when (style) {
        ButtonStyle.FILLED -> com.google.android.material.R.attr.materialButtonStyle
        ButtonStyle.TONAL -> com.google.android.material.R.attr.materialButtonStyle
        ButtonStyle.OUTLINED -> com.google.android.material.R.attr.materialButtonOutlinedStyle
        ButtonStyle.TEXT -> com.google.android.material.R.attr.materialButtonStyle
    }).apply {
        text = label
        isAllCaps = false
        minimumWidth = 0
        setPadding(dp(12), paddingTop, dp(12), paddingBottom)
        minHeight = dp(48)
        insetTop = 0
        insetBottom = 0
        if (style == ButtonStyle.TONAL) {
            backgroundTintList = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorSecondaryContainer))
            setTextColor(color(com.google.android.material.R.attr.colorOnSecondaryContainer))
        }
        icon?.let {
            setIconResource(it)
            iconPadding = dp(7)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconTint = ColorStateList.valueOf(color(if (style == ButtonStyle.FILLED) com.google.android.material.R.attr.colorOnPrimary else com.google.android.material.R.attr.colorPrimary))
        }
        isEnabled = !app.busy
        setOnClickListener { action() }
        buttons.add(this)
    }

    private fun segmentButton(label: String, id: Int) = MaterialButton(
        this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        this.id = id
        text = label
        isAllCaps = false
        isCheckable = true
        minimumWidth = 0
        minWidth = 0
        setPadding(dp(8), paddingTop, dp(8), paddingBottom)
        minHeight = dp(48)
        insetTop = 0
        insetBottom = 0
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private fun imageButton(icon: Int, description: String, action: (View) -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        setBackgroundResource(value.resourceId)
        contentDescription = description
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        isEnabled = true
        setOnClickListener { action(this) }
    }

    private fun horizontalRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun text(value: String, size: Int) = TextView(this).apply {
        text = value
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
    }

    private fun rowParams(top: Int = 0, bottom: Int = 8) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun setActionsEnabled(enabled: Boolean) {
        buttons.forEach { it.isEnabled = enabled && !app.busy }
    }

    private fun phaseLabel(phase: String) = when (phase) {
        "ready" -> "Telegram 已连接"
        "code" -> "等待验证码"
        "password" -> "等待两步验证"
        "connecting" -> "正在连接 Telegram"
        "offline" -> "网络中断，正在重连"
        "expired" -> "登录已失效"
        "signed_out" -> "尚未登录 Telegram"
        else -> app.startupError ?: "正在准备播放内核"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.getDefault(), "%.1f GiB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.getDefault(), "%.0f MiB", bytes / (1024.0 * 1024))
        else -> String.format(java.util.Locale.getDefault(), "%.0f KiB", bytes / 1024.0)
    }

    private fun TextInputEditText.trackText(changed: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun updateMiniPlayer() {
        lastPlayingKey = PlaybackService.currentKey
        miniPlayer.removeAllViews()
        refreshToolbarMenu()
        val key = lastPlayingKey ?: return
        val caption = text("返回正在播放", 14).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(14), 0, dp(6), 0)
        }
        val row = horizontalRow().apply {
            minimumHeight = dp(56); gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(com.google.android.material.R.attr.colorPrimaryContainer))
            isClickable = true; isFocusable = true
            contentDescription = "返回正在播放"
            setOnClickListener { startActivity(Intent(this@MainActivity, PlayerActivity::class.java)) }
        }
        row.addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(imageButton(R.drawable.ic_play, "返回正在播放") {
            startActivity(Intent(this, PlayerActivity::class.java))
        }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; isFocusable = false }, LinearLayout.LayoutParams(dp(48), dp(48)))
        miniPlayer.addView(row)
        queryWorker.execute {
            val title = runCatching { app.database.video(key)?.optString("name") }.getOrNull() ?: "本地测试视频"
            handler.post { if (!isDestroyed && lastPlayingKey == key) { caption.text = title; row.contentDescription = "返回正在播放：$title" } }
        }
    }

    private fun color(attr: Int) = MaterialColors.getColor(this, attr, "TGProxy")
    private fun message(value: String) {
        Snackbar.make(root, value, Snackbar.LENGTH_LONG).setAnchorView(navigation).show()
    }
    private fun dp(size: Int) = (size * resources.displayMetrics.density).toInt()

    private enum class ButtonStyle { FILLED, TONAL, OUTLINED, TEXT }

    companion object {
        private const val PAGE_SIZE = 50
        private const val MENU_CURRENT_PLAYBACK = 1001
        private const val MENU_TEST_MEDIA = 1002
        private val AUTHENTICATED_PHASES = setOf("ready", "connecting", "offline", "expired")
    }
}
