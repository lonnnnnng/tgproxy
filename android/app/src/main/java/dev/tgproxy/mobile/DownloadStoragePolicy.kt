package dev.tgproxy.mobile

import java.io.IOException

class DownloadSpaceError : IOException("存储空间不足，释放空间后继续")

object DownloadStoragePolicy {
    private const val RESERVE = 16L * 1024 * 1024

    fun check(size: Long, partial: Long, available: Long, reserve: Long = RESERVE) {
        require(size > 0 && reserve >= 0)
        val remaining = size - (partial.takeIf { it in 0..size } ?: 0L)
        // long: 留出断点与 SQLite 的写入余量，空间耗尽时仍有机会安全保存暂停状态。
        if (available < reserve || remaining > available - reserve) throw DownloadSpaceError()
    }
}
