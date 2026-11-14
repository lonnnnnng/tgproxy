package dev.tgproxy.mobile

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.activity.OnBackPressedCallback
import dev.longvideo.controls.VideoControlsView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import dev.longvideo.controls.PlaybackPreferences
import org.json.JSONObject
import org.json.JSONArray
import com.google.common.util.concurrent.ListenableFuture

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerActivity : AppCompatActivity() {
    private val app get() = application as TgApplication
    private lateinit var view: VideoControlsView
    private lateinit var note: TextView
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var key = ""
    private var resumePosition = -1L
    private var explicitOpen = true
    private var openError: String? = null
    private var opening = false
    private var openRequest = 0
    private var pendingSelection = false
    private var pendingSelectionID: String? = null
    private var requestedTitle = ""
    private var scopeSort = "newest"
    private var scopeSearch = ""
    private var scopeOffline = false
    private var queueRequest = 0
    private var previousKey = ""
    private var nextKey = ""
    private var picker: BottomSheetDialog? = null
    private var pickerBody: LinearLayout? = null
    private var pickerRequest = 0
    private var pickerPage = 0
    private val pickerCursors = mutableMapOf<Int, String>()
    private val handler = Handler(Looper.getMainLooper())
    private val notes = object : Runnable {
        override fun run() { updateNote(); handler.postDelayed(this, 500) }
    }
    private val validation get() = intent.getBooleanExtra("validation", false)
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) { updateNote() }
        override fun onTracksChanged(tracks: Tracks) { updateNote() }
        override fun onPlayerError(error: PlaybackException) { updateNote() }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            bindDisplay()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!opening && openError == null && mediaItem != null) { key = mediaItem.mediaId; pendingSelection = false; pendingSelectionID = null }
            bindDisplay()
            if (!opening) { if (picker != null) loadPicker() else loadQueue() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        key = savedInstanceState?.getString("key") ?: if (validation) "validation" else intent.getStringExtra("key") ?: ""
        requestedTitle = savedInstanceState?.getString("requested_title") ?: intent.getStringExtra("title") ?: "正在打开视频"
        pendingSelection = savedInstanceState?.getBoolean("pending_selection") ?: false
        pendingSelectionID = savedInstanceState?.getString("pending_selection_id")
        scopeSort = savedInstanceState?.getString("queue_sort") ?: intent.getStringExtra("queue_sort") ?: "newest"
        scopeSearch = savedInstanceState?.getString("queue_search") ?: intent.getStringExtra("queue_search") ?: ""
        scopeOffline = savedInstanceState?.getBoolean("queue_offline") ?: intent.getBooleanExtra("queue_offline", false)
        resumePosition = savedInstanceState?.getLong("position", -1L) ?: -1L
        explicitOpen = savedInstanceState?.getBoolean("explicit_open", true) ?: true
        openError = savedInstanceState?.getString("open_error")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        view = VideoControlsView(this).apply {
            title = intent.getStringExtra("title") ?: "正在播放"
            attachWindow(window)
            onBack = { finish() }
            onRotate = { toggleOrientation() }
            onInfo = { showInfo() }
            onPrevious = { loadQueue(-1) }
            onNext = { loadQueue(1) }
            onQueue = { showPicker() }
            continuousPlaying = PlaybackPreferences(this@PlayerActivity).continuous
            onToggleContinuous = {
                val preferences = PlaybackPreferences(this@PlayerActivity)
                preferences.continuous = !preferences.continuous
                continuousPlaying = preferences.continuous
                PlaybackService.applyContinuousPreference()
            }
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (view.isLocked) view.setLocked(false) else finish()
            }
        })
        note = TextView(this).apply {
            textSize = 14f; setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(0x99000000.toInt())
            setOnClickListener { controller?.let { player ->
                if (openError != null || player.playerError != null || PlaybackService.failedPlaybackTarget() != null)
                    PlaybackService.failedPlaybackTarget()?.let(::openSelected) ?: openVideo(player)
            } }
        }
        root.addView(note, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(150) })
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        if (app.engine == null && key.isNotEmpty() && !key.startsWith("download:")) { note.text = "播放内核尚未恢复，请返回后重试"; return }
        val pending = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future = pending
        pending.addListener({
            if (future !== pending || isDestroyed) return@addListener
            try {
                val player = pending.get()
                controller = player
                val sameTarget = player.currentMediaItem?.mediaId == key || player.mediaMetadata.extras?.getString("original_key") == key
                if (!explicitOpen && pendingSelection && player.currentMediaItem != null && (sameTarget || PlaybackService.selectionFulfilled(pendingSelectionID))) {
                    key = player.currentMediaItem!!.mediaId; pendingSelection = false; pendingSelectionID = null
                }
                if (!explicitOpen && !pendingSelection && openError == null && player.currentMediaItem != null) key = player.currentMediaItem!!.mediaId
                bindDisplay()
                player.addListener(listener)
                // long: 未完成的选片要继续打开目标；正常后台重绑则跟随服务已经切换的当前视频。
                if (key.isNotEmpty() && (explicitOpen || pendingSelection || (player.currentMediaItem == null && openError == null))) {
                    explicitOpen = false
                    openVideo(player)
                } else if (key.isEmpty() && player.currentMediaItem == null) {
                    openError = "没有正在播放的视频，请返回视频库选择"
                }
                updateNote()
                loadQueue()
                handler.post(notes)
            } catch (_: Exception) { note.visibility = View.VISIBLE; note.text = "播放服务连接失败，请返回后重试" }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        openRequest++; opening = false
        queueRequest++; pickerRequest++; picker?.dismiss(); handler.removeCallbacks(notes)
        view.onHostStop()
        if (key.isEmpty() || controller?.currentMediaItem?.mediaId == key) resumePosition = controller?.currentPosition ?: resumePosition
        controller?.removeListener(listener)
        view.player = null
        controller = null
        future?.let(MediaController::releaseFuture)
        future = null
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", controller?.currentPosition ?: resumePosition); outState.putBoolean("explicit_open", explicitOpen); outState.putString("open_error", openError)
        outState.putString("key", key); outState.putBoolean("pending_selection", pendingSelection); outState.putString("requested_title", requestedTitle)
        outState.putString("pending_selection_id", pendingSelectionID)
        outState.putString("queue_sort", scopeSort); outState.putString("queue_search", scopeSearch); outState.putBoolean("queue_offline", scopeOffline)
        super.onSaveInstanceState(outState)
    }

    private fun openVideo(player: MediaController) {
        val target = key.ifEmpty { player.currentMediaItem?.mediaId.orEmpty() }
        if (target.isEmpty()) return
        val request = ++openRequest
        val selectionID = pendingSelectionID ?: java.util.UUID.randomUUID().toString().also { pendingSelectionID = it }
        opening = true; pendingSelection = true; openError = null; view.setNavigation(false, false); bindDisplay(); updateNote()
        val pending = player.sendCustomCommand(PlaybackService.OPEN, Bundle().apply {
            putString("key", target); putLong("position", resumePosition); putString("selection_id", selectionID)
            putString("queue_sort", scopeSort); putString("queue_search", scopeSearch); putBoolean("queue_offline", scopeOffline)
        })
        pending.addListener({
            if (controller !== player || isDestroyed || request != openRequest) return@addListener
            opening = false; pendingSelection = false; pendingSelectionID = null
            val result = runCatching { pending.get() }.getOrNull()
            openError = if (result?.resultCode == SessionResult.RESULT_SUCCESS) null else result?.sessionError?.message ?: "视频打开失败，请重试"
            if (openError == null) key = player.currentMediaItem?.mediaId ?: target
            bindDisplay(); updateNote(); loadQueue()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindDisplay() {
        val player = controller ?: return
        val matches = key.isEmpty() || player.currentMediaItem?.mediaId == key
        // long: 打开新视频失败时旧媒体可能仍在服务中，不能把它的画面和标题当成目标资源展示。
        view.player = if (matches) player else null
        view.title = if (matches) player.mediaMetadata.title ?: "正在播放" else requestedTitle
        view.source = if (matches) player.mediaMetadata.artist ?: "" else ""
    }

    private fun updateNote() {
        val player = controller ?: return
        note.text = when {
            opening -> "正在打开视频…"
            openError != null -> "$openError · 点击重试"
            PlaybackService.recoveryStatus() != null -> PlaybackService.recoveryStatus()
            player.playerError != null -> "视频暂时无法播放，请查看播放信息或点击重试"
            player.playbackState == Player.STATE_ENDED -> "播放完毕"
            else -> ""
        }
        note.visibility = if (note.text.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun openSelected(target: String, title: String = "正在打开视频") {
        val player = controller ?: return
        key = target; requestedTitle = title; resumePosition = -1; pendingSelectionID = null
        openVideo(player)
    }
    private fun loadQueue(direction: Int = 0) {
        val player = controller ?: return
        val current = player.currentMediaItem?.mediaId ?: return
        if (opening || openError != null) return
        val request = ++queueRequest
        if (direction != 0) view.setNavigation(false, false)
        val pending = player.sendCustomCommand(PlaybackService.QUEUE, Bundle.EMPTY)
        pending.addListener({
            if (controller !== player || isDestroyed || request != queueRequest || player.currentMediaItem?.mediaId != current) return@addListener
            val result = runCatching { pending.get() }.getOrNull()
            val data = runCatching { JSONObject(result?.extras?.getString("queue") ?: "{}") }.getOrDefault(JSONObject())
            previousKey = data.optString("previous"); nextKey = data.optString("next")
            scopeSort = data.optString("sort", scopeSort); scopeOffline = data.optBoolean("offline", scopeOffline)
            view.continuousPlaying = PlaybackPreferences(this).continuous
            view.setNavigation(previousKey.isNotEmpty(), nextKey.isNotEmpty())
            val target = if (direction < 0) previousKey else if (direction > 0) nextKey else ""
            if (target.isNotEmpty()) openSelected(target)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showPicker() {
        picker?.dismiss(); pickerPage = 0; pickerCursors.clear()
        val dialog = BottomSheetDialog(ContextThemeWrapper(this, com.google.android.material.R.style.Theme_Material3_Dark))
        val content = LinearLayout(dialog.context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(20)) }
        picker = dialog; pickerBody = content
        dialog.setContentView(ScrollView(dialog.context).apply { addView(content) })
        dialog.setOnDismissListener { if (picker === dialog) { picker = null; pickerBody = null; pickerRequest++ } }
        content.addView(TextView(dialog.context).apply { text = "正在读取视频列表…"; textSize = 14f; setTextColor(android.graphics.Color.WHITE) })
        dialog.show(); dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        loadPicker()
    }
    private fun loadPicker() {
        val dialog = picker ?: return
        val content = pickerBody ?: return
        val player = controller ?: return
        val current = player.currentMediaItem?.mediaId ?: return
        val request = ++pickerRequest
        val pending = player.sendCustomCommand(PlaybackService.QUEUE, Bundle().apply {
            putBoolean("list", true); putInt("page", pickerPage); putString("sort", scopeSort); putString("cursor", pickerCursors[pickerPage])
        })
        pending.addListener({
            if (isDestroyed || picker !== dialog || request != pickerRequest || controller !== player || player.currentMediaItem?.mediaId != current) return@addListener
            val result = runCatching { pending.get() }.getOrNull()
            val data = runCatching { JSONObject(result?.extras?.getString("queue") ?: "{}") }.getOrDefault(JSONObject())
            val rows = data.optJSONArray("items") ?: JSONArray()
            content.removeAllViews()
            content.addView(TextView(dialog.context).apply { text = if (data.optBoolean("offline")) "本机视频" else "同频道视频"; textSize = 18f; setTextColor(android.graphics.Color.WHITE) })
            content.addView(pickerButton(dialog, when (scopeSort) { "name" -> "顺序：名称"; "oldest" -> "顺序：发布时间从旧到新"; else -> "顺序：发布时间从新到旧" }) {
                val orders = arrayOf("newest", "oldest", "name")
                MaterialAlertDialogBuilder(dialog.context).setTitle("播放顺序").setSingleChoiceItems(arrayOf("发布时间从新到旧", "发布时间从旧到新", "名称"), orders.indexOf(scopeSort)) { chooser, index ->
                    scopeSort = orders[index]; pickerPage = 0; pickerCursors.clear(); chooser.dismiss(); loadPicker()
                }.show()
            })
            if (result?.resultCode != SessionResult.RESULT_SUCCESS) content.addView(pickerButton(dialog, "列表暂时不可用，点击重试") { loadPicker() })
            else if (rows.length() == 0) content.addView(TextView(dialog.context).apply { text = "当前没有可选择的视频"; textSize = 13f; setTextColor(0xFFC0C9DA.toInt()); setPadding(0, dp(16), 0, dp(16)) })
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                content.addView(pickerButton(dialog, row.optString("name") + if (row.optBoolean("current")) " · 正在播放" else "") {
                    dialog.dismiss(); openSelected(row.getString("key"), row.optString("name"))
                })
            }
            val bar = LinearLayout(dialog.context).apply { gravity = Gravity.CENTER }
            bar.addView(pickerButton(dialog, "上一页") { if (pickerPage > 0) { pickerPage--; loadPicker() } }.apply { isEnabled = pickerPage > 0 }, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(pickerButton(dialog, "下一页") {
                pickerCursors[pickerPage + 1] = data.optString("next_cursor"); pickerPage++; loadPicker()
            }.apply { isEnabled = data.optBoolean("more") }, LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(bar)
        }, ContextCompat.getMainExecutor(this))
    }
    private fun pickerButton(dialog: BottomSheetDialog, label: String, action: () -> Unit) =
        MaterialButton(dialog.context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = label; textSize = 13f; isAllCaps = false; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; setTextColor(android.graphics.Color.WHITE)
            setOnClickListener { action() }
        }
    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
    private fun showInfo() {
        val player = controller ?: return
        val audio = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val info = if (audio.isEmpty()) "当前未检测到音轨" else audio.joinToString("\n") { group ->
            (0 until group.length).joinToString("\n") { i ->
                val format = group.getTrackFormat(i)
                "${format.sampleMimeType ?: "未知音频格式"} · ${format.channelCount} 声道 · " +
                    if (group.isTrackSelected(i)) "正在使用" else if (group.isTrackSupported(i)) "可用" else "设备不支持"
            }
        }
        val source = if (player.currentMediaItem?.mediaId?.startsWith("download:") == true) "本机文件" else "Telegram 在线视频"
        val error = player.playerError?.errorCodeName?.let { "\n错误类型：$it" }.orEmpty()
        MaterialAlertDialogBuilder(this).setTitle("播放信息").setMessage("播放来源：$source\n" + info + "\n缓冲至 ${player.bufferedPosition / 1000} 秒" + error)
            .setPositiveButton("关闭", null).setNeutralButton("停止播放") { _, _ -> PlaybackService.stopPlayback(this); finish() }.show()
    }
    internal fun testPlayer(): Player? = controller
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

}
