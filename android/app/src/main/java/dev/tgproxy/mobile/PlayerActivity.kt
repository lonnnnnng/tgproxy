package dev.tgproxy.mobile

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
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
    private val validation get() = intent.getBooleanExtra("validation", false)
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) { updateNote() }
        override fun onTracksChanged(tracks: Tracks) { updateNote() }
        override fun onPlayerError(error: PlaybackException) { updateNote() }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            bindDisplay()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { bindDisplay() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        key = if (validation) "validation" else intent.getStringExtra("key") ?: ""
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
            setOnClickListener { controller?.let { if (openError != null || it.playerError != null) openVideo(it) } }
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
        if (app.engine == null) { note.text = "播放内核尚未恢复，请返回后重试"; return }
        val pending = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        future = pending
        pending.addListener({
            if (future !== pending || isDestroyed) return@addListener
            try {
                val player = pending.get()
                controller = player
                bindDisplay()
                player.addListener(listener)
                if (key.isNotEmpty() && (explicitOpen || (player.currentMediaItem?.mediaId != key && openError == null))) {
                    explicitOpen = false
                    openVideo(player)
                } else if (key.isEmpty() && player.currentMediaItem == null) {
                    openError = "没有正在播放的视频，请返回视频库选择"
                }
                updateNote()
            } catch (_: Exception) { note.visibility = View.VISIBLE; note.text = "播放服务连接失败，请返回后重试" }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        openRequest++; opening = false
        view.onHostStop()
        if (key.isEmpty() || controller?.currentMediaItem?.mediaId == key) resumePosition = controller?.currentPosition ?: resumePosition
        controller?.removeListener(listener)
        view.player = null
        controller = null
        future?.let(MediaController::releaseFuture)
        future = null
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("position", controller?.currentPosition ?: resumePosition); outState.putBoolean("explicit_open", explicitOpen); outState.putString("open_error", openError); super.onSaveInstanceState(outState) }

    private fun openVideo(player: MediaController) {
        val target = key.ifEmpty { player.currentMediaItem?.mediaId.orEmpty() }
        if (target.isEmpty()) return
        val request = ++openRequest
        opening = true; openError = null; updateNote()
        val pending = player.sendCustomCommand(PlaybackService.OPEN, Bundle().apply { putString("key", target); putLong("position", resumePosition) })
        pending.addListener({
            if (controller !== player || isDestroyed || request != openRequest) return@addListener
            opening = false
            val result = runCatching { pending.get() }.getOrNull()
            openError = if (result?.resultCode == SessionResult.RESULT_SUCCESS) null else result?.sessionError?.message ?: "视频打开失败，请重试"
            updateNote()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindDisplay() {
        val player = controller ?: return
        val matches = key.isEmpty() || player.currentMediaItem?.mediaId == key
        // long: 打开新视频失败时旧媒体可能仍在服务中，不能把它的画面和标题当成目标资源展示。
        view.player = if (matches) player else null
        view.title = if (matches) player.mediaMetadata.title ?: "正在播放" else intent.getStringExtra("title") ?: "正在打开视频"
        view.source = if (matches) player.mediaMetadata.artist ?: "" else ""
    }

    private fun updateNote() {
        val player = controller ?: return
        note.text = when {
            opening -> "正在打开视频…"
            openError != null -> "$openError · 点击重试"
            player.playerError != null -> getString(R.string.playback_error, player.playerError!!.errorCodeName) + " · 点击重试"
            player.playbackState == Player.STATE_ENDED -> "播放完毕"
            else -> ""
        }
        note.visibility = if (note.text.isEmpty()) View.GONE else View.VISIBLE
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
        MaterialAlertDialogBuilder(this).setTitle("播放信息").setMessage(info + "\n缓冲至 ${player.bufferedPosition / 1000} 秒")
            .setPositiveButton("关闭", null).setNeutralButton("停止播放") { _, _ -> PlaybackService.stopPlayback(this); finish() }.show()
    }
    internal fun testPlayer(): Player? = controller
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

}
