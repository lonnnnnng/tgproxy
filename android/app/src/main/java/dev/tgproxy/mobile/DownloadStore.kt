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
    val bytes: Long, val error: String, val position: Long = 0) {
    val key get() = video.getString("key")
    val name get() = video.optString("name", "未命名视频")
    val size get() = video.optLong("size")
    val document get() = video.optLong("document_id")
    val mime get() = video.optString("mime_type").ifBlank { video.optString("mime", "video/mp4") }
}

/** long: 下载与频道索引分库存储，移除频道或退出账号不删除用户明确下载的本机文件。 */
class DownloadStore(context: Context, name: String = "downloads.db",
    val directory: File = File(context.filesDir, "downloads")) : SQLiteOpenHelper(context, name, null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE downloads (id TEXT PRIMARY KEY,owner INTEGER NOT NULL,video_key TEXT NOT NULL,document INTEGER NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,bytes INTEGER NOT NULL,error TEXT NOT NULL,created INTEGER NOT NULL,position INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE UNIQUE INDEX download_identity ON downloads(owner,video_key,document)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private fun read(cursor: android.database.Cursor) = DownloadRecord(cursor.getString(0), cursor.getLong(1),
        JSONObject(cursor.getString(2)), DownloadState.valueOf(cursor.getString(3)), cursor.getLong(4), cursor.getString(5), cursor.getLong(6))
    fun list(): List<DownloadRecord> = readableDatabase.rawQuery("SELECT id,owner,payload,state,bytes,error,position FROM downloads ORDER BY created DESC,id", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(read(cursor)) }
    }
    fun get(id: String): DownloadRecord? = readableDatabase.rawQuery("SELECT id,owner,payload,state,bytes,error,position FROM downloads WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) read(it) else null }
    fun find(owner: Long, video: JSONObject): DownloadRecord? = readableDatabase.rawQuery(
        "SELECT id,owner,payload,state,bytes,error,position FROM downloads WHERE owner=? AND video_key=? AND document=?",
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
    fun update(id: String, state: DownloadState, bytes: Long, error: String = "") {
        writableDatabase.update("downloads", ContentValues().apply {
            put("state", state.name); put("bytes", bytes.coerceAtLeast(0)); put("error", error)
        }, "id=?", arrayOf(id))
    }
    fun position(id: String, value: Long) { writableDatabase.update("downloads", ContentValues().apply { put("position", value.coerceAtLeast(0)) }, "id=?", arrayOf(id)) }
    fun delete(id: String) { writableDatabase.delete("downloads", "id=?", arrayOf(id)) }
    fun file(id: String, partial: Boolean = false): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        return File(directory, id + if (partial) ".part" else ".video")
    }
    fun completed(id: String): File? = get(id)?.takeIf { it.state == DownloadState.COMPLETE }?.let {
        file(id).takeIf { file -> file.isFile && file.length() == it.size }
    }
    fun recover() {
        list().forEach { item ->
            when {
                item.state == DownloadState.DELETING -> { removeFiles(item.id); delete(item.id) }
                item.state == DownloadState.COMPLETE && completed(item.id) == null -> update(item.id, DownloadState.FAILED, 0, "下载文件已丢失，请重新下载")
                item.state != DownloadState.COMPLETE && file(item.id).let { it.isFile && it.length() == item.size } ->
                    update(item.id, DownloadState.COMPLETE, item.size)
                item.state in listOf(DownloadState.RUNNING, DownloadState.QUEUED) -> update(item.id, DownloadState.PAUSED,
                    file(item.id, true).length().coerceAtMost(item.size), "上次下载已中断，点击继续")
            }
        }
    }
    fun removeFiles(id: String) {
        // long: 路径仅由校验过的任务 UUID 生成，删除不会触及用户导出到其他目录的文件。
        for (target in listOf(file(id), file(id, true), File(file(id, true).path + ".etag"))) {
            check(!target.exists() || target.delete()) { "文件暂时无法删除，请重试" }
        }
    }
}
