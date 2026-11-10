package dev.tgproxy.mobile

enum class ScanMode { UPDATE, HISTORY, RECHECK }
enum class ScanPhase { WAITING, RUNNING, STOPPING, DONE, ERROR, PAUSED }
data class ChannelScanTask(val channelID: Long, val mode: ScanMode = ScanMode.UPDATE)
data class ChannelScanEntry(val task: ChannelScanTask, val phase: ScanPhase, val message: String,
    val total: Long? = null, val error: String? = null)
data class ChannelScanSnapshot(val revision: Long = 0, val runID: Long = 0, val running: Boolean = false,
    val all: Boolean = false, val tasks: List<ChannelScanTask> = emptyList(),
    val entries: Map<Long, ChannelScanEntry> = emptyMap(), val summary: String = "")
data class ChannelScanResult(val message: String, val total: Long? = null)

/** long: 队列状态独立于页面生命周期，频道切换间也保持运行，避免界面误报整批完成。 */
class ChannelScanQueue {
    @Volatile var snapshot = ChannelScanSnapshot()
        private set
    @Volatile private var cancelled = false
    private var executing = false

    @Synchronized fun begin(tasks: List<ChannelScanTask>, all: Boolean = false): Boolean {
        if (snapshot.running) return false
        val unique = tasks.filter { it.channelID > 0 }.distinctBy { it.channelID }
        if (unique.isEmpty()) return false
        cancelled = false
        val entries = snapshot.entries.toMutableMap()
        unique.forEach { entries[it.channelID] = ChannelScanEntry(it, ScanPhase.WAITING, "等待更新") }
        snapshot = ChannelScanSnapshot(snapshot.revision + 1, snapshot.runID + 1, true, all, unique, entries)
        return true
    }

    @Synchronized fun cancel() {
        if (!snapshot.running) return
        cancelled = true
        val entries = snapshot.entries.mapValues { (_, entry) ->
            when (entry.phase) {
                ScanPhase.RUNNING -> entry.copy(phase = ScanPhase.STOPPING, message = "正在暂停…")
                ScanPhase.WAITING -> entry.copy(phase = ScanPhase.PAUSED, message = "已暂停，点击继续")
                else -> entry
            }
        }
        snapshot = snapshot.copy(revision = snapshot.revision + 1, entries = entries)
    }

    @Synchronized private fun update(id: Long, transform: (ChannelScanEntry) -> ChannelScanEntry) {
        val entry = snapshot.entries[id] ?: return
        snapshot = snapshot.copy(revision = snapshot.revision + 1,
            entries = snapshot.entries + (id to transform(entry)))
    }

    // long: 执行器只串行调用；单频道失败留在该行，暂停则同时阻止后续频道发起请求。
    fun execute(action: (ChannelScanTask, (String, Long?) -> Unit) -> ChannelScanResult) {
        val tasks = synchronized(this) {
            if (!snapshot.running || executing) return
            executing = true
            snapshot.tasks
        }
        try {
            for (task in tasks) {
                val start = synchronized(this) {
                    if (cancelled) false else {
                        update(task.channelID) { it.copy(phase = ScanPhase.RUNNING, message = "正在连接…") }
                        true
                    }
                }
                if (!start) break
                try {
                    val result = action(task) { message, total ->
                        update(task.channelID) {
                            it.copy(message = if (cancelled) "正在暂停…" else message, total = total)
                        }
                    }
                    update(task.channelID) { it.copy(phase = if (cancelled) ScanPhase.PAUSED else ScanPhase.DONE,
                        message = if (cancelled) "已暂停，点击继续" else result.message, total = result.total ?: it.total) }
                } catch (failure: Exception) {
                    update(task.channelID) { it.copy(phase = if (cancelled) ScanPhase.PAUSED else ScanPhase.ERROR,
                        message = if (cancelled) "已暂停，点击继续" else "更新失败，点击重试",
                        error = if (cancelled) null else failure.message?.take(180) ?: "请稍后重试") }
                }
            }
        } finally {
            synchronized(this) {
                tasks.forEach { task -> update(task.channelID) {
                    if (it.phase in listOf(ScanPhase.WAITING, ScanPhase.RUNNING, ScanPhase.STOPPING))
                        it.copy(phase = ScanPhase.PAUSED, message = "已暂停，点击继续") else it
                } }
                val results = tasks.mapNotNull { snapshot.entries[it.channelID] }
                val failed = results.count { it.phase == ScanPhase.ERROR }
                val done = results.count { it.phase == ScanPhase.DONE }
                val summary = when {
                    cancelled -> "已暂停，完成的内容和断点已保留"
                    failed > 0 -> "完成 $done 个频道，$failed 个失败，可在对应频道重试"
                    tasks.size == 1 -> results.first().message
                    else -> "已更新 ${tasks.size} 个频道"
                }
                snapshot = snapshot.copy(revision = snapshot.revision + 1, running = false, summary = summary)
                executing = false
            }
        }
    }

    @Synchronized fun clear() {
        check(!snapshot.running)
        snapshot = ChannelScanSnapshot(revision = snapshot.revision + 1, runID = snapshot.runID)
    }

    @Synchronized fun forget(channelID: Long) {
        check(!snapshot.running)
        // long: 重新添加频道必须使用新索引状态，不能沿用移除前的数量和暂停任务类型。
        snapshot = snapshot.copy(revision = snapshot.revision + 1, entries = snapshot.entries - channelID)
    }
}
