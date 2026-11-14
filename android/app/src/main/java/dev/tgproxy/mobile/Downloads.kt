package dev.tgproxy.mobile

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.Executors

class Downloads(private val app: TgApplication, val store: DownloadStore,
    private val availableBytes: () -> Long = { store.directory.usableSpace },
    private val currentOwner: () -> Long = { app.requireMediaAccount(); app.database.ownerID() },
    private val startService: () -> Unit = { DownloadService.start(app) },
    private val commands: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor(),
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
    private val transfers = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var processing = false
    private var generation = 0L
    private var controlGeneration = 0L
    private var stopRequested = false
    private var currentID: String? = null
    private var cancellation: DownloadCancellation? = null
    @Volatile var ready = false
        private set
    @Volatile private var snapshot = DownloadSnapshot.create(emptyList(), 0)
    @Volatile private var latestProgress: DownloadRecord? = null
    @Volatile var progressRevision = 0L
        private set
    val revision get() = snapshot.revision
    val stateRecords get() = snapshot.items
    @Volatile var bytesPerSecond = 0L
        private set
    val records: List<DownloadRecord> get() {
        val saved = snapshot
        val progress = latestProgress ?: return saved.items
        val index = saved.indices[progress.id] ?: return saved.items
        return if (saved.items[index].state == DownloadState.RUNNING) SingleItemList(saved.items, index, progress) else saved.items
    }
    val stats: DownloadStats get() {
        val saved = snapshot
        val progress = latestProgress ?: return saved.stats
        val old = saved.indices[progress.id]?.let(saved.items::get) ?: return saved.stats
        return if (old.state == DownloadState.RUNNING) saved.stats.copy(bytes = saved.stats.bytes + progress.bytes - old.bytes) else saved.stats
    }
    val activeRecord: DownloadRecord? get() {
        val saved = snapshot
        return saved.items.getOrNull(saved.activeIndex)?.let { effective(saved, it.id) }
    }
    fun record(id: String): DownloadRecord? = effective(snapshot, id)
    fun find(owner: Long, key: String, document: Long): DownloadRecord? {
        val saved = snapshot
        return saved.identities[Triple(owner, key, document)]?.let { effective(saved, saved.items[it].id) }
    }
    private fun effective(saved: DownloadSnapshot, id: String): DownloadRecord? {
        val item = saved.indices[id]?.let(saved.items::get) ?: return null
        val progress = latestProgress
        return if (item.state == DownloadState.RUNNING && progress?.id == id && progress.document == item.document) progress else item
    }
    private var idle: (() -> Unit)? = null
    @Volatile var problem: String? = null
        private set
    internal val isRunning get() = synchronized(lock) { processing }

    init { refresh() }
    private fun changed() {
        val items = store.list()
        latestProgress = null
        snapshot = DownloadSnapshot.create(items, snapshot.revision + 1)
    }
    fun refresh() = command({}, allowUnavailable = true) {
        if (!ready || (!processing && problem != null)) {
            val issues = store.recover()
            problem = if (issues > 0) "部分下载记录恢复失败，其他任务仍可使用；点击这里重新读取" else null
        }
        changed(); ready = true
    }

    fun enqueue(key: String, finished: (String?) -> Unit) = command(finished) {
        val owner = currentOwner()
        val video = app.database.video(key) ?: error("视频已移除，请返回列表")
        val item = store.add(owner, video)
        check(item.valid) { item.error }
        check(currentID != item.id || cancellation?.cancelled != true) { "正在暂停，请稍后点击继续" }
        check(item.state != DownloadState.DELETING) { "正在删除这个下载，请稍后重试" }
        if (item.state != DownloadState.RUNNING && store.completed(item.id) == null)
            store.update(item.id, DownloadState.QUEUED, store.file(item.id, true).length())
        changed()
        main.post(startService)
    }
    fun resume(id: String, finished: (String?) -> Unit) = command(finished) {
        val item = store.get(id) ?: error("任务已移除")
        check(item.valid) { item.error }
        check(currentID != id) { "正在暂停，请稍后点击继续" }
        check(item.state !in listOf(DownloadState.DELETING, DownloadState.RUNNING)) { "请等待当前操作完成" }
        check(currentOwner() == item.owner) { "请登录下载任务所属的 Telegram 账号" }
        if (store.completed(id) == null) store.update(id, DownloadState.QUEUED, store.file(id, true).length())
        changed(); main.post(startService)
    }
    fun resumeAll(failedOnly: Boolean = false, finished: (String?) -> Unit) = command(finished) {
        val count = store.queueForOwner(currentOwner(), failedOnly, currentID)
        check(count > 0) { if (failedOnly) "没有当前账号可重试的失败任务" else "没有当前账号可继续的任务" }
        problem = null; changed(); main.post(startService)
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
    fun serviceStopped() {
        val request = synchronized(lock) { stopRequested = true; cancellation?.cancel(); ++controlGeneration }
        command({}) {
            // long: 系统停止后的落盘可能排队，新一轮主动继续已经开始时不能被旧停止请求再次暂停。
            if (controlGeneration == request) pauseAllLocked("后台任务已停止，可继续下载")
        }
    }
    private fun pauseAllLocked(reason: String) {
        stopRequested = true; cancellation?.cancel()
        store.list().filter { it.state in listOf(DownloadState.QUEUED, DownloadState.RUNNING) }.forEach {
            store.update(it.id, DownloadState.PAUSED, store.file(it.id, true).length(), reason)
        }
        cancellation?.cancel(); changed()
    }
    fun remove(id: String, finished: (String?) -> Unit) = command(finished) {
        val item = store.get(id) ?: return@command
        if (currentID == id) {
            store.update(id, DownloadState.DELETING, item.bytes); cancellation?.cancel()
        } else {
            // long: 损坏的 UUID 无法证明文件路径，只移除该记录，不能推测路径后误删其他文件。
            if (store.canLocateFiles(id)) store.removeFiles(id)
            store.delete(id)
        }
        changed()
    }
    private fun command(finished: (String?) -> Unit, allowUnavailable: Boolean = false, action: () -> Unit) {
        runCatching {
            commands.execute {
                val error = synchronized(lock) {
                    runCatching { check(allowUnavailable || ready) { "下载记录尚未就绪，请点击提示重新读取" }; action() }.exceptionOrNull()?.let { failure ->
                        if (failure is android.database.sqlite.SQLiteException || !ready) problem = "无法读取或保存下载记录，点击这里重试"
                        safeError(failure)
                    }
                }
                main.post { finished(error) }
            }
        }.onFailure { main.post { finished("下载任务已停止，请重新打开页面") } }
    }

    fun start(onIdle: () -> Unit) {
        val run = synchronized(lock) {
            if (!ready) { main.post(onIdle); return }
            idle = onIdle
            controlGeneration++
            stopRequested = false
            if (processing) return
            processing = true; ++generation
        }
        runCatching { transfers.execute {
            try { while (true) {
                val pair = synchronized(lock) {
                    val item = if (stopRequested) null else store.list().lastOrNull { it.state == DownloadState.QUEUED && it.valid }
                    if (item == null) {
                        releaseRun(run)
                        return@execute
                    }
                    val cancel = DownloadCancellation()
                    currentID = item.id; cancellation = cancel
                    store.update(item.id, DownloadState.RUNNING, store.file(item.id, true).length()); changed()
                    item to cancel
                }
                transfer(pair.first, pair.second)
            } } catch (_: Exception) {
                synchronized(lock) {
                    problem = "无法保存下载状态，任务已暂停；点击这里重新读取后继续"
                    runCatching { pauseAllLocked(problem!!) }
                }
            } finally { synchronized(lock) { releaseRun(run) } }
        } }.onFailure { synchronized(lock) { problem = "下载任务未能启动，请重新打开页面"; releaseRun(run) } }
    }
    private fun releaseRun(run: Long) {
        // long: 空队列与新入队可能交错，旧任务收尾不能清掉已经开始的新一轮任务。
        if (generation != run || !processing) return
        processing = false; currentID = null; cancellation = null; bytesPerSecond = 0
        main.post { synchronized(lock) { if (generation == run && !processing) idle?.invoke() } }
    }
    private fun checkSpace(item: DownloadRecord, partial: java.io.File) {
        check(store.directory.isDirectory || store.directory.mkdirs()) { "无法创建下载目录，请检查存储" }
        DownloadStoragePolicy.check(item.size, partial.length(), availableBytes())
    }
    private fun transfer(item: DownloadRecord, cancel: DownloadCancellation) {
        var sampleTime = System.nanoTime()
        var sampleBytes = store.file(item.id, true).length()
        try {
            checkSpace(item, store.file(item.id, true))
            val source = resolve(item)
            DownloadTransfer().copy(source, store.file(item.id, true), item.size, cancel) { bytes ->
                synchronized(lock) {
                    cancel.check()
                    checkSpace(item, store.file(item.id, true))
                    val now = System.nanoTime()
                    if (now - sampleTime >= 500_000_000) {
                        bytesPerSecond = ((bytes - sampleBytes).coerceAtLeast(0) * 1_000_000_000 / (now - sampleTime))
                        sampleBytes = bytes; sampleTime = now
                    }
                    store.update(item.id, DownloadState.RUNNING, bytes)
                    // long: 字节变化只发布当前任务，完成历史只在增删、状态变化或显式刷新时读取一次。
                    val saved = snapshot
                    val base = saved.indices[item.id]?.let(saved.items::get) ?: item
                    latestProgress = base.copy(state = DownloadState.RUNNING, bytes = bytes, error = "")
                    progressRevision++
                }
            }
            synchronized(lock) {
                cancel.check()
                // long: 完整长度检查、落盘及原子改名后才标记完成，迟到的取消不能写回完成状态。
                check(store.file(item.id, true).length() == item.size)
                check(store.file(item.id, true).renameTo(store.file(item.id))) { "无法保存下载文件，请检查剩余空间" }
                store.update(item.id, DownloadState.COMPLETE, item.size)
            }
        } catch (failure: Exception) {
            synchronized(lock) {
                val lowSpace = failure is DownloadSpaceError || failure.message?.contains("ENOSPC") == true
                if (lowSpace) {
                    // long: 空间不足影响整批任务，暂停后续下载，避免每个任务依次失败并耗尽数据库余量。
                    pauseAllLocked("存储空间不足，释放空间后全部继续")
                }
                val state = store.get(item.id)?.state
                if (state != null && state != DownloadState.DELETING) store.update(item.id,
                    if (cancel.cancelled) DownloadState.PAUSED else DownloadState.FAILED,
                    store.file(item.id, true).length(), when { lowSpace -> "存储空间不足，释放空间后继续"; cancel.cancelled -> "已暂停，可继续下载"; else -> safeError(failure) })
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
    private fun safeError(failure: Throwable): String = when {
        failure is DownloadSpaceError -> failure.message!!
        failure is IllegalStateException || failure is IllegalArgumentException -> failure.message?.take(180) ?: "下载操作未完成，请重试"
        failure is android.database.sqlite.SQLiteException -> "下载记录暂时无法读取或保存，请重新读取后重试"
        failure is DownloadProtocolError -> failure.message ?: "视频源响应无效"
        failure.message?.contains("ENOSPC") == true -> "存储空间不足，释放空间后继续"
        failure is IOException -> "连接中断或文件写入失败，请检查网络和存储后重试"
        else -> "无法下载，请确认原账号及频道资源后重试"
    }
    fun failedToStart() { pauseAll("后台任务未能启动，请保持 App 在前台后继续") }
    fun shutdown() { synchronized(lock) { runCatching { pauseAllLocked("任务已停止") } }; commands.shutdown(); transfers.shutdown() }
}
