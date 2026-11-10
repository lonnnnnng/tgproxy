package dev.tgproxy.mobile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackService : MediaSessionService() {
    private val app get() = application as TgApplication
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    @Volatile private var stopping = false
    @Volatile private var released = false
    private val handler = Handler(Looper.getMainLooper())
    private val checkpoint = object : Runnable {
        override fun run() { savePosition(); handler.postDelayed(this, 5000) }
    }

    override fun onCreate() {
        super.onCreate()
        active = this
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15000).setReadTimeoutMs(35000)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setRenderersFactory(DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15000, 45000, 1000, 2000).setTargetBufferBytes(16 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { savePosition() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { currentKey = mediaItem?.mediaId }
            override fun onPlaybackStateChanged(playbackState: Int) { savePosition() }
        })
        val activity = PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, items: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                    return try {
                        check(!stopping) { "播放服务正在关闭" }
                        require(controller.uid == applicationInfo.uid) { "只有本机视频库可打开媒体" }
                        val core = app.engine ?: error("播放内核尚未恢复")
                        savePosition()
                        // long: 控制器只提交数据库主键，服务重新解析 URL 和令牌，外部 URI 无法诱导内核向外发送凭证。
                        http.setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${core.token()}"))
                        Futures.immediateFuture(items.map { item ->
                            val key = item.mediaId
                            val row = if (key == "validation") null else app.database.video(key) ?: error("视频已从索引移除")
                            if (row != null) core.registerVideo(row.toString())
                            val uri = if (key == "validation") app.validationMedia() else core.playbackURL(key)
                            item.buildUpon().setUri(uri).setMediaMetadata(MediaMetadata.Builder()
                                .setTitle(row?.getString("name") ?: "本地播放测试")
                                .setArtist("TGProxy").build()).build()
                        })
                    } catch (_: Exception) { Futures.immediateFailedFuture(IllegalStateException("无法打开视频，请检查连接和索引")) }
                }
            }).build()
        handler.post(checkpoint)
    }

    private fun savePosition() {
        if (!::player.isInitialized || released) return
        val key = player.currentMediaItem?.mediaId ?: return
        if (key != "validation") {
            val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
            runCatching { app.database.savePosition(key, position) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = if (stopping) null else session

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        savePosition()
        currentKey = null
        active = null
        releaseResources()
        super.onDestroy()
    }

    private fun releaseResources() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        session.release()
        player.release()
    }

    companion object {
        private var active: PlaybackService? = null
        @Volatile var currentKey: String? = null
            private set
        fun stopPlayback(context: Context, revokeSession: Boolean = false) {
            // long: 普通停止允许已绑定页面完成解绑；退出账号则封锁并释放旧会话，避免跨账号继续取流。
            active?.let {
                it.savePosition()
                if (!it.released) { it.player.stop(); it.player.clearMediaItems() }
                if (revokeSession) {
                    it.stopping = true
                    it.releaseResources()
                }
                it.stopSelf()
            }
            context.stopService(Intent(context, PlaybackService::class.java))
            currentKey = null
        }
        internal fun testPlayer(): Player? = active?.player
    }
}
