package dev.tgproxy.mobile

import org.json.JSONObject

/** long: 页读取与事务索引分离，断点、首次更新范围和取消边界可用独立数据库验证。 */
class ChannelIndexer(private val database: MediaDatabase) {
    fun scan(channelID: Long, refresh: Boolean, cancelled: () -> Boolean, requireAccount: () -> Unit,
        readPage: (Long, Long) -> JSONObject, report: (Long, Long, Long) -> Unit): String {
        val channel = database.channel(channelID) ?: error("频道已移除")
        var offset = if (refresh) channel.optLong("refresh_offset") else channel.optLong("history_offset")
        val minID = if (refresh) channel.optLong(if (channel.has("refresh_offset")) "refresh_base" else "latest_id") else 0L
        var found = 0L
        var added = 0L
        // long: 首次更新只建立最新一页和历史游标；之后检查新增，较早内容由历史扫描补齐。
        val firstUpdate = refresh && channel.optLong("latest_id") == 0L && !channel.has("refresh_offset") &&
            channel.optLong("history_offset") == 0L && !channel.optBoolean("history_done")
        while (true) {
            if (cancelled()) return "paused"
            requireAccount()
            val page = try { readPage(offset, minID) }
                catch (failure: Exception) { if (cancelled()) return "paused" else throw failure }
            // long: 暂停保留刚刚完整返回的一页；页事务成功后，下一轮才检查是否继续请求。
            added += database.applyPage(channelID, page, refresh && !firstUpdate)
            found += page.getJSONArray("items").length()
            val saved = database.channel(channelID) ?: error("频道已移除")
            report(found, added, database.count(channelID))
            if (page.getBoolean("done") || firstUpdate) return saved.put("scan_added", added).toString()
            offset = if (refresh) saved.optLong("refresh_offset") else saved.optLong("history_offset")
            if (offset <= 0L) error("频道扫描游标无效")
        }
    }
}
