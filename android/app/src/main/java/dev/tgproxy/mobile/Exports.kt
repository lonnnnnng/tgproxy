package dev.tgproxy.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.IOException
import java.util.concurrent.Executors

class Exports(private val context: Context, val store: ExportStore,
    private val downloads: DownloadStore = (context.applicationContext as TgApplication).downloads.store,
    private val transfer: ExportTransfer = ExportTransfer(),
    private val startService: () -> Unit = { ExportService.start(context) }) {
    private val commands = Executors.newSingleThreadExecutor()
    private val worker = Executors.newSingleThreadExecutor()
    private val closer = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val cleaning = mutableSetOf<String>()
    private data class Run(val item: ExportRecord, val cancellation: ExportCancellation = ExportCancellation(),
        val signal: CancellationSignal = CancellationSignal(), @Volatile var descriptor: ParcelFileDescriptor? = null,
        @Volatile var stopReason: String? = null)
    @Volatile private var active: Run? = null
    private var processing = false
    private var stopping = false
    private var generation = 0L
    private var serviceEpoch = 0L
    private var idle: (() -> Unit)? = null
    @Volatile var ready = false
        private set
    @Volatile var records: List<ExportRecord> = emptyList()
        private set
    @Volatile var current: ExportRecord? = null
        private set
    @Volatile var problem: String? = null
        private set
    @Volatile var revision = 0L
        private set
    val latest get() = current ?: records.firstOrNull()
    val running get() = synchronized(lock) { processing }
    init { refresh() }
    private fun changed() { records = store.list(); revision++ }
    private fun command(finished: (String?) -> Unit = {}, locked: Boolean = true, action: () -> Unit) {
        runCatching { commands.execute {
            val failure = runCatching { if (locked) synchronized(lock) { action() } else action() }.exceptionOrNull()
            if (failure is android.database.sqlite.SQLiteException || !ready) problem = "另存记录暂时不可读，点击重新读取"
            main.post { finished(failure?.let(::safeError)) }
        } }.onFailure { main.post { finished("另存任务已停止，请重新打开下载管理") } }
    }
    fun refresh() = command {
        if (!ready || (!processing && problem != null)) store.recover()
        changed(); ready = true; problem = null
    }
    fun enqueue(download: String, uri: Uri, flags: Int, finished: (String?) -> Unit) = command(finished, locked = false) {
        synchronized(lock) {
            check(ready) { "另存记录尚未恢复，请稍后重试" }
            check(problem == null) { "请先重新读取另存记录，再选择保存位置" }
            check(uri.toString() !in cleaning && !store.protectedTarget(uri.toString())) { "此位置已有另存任务或副本，请选择新文件名" }
        }
        check(uri.scheme == "content" && flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) { "保存位置不可写，请重新选择" }
        val source = downloads.get(download)?.takeIf { it.valid && it.state == DownloadState.COMPLETE } ?: error("源视频尚未下载完成或已移除")
        check(downloads.completed(download) != null) { "源视频文件已丢失，请重新下载" }
        val modes = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // long: 只持久化系统实际授予的读写权限；没有持久授权时仍可完成本次复制，重启后重新选位置。
        val persisted = flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
            runCatching { context.contentResolver.takePersistableUriPermission(uri, modes) }.isSuccess
        synchronized(lock) { store.add(source, uri, persisted); changed() }
        main.post(startService)
    }
    fun history(page: Int, finished: (List<ExportRecord>, String?) -> Unit) {
        var values = emptyList<ExportRecord>()
        command({ error -> finished(values, error) }) { values = store.list(offset = page.coerceIn(0, 1000000) * 50) }
    }
    fun cancel(id: String, finished: (String?) -> Unit = {}) {
        // long: 取消与完成提交共享边界，完成后到达的取消不能把完整副本改成失败。
        val cancelling = synchronized(lock) { active?.takeIf { it.item.id == id }?.also { it.cancellation.cancel() } }
        cancelling?.let(::closeRun)
        command(finished) {
            val item = store.get(id) ?: return@command
            if (item.state !in setOf(ExportState.QUEUED, ExportState.RUNNING, ExportState.CANCELLING)) return@command
            val run = active?.takeIf { it.item.id == id }
            run?.cancellation?.cancel()
            store.update(id, if (run == null) ExportState.CANCELLED else ExportState.CANCELLING, item.bytes, "取消后可重新选择位置")
            current = store.get(id)?.takeIf { run != null }; changed()
        }
    }
    private fun closeRun(run: Run) {
        closer.execute {
            runCatching { run.signal.cancel() }
            run.descriptor?.let { fd -> runCatching { fd.closeWithError("另存已取消") }.onFailure { runCatching { fd.close() } } }
        }
    }
    fun cleanup(id: String, finished: (String?) -> Unit) = command(finished, locked = false) {
        val item = synchronized(lock) {
            (store.get(id) ?: error("另存记录已移除")).also {
                check(it.valid && it.touched && it.state in setOf(ExportState.FAILED, ExportState.CANCELLED, ExportState.INTERRUPTED) && active?.item?.id != id) { "另存尚未停止或未写入副本，不能清理此位置" }
                check(!store.protectedTarget(it.uri, id) && cleaning.add(it.uri)) { "此位置还有其他另存任务，不能清理" }
            }
        }
        val uri = Uri.parse(item.uri)
        // long: 只在用户确认后清理该次已写入的副本，不能自动删除系统选择器返回的未知现有文件。
        try {
            // long: 文件提供器可能较慢，删除和权限释放不能持有播放/取消状态锁，避免阻塞页面。
            val removed = if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
                else context.contentResolver.delete(uri, null, null) > 0
            check(removed) { "此位置不支持清理，请使用系统文件管理器处理" }
            synchronized(lock) { store.update(id, item.state, item.bytes, "未完成副本已清理，可重新选择位置", touched = false); changed() }
            if (item.persisted) runCatching {
                val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
                val modes = (if (grant?.isReadPermission == true) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (grant?.isWritePermission == true) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                if (modes != 0) context.contentResolver.releasePersistableUriPermission(uri, modes)
            }
        } finally { synchronized(lock) { cleaning.remove(item.uri) } }
    }
    fun start(onIdle: () -> Unit): Long {
        val lease: Long
        val run: Long
        synchronized(lock) {
            lease = ++serviceEpoch; idle = onIdle; stopping = false
            if (processing) return lease
            if (!ready) { main.post(onIdle); return lease }
            processing = true; run = ++generation
        }
        runCatching { worker.execute {
            try {
                while (true) {
                    val next = synchronized(lock) {
                        val item = if (stopping) null else store.next()
                        if (item == null) { release(run); return@execute }
                        if (!item.valid) { store.update(item.id, ExportState.FAILED, item.bytes, "另存记录已损坏"); null }
                        else Run(item).also { active = it; store.update(item.id, ExportState.RUNNING, 0); current = store.get(item.id); changed() }
                    } ?: continue
                    copy(next)
                }
            } catch (_: Exception) {
                synchronized(lock) { problem = "另存状态未能保存，请重新读取记录"; stopping = true }
            } finally { synchronized(lock) { release(run) } }
        } }.onFailure { synchronized(lock) { problem = "另存任务未能启动"; release(run) } }
        return lease
    }
    private fun release(run: Long) {
        if (generation != run || !processing) return
        processing = false; active = null; current = null
        main.post { synchronized(lock) { if (generation == run && !processing) idle?.invoke() } }
    }
    fun serviceStopped(lease: Long, reason: String = "后台另存已停止，请重新选择位置") {
        synchronized(lock) {
            if (serviceEpoch != lease) return
            stopping = true; active?.let { it.stopReason = reason; it.cancellation.cancel(); closeRun(it) }
        }
        command {
            if (serviceEpoch != lease) return@command
            store.interruptPending(active?.item?.id, reason)
            changed()
        }
    }
    fun failedToStart() {
        command {
            if (processing) return@command
            store.recover(); changed(); problem = "后台另存未能启动，点击重新读取后选择位置"
        }
    }
    private fun copy(run: Run) {
        val item = run.item
        var copied = 0L
        try {
            run.cancellation.check()
            val source = downloads.get(item.download)?.takeIf { it.valid && it.owner == item.owner && it.document == item.document && it.size == item.size }
                ?: error("源视频已变化或移除")
            val file = downloads.completed(source.id) ?: error("源视频文件已丢失")
            val uri = Uri.parse(item.uri)
            val existingSize = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null, run.signal)?.use {
                val column = it.getColumnIndex(OpenableColumns.SIZE)
                if (it.moveToFirst() && column >= 0 && !it.isNull(column)) it.getLong(column) else null
            }
            check(existingSize != null) { "无法确认保存位置为空，请重新选择本机目录" }
            check(existingSize == 0L) { "保存位置已有内容，请重新选择新文件名" }
            val fd = context.contentResolver.openFileDescriptor(uri, "wt", run.signal) ?: error("保存位置暂时不可用")
            run.descriptor = fd
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use { output ->
                synchronized(lock) { run.cancellation.check(); store.update(item.id, ExportState.RUNNING, 0, touched = true) }
                file.inputStream().use { input ->
                    transfer.copy(input, output, item.size, run.cancellation) { bytes ->
                        synchronized(lock) {
                            run.cancellation.check()
                            copied = bytes; store.update(item.id, ExportState.RUNNING, bytes)
                            current = item.copy(state = ExportState.RUNNING, bytes = bytes, touched = true)
                        }
                    }
                }
                if (fd.statSize >= 0) output.fd.sync()
            }
            synchronized(lock) { run.cancellation.check(); store.update(item.id, ExportState.COMPLETE, item.size, touched = true) }
        } catch (failure: Exception) {
            synchronized(lock) {
                val state = if (run.stopReason != null) ExportState.INTERRUPTED else if (run.cancellation.cancelled) ExportState.CANCELLED else ExportState.FAILED
                store.update(item.id, state, maxOf(copied, run.cancellation.bytesCopied), run.stopReason ?:
                    if (run.cancellation.cancelled) "另存已取消，目标可能保留未完成副本" else safeError(failure))
            }
        } finally {
            run.descriptor?.let { runCatching { it.close() } }; run.descriptor = null
            synchronized(lock) { if (active === run) { active = null; current = null }; changed() }
        }
    }
    private fun safeError(failure: Throwable): String = when {
        failure is android.database.sqlite.SQLiteException -> "另存记录暂时不可读，点击重新读取"
        failure is SecurityException -> "保存位置访问权已失效，请重新选择"
        failure.message?.contains("ENOSPC") == true -> "保存位置空间不足，请更换目录后重试"
        failure is IllegalStateException || failure is IllegalArgumentException -> failure.message?.take(160) ?: "另存未完成，请重新选择"
        failure is IOException -> "文件复制未完成，请检查保存位置和剩余空间后重试"
        else -> "另存未完成，请重新选择保存位置"
    }
    fun shutdown() {
        synchronized(lock) { stopping = true; active?.cancellation?.cancel(); active?.let(::closeRun) }
        commands.shutdown(); worker.shutdown(); closer.shutdown()
    }
}
