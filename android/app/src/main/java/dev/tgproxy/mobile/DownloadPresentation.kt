package dev.tgproxy.mobile

object DownloadPresentation {
    fun status(item: DownloadRecord): String = when (item.state) {
        DownloadState.QUEUED -> "等待下载"
        DownloadState.RUNNING -> "下载中 · ${item.bytes * 100 / item.size.coerceAtLeast(1)}%"
        DownloadState.PAUSED -> "已暂停"
        DownloadState.FAILED -> "下载失败"
        DownloadState.COMPLETE -> "已完成 · 可离线播放"
        DownloadState.DELETING -> "正在删除…"
    }
    fun detail(item: DownloadRecord): String = when (item.state) {
        DownloadState.COMPLETE -> MediaPresentation.size(item.size)
        else -> "${MediaPresentation.size(item.bytes)} / ${MediaPresentation.size(item.size)}"
    }
    fun safeName(item: DownloadRecord) = item.name.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").take(180).ifBlank { "video.mp4" }
}
