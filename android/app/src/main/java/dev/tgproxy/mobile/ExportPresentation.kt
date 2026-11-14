package dev.tgproxy.mobile

object ExportPresentation {
    fun status(item: ExportRecord): String = when (item.state) {
        ExportState.QUEUED -> "等待另存"
        ExportState.RUNNING -> "正在另存"
        ExportState.CANCELLING -> "正在取消"
        ExportState.COMPLETE -> "已另存"
        ExportState.CANCELLED -> "已取消另存"
        ExportState.FAILED -> "另存失败"
        ExportState.INTERRUPTED -> "另存已中断"
    }
    fun detail(item: ExportRecord): String = "${status(item)} · ${MediaPresentation.size(item.bytes)} / ${MediaPresentation.size(item.size)}"
    fun pending(item: ExportRecord) = item.state in setOf(ExportState.QUEUED, ExportState.RUNNING, ExportState.CANCELLING)
}
