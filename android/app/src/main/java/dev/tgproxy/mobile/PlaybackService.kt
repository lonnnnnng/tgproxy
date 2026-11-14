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
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
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
    private val fulfilledSelections = linkedSetOf<String>()
    private lateinit var http: DefaultHttpDataSource.Factory
    private lateinit var catalog: PlaybackCatalog
    private var scope: PlaybackScope? = null
    private var advanceGeneration: Long? = null
    private val retryPolicy = PlaybackRetryPolicy()
    private var retryTask: Runnable? = null
    private var stableTask: Runnable? = null
    private var sourceCore: dev.tgproxy.core.mobilecore.Engine? = null
    private var recoveryText: String? = null
    private var failedTarget: String? = null
    private var wantsPlayback = false
    private val checkpoint = object : Runnable {
        override fun run() { savePosition(); handler.postDelayed(this, 5000) }
    }

    override fun onCreate() {
        super.onCreate()
        active = this
        catalog = PlaybackCatalog(app.database, app.downloads)
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
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                savePosition(); stableTask?.let(handler::removeCallbacks); stableTask = null
                if (isPlaying) {
                    recoveryText = null
                    val generation = openGeneration
                    stableTask = Runnable {
                        if (!released && generation == openGeneration && player.isPlaying) retryPolicy.reset()
                    }.also { handler.postDelayed(it, 10000) }
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { currentKey = mediaItem?.mediaId }
            override fun onPlaybackStateChanged(playbackState: Int) { savePosition(); if (playbackState == Player.STATE_ENDED) maybeAdvance() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                wantsPlayback = playWhenReady
                if (!playWhenReady) { retryTask?.let(handler::removeCallbacks); retryTask = null; recoveryText = null }
            }
            override fun onPlayerError(error: PlaybackException) { recoverPlayback(error) }
        })
        val activity = PendingIntent.getActivity(this, 0, Intent(this, PlayerActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnectAsync(session: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.ConnectionResult> {
                    val base = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
                    val commands = if (controller.uid == applicationInfo.uid) base.availableSessionCommands.buildUpon().add(OPEN).add(QUEUE).build() else base.availableSessionCommands
                    return Futures.immediateFuture(MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).setAvailableSessionCommands(commands).build())
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (controller.uid != applicationInfo.uid) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    return when (customCommand) {
                        OPEN -> openMedia(args.getString("key").orEmpty(), args.getLong("position", -1), args)
                        QUEUE -> queueState(args)
                        else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    }
                }
                override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, items: List<MediaItem>): ListenableFuture<List<MediaItem>> =
                    Futures.immediateFailedFuture(IllegalArgumentException("请从本机视频库打开媒体"))

            }).build()
        handler.post(checkpoint)
    }

    private data class ResolvedMedia(val item: MediaItem, val position: Long, val token: String, val offline: Boolean = false)
    private fun resolveLocal(id: String, core: dev.tgproxy.core.mobilecore.Engine?): ResolvedMedia {
        // long: 本机播放只接受已完成任务，索引变动和断网都不能改变该文件的身份或改走任意路径。
        val record = app.downloads.store.get(id) ?: error("下载任务已移除")
        val file = app.downloads.store.completed(id) ?: error("下载文件已丢失或尚未完成")
        val item = MediaItem.Builder().setMediaId("download:" + id).setUri(android.net.Uri.fromFile(file)).setMimeType(record.mime)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(record.name).setArtist("本机下载 · 离线播放").setExtras(Bundle().apply {
                putLong("document_id", record.document); putLong("account_id", record.owner)
                putLong("channel_id", record.video.optLong("channel_id")); putString("original_key", record.key)
            }).build()).build()
        val connected = runCatching { core?.let { org.json.JSONObject(it.status()).optBoolean("connected") } == true }.getOrDefault(false)
        val offline = !app.mediaReady() || runCatching { app.database.ownerID() }.getOrDefault(0) != record.owner || !connected
        return ResolvedMedia(item, app.history.forLocal(record).position, "", offline)
    }

    private fun openMedia(key: String, requestedPosition: Long, args: Bundle = Bundle.EMPTY): ListenableFuture<SessionResult> {
        if (key.isBlank() || released || stopping) return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        cancelOpenRequests()
        if (player.currentMediaItem?.mediaId != key) player.pause()
        val local = key.startsWith("download:")
        val expectedCore = app.engine
        val selectionID = args.getString("selection_id")?.takeIf { value ->
            runCatching { java.util.UUID.fromString(value).toString() == value }.getOrDefault(false)
        }
        if (!local && expectedCore == null) return Futures.immediateFuture(openFailure("播放内核尚未恢复，请返回后重试"))
        if (!local && key != "validation" && !app.mediaReady()) return Futures.immediateFuture(openFailure(app.accountIssue ?: "请先确认 Telegram 账号"))
        val generation = openGeneration
        val result = SettableFuture.create<SessionResult>(); pendingOpens.add(result)
        // long: 即使主键相同也重新读取文档身份，资源换片后不能复用旧播放器和断点。
        mediaWorker.execute {
            val resolved = runCatching {
                if (local) return@runCatching resolveLocal(key.removePrefix("download:"), expectedCore)
                val core = expectedCore ?: error("播放内核尚未恢复")
                if (key != "validation") app.requireMediaAccount()
                val row = if (key == "validation") null else app.database.video(key) ?: error("视频已从本机索引移除，请返回视频列表")
                val owner = app.database.ownerID()
                val cached = row?.let { app.downloads.find(owner, key, it.optLong("document_id")) }?.takeIf { it.valid && it.state == DownloadState.COMPLETE }
                // long: 选片列表可能早于下载完成，最终打开时再次优先选择同一文档的完整本机文件。
                if (cached != null && app.downloads.store.completed(cached.id) != null) return@runCatching resolveLocal(cached.id, expectedCore)
                if (row != null) core.registerVideo(row.toString())
                val uri = if (key == "validation") app.validationMedia(core) else core.playbackURL(key)
                ResolvedMedia(MediaItem.Builder().setMediaId(key).setUri(uri).setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(row?.optString("name") ?: "本地播放测试")
                    .setArtist(row?.let { app.database.channel(it.optLong("channel_id"))?.optString("title") } ?: "TGProxy")
                    .setExtras(Bundle().apply { putLong("document_id", row?.optLong("document_id") ?: 0); putLong("account_id", app.database.ownerID())
                        putLong("channel_id", row?.optLong("channel_id") ?: 0); putString("original_key", key) }).build()).build(),
                    if (key == "validation" && requestedPosition >= 0) requestedPosition else row?.let { app.history.forVideo(it, app.database.ownerID()).position } ?: 0L, core.token())
            }
            handler.post {
                pendingOpens.remove(result)
                // long: 换账号、停止播放和后发请求都会使旧解析作废，不能让迟到结果重新启动旧媒体。
                if (released || stopping || generation != openGeneration || (!local && (expectedCore !== app.engine || (key != "validation" && !app.mediaReady())))) {
                    result.set(SessionResult(SessionError.ERROR_INVALID_STATE)); return@post
                }
                val media = resolved.getOrNull()
                if (media == null) result.set(openFailure(if (local) "本机下载文件不可用，请在下载管理中检查后重试" else "资源暂时无法打开，请确认账号或校验频道后重试"))
                else {
                    val applied = runCatching {
                        val extra = media.item.mediaMetadata.extras
                        val owner = extra?.getLong("account_id") ?: 0
                        val channel = extra?.getLong("channel_id") ?: 0
                        val old = scope?.takeIf { it.owner == owner && it.channel == channel }
                        val sort = args.getString("queue_sort") ?: old?.sort ?: "newest"
                        // long: 最近观看时间会随播放改变，连续浏览改用固定的发布时间顺序，避免来回循环已看条目。
                        scope = if (key == "validation") null else PlaybackScope(owner, channel, args.getString("queue_search") ?: old?.search.orEmpty(),
                            sort.takeIf { it in setOf("name", "oldest", "newest") } ?: "newest",
                            args.getBoolean("queue_offline", old?.offline ?: false) || media.offline)
                        val actualLocal = media.item.mediaId.startsWith("download:")
                        val sameDocument = player.mediaMetadata.extras?.getLong("document_id", 0) == extra?.getLong("document_id", 0) &&
                            player.mediaMetadata.extras?.getLong("account_id", 0) == owner &&
                            player.mediaMetadata.extras?.getString("original_key") == extra?.getString("original_key")
                        val sameFile = sameDocument && player.currentMediaItem?.mediaId == media.item.mediaId && (actualLocal || sourceCore === expectedCore)
                        val livePosition = if (sameDocument) { if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition } else media.position
                        sourceCore = if (actualLocal) null else expectedCore
                        if (sameFile && player.playerError == null) Util.handlePlayButtonAction(player)
                        else {
                            // long: 同文档切来源继承即时位置；已看完则从头重播，不能绕过归零断点跳回片尾。
                            val start = livePosition
                            savePosition()
                            http.setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${media.token}"))
                            wantsPlayback = true
                            player.setMediaItem(media.item, start); player.prepare(); player.play()
                        }
                    }
                    if (applied.isSuccess && selectionID != null) synchronized(fulfilledSelections) {
                        // long: 页面解绑后成功选片仍要可确认，后续自动切集不能让旧页面误以为选择还没兑现。
                        fulfilledSelections.remove(selectionID); fulfilledSelections.add(selectionID)
                        if (fulfilledSelections.size > 32) fulfilledSelections.remove(fulfilledSelections.first())
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
        retryTask?.let(handler::removeCallbacks); stableTask?.let(handler::removeCallbacks)
        retryTask = null; stableTask = null; recoveryText = null; failedTarget = null
        retryPolicy.reset()
        pendingOpens.forEach { it.set(SessionResult(SessionError.ERROR_INVALID_STATE)) }; pendingOpens.clear()
    }

    private fun queueState(args: Bundle): ListenableFuture<SessionResult> {
        val item = player.currentMediaItem ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        val selected = scope ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putString("queue", "{}") }))
        val requestedSort = args.getString("sort")?.takeIf { it in setOf("newest", "oldest", "name") }
        val selectedScope = if (requestedSort != null) selected.copy(sort = requestedSort).also { scope = it } else selected
        val original = item.mediaMetadata.extras?.getString("original_key") ?: item.mediaId
        val generation = openGeneration
        val result = SettableFuture.create<SessionResult>()
        pendingOpens.add(result)
        mediaWorker.execute {
            if (result.isDone) return@execute
            val data = runCatching {
                val neighbors = catalog.neighbors(item.mediaId, original, selectedScope)
                val value = if (args.getBoolean("list")) catalog.page(item.mediaId, selectedScope,
                    args.getString("cursor")?.let(VideoPageCursor::decode), args.getInt("page"), item.mediaMetadata.extras?.getLong("document_id")) else org.json.JSONObject()
                value.put("previous", neighbors.first ?: "").put("next", neighbors.second ?: "").put("current", item.mediaId)
                    .put("sort", selectedScope.sort).put("offline", selectedScope.offline)
                value.toString()
            }
            handler.post {
                pendingOpens.remove(result)
                if (released || stopping || generation != openGeneration || player.currentMediaItem?.mediaId != item.mediaId) result.set(SessionResult(SessionError.ERROR_INVALID_STATE))
                else result.set(data.getOrNull()?.let { SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putString("queue", it) }) }
                    ?: openFailure("播放列表暂时无法读取，请稍后重试"))
            }
        }
        return result
    }

    private fun maybeAdvance() {
        val selected = scope ?: return
        val item = player.currentMediaItem ?: return
        if (!PlaybackPreferences(this).continuous || !player.playWhenReady || advanceGeneration == openGeneration || released || stopping) return
        val generation = openGeneration; advanceGeneration = generation
        mediaWorker.execute {
            val next = runCatching { catalog.neighbors(item.mediaId, item.mediaMetadata.extras?.getString("original_key") ?: item.mediaId, selected).second }.getOrNull()
            handler.post {
                if (advanceGeneration == generation) advanceGeneration = null
                if (released || stopping || generation != openGeneration || player.currentMediaItem?.mediaId != item.mediaId ||
                    player.playbackState != Player.STATE_ENDED || !player.playWhenReady || !PlaybackPreferences(this).continuous || next == null) return@post
                val pending = openMedia(next, -1, Bundle().apply {
                    putString("queue_sort", selected.sort); putString("queue_search", selected.search); putBoolean("queue_offline", selected.offline)
                })
                val activeGeneration = openGeneration
                pending.addListener({
                    val outcome = runCatching { pending.get() }.getOrNull()
                    if (!released && activeGeneration == openGeneration && outcome?.resultCode != SessionResult.RESULT_SUCCESS) { failedTarget = next; recoveryText = "下一条暂时无法播放，点击重试" }
                }, androidx.core.content.ContextCompat.getMainExecutor(this))
            }
        }
    }

    private fun recoverPlayback(error: PlaybackException) {
        stableTask?.let(handler::removeCallbacks); stableTask = null
        val key = player.currentMediaItem?.mediaId ?: return
        val cause = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val wait = cause?.headerFields?.entries?.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
            ?.toLongOrNull()?.coerceIn(0, 30)?.times(1000) ?: 0
        val delay = if (!key.startsWith("download:") && sourceCore === app.engine && (key == "validation" || app.mediaReady()))
            retryPolicy.nextDelay(error.errorCode, cause?.responseCode, wait) else null
        recoveryText = when {
            error.errorCode in 3000..4999 -> "设备暂时无法解码此视频，请检查格式或换一个资源"
            key.startsWith("download:") -> "本机文件暂时无法播放，请检查下载记录后重试"
            cause?.responseCode in setOf(401, 403, 404, 412) -> "频道资源或授权已变化，请校验频道后重试"
            delay != null -> "连接中断，正在重试 ${retryPolicy.attempts}/3…"
            error.errorCode in 2000..2999 -> "连接暂未恢复，请检查网络后点击重试"
            else -> "视频暂时无法播放，请查看播放信息或换一个资源"
        }
        if (delay == null || !wantsPlayback) return
        val generation = openGeneration
        retryTask = Runnable {
            retryTask = null
            // long: 用户暂停、换片、换账号和停止都会撤销恢复，迟到的重试不能重新启动旧媒体。
            if (released || stopping || generation != openGeneration || !wantsPlayback || player.currentMediaItem?.mediaId != key ||
                sourceCore !== app.engine || (key != "validation" && !app.mediaReady()) || player.playerError == null ||
                player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) return@Runnable
            player.prepare(); player.play()
        }.also { handler.postDelayed(it, delay) }
    }

    private fun savePosition() {
        if (!::player.isInitialized || released) return
        val key = player.currentMediaItem?.mediaId ?: return
        val updated = System.currentTimeMillis()
        val document = player.mediaMetadata.extras?.getLong("document_id", 0) ?: 0
        val owner = player.mediaMetadata.extras?.getLong("account_id", 0) ?: 0
        if (key.startsWith("download:")) {
            val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
            if (!mediaWorker.isShutdown) mediaWorker.execute { runCatching { app.history.saveLocal(key.removePrefix("download:"), position, document, owner, updated) } }
            return
        }
        if (key != "validation") {
            val position = if (player.playbackState == Player.STATE_ENDED) 0L else player.currentPosition
            if (!mediaWorker.isShutdown) mediaWorker.execute { runCatching { app.history.saveRemote(key, position, document, owner, updated) } }
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
        synchronized(fulfilledSelections) { fulfilledSelections.clear() }
        cancelOpenRequests()
        mediaWorker.shutdown()
        handler.removeCallbacksAndMessages(null)
        session.release()
        player.release()
    }

    companion object {
        val OPEN = SessionCommand("dev.tgproxy.mobile.OPEN", Bundle.EMPTY)
        val QUEUE = SessionCommand("dev.tgproxy.mobile.QUEUE", Bundle.EMPTY)
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
        fun recoveryStatus(): String? = active?.recoveryText
        fun failedPlaybackTarget(): String? = active?.failedTarget
        fun selectionFulfilled(id: String?): Boolean {
            val service = active ?: return false
            return id != null && synchronized(service.fulfilledSelections) { id in service.fulfilledSelections }
        }
        fun applyContinuousPreference() {
            val service = active ?: return
            service.handler.post { if (active === service && !service.released && service.player.playbackState == Player.STATE_ENDED) service.maybeAdvance() }
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
                synchronized(it.fulfilledSelections) { it.fulfilledSelections.clear() }
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
