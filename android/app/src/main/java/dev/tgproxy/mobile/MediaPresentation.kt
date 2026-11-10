package dev.tgproxy.mobile

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object MediaPresentation {
    // long: 索引 date 来自 Telegram 消息的 Unix 秒数，不是扫描时间；缺失日期不能显示成 1970 年。
    fun published(seconds: Long, full: Boolean = false, zone: ZoneId = ZoneId.systemDefault()): String {
        if (seconds <= 0 || seconds > 253402300799L) return ""
        return runCatching {
            DateTimeFormatter.ofPattern(if (full) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd", Locale.ROOT)
                .withZone(zone).format(Instant.ofEpochSecond(seconds))
        }.getOrDefault("")
    }

    fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.2f GiB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.getDefault(), "%.0f MiB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.getDefault(), "%.0f KiB", bytes / 1024.0)
    }

    fun position(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
    }
}
