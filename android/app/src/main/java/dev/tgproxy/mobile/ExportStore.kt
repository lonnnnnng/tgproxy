package dev.tgproxy.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.util.UUID

enum class ExportState { QUEUED, RUNNING, CANCELLING, COMPLETE, CANCELLED, FAILED, INTERRUPTED }
data class ExportRecord(val id: String, val download: String, val name: String, val size: Long, val uri: String,
    val owner: Long, val document: Long, val mime: String, val state: ExportState, val bytes: Long,
    val error: String, val touched: Boolean, val persisted: Boolean, val valid: Boolean = true)

open class ExportStore(context: Context, name: String = "exports.db") : SQLiteOpenHelper(context, name, null, 1) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE exports(id TEXT PRIMARY KEY,download_id TEXT NOT NULL,name TEXT NOT NULL,size INTEGER NOT NULL,uri TEXT NOT NULL,owner INTEGER NOT NULL,document INTEGER NOT NULL,mime TEXT NOT NULL,state TEXT NOT NULL,bytes INTEGER NOT NULL,error TEXT NOT NULL,touched INTEGER NOT NULL,persisted INTEGER NOT NULL,created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX export_created ON exports(created DESC,id)")
        db.execSQL("CREATE INDEX export_state ON exports(state)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    private fun read(cursor: android.database.Cursor): ExportRecord {
        val state = ExportState.entries.firstOrNull { it.name == cursor.getString(8) }
        val valid = runCatching { UUID.fromString(cursor.getString(0)).toString() == cursor.getString(0) &&
            UUID.fromString(cursor.getString(1)).toString() == cursor.getString(1) &&
            Uri.parse(cursor.getString(4)).scheme == "content" && cursor.getLong(3) > 0 }.getOrDefault(false) && state != null
        return ExportRecord(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getString(4),
            cursor.getLong(5), cursor.getLong(6), cursor.getString(7), if (valid) state!! else ExportState.FAILED,
            cursor.getLong(9).coerceIn(0, cursor.getLong(3).coerceAtLeast(0)), if (valid) cursor.getString(10) else "另存记录已损坏",
            cursor.getInt(11) != 0, cursor.getInt(12) != 0, valid)
    }
    open fun list(limit: Int = 50, offset: Int = 0): List<ExportRecord> = readableDatabase.rawQuery(
        "SELECT * FROM exports ORDER BY created DESC,rowid DESC LIMIT ? OFFSET ?", arrayOf(limit.coerceIn(1, 100).toString(), offset.coerceAtLeast(0).toString())).use { cursor ->
        buildList { while (cursor.moveToNext()) add(read(cursor)) }
    }
    fun get(id: String): ExportRecord? = readableDatabase.rawQuery("SELECT * FROM exports WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) read(it) else null }
    // long: 同一毫秒的多个另存按实际入队顺序执行，随机 UUID 不能决定先后。
    fun next(): ExportRecord? = readableDatabase.rawQuery("SELECT * FROM exports WHERE state='QUEUED' ORDER BY rowid LIMIT 1", null).use { if (it.moveToFirst()) read(it) else null }
    fun protectedTarget(uri: String, excluding: String = ""): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM exports WHERE uri=? AND id<>? AND (touched=1 OR state IN ('QUEUED','RUNNING','CANCELLING')) LIMIT 1",
        arrayOf(uri, excluding)).use { it.moveToFirst() }
    fun add(source: DownloadRecord, uri: Uri, persisted: Boolean): ExportRecord {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("exports", null, ContentValues().apply {
            put("id", id); put("download_id", source.id); put("name", source.name); put("size", source.size); put("uri", uri.toString())
            put("owner", source.owner); put("document", source.document); put("mime", source.mime); put("state", ExportState.QUEUED.name)
            put("bytes", 0); put("error", ""); put("touched", 0); put("persisted", if (persisted) 1 else 0); put("created", System.currentTimeMillis())
        })
        return get(id)!!
    }
    open fun update(id: String, state: ExportState, bytes: Long, error: String = "", touched: Boolean? = null) {
        writableDatabase.update("exports", ContentValues().apply {
            put("state", state.name); put("bytes", bytes.coerceAtLeast(0)); put("error", error); touched?.let { put("touched", if (it) 1 else 0) }
        }, "id=?", arrayOf(id))
    }
    open fun recover() {
        // long: 文件提供器未承诺可续写，进程中断只恢复结果并要求重新选择位置，不能覆盖旧副本。
        writableDatabase.execSQL("UPDATE exports SET state='INTERRUPTED',error='上次另存已中断，请重新选择保存位置' WHERE state IN ('QUEUED','RUNNING','CANCELLING')")
    }
    fun interruptPending(activeID: String?, reason: String) {
        // long: 服务停止需要覆盖整批任务，不能受历史页大小限制；正在关闭的写入者先显示取消中。
        writableDatabase.execSQL("UPDATE exports SET state=CASE WHEN id=? THEN 'CANCELLING' ELSE 'INTERRUPTED' END,error=? WHERE state IN ('QUEUED','RUNNING','CANCELLING')",
            arrayOf(activeID.orEmpty(), reason))
    }
}
