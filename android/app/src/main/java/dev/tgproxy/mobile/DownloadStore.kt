package dev.tgproxy.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class DownloadState { QUEUED, RUNNING, PAUSED, FAILED, COMPLETE, DELETING }
data class DownloadRecord(val id: String, val owner: Long, val video: JSONObject, val state: DownloadState,
    val bytes: Long, val error: String, val position: Long = 0, val valid: Boolean = true, val watched: Long = 0) {
    val key get() = video.optString("key")
    val name get() = video.optString("name", "未命名视频")
    val size get() = video.optLong("size")
    val document get() = video.optLong("document_id")
    val mime get() = video.optString("mime_type").ifBlank { video.optString("mime", "video/mp4") }
}

/** long: 下载与频道索引分库存储，移除频道或退出账号不删除用户明确下载的本机文件。 */
open class DownloadStore(context: Context, name: String = "downloads.db",
    val directory: File = File(context.filesDir, "downloads")) : SQLiteOpenHelper(context, name, null, 2) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE downloads (id TEXT PRIMARY KEY,owner INTEGER NOT NULL,video_key TEXT NOT NULL,document INTEGER NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,bytes INTEGER NOT NULL,error TEXT NOT NULL,created INTEGER NOT NULL,position INTEGER NOT NULL DEFAULT 0,watched INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE UNIQUE INDEX download_identity ON downloads(owner,video_key,document)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // long: 旧下载的断点原样保留；未知观看时间记为零，不能冒充比已知的新观看更近。
        if (oldVersion < 2) db.execSQL("ALTER TABLE downloads ADD COLUMN watched INTEGER NOT NULL DEFAULT 0")
    }
    private fun read(cursor: android.database.Cursor): DownloadRecord {
        val id = cursor.getString(0)
        val payload = runCatching { JSONObject(cursor.getString(2)) }.getOrNull()
        val state = DownloadState.entries.firstOrNull { it.name == cursor.getString(3) }
        val valid = canLocateFiles(id) &&
            cursor.getLong(1) > 0 && payload != null && payload.optLong("size") > 0 &&
            payload.optString("key") == cursor.getString(7) && payload.optLong("document_id") == cursor.getLong(8) && state != null
        // long: 损坏记录仍显示为可处理的失败项，不能使其他下载消失，也不能凭坏元数据删除文件。
        val video = payload ?: JSONObject().put("key", cursor.getString(7)).put("document_id", cursor.getLong(8))
            .put("name", "无法读取的下载").put("size", 0)
        return DownloadRecord(id, cursor.getLong(1), video, if (valid) state!! else DownloadState.FAILED,
            cursor.getLong(4).coerceAtLeast(0), if (valid) cursor.getString(5) else "下载记录无法读取，请从更多菜单删除后重新下载",
            cursor.getLong(6).coerceAtLeast(0), valid, cursor.getLong(9).coerceAtLeast(0))
    }
    private val columns = "id,owner,payload,state,bytes,error,position,video_key,document,watched"
    open fun list(): List<DownloadRecord> = readableDatabase.rawQuery("SELECT $columns FROM downloads ORDER BY created DESC,id", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(read(cursor)) }
    }
    fun get(id: String): DownloadRecord? = readableDatabase.rawQuery("SELECT $columns FROM downloads WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) read(it) else null }
    fun find(owner: Long, video: JSONObject): DownloadRecord? = readableDatabase.rawQuery(
        "SELECT $columns FROM downloads WHERE owner=? AND video_key=? AND document=?",
        arrayOf(owner.toString(), video.getString("key"), video.optLong("document_id").toString())).use { if (it.moveToFirst()) read(it) else null }
    fun add(owner: Long, video: JSONObject): DownloadRecord {
        require(owner > 0 && video.optLong("size") > 0)
        find(owner, video)?.let { return it }
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("downloads", null, ContentValues().apply {
            put("id", id); put("owner", owner); put("video_key", video.getString("key")); put("document", video.optLong("document_id"))
            put("payload", video.toString()); put("state", DownloadState.QUEUED.name); put("bytes", 0); put("error", ""); put("created", System.currentTimeMillis())
        })
        return get(id)!!
    }
    open fun update(id: String, state: DownloadState, bytes: Long, error: String = "") {
        writableDatabase.update("downloads", ContentValues().apply {
            put("state", state.name); put("bytes", bytes.coerceAtLeast(0)); put("error", error)
        }, "id=?", arrayOf(id))
    }
    fun position(id: String, value: Long, updated: Long = System.currentTimeMillis()) {
        // long: 迟到的播放回调不能覆盖另一播放来源已经保存的新断点。
        writableDatabase.update("downloads", ContentValues().apply { put("position", value.coerceAtLeast(0)); put("watched", updated.coerceAtLeast(0)) },
            "id=? AND watched<=?", arrayOf(id, updated.coerceAtLeast(0).toString()))
    }
    fun delete(id: String) { writableDatabase.delete("downloads", "id=?", arrayOf(id)) }
    fun canLocateFiles(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    fun file(id: String, partial: Boolean = false): File {
        require(canLocateFiles(id))
        return File(directory, id + if (partial) ".part" else ".video")
    }
    fun completed(id: String): File? = get(id)?.takeIf { it.valid && it.state == DownloadState.COMPLETE }?.let {
        file(id).takeIf { file -> file.isFile && file.length() == it.size }
    }
    fun recover(): Int {
        var issues = 0
        list().forEach { item ->
            // long: 一个文件删除失败或坏记录只能影响自身，其余完整文件与断点仍需恢复。
            runCatching {
                when {
                    !item.valid -> { issues++; update(item.id, DownloadState.FAILED, item.bytes, item.error) }
                    item.state == DownloadState.DELETING -> { removeFiles(item.id); delete(item.id) }
                    item.state == DownloadState.COMPLETE && completed(item.id) == null -> update(item.id, DownloadState.FAILED, 0, "下载文件已丢失，请重新下载")
                    item.state != DownloadState.COMPLETE && file(item.id).let { it.isFile && it.length() == item.size } ->
                        update(item.id, DownloadState.COMPLETE, item.size)
                    item.state in listOf(DownloadState.RUNNING, DownloadState.QUEUED) -> update(item.id, DownloadState.PAUSED,
                        file(item.id, true).length().coerceAtMost(item.size), "上次下载已中断，点击继续")
                }
            }.onFailure {
                issues++
                runCatching { update(item.id, DownloadState.FAILED, item.bytes, "本机文件恢复失败，请从更多菜单重试删除或重新下载") }
            }
        }
        return issues
    }
    fun queueForOwner(owner: Long, failedOnly: Boolean = false, excludedID: String? = null): Int {
        require(owner > 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val candidates = list().filter { item -> item.valid && item.owner == owner && item.id != excludedID &&
                (item.state == DownloadState.FAILED || (!failedOnly && item.state == DownloadState.PAUSED)) }
            // long: 批量继续只改原账号的可恢复任务，保持创建顺序，并避开还没有退出写入的当前任务。
            candidates.forEach { update(it.id, DownloadState.QUEUED, file(it.id, true).length()) }
            db.setTransactionSuccessful()
            return candidates.size
        } finally { db.endTransaction() }
    }
    open fun removeFiles(id: String) {
        // long: 路径仅由校验过的任务 UUID 生成，删除不会触及用户导出到其他目录的文件。
        for (target in listOf(file(id), file(id, true), File(file(id, true).path + ".etag"))) {
            check(!target.exists() || target.delete()) { "文件暂时无法删除，请重试" }
        }
    }
}
