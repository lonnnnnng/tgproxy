package dev.tgproxy.mobile

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.Executors

class Downloads(private val app: TgApplication, val store: DownloadStore,
    private val resolve: (DownloadRecord) -> DownloadSource = { item ->
        app.requireMediaAccount()
        check(app.database.ownerID() == item.owner) { "请登录下载任务所属的 Telegram 账号" }
        val core = app.engine ?: error("下载内核尚未就绪")
        val current = app.database.video(item.key)
        check(current == null || current.optLong("document_id") == item.document) { "该资源已换片，请从详情页重新下载" }
        core.registerVideo(item.video.toString())
        DownloadSource(core.playbackURL(item.key), core.token(),
            if (item.document > 0) "\"document:${item.document}:${item.size}\"" else "") {
            app.requireMediaAccount()
            check(app.engine === core && app.database.ownerID() == item.owner) { "账号已变化，下载已停止" }
        }
    }) {
    private val commands = Executors.newSingleThreadExecutor()
    private val transfers = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var processing = false
    private var currentID: String? = null
    private var cancellation: DownloadCancellation? = null
    @Volatile var ready = false
        private set
    @Volatile var revision = 0L
        private set
    @Volatile var bytesPerSecond = 0L
        private set
    @Volatile var records: List<DownloadRecord> = emptyList()
        private set
    private var idle: (() -> Unit)? = null

    init { commands.execute { synchronized(lock) { store.recover(); ready = true; changed() } } }
    private fun changed() { records = store.list(); revision++ }
    fun refresh() { commands.execute { synchronized(lock) { changed() } } }

    fun enqueue(key: String, finished: (String?) -> Unit) = command(finished) {
        app.requireMediaAccount()
        val video = app.database.video(key) ?: error("视频已移除，请返回列表")
        val item = store.add(app.database.ownerID(), video)
        check(currentID != item.id || cancellation?.cancelled != true) { "正在暂停，请稍后点击继续" }
        check(item.state != DownloadState.DELETING) { "正在删除这个下载，请稍后重试" }
        if (item.state != DownloadState.RUNNING && store.completed(item.id) == null)
            store.update(item.id, DownloadState.QUEUED, store.file(item.id, true).length())
        changed()
        main.post { DownloadService.start(app) }
    }
    fun resume(id: String, finished: (String?) -> Unit) = command(finished) {
        val item = store.get(id) ?: error("任务已移除")
        check(currentID != id) { "正在暂停，请稍后点击继续" }
        check(item.state !in listOf(DownloadState.DELETING, DownloadState.RUNNING)) { "请等待当前操作完成" }
        app.requireMediaAccount()
        check(app.database.ownerID() == item.owner) { "请登录下载任务所属的 Telegram 账号" }
        if (store.completed(id) == null) store.update(id, DownloadState.QUEUED, store.file(id, true).length())
        changed(); main.post { DownloadService.start(app) }
    }
    fun pause(id: String, finished: (String?) -> Unit = {}) = command(finished) {
        val item = store.get(id) ?: return@command
        if (item.state !in listOf(DownloadState.COMPLETE, DownloadState.DELETING)) {
            store.update(id, DownloadState.PAUSED, store.file(id, true).length())
            if (currentID == id) cancellation?.cancel()
            changed()
        }
    }
    fun pauseAll(reason: String = "已暂停", finished: (String?) -> Unit = {}) = command(finished) {
        pauseAllLocked(reason)
    }
    fun serviceStopped() { synchronized(lock) { pauseAllLocked("后台任务已停止，可继续下载") } }
    private fun pauseAllLocked(reason: String) {
        store.list().filter { it.state in listOf(DownloadState.QUEUED, DownloadState.RUNNING) }.forEach {
            store.update(it.id, DownloadState.PAUSED, store.file(it.id, true).length(), reason)
        }
        cancellation?.cancel(); changed()
    }
    fun remove(id: String, finished: (String?) -> Unit) = command(finished) {
        val item = store.get(id) ?: return@command
        if (currentID == id) {
            store.update(id, DownloadState.DELETING, item.bytes); cancellation?.cancel()
        } else { store.removeFiles(id); store.delete(id) }
        changed()
    }
    private fun command(finished: (String?) -> Unit, action: () -> Unit) {
        commands.execute {
            val error = synchronized(lock) { runCatching(action).exceptionOrNull()?.message?.take(180) }
            main.post { finished(error) }
        }
    }

    fun start(onIdle: () -> Unit) {
        synchronized(lock) {
            idle = onIdle
            if (processing) return
            processing = true
        }
        transfers.execute {
            while (true) {
                val pair = synchronized(lock) {
                    val item = store.list().lastOrNull { it.state == DownloadState.QUEUED }
                    if (item == null) {
                        processing = false; currentID = null; bytesPerSecond = 0
                        main.post { synchronized(lock) { if (!processing) idle?.invoke() } }
                        return@execute
                    }
                    val cancel = DownloadCancellation()
                    currentID = item.id; cancellation = cancel
                    store.update(item.id, DownloadState.RUNNING, store.file(item.id, true).length()); changed()
                    item to cancel
                }
                transfer(pair.first, pair.second)
            }
        }
    }
    private fun transfer(item: DownloadRecord, cancel: DownloadCancellation) {
        var sampleTime = System.nanoTime()
        var sampleBytes = store.file(item.id, true).length()
        try {
            val source = resolve(item)
            DownloadTransfer().copy(source, store.file(item.id, true), item.size, cancel) { bytes ->
                synchronized(lock) {
                    cancel.check()
                    val now = System.nanoTime()
                    if (now - sampleTime >= 500_000_000) {
                        bytesPerSecond = ((bytes - sampleBytes).coerceAtLeast(0) * 1_000_000_000 / (now - sampleTime))
                        sampleBytes = bytes; sampleTime = now
                    }
                    store.update(item.id, DownloadState.RUNNING, bytes); changed()
                }
            }
            synchronized(lock) {
                cancel.check()
                // long: 完整长度检查、落盘及原子改名后才标记完成，迟到的取消不能写回完成状态。
                check(store.file(item.id, true).length() == item.size)
                check(store.file(item.id, true).renameTo(store.file(item.id))) { "无法保存下载文件，请检查剩余空间" }
                store.update(item.id, DownloadState.COMPLETE, item.size); changed()
            }
        } catch (failure: Exception) {
            synchronized(lock) {
                val state = store.get(item.id)?.state
                if (state != null && state != DownloadState.DELETING) store.update(item.id,
                    if (cancel.cancelled) DownloadState.PAUSED else DownloadState.FAILED,
                    store.file(item.id, true).length(), if (cancel.cancelled) "已暂停，可继续下载" else safeError(failure))
            }
        } finally {
            synchronized(lock) {
                if (store.get(item.id)?.state == DownloadState.DELETING) {
                    runCatching { store.removeFiles(item.id); store.delete(item.id) }
                        .onFailure { store.update(item.id, DownloadState.FAILED, 0, "文件删除失败，请重试") }
                }
                if (currentID == item.id) { currentID = null; cancellation = null; bytesPerSecond = 0 }
                changed()
            }
        }
    }
    private fun safeError(failure: Exception): String = when {
        failure is DownloadProtocolError -> failure.message ?: "视频源响应无效"
        failure.message?.contains("ENOSPC") == true -> "存储空间不足，释放空间后继续"
        failure is IOException -> "连接中断或文件写入失败，请检查网络和存储后重试"
        else -> "无法下载，请确认原账号及频道资源后重试"
    }
    fun failedToStart() { pauseAll("后台任务未能启动，请保持 App 在前台后继续") }
    fun shutdown() { synchronized(lock) { pauseAllLocked("任务已停止") }; commands.shutdown(); transfers.shutdown() }
}
