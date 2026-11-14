package dev.tgproxy.mobile

data class DownloadStats(val total: Int = 0, val completed: Int = 0, val bytes: Long = 0, val active: Int = 0, val resumable: Int = 0)

internal data class DownloadSnapshot(val items: List<DownloadRecord>, val indices: Map<String, Int>,
    val identities: Map<Triple<Long, String, Long>, Int>, val stats: DownloadStats, val activeIndex: Int, val revision: Long) {
    companion object {
        fun create(items: List<DownloadRecord>, revision: Long) = DownloadSnapshot(items,
            items.mapIndexed { index, item -> item.id to index }.toMap(),
            items.mapIndexedNotNull { index, item -> if (item.valid) Triple(item.owner, item.key, item.document) to index else null }.toMap(),
            DownloadStats(items.size, items.count { it.state == DownloadState.COMPLETE }, items.sumOf { it.bytes },
                items.count { it.state in setOf(DownloadState.RUNNING, DownloadState.QUEUED) },
                items.count { it.valid && it.state in setOf(DownloadState.PAUSED, DownloadState.FAILED) }),
            items.indexOfFirst { it.state == DownloadState.RUNNING }, revision)
    }
}

internal class SingleItemList<T>(private val base: List<T>, private val index: Int, private val value: T) : AbstractList<T>() {
    init { require(index in base.indices) }
    override val size get() = base.size
    // long: 每个进度样本只替换当前记录的视图，不复制历史列表，也不会形成层层嵌套的替换链。
    override fun get(index: Int): T = if (index == this.index) value else base[index]
}
