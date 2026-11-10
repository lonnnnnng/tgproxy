package dev.tgproxy.mobile

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlayerActivity : AppCompatActivity() {
    private val app get() = application as TgApplication
    private lateinit var view: PlayerView
    private lateinit var note: TextView
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var key = ""
    private var resumePosition = 0L
    private val validation get() = intent.getBooleanExtra("validation", false)
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) { updateNote() }
        override fun onTracksChanged(tracks: Tracks) { updateNote() }
        override fun onPlayerError(error: PlaybackException) { updateNote() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        key = if (validation) "validation" else intent.getStringExtra("key") ?: ""
        resumePosition = savedInstanceState?.getLong("position") ?: if (validation || key.isEmpty()) 0L else app.database.position(key)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        view = PlayerView(this).apply { controllerShowTimeoutMs = 4000; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0x99000000.toInt()) }
        top.addView(TextView(this).apply {
            text = intent.getStringExtra("title") ?: "正在播放"; textSize = 16f; setTextColor(android.graphics.Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(4)); maxLines = 2
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun control(label: String, action: () -> Unit) = MaterialButton(this).apply { text = label; minHeight = dp(48); setOnClickListener { action() } }
        actions.addView(control("返回") { finish() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(control("横竖屏") {
            requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(control("播放信息") { showInfo() }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(actions)
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        note = TextView(this).apply {
            textSize = 14f; setTextColor(android.graphics.Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12)); setBackgroundColor(0x99000000.toInt())
            setOnClickListener { controller?.let { if (it.playerError != null) { it.prepare(); it.play() } } }
        }
        root.addView(note, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(110) })
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { top.visibility = it })
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
                view.player = player
                player.addListener(listener)
                // long: 同一视频返回页面只重新绑定控制器，不能重新 setMediaItem 导致跳回旧进度。
                if (key.isNotEmpty() && player.currentMediaItem?.mediaId != key) {
                    player.setMediaItem(MediaItem.Builder().setMediaId(key).build(), resumePosition)
                    player.prepare(); player.play()
                } else if (key.isEmpty() && player.currentMediaItem == null) {
                    note.text = "没有正在播放的视频，请返回视频库选择"; return@addListener
                }
                updateNote()
            } catch (_: Exception) { note.visibility = View.VISIBLE; note.text = "播放服务连接失败，请返回后重试" }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        resumePosition = controller?.currentPosition ?: resumePosition
        controller?.removeListener(listener)
        view.player = null
        controller = null
        future?.let(MediaController::releaseFuture)
        future = null
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("position", controller?.currentPosition ?: resumePosition); super.onSaveInstanceState(outState) }

    private fun updateNote() {
        val player = controller ?: return
        note.text = when {
            player.playerError != null -> getString(R.string.playback_error, player.playerError!!.errorCodeName) + " · 点击重试"
            player.playbackState == Player.STATE_BUFFERING -> "正在缓冲…"
            player.playbackState == Player.STATE_ENDED -> "播放完毕"
            else -> ""
        }
        note.visibility = if (note.text.isEmpty()) View.GONE else View.VISIBLE
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
