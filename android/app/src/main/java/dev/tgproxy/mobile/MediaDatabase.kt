package dev.tgproxy.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class MediaDatabase(context: Context, name: String = "media.db") : SQLiteOpenHelper(context, name, null, 2) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE videos (key TEXT PRIMARY KEY, channel_id INTEGER NOT NULL, name TEXT NOT NULL, payload TEXT NOT NULL, date INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE channels (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE playback (key TEXT PRIMARY KEY, position INTEGER NOT NULL, updated INTEGER NOT NULL DEFAULT 0)")
        createIndexes(db)
    }
    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS video_date ON videos(date DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS video_channel ON videos(channel_id,date DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // long: 从验证版原表逐行补充频道归属，保留视频主键和播放进度，不依赖设备 SQLite 的 JSON 扩展。
            db.execSQL("ALTER TABLE videos ADD COLUMN channel_id INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE playback ADD COLUMN updated INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE TABLE channels (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
            var oldest = 0L
            var newest = 0L
            db.rawQuery("SELECT key,payload FROM videos", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONObject(cursor.getString(1))
                    db.update("videos", ContentValues().apply { put("channel_id", row.getLong("channel_id")) }, "key=?", arrayOf(cursor.getString(0)))
                    val id = row.getLong("message_id")
                    if (oldest == 0L || id < oldest) oldest = id
                    newest = maxOf(newest, id)
                }
            }
            db.rawQuery("SELECT payload FROM channel", null).use {
                while (it.moveToNext()) {
                    val channel = JSONObject(it.getString(0)).put("history_offset", oldest).put("latest_id", newest)
                    putChannel(db, channel)
                }
            }
            createIndexes(db)
        }
    }
    private fun putChannel(db: SQLiteDatabase, channel: JSONObject) {
        check(db.insertWithOnConflict("channels", null, ContentValues().apply {
            put("id", channel.getLong("channel_id")); put("payload", channel.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "频道写入失败" }
    }
    fun addChannel(channel: JSONObject) {
        val existing = channel(channel.getLong("channel_id"))
        // long: 重复添加只更新名称和引用，不能抹掉该频道已经提交的扫描断点。
        putChannel(writableDatabase, (existing ?: channel).put("title", channel.getString("title")).put("ref", channel.getString("ref")))
    }
    private fun putVideos(db: SQLiteDatabase, rows: JSONArray, channelID: Long) {
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            require(row.getLong("channel_id") == channelID && row.getLong("message_id") > 0 && row.getString("key") == "${channelID}_${row.getLong("message_id")}")
            check(db.insertWithOnConflict("videos", null, ContentValues().apply {
                put("key", row.getString("key")); put("channel_id", channelID); put("name", row.getString("name"))
                put("payload", row.toString()); put("date", row.getLong("date"))
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "视频索引写入失败" }
        }
    }
    fun replace(result: JSONObject) {
        // long: 兼容原验证用例的整批入口，现在只增补指定频道，不再删除其他频道。
        val db = writableDatabase
        db.beginTransaction()
        try {
            putVideos(db, result.getJSONArray("items"), result.getLong("channel_id"))
            putChannel(db, JSONObject(result.toString()).apply { remove("items") })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun applyPage(channelID: Long, page: JSONObject, refresh: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val channel = channel(channelID) ?: error("频道已移除")
            putVideos(db, page.getJSONArray("items"), channelID)
            val newest = page.getLong("newest_id")
            if (refresh) {
                // long: 新消息按从新到旧分页；全部追到旧水位后才提升 latest_id，中途失败沿独立断点续扫。
                val target = maxOf(channel.optLong("refresh_target"), newest)
                if (page.getBoolean("done")) {
                    channel.put("latest_id", maxOf(channel.optLong("latest_id"), target))
                    channel.remove("refresh_offset"); channel.remove("refresh_target"); channel.remove("refresh_base")
                } else {
                    if (!channel.has("refresh_offset")) channel.put("refresh_base", channel.optLong("latest_id"))
                    channel.put("refresh_offset", page.getLong("next_id")).put("refresh_target", target)
                }
            } else {
                if (channel.optLong("history_offset") == 0L) channel.put("latest_id", maxOf(channel.optLong("latest_id"), newest))
                if (page.getLong("next_id") > 0L) channel.put("history_offset", page.getLong("next_id"))
                channel.put("history_done", page.getBoolean("done"))
            }
            channel.put("updated", System.currentTimeMillis())
            putChannel(db, channel)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun channels(): JSONArray = readableDatabase.rawQuery("SELECT payload FROM channels ORDER BY id", null).use {
        JSONArray().apply { while (it.moveToNext()) put(JSONObject(it.getString(0))) }
    }
    fun channelCounts(): Map<Long, Long> = readableDatabase.rawQuery("SELECT channel_id,COUNT(*) FROM videos GROUP BY channel_id", null).use {
        // long: 频道列表一次聚合数量，避免每张频道卡重复访问数据库拖慢滚动和首屏。
        buildMap { while (it.moveToNext()) put(it.getLong(0), it.getLong(1)) }
    }
    fun channel(id: Long = 0): JSONObject? = readableDatabase.rawQuery(
        if (id == 0L) "SELECT payload FROM channels ORDER BY id LIMIT 1" else "SELECT payload FROM channels WHERE id=?",
        if (id == 0L) null else arrayOf(id.toString())).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    fun removeChannel(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM playback WHERE key IN (SELECT key FROM videos WHERE channel_id=?)", arrayOf(id))
            db.delete("videos", "channel_id=?", arrayOf(id.toString()))
            db.delete("channels", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun videos(search: String = "", offset: Int = 0, limit: Int = 50, channelID: Long = 0, recent: Boolean = false, sort: String = "newest"): JSONArray {
        val term = "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        // long: LIKE 通配符由应用转义，用户可按字面搜索带下划线和百分号的文件名。
        val sql = "SELECT v.payload FROM videos v " + (if (recent) "JOIN playback p ON p.key=v.key " else "") +
            "WHERE v.name LIKE ? ESCAPE '\\' AND (CAST(? AS INTEGER)=0 OR v.channel_id=?) ORDER BY " +
            (if (recent) "p.updated DESC," else "") +
            (when (sort) { "oldest" -> "v.date ASC,v.key"; "name" -> "v.name COLLATE NOCASE,v.key"; else -> "v.date DESC,v.key" }) + " LIMIT ? OFFSET ?"
        return readableDatabase.rawQuery(sql, arrayOf(term, channelID.toString(), channelID.toString(), limit.coerceIn(1, 1000).toString(), offset.coerceAtLeast(0).toString())).use {
            JSONArray().apply { while (it.moveToNext()) put(JSONObject(it.getString(0))) }
        }
    }
    fun continueWatching(limit: Int = 2): JSONArray = readableDatabase.rawQuery(
        "SELECT v.payload,p.position FROM videos v JOIN playback p ON p.key=v.key WHERE p.position>0 ORDER BY p.updated DESC,v.key LIMIT ?",
        arrayOf(limit.coerceIn(1, 10).toString())).use { cursor ->
        // long: 最近播放包含已经看完的视频，继续观看只选仍有断点的条目。
        JSONArray().apply { while (cursor.moveToNext()) put(JSONObject(cursor.getString(0)).put("position", cursor.getLong(1))) }
    }
    fun video(key: String): JSONObject? = readableDatabase.rawQuery("SELECT payload FROM videos WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }
    fun count(channelID: Long = 0): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM videos WHERE CAST(? AS INTEGER)=0 OR channel_id=?", arrayOf(channelID.toString(), channelID.toString())).use { it.moveToFirst(); it.getLong(0) }
    fun position(key: String): Long = readableDatabase.rawQuery("SELECT position FROM playback WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    fun savePosition(key: String, position: Long) {
        writableDatabase.insertWithOnConflict("playback", null, ContentValues().apply {
            put("key", key); put("position", position.coerceAtLeast(0)); put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("videos", "channels", "playback")) db.delete(table, null, null)
            // long: 升级保留的旧表不参与业务，但退出账号时也要清掉旧索引，避免残留上一账号信息。
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='channel'", null).use { if (it.moveToFirst()) db.delete("channel", null, null) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
