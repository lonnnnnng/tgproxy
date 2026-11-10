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
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import dev.longvideo.controls.PlaybackPreferences
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
    private lateinit var navigation: BottomNavigationView
    private lateinit var scroll: ScrollView
    private lateinit var videoHeader: LinearLayout
    private lateinit var miniPlayer: LinearLayout
    private var lastPlayingKey: String? = null
    private var lastPlaying = false
    private var sort = "newest"
    private var activeSheet: BottomSheetDialog? = null
    internal var activeSheetContent: View? = null
        private set
    private var updateAllButton: ChannelUpdateButton? = null
    private var channelSummary: TextView? = null
    private data class ChannelRowState(val channel: JSONObject, val count: Long, val label: TextView, val update: ChannelUpdateButton)
    private val channelRows = mutableMapOf<Long, ChannelRowState>()
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
    private var completedScanID = 0L
    private var hasStarted = false
    private var status = JSONObject()
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
    private var pendingRecheck = false
    private var pendingAll = false
    private var scanLaunching = false
    private var recentSort = "watched"
    private var lastAccountIssue: String? = null
    private var lastStartupIssue: String? = null
    private var searchRunnable: Runnable? = null
    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {
        pendingScan?.let { launchScan(it.first, it.second, pendingRecheck, pendingAll) }
        pendingScan = null
    }

    private val poll = object : Runnable {
        override fun run() {
            status = app.engine?.let { JSONObject(it.status()) } ?: JSONObject()
            val phase = status.optString("state", "starting")
            if (lastAccountIssue != app.accountIssue || lastStartupIssue != app.startupError) {
                lastAccountIssue = app.accountIssue; lastStartupIssue = app.startupError; render()
            }
            // long: 扫描在原按钮和频道行反馈，不能再出现全局加载条挤动页面。
            val scans = app.scanQueue.snapshot
            progress.visibility = if (app.busy && !app.scanning && scans.runID <= completedScanID) View.VISIBLE else View.GONE
            updateScanViews(scans)
            if (!scans.running && scans.runID > completedScanID && !app.busy) {
                completedScanID = scans.runID
                message(scans.summary)
                if (destination == R.id.nav_videos) {
                    pendingScrollRestore = scroll.scrollY
                    showVideos()
                }
                channelListContainer?.let(::loadChannels)
            }
            val signedOutForm = phase in setOf("signed_out", "starting")
            if (destination == R.id.nav_account && phase != lastPhase && !app.busy &&
                !(signedOutForm && loginFormPhase == "phone")) render()
            if (destination == R.id.nav_videos && phase != lastPhase && !app.busy) {
                if (lastPhase == "starting" || phase == "signed_out" || (lastPhase in setOf("connecting", "reconnecting") && phase == "ready")) render()
            }
            if (PlaybackService.currentKey != lastPlayingKey || PlaybackService.isPlaybackRequested() != lastPlaying) updateMiniPlayer()
            if (app.busy != lastBusy) {
                buttons.forEach { it.isEnabled = !app.busy }
                if (lastBusy && !app.busy && destination == R.id.nav_channels && scans.runID == 0L) channelListContainer?.let(::loadChannels)
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
        completedScanID = savedInstanceState?.getLong("completedScanID") ?: app.scanQueue.snapshot.runID
        pendingScan = savedInstanceState?.takeIf { it.containsKey("pendingScan") }?.let {
            it.getLong("pendingScan") to it.getBoolean("pendingRefresh")
        }
        pendingRecheck = savedInstanceState?.getBoolean("pendingRecheck") ?: false
        pendingAll = savedInstanceState?.getBoolean("pendingAll") ?: false
        if (savedInstanceState == null && intent.getBooleanExtra("open_channels", false)) destination = R.id.nav_channels
        if (savedInstanceState == null && intent.hasExtra("browse_channel")) {
            channelFilter = intent.getLongExtra("browse_channel", 0)
        }
        sort = getSharedPreferences("library", MODE_PRIVATE).getString("sort", "newest") ?: "newest"
        recentSort = getSharedPreferences("library", MODE_PRIVATE).getString("recent_sort", "watched") ?: "watched"
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

        progress = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            trackThickness = dp(2)
            trackColor = android.graphics.Color.TRANSPARENT
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))

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
            menu.add(0, R.id.nav_account, 2, "我的").setIcon(R.drawable.ic_account)
            itemIconSize = dp(20)
            itemActiveIndicatorColor = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorPrimaryContainer))
            val navColors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(color(com.google.android.material.R.attr.colorPrimary), muted()))
            itemIconTintList = navColors; itemTextColor = navColors
            itemPaddingTop = dp(5); itemPaddingBottom = dp(3)
            itemTextAppearanceActive = R.style.TextAppearance_TGProxy_Nav
            itemTextAppearanceInactive = R.style.TextAppearance_TGProxy_Nav
            selectedItemId = destination
            setOnItemSelectedListener { item ->
                scrollPositions[destination] = scroll.scrollY
                destination = item.itemId; render()
                true
            }
        }
        miniPlayer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(miniPlayer)
        root.addView(navigation, LinearLayout.LayoutParams(-1, dp(56)))
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra("browse_channel")) {
            browseChannel(intent.getLongExtra("browse_channel", 0))
        }
        if (intent.getBooleanExtra("open_channels", false)) navigation.selectedItemId = R.id.nav_channels
    }

    private fun browseChannel(id: Long) {
        // long: 从频道或详情进入时清除旧搜索和偏移，否则新频道可能看起来没有资源。
        channelFilter = id; recent = false; search = ""; page = 0
        scrollPositions[R.id.nav_videos] = 0
        scroll.scrollTo(0, 0)
        navigation.selectedItemId = R.id.nav_videos
    }

    override fun onStop() {
        scrollPositions[destination] = scroll.scrollY
        handler.removeCallbacks(poll)
        updateAllButton?.stopAnimation()
        channelRows.values.forEach { it.update.stopAnimation() }
        searchRunnable?.let(handler::removeCallbacks)
        super.onStop()
    }

    override fun onDestroy() {
        activeSheet?.dismiss()
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
        outState.putLong("completedScanID", completedScanID)
        pendingScan?.let { outState.putLong("pendingScan", it.first); outState.putBoolean("pendingRefresh", it.second) }
        outState.putBoolean("pendingRecheck", pendingRecheck)
        outState.putBoolean("pendingAll", pendingAll)
        if (::scroll.isInitialized) outState.putInt("scroll", scroll.scrollY)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        if (!::body.isInitialized) return
        videoRequest++
        channelRequest++
        searchRunnable?.let(handler::removeCallbacks)
        activeSheet?.dismiss()
        buttons.clear()
        updateAllButton?.stopAnimation(); updateAllButton = null; channelSummary = null
        channelRows.values.forEach { it.update.stopAnimation() }; channelRows.clear()
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

    private fun videosPage() {
        if (app.accountIssue != null) {
            videoHeader.visibility = View.GONE
            emptyState(R.drawable.ic_account, "请确认账号", app.accountIssue!!, "管理账号") { navigation.selectedItemId = R.id.nav_account }
            return
        }
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

        val searchBox = horizontalRow().apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44)
            setPadding(dp(12), 0, dp(4), 0)
            background = rounded(color(com.google.android.material.R.attr.colorSurfaceContainerLow), 10)
        }
        searchBox.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search); imageTintList = ColorStateList.valueOf(muted())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        val clearSearch = imageButton(R.drawable.ic_close, "清空搜索") { searchField?.text?.clear() }.apply {
            visibility = if (search.isEmpty()) View.GONE else View.VISIBLE
        }
        val searchInput = TextInputEditText(searchBox.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine(true)
            hint = "搜索视频名称"
            contentDescription = "搜索视频名称"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            minHeight = dp(44)
            // long: 搜索没有浮动标签，使用普通内边距，避免大字体沿用表单标签预留高度而裁切文字。
            background = null; gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
            setPadding(dp(10), dp(4), dp(4), dp(4)); setHintTextColor(muted())
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
                    clearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
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
        searchBox.addView(searchInput, LinearLayout.LayoutParams(0, -2, 1f))
        searchBox.addView(clearSearch, LinearLayout.LayoutParams(dp(44), dp(44)))
        videoHeader.addView(searchBox, rowParams())

        val channelsById = buildMap<Long, String> {
            for (index in 0 until channels.length()) {
                val channel = channels.getJSONObject(index)
                put(channel.getLong("channel_id"), channel.optString("title", "Telegram 频道"))
            }
        }
        val filters = horizontalRow().apply { gravity = Gravity.CENTER_VERTICAL }
        if (channelFilter != 0L && !channelsById.containsKey(channelFilter)) { channelFilter = 0; page = 0; pendingScrollRestore = 0 }
        val channelTitle = channelsById[channelFilter] ?: "全部频道"
        channelFilterButton = actionButton(
            label = channelTitle,
            style = ButtonStyle.TEXT,
            icon = R.drawable.ic_filter,
            action = { chooseVideoChannel(channels) }
        ).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL }


        val mode = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val allId = View.generateViewId()
        val recentId = View.generateViewId()
        mode.addView(segmentButton("全部视频", allId), LinearLayout.LayoutParams(0, dp(44), 1f))
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
        filters.addView(mode, LinearLayout.LayoutParams(0, dp(44), 1f))
        filters.addView(channelFilterButton, LinearLayout.LayoutParams(dp(124), dp(44)))
        videoHeader.addView(filters, rowParams(bottom = 0))
        videoHeader.addView(divider())

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
        val order = if (recent) recentSort else sort
        queryWorker.execute {
            val rows = runCatching { app.database.videos(query, offset, PAGE_SIZE + 1, selectedChannel, playbackOnly, order) }
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
                if (result.length() == 0 && page > 0) {
                    page = 0; pendingScrollRestore = 0; showVideos(names); return@post
                }
                val heading = horizontalRow().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(2)) }
                heading.addView(text(if (query.isNotEmpty()) "搜索结果" else if (playbackOnly) "最近播放" else "视频列表", 14).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
                heading.addView(imageButton(R.drawable.ic_sort, "视频排序") { showSortPicker() }, LinearLayout.LayoutParams(dp(40), dp(40)))
                target.addView(heading)
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
        val title = item.optString("name", "未命名视频")
        val channel = channelNames[item.optLong("channel_id")] ?: "Telegram 频道"
        val published = MediaPresentation.published(item.optLong("date"))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(55)
            setPadding(0, dp(7), 0, dp(7))
            clickableRow(this) {
                startActivity(Intent(this@MainActivity, VideoDetailActivity::class.java).putExtra("key", item.getString("key")))
            }
            contentDescription = "查看视频：$title，$channel" + if (published.isNotEmpty()) "，发布于 $published" else ""
        }
        row.addView(text(title, 13).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val meta = horizontalRow().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, 0) }
        meta.addView(text(channel, 11).apply { setTextColor(muted()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        if (published.isNotEmpty()) meta.addView(text(published, 11).apply {
            setTextColor(muted()); setPadding(dp(8), 0, 0, 0)
            contentDescription = "发布时间 $published"
        })
        meta.addView(text(formatSize(item.optLong("size")), 11).apply { setTextColor(muted()); setPadding(dp(10), 0, 0, 0) })
        row.addView(meta)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; addView(row, LinearLayout.LayoutParams(-1, -2)); addView(divider())
        }
    }

    private fun showSortPicker() {
        val values = if (recent) arrayOf("watched", "newest", "oldest", "name") else arrayOf("newest", "oldest", "name")
        val options = if (recent) arrayOf("最近观看", "发布时间：从新到旧", "发布时间：从旧到新", "名称") else arrayOf("发布时间：从新到旧", "发布时间：从旧到新", "名称")
        val sheet = sheet("视频排序")
        options.forEachIndexed { index, label ->
            sheet.addView(settingRow(label, if ((if (recent) recentSort else sort) == values[index]) "已选" else "", R.drawable.ic_sort) {
                if (recent) recentSort = values[index] else sort = values[index]
                getSharedPreferences("library", MODE_PRIVATE).edit().putString("sort", sort).putString("recent_sort", recentSort).apply()
                activeSheet?.dismiss(); page = 0; pendingScrollRestore = 0; showVideos()
            })
        }
    }

    private fun channelsPage() {
        if (app.accountIssue != null) {
            emptyState(R.drawable.ic_account, "请确认账号", app.accountIssue!!, "管理账号") { navigation.selectedItemId = R.id.nav_account }; return
        }
        val bar = horizontalRow().apply { gravity = Gravity.CENTER_VERTICAL }
        val summary = text("正在读取本机索引…", 11).apply { setTextColor(muted()) }
        channelSummary = summary
        bar.addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
        updateAllButton = ChannelUpdateButton(this).apply {
            setOnClickListener {
                if (app.scanQueue.snapshot.running) pauseScan()
                else if (channelRows.isEmpty()) message("请先添加频道")
                else beginScan(0, true, all = true)
            }
        }
        bar.addView(updateAllButton, LinearLayout.LayoutParams(dp(44), dp(48)))
        bar.addView(actionButton("添加频道", ButtonStyle.TEXT, R.drawable.ic_add) { showAddChannel() }.apply { textSize = 12f; iconSize = dp(17) }, LinearLayout.LayoutParams(-2, dp(44)))
        body.addView(bar)
        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        channelListContainer = listHost
        body.addView(listHost)
        loadChannels(listHost)
        updateScanViews(app.scanQueue.snapshot)
        body.addView(text("频道视频只保存在本机索引中。移除频道不会退出 Telegram 频道。", 11).apply {
            setTextColor(muted()); setPadding(0, dp(16), 0, 0)
        })
    }

    private fun showAddChannel() {
        val content = sheet("添加频道")
        content.addView(actionButton("从 Telegram 选择", ButtonStyle.TONAL, R.drawable.ic_channels) {
            activeSheet?.dismiss(); chooseChannelsFromTelegram()
        }, rowParams())
        val (box, reference) = createInput("频道链接 / 用户名 / ID", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        reference.setText(channelDraft)
        reference.trackText { channelDraft = it }
        content.addView(box, rowParams(top = 12))
        content.addView(actionButton("添加", ButtonStyle.FILLED, R.drawable.ic_add) {
            if (!app.mediaReady()) { message(app.accountIssue ?: "请先确认 Telegram 账号"); return@actionButton }
            val ref = reference.text.toString().trim()
            if (ref.isEmpty()) { reference.error = "输入频道链接、用户名或 ID"; return@actionButton }
            setActionsEnabled(false)
            app.perform({ core -> core.findChannel(ref).also { app.database.addChannel(JSONObject(it)) } }) { _, error ->
                if (!isDestroyed) {
                    if (error != null) { setActionsEnabled(true); box.error = error }
                    else { channelDraft = ""; activeSheet?.dismiss(); message("频道已加入"); render() }
                }
            }
        }, rowParams(top = 10))
    }

    private fun pauseScan() {
        app.cancelScan()
        updateScanViews(app.scanQueue.snapshot)
    }

    private fun updateScanViews(state: ChannelScanSnapshot) {
        val stopping = state.entries.values.any { it.phase == ScanPhase.STOPPING }
        updateAllButton?.bind(if (state.running) "暂停当前更新任务" else "更新全部频道",
            state.running && !stopping, !stopping && (!app.busy || state.running))
        navigation.getOrCreateBadge(R.id.nav_channels).apply { isVisible = state.running }
        channelRows.forEach { (id, row) ->
            val entry = state.entries[id]
            val active = entry?.phase == ScanPhase.RUNNING
            val defaultLabel = when {
                row.channel.optLong("refresh_offset") > 0 -> "更新可续"
                row.channel.optBoolean("history_done") -> "历史完成"
                row.channel.optLong("history_offset") > 0 -> "历史可续"
                else -> "尚未更新"
            }
            val label = entry?.message ?: defaultLabel
            row.label.text = "${entry?.total ?: row.count} 个视频 · $label"
            row.label.contentDescription = row.label.text
            row.label.setTextColor(if (entry?.phase == ScanPhase.ERROR) color(com.google.android.material.R.attr.colorError) else muted())
            val title = row.channel.optString("title")
            val action = when (entry?.phase) {
                ScanPhase.RUNNING -> "暂停更新"
                ScanPhase.STOPPING -> "正在暂停"
                ScanPhase.WAITING -> "等待更新"
                ScanPhase.ERROR -> "重试更新"
                ScanPhase.PAUSED -> "继续更新"
                else -> "更新频道"
            }
            row.update.bind("$action：$title", active,
                active || (!state.running && !app.busy))
        }
        channelSummary?.text = if (state.running) {
            val complete = state.tasks.count { state.entries[it.channelID]?.phase in listOf(ScanPhase.DONE, ScanPhase.ERROR) }
            if (stopping) "正在暂停…" else "处理中 · $complete / ${state.tasks.size} 个频道"
        } else if (channelRows.isNotEmpty()) {
            val total = channelRows.values.sumOf { state.entries[it.channel.getLong("channel_id")]?.total ?: it.count }
            "${channelRows.size} 个频道 / $total 个视频"
        } else "0 个频道 / 0 个视频"
    }

    private fun loadChannels(host: LinearLayout) {
        val request = ++channelRequest
        queryWorker.execute {
            val result = runCatching {
                val channels = app.database.channels()
                channels to app.database.channelCounts()
            }
            handler.post {
                if (isDestroyed || request != channelRequest || channelListContainer !== host) return@post
                channelRows.values.forEach { it.update.stopAnimation() }; channelRows.clear()
                host.removeAllViews()
                result.onSuccess { (channels, counts) -> renderChannelCards(host, channels, counts) }
                    .onFailure { host.addView(text("无法读取频道列表，请重新进入此页。", 14)) }
                restoreScroll()
                updateScanViews(app.scanQueue.snapshot)
            }
        }
    }

    private fun renderChannelCards(host: LinearLayout, channels: JSONArray, counts: Map<Long, Long>) {
        if (channels.length() == 0) { host.addView(emptyInline("还没有添加频道", "从 Telegram 选择") { chooseChannelsFromTelegram() }); return }
        for (index in 0 until channels.length()) {
            val channel = channels.getJSONObject(index)
            val id = channel.getLong("channel_id")
            val title = channel.optString("title", "Telegram 频道")
            val row = horizontalRow().apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(52); setPadding(0, dp(3), 0, dp(3)) }
            row.addView(text(title.take(1), 13).apply {
                gravity = Gravity.CENTER
                setTextColor(color(com.google.android.material.R.attr.colorPrimary))
                background = rounded(color(com.google.android.material.R.attr.colorPrimaryContainer), 9)
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(44)
                clickableRow(this) { browseChannel(id) }
            }
            copy.addView(text(title, 13).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTypeface(typeface, Typeface.BOLD) })
            val stateLabel = text("", 11).apply {
                setTextColor(muted()); setPadding(0, dp(3), 0, 0)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            copy.addView(stateLabel)
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
            val update = ChannelUpdateButton(this).apply {
                setOnClickListener {
                    val entry = app.scanQueue.snapshot.entries[id]
                    if (entry?.phase == ScanPhase.RUNNING) pauseScan()
                    else {
                        val mode = if (entry?.phase in listOf(ScanPhase.PAUSED, ScanPhase.ERROR)) entry!!.task.mode else ScanMode.UPDATE
                        beginScan(id, mode == ScanMode.UPDATE, mode == ScanMode.RECHECK)
                    }
                }
            }
            channelRows[id] = ChannelRowState(channel, counts[id] ?: 0, stateLabel, update)
            row.addView(update, LinearLayout.LayoutParams(dp(44), dp(48)))
            row.addView(imageButton(R.drawable.ic_more, "$title 更多操作") { channelMenu(it, channel) }, LinearLayout.LayoutParams(dp(40), dp(44)))
            host.addView(row); host.addView(divider())
        }
    }

    private fun channelMenu(itAnchor: View, channel: JSONObject) {
        val id = channel.getLong("channel_id")
        if (app.busy) { message("当前操作完成后再试"); return }
        PopupMenu(this, itAnchor).apply {
            menu.add("浏览视频").setOnMenuItemClickListener {
                browseChannel(id); true
            }
            menu.add(if (channel.has("recheck_key")) "继续校验已索引资源" else "校验已索引资源").setOnMenuItemClickListener { beginScan(id, false, true); true }
            app.scanQueue.snapshot.entries[id]?.error?.let { failure ->
                menu.add("查看更新失败原因").setOnMenuItemClickListener {
                    MaterialAlertDialogBuilder(this@MainActivity).setTitle("更新未完成").setMessage(failure)
                        .setPositiveButton("知道了", null).show(); true
                }
            }
            if (!channel.optBoolean("history_done")) menu.add(if (channel.optLong("history_offset") > 0) "继续扫描历史" else "扫描历史").setOnMenuItemClickListener { beginScan(id, false); true }
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
                app.perform({ _ -> app.database.removeChannel(id); app.scanQueue.forget(id); "" }) { _, error ->
                    if (!isDestroyed) {
                        if (channelFilter == id) channelFilter = 0
                        page = 0; scrollPositions[R.id.nav_videos] = 0
                        if (error != null) message(error) else message("频道已从本机移除")
                        render()
                    }
                }
            }.show()
    }

    private fun chooseChannelsFromTelegram() {
        if (!status.optBoolean("connected") || !app.mediaReady()) { message(app.accountIssue ?: "请先确认 Telegram 账号"); return }
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

    private fun beginScan(id: Long, refresh: Boolean, recheck: Boolean = false, all: Boolean = false) {
        if (app.busy || app.scanning || scanLaunching || pendingScan != null) { message("当前有索引任务正在运行"); return }
        if (!status.optBoolean("connected") || !app.mediaReady()) { message(app.accountIssue ?: "请先连接并确认 Telegram 账号"); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingScan = id to refresh; pendingRecheck = recheck; pendingAll = all
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else launchScan(id, refresh, recheck, all)
    }

    private fun launchScan(id: Long, refresh: Boolean, recheck: Boolean = false, all: Boolean = false) {
        if (app.busy || app.scanning || scanLaunching) return
        scanLaunching = true
        runCatching {
            ContextCompat.startForegroundService(this,
                Intent(this, ScanService::class.java).putExtra("channel", id).putExtra("refresh", refresh)
                    .putExtra("recheck", recheck).putExtra("all", all))
        }.onFailure { message("暂时无法开始更新，请回到前台重试") }
        handler.postDelayed({ scanLaunching = false; updateScanViews(app.scanQueue.snapshot) }, 500)
    }

    private fun accountPage() {
        val phase = status.optString("state", "signed_out")
        loginFormPhase = phase
        if (phase in AUTHENTICATED_PHASES) {
            connectedAccountPage(phase)
        } else {
            loginPage(phase)
        }
        if (app.accountIssue != null) {
            paragraph(app.accountIssue!!)
            if (app.legacyOwnershipPending) body.addView(actionButton("确认旧索引归属", ButtonStyle.OUTLINED, R.drawable.ic_check) {
                val id = status.optLong("account_id")
                MaterialAlertDialogBuilder(this).setTitle("保留并绑定旧版索引？")
                    .setMessage("旧版没有记录索引所属账号。当前 Telegram 账号 ID 为 $id。确认这是原账号后，才能继续使用这份频道和播放记录。更换账号请退出此设备。")
                    .setNegativeButton("取消", null).setPositiveButton("这是原账号，保留索引") { _, _ -> app.confirmLegacyOwner(id) { failure -> if (!isDestroyed) { if (failure != null) message(failure); render() } } }.show()
            }, rowParams())
            body.addView(actionButton("重新登录原账号", ButtonStyle.OUTLINED, R.drawable.ic_refresh) {
                app.recoverAuthorization { failure -> if (!isDestroyed) { if (failure != null) message(failure) else { status = JSONObject().put("state", "signed_out"); render() } } }
            }, rowParams())
        }
        if (app.startupError != null) {
            paragraph(app.startupError!!)
            body.addView(actionButton("重置本机授权", ButtonStyle.OUTLINED, R.drawable.ic_refresh) {
                MaterialAlertDialogBuilder(this).setTitle("重置本机授权？").setMessage("将清除损坏的登录授权并重新登录，频道索引保留并继续校验账号归属。")
                    .setNegativeButton("取消", null).setPositiveButton("重置授权") { _, _ -> app.recoverAuthorization { failure ->
                        if (!isDestroyed) { if (failure != null) message(failure) else { resetBrowseState(); render() } }
                    } }.show()
            }, rowParams())
        }
        appearanceSettings()
    }

    private fun connectedAccountPage(phase: String) {
        val connected = phase == "ready"
        val profile = horizontalRow().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(16)) }
        profile.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_account); setPadding(dp(9), dp(9), dp(9), dp(9))
            imageTintList = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorPrimary))
            background = rounded(color(com.google.android.material.R.attr.colorPrimaryContainer), 20)
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(11) })
        profile.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Telegram", 16).apply { setTypeface(typeface, Typeface.BOLD) })
            addView(text(phaseLabel(phase), 11).apply { setTextColor(muted()); setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        profile.addView(text(if (connected) "已授权" else "", 11).apply { setTextColor(color(com.google.android.material.R.attr.colorSecondary)) })
        body.addView(profile)
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
    }

    private fun appearanceSettings() {
        body.addView(sectionHeader("播放与显示"))
        val modes = intArrayOf(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES)
        val labels = arrayOf("跟随系统", "浅色", "深色")
        val current = getSharedPreferences("appearance", MODE_PRIVATE).getInt("theme", modes[0])
        val choice = modes.indexOf(current).coerceAtLeast(0)
        body.addView(settingRow("外观", labels[choice], R.drawable.ic_appearance) {
            MaterialAlertDialogBuilder(this).setTitle("外观").setSingleChoiceItems(labels, choice) { dialog, index ->
                getSharedPreferences("appearance", MODE_PRIVATE).edit().putInt("theme", modes[index]).apply()
                dialog.dismiss(); AppCompatDelegate.setDefaultNightMode(modes[index])
            }.show()
        })
        val preferences = PlaybackPreferences(this)
        body.addView(settingRow("默认播放速度", "${preferences.speed}×", R.drawable.ic_speed) {
            val speeds = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
            MaterialAlertDialogBuilder(this).setTitle("默认播放速度").setSingleChoiceItems(speeds.map { "${it}×" }.toTypedArray(), speeds.indexOfFirst { it == preferences.speed }) { dialog, i ->
                preferences.speed = speeds[i]
                PlaybackService.setPreferredSpeed(speeds[i])
                dialog.dismiss(); render()
            }.show()
        })
        body.addView(settingRow("播放手势", "操作说明", R.drawable.ic_info) {
            MaterialAlertDialogBuilder(this).setTitle("播放手势").setMessage(dev.longvideo.controls.R.string.pc_help_text).setPositiveButton("知道了", null).show()
        })
        body.addView(sectionHeader("账号与数据"))
        body.addView(settingRow("管理频道", "${app.database.channels().length()} 个", R.drawable.ic_channels) { navigation.selectedItemId = R.id.nav_channels })
        body.addView(settingRow("本机索引", "索引说明", R.drawable.ic_video) {
            MaterialAlertDialogBuilder(this).setTitle("本机索引").setMessage("索引和播放记录保存在此设备。视频在播放时按需读取，不会自动下载完整视频。\n\n列表日期是 Telegram 消息发布时间，按设备时区显示。").setPositiveButton("关闭", null).show()
        })
        body.addView(settingRow("运行诊断", "连接与下载", R.drawable.ic_info) { showDiagnostics() })
        body.addView(settingRow("播放诊断", "本地测试片", R.drawable.ic_play) { playValidation() })
        if (status.optString("state") in AUTHENTICATED_PHASES || app.startupError != null || app.accountIssue != null) body.addView(settingRow("退出此设备", "", R.drawable.ic_logout) { confirmLogout() })
        body.addView(text("TGProxy ${BuildConfig.VERSION_NAME}", 11).apply { setTextColor(muted()); gravity = Gravity.CENTER; setPadding(0, dp(22), 0, dp(8)) })
    }

    private fun resetBrowseState() {
        channelFilter = 0; search = ""; page = 0; recent = false; channelDraft = ""
        scrollPositions.clear(); pendingScrollRestore = 0
    }
    private fun showDiagnostics() {
        val first = app.engine?.let { JSONObject(it.status()) } ?: JSONObject()
        val copy = text("正在统计下载状态…", 13)
        val panel = sheet("运行诊断"); panel.addView(copy)
        handler.postDelayed({
            if (copy.isAttachedToWindow) {
                val now = app.engine?.let { JSONObject(it.status()) } ?: JSONObject()
                val speed = (now.optLong("downloaded_bytes") - first.optLong("downloaded_bytes")).coerceAtLeast(0) / 2
                copy.text = "${phaseLabel(now.optString("state"))}\n最近下载速度：${MediaPresentation.size(speed)} / 秒\n分块缓存：${MediaPresentation.size(now.optLong("cache_bytes"))}\n缓存命中 / 未命中：${now.optLong("hits")} / ${now.optLong("misses")}\n分块失败：${now.optLong("fetch_errors")}\n最近失败：${now.optString("last_failure").ifBlank { "无" }}\n连接重试：${now.optLong("reconnects")}\n文件引用刷新：${now.optLong("reference_refreshes")}\n文件 DC 迁移：${now.optLong("dc_migrations")}"
            }
        }, 2000)
    }

    private fun muted() = color(com.google.android.material.R.attr.colorOnSurfaceVariant)
    private fun divider() = View(this).apply {
        setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }
    private fun rounded(value: Int, radius: Int) = GradientDrawable().apply { setColor(value); cornerRadius = dp(radius).toFloat() }
    private fun clickableRow(view: View, action: () -> Unit) {
        val ripple = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        view.setBackgroundResource(ripple.resourceId); view.isClickable = true; view.isFocusable = true
        view.setOnClickListener { action() }
    }
    private fun sectionHeader(label: String) = text(label, 12).apply {
        setTextColor(muted()); setPadding(0, dp(17), 0, dp(5)); ViewCompat.setAccessibilityHeading(this, true)
    }
    private fun settingRow(label: String, value: String, icon: Int, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val row = horizontalRow().apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48)
            contentDescription = "$label，$value"
            clickableRow(this, action)
            addView(ImageView(this@MainActivity).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(muted()) }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(10) })
            addView(text(label, 13).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text(value, 11).apply { setTextColor(muted()); setPadding(dp(6), 0, dp(5), 0) })
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_chevron_right); imageTintList = ColorStateList.valueOf(muted()) }, LinearLayout.LayoutParams(dp(14), dp(14)))
        }
        addView(row); addView(divider())
    }
    private fun sheet(title: String): LinearLayout {
        activeSheet?.dismiss()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(24)) }
        val header = horizontalRow().apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(title, 18).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(imageButton(R.drawable.ic_close, "关闭面板") { activeSheet?.dismiss() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        content.addView(header)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(ScrollView(this).apply { addView(content) })
        dialog.setOnDismissListener { if (activeSheet === dialog) { activeSheet = null; activeSheetContent = null } }
        activeSheet = dialog; activeSheetContent = content; dialog.show()
        dialog.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        return content
    }

    private fun loginPage(phase: String) {
        paragraph(when (phase) {
            "expired" -> "授权已过期。重新连接后可继续使用已保存的频道索引。"
            "offline", "connecting", "reconnecting" -> "正在恢复 Telegram 连接。检查网络后通常会自动重连。"
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
        if (phase in setOf("code", "password")) body.addView(actionButton("重新开始登录", ButtonStyle.TEXT, R.drawable.ic_refresh) {
            app.perform({ core -> core.restartLogin(); "signed_out" }) { _, failure ->
                if (!isDestroyed) { if (failure != null) message(failure) else { codeDraft = ""; passwordDraft = ""; status = JSONObject().put("state", "signed_out"); render() } }
            }
        }, rowParams())
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
                        else { resetBrowseState(); status = JSONObject().put("state", "signed_out"); credentialsLoaded = false; credentialEdited = false; credentialsExpanded = false; credentialDraftId = ""; credentialDraftHash = ""; codeDraft = ""; passwordDraft = ""; render() }
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
        minHeight = dp(44)
        strokeWidth = 0
        cornerRadius = dp(8)
        backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(color(com.google.android.material.R.attr.colorPrimaryContainer), android.graphics.Color.TRANSPARENT))
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(color(com.google.android.material.R.attr.colorPrimary), muted())))
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
        "offline", "reconnecting" -> "网络中断，正在重连"
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
        lastPlaying = PlaybackService.isPlaybackRequested()
        miniPlayer.removeAllViews()
        val key = lastPlayingKey ?: return
        val caption = text("返回正在播放", 12).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val row = horizontalRow().apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(50); setPadding(dp(20), 0, dp(4), 0); setBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerLow)) }
        clickableRow(caption) { startActivity(Intent(this, PlayerActivity::class.java)) }
        caption.minimumHeight = dp(48); caption.gravity = Gravity.CENTER_VERTICAL
        row.addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(imageButton(if (lastPlaying) R.drawable.ic_pause else R.drawable.ic_play, if (lastPlaying) "暂停播放" else "继续播放") {
            PlaybackService.togglePlayback(); updateMiniPlayer()
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        row.addView(imageButton(R.drawable.ic_stop, "停止播放") { PlaybackService.stopPlayback(this); updateMiniPlayer() }, LinearLayout.LayoutParams(dp(44), dp(48)))
        miniPlayer.addView(row)
        queryWorker.execute {
            val title = runCatching { app.database.video(key)?.optString("name") }.getOrNull() ?: "本地测试视频"
            handler.post { if (!isDestroyed && lastPlayingKey == key) { caption.text = title; caption.contentDescription = "返回正在播放：$title" } }
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
        private val AUTHENTICATED_PHASES = setOf("ready", "connecting", "reconnecting", "offline", "expired")
    }
}
