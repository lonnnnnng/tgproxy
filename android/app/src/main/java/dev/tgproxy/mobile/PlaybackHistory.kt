package dev.tgproxy.mobile

import org.json.JSONObject

class PlaybackHistory(private val database: MediaDatabase, private val downloads: DownloadStore) {
    fun forVideo(video: JSONObject, owner: Long): PlaybackCheckpoint {
        val local = runCatching { downloads.find(owner, video) }.getOrNull()?.takeIf { it.valid && it.state == DownloadState.COMPLETE }
        return PlaybackCheckpoint.latest(runCatching { database.checkpoint(video.getString("key"), video.optLong("document_id"), owner) }.getOrNull(),
            local?.let { PlaybackCheckpoint(it.position, it.watched) })
    }

    fun forLocal(item: DownloadRecord): PlaybackCheckpoint = PlaybackCheckpoint.latest(
        // long: 另一份数据库暂时不可读时仍使用本来源的断点，离线播放不依赖索引库能否打开。
        runCatching { database.checkpoint(item.key, item.document, item.owner) }.getOrNull(), PlaybackCheckpoint(item.position, item.watched))

    fun saveLocal(id: String, position: Long, expectedDocument: Long? = null, expectedOwner: Long? = null,
        updated: Long = System.currentTimeMillis()) {
        val item = downloads.get(id)?.takeIf { it.valid && it.state == DownloadState.COMPLETE } ?: return
        if ((expectedDocument != null && item.document != expectedDocument) || (expectedOwner != null && item.owner != expectedOwner)) return
        if (forLocal(item).updated > updated) return
        // long: 离线文件在退出账号后仍可记录进度；只有当前索引属于同一账号和文档时才同步最近播放。
        downloads.position(id, position, updated)
        database.savePosition(item.key, position, item.document, item.owner, updated)
    }

    fun saveRemote(key: String, position: Long, document: Long, owner: Long, updated: Long = System.currentTimeMillis()) {
        val video = database.video(key) ?: return
        val local = runCatching { downloads.find(owner, video) }.getOrNull()?.takeIf { it.valid && it.document == document && it.state == DownloadState.COMPLETE }
        if (local != null && local.watched > updated) return
        if (!database.savePosition(key, position, document, owner, updated)) return
        // long: 同一已下载文档继承在线的新断点，下载完成后改走本机文件也能接着观看。
        local?.let { downloads.position(it.id, position, updated) }
    }
}
