package dev.tgproxy.mobile

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.longvideo.controls.PlaybackPreferences
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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.SettableFuture
import androidx.media3.common.util.Util
import java.util.concurrent.Executors
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
    private val mediaWorker = Executors.newSingleThreadExecutor()
    private var openGeneration = 0L
    private val pendingOpens = mutableSetOf<SettableFuture<SessionResult>>()
    private lateinit var http: DefaultHttpDataSource.Factory
    private val checkpoint = object : Runnable {
        override fun run() { savePosition(); handler.postDelayed(this, 5000) }
    }

    override fun onCreate() {
        super.onCreate()
        active = this
        http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(15000).setReadTimeoutMs(35000)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setRenderersFactory(DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15000, 45000, 1000, 2000).setTargetBufferBytes(16 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(false).build())
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        // long: 通知入口或后台重建服务同样恢复已选倍率，临时 2× 不写入该偏好。
        player.setPlaybackSpeed(PlaybackPreferences(this).speed)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { savePosition() }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { currentKey = mediaItem?.mediaId }
            override fun onPlaybackStateChanged(playbackState: Int) { savePosition() }
        })
        val activity = PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
                    val base = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
                    val commands = if (controller.uid == applicationInfo.uid) base.availableSessionCommands.buildUpon().add(OPEN).build() else base.availableSessionCommands
                    return Futures.immediateFuture(MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).setAvailableSessionCommands(commands).build())
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (customCommand != OPEN || controller.uid != applicationInfo.uid) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    return openMedia(args.getString("key").orEmpty(), args.getLong("position", -1))
                }
                override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, items: List<MediaItem>): ListenableFuture<List<MediaItem>> =
                    Futures.immediateFailedFuture(IllegalArgumentException("请从本机视频库打开媒体"))

            }).build()
        handler.post(checkpoint)
    }

    private data class ResolvedMedia(val item: MediaItem, val position: Long, val token: String)

    private fun openMedia(key: String, requestedPosition: Long): ListenableFuture<SessionResult> {
        if (key.isBlank() || released || stopping) return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        cancelOpenRequests()
        if (player.currentMediaItem?.mediaId != key) player.pause()
        val local = key.startsWith("download:")
        val expectedCore = app.engine
        if (!local && expectedCore == null) return Futures.immediateFuture(openFailure("播放内核尚未恢复，请返回后重试"))
        if (!local && key != "validation" && !app.mediaReady()) return Futures.immediateFuture(openFailure(app.accountIssue ?: "请先确认 Telegram 账号"))
        val generation = openGeneration
        val result = SettableFuture.create<SessionResult>(); pendingOpens.add(result)
        // long: 即使主键相同也重新读取文档身份，资源换片后不能复用旧播放器和断点。
        mediaWorker.execute {
            val resolved = runCatching {
                if (local) {
                    // long: 离线播放只接受本应用保存的已完成任务 ID，不允许调用方传任意文件路径。
                    val id = key.removePrefix("download:")
                    val record = app.downloads.store.get(id) ?: error("下载任务已移除")
                    val file = app.downloads.store.completed(id) ?: error("下载文件已丢失或尚未完成")
                    return@runCatching ResolvedMedia(MediaItem.Builder().setMediaId(key).setUri(android.net.Uri.fromFile(file))
                        .setMimeType(record.mime).setMediaMetadata(MediaMetadata.Builder().setTitle(record.name).setArtist("本机下载 · 离线播放").build()).build(),
                        record.position, "")
                }
                val core = expectedCore ?: error("播放内核尚未恢复")
                if (key != "validation") app.requireMediaAccount()
                val row = if (key == "validation") null else app.database.video(key) ?: error("视频已从本机索引移除，请返回视频列表")
                if (row != null) core.registerVideo(row.toString())
                val uri = if (key == "validation") app.validationMedia(core) else core.playbackURL(key)
                ResolvedMedia(MediaItem.Builder().setMediaId(key).setUri(uri).setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(row?.optString("name") ?: "本地播放测试")
                    .setArtist(row?.let { app.database.channel(it.optLong("channel_id"))?.optString("title") } ?: "TGProxy")
                    .setExtras(Bundle().apply { putLong("document_id", row?.optLong("document_id") ?: 0); putLong("account_id", app.database.ownerID()) }).build()).build(),
                    if (key == "validation" && requestedPosition >= 0) requestedPosition else app.database.position(key), core.token())
            }
            handler.post {
                pendingOpens.remove(result)
                // long: 换账号、停止播放和后发请求都会使旧解析作废，不能让迟到结果重新启动旧媒体。
                if (released || stopping || generation != openGeneration || (!local && (expectedCore !== app.engine || (key != "validation" && !app.mediaReady())))) {
                    result.set(SessionResult(SessionError.ERROR_INVALID_STATE)); return@post
                }
                val media = resolved.getOrNull()
                if (media == null) result.set(openFailure("无法打开视频，请检查账号或校验频道资源后重试"))
                else {
                    val applied = runCatching {
                        val sameFile = player.currentMediaItem?.mediaId == key &&
                            player.mediaMetadata.extras?.getLong("document_id", 0) == media.item.mediaMetadata.extras?.getLong("document_id", 0)
                        if (sameFile && player.playerError == null) Util.handlePlayButtonAction(player)
                        else {
                            val start = if (sameFile) player.currentPosition else media.position
                            savePosition()
                            http.setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${media.token}"))
                            player.setMediaItem(media.item, start); player.prepare(); player.play()
                        }
                    }
                    result.set(if (applied.isSuccess) SessionResult(SessionResult.RESULT_SUCCESS) else openFailure("无法准备播放，请重试"))
                }
            }
        }
        return result
    }
    private fun openFailure(message: String) = SessionResult(SessionError(SessionError.ERROR_IO, message))
    private fun cancelOpenRequests() {
        openGeneration++
        pendingOpens.forEach { it.set(SessionResult(SessionError.ERROR_INVALID_STATE)) }; pendingOpens.clear()
    }

    private fun savePosition() {
        if (!::player.isInitialized || released) return
        val key = player.currentMediaItem?.mediaId ?: return
        if (key.startsWith("download:")) {
            val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
            if (!mediaWorker.isShutdown) mediaWorker.execute { runCatching { app.downloads.store.position(key.removePrefix("download:"), position) } }
            return
        }
        if (key != "validation") {
            val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
            val document = player.mediaMetadata.extras?.getLong("document_id", 0) ?: 0
            val owner = player.mediaMetadata.extras?.getLong("account_id", 0) ?: 0
            if (!mediaWorker.isShutdown) mediaWorker.execute { runCatching { app.database.savePosition(key, position, document, owner) } }
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
        cancelOpenRequests()
        mediaWorker.shutdown()
        handler.removeCallbacksAndMessages(null)
        session.release()
        player.release()
    }

    companion object {
        val OPEN = SessionCommand("dev.tgproxy.mobile.OPEN", Bundle.EMPTY)
        private var active: PlaybackService? = null
        @Volatile var currentKey: String? = null
            private set
        fun isPlaybackRequested(): Boolean = active?.let {
            !it.released && !it.stopping && it.player.playWhenReady && it.player.playbackState != Player.STATE_ENDED && it.player.playbackState != Player.STATE_IDLE && it.player.playerError == null
        } ?: false
        fun togglePlayback() = withActivePlayer { player ->
            if (player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
                if (player.playerError != null || player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) Util.handlePlayButtonAction(player)
                else if (player.playWhenReady) player.pause() else Util.handlePlayButtonAction(player)
            }
        }
        fun setPreferredSpeed(speed: Float) = withActivePlayer { player ->
            if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) player.setPlaybackSpeed(speed)
        }
        private fun withActivePlayer(action: (Player) -> Unit) {
            // long: 首页控制入口可能晚于服务关闭回调执行，只在主线程确认旧播放器仍存活后操作。
            val service = active ?: return
            val run = Runnable { if (active === service && !service.released && !service.stopping) action(service.player) }
            if (Looper.myLooper() == Looper.getMainLooper()) run.run() else service.handler.post(run)
        }
        fun stopPlayback(context: Context, revokeSession: Boolean = false) {
            // long: 普通停止允许已绑定页面完成解绑；退出账号则封锁并释放旧会话，避免跨账号继续取流。
            active?.let {
                it.cancelOpenRequests()
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
