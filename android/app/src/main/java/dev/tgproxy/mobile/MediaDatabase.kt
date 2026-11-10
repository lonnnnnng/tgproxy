package dev.tgproxy.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class MediaDatabase(context: Context, name: String = "media.db") : SQLiteOpenHelper(context, name, null, 3) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE videos (key TEXT PRIMARY KEY, channel_id INTEGER NOT NULL, name TEXT NOT NULL, payload TEXT NOT NULL, date INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE channels (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE playback (key TEXT PRIMARY KEY, position INTEGER NOT NULL, updated INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
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
        if (oldVersion < 3) db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
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
    fun applyPage(channelID: Long, page: JSONObject, refresh: Boolean): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val channel = channel(channelID) ?: error("频道已移除")
            val before = count(channelID)
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
            // long: 以已提交的新主键数量反馈新增，重试返回的重复视频不能再次计数。
            val added = count(channelID) - before
            db.setTransactionSuccessful()
            return added
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
        // long: 频道过滤单独生成条件才能使用频道索引；用户选定的排序必须是主排序。
        val arguments = mutableListOf(term)
        val filter = if (channelID > 0) { arguments.add(channelID.toString()); " AND v.channel_id=?" } else ""
        val order = when (sort) { "watched" -> if (recent) "p.updated DESC,v.key" else "v.date DESC,v.key"; "oldest" -> "v.date ASC,v.key"; "name" -> "v.name COLLATE NOCASE,v.key"; else -> "v.date DESC,v.key" }
        val sql = "SELECT v.payload FROM videos v " + (if (recent) "JOIN playback p ON p.key=v.key " else "") +
            "WHERE v.name LIKE ? ESCAPE '\\'" + filter + " ORDER BY $order LIMIT ? OFFSET ?"
        arguments.add(limit.coerceIn(1, 1000).toString()); arguments.add(offset.coerceAtLeast(0).toString())
        return readableDatabase.rawQuery(sql, arguments.toTypedArray()).use {
            JSONArray().apply { while (it.moveToNext()) put(JSONObject(it.getString(0))) }
        }
    }
    fun video(key: String): JSONObject? = readableDatabase.rawQuery("SELECT payload FROM videos WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }
    fun count(channelID: Long = 0): Long = readableDatabase.rawQuery(if (channelID > 0) "SELECT COUNT(*) FROM videos WHERE channel_id=?" else "SELECT COUNT(*) FROM videos", if (channelID > 0) arrayOf(channelID.toString()) else null).use { it.moveToFirst(); it.getLong(0) }
    fun ownerID(): Long = readableDatabase.rawQuery("SELECT value FROM metadata WHERE key='account_id'", null).use { if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0 else 0 }
    fun bindOwner(id: Long) {
        require(id > 0)
        val owner = ownerID(); require(owner == 0L || owner == id) { "当前账号与本机索引不一致" }
        writableDatabase.insertWithOnConflict("metadata", null, ContentValues().apply { put("key", "account_id"); put("value", id.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun recheckBatch(channelID: Long, after: String = ""): JSONArray = readableDatabase.rawQuery("SELECT payload FROM videos WHERE channel_id=? AND key>? ORDER BY key LIMIT 100", arrayOf(channelID.toString(), after)).use { cursor ->
        JSONArray().apply { while (cursor.moveToNext()) put(JSONObject(cursor.getString(0))) }
    }
    fun applyRecheck(channelID: Long, requested: JSONArray, result: JSONObject, lastKey: String) {
        val expected = (0 until requested.length()).map { requested.getLong(it) }.toSet()
        val items = result.getJSONArray("items"); val missing = result.getJSONArray("missing_ids")
        val actual = (0 until items.length()).map { items.getJSONObject(it).getLong("message_id") } + (0 until missing.length()).map { missing.getLong(it) }
        require(actual.size == actual.toSet().size && actual.toSet() == expected) { "校验结果不完整，保留本机索引" }
        val db = writableDatabase; db.beginTransaction()
        try {
            val channel = channel(channelID) ?: error("频道已移除")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i); val previous = video(item.getString("key"))
                // long: 同一消息换片后旧断点不再适用，但改名和元数据刷新保留原播放位置。
                if (previous != null && previous.optLong("document_id") > 0 && previous.optLong("document_id") != item.optLong("document_id")) db.delete("playback", "key=?", arrayOf(item.getString("key")))
            }
            putVideos(db, items, channelID)
            for (i in 0 until missing.length()) {
                val key = "${channelID}_${missing.getLong(i)}"
                db.delete("playback", "key=?", arrayOf(key)); db.delete("videos", "key=? AND channel_id=?", arrayOf(key, channelID.toString()))
            }
            result.optString("title").takeIf { it.isNotBlank() }?.let { channel.put("title", it) }
            channel.put("recheck_key", lastKey); putChannel(db, channel)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun completeRecheck(channelID: Long) {
        val ch = channel(channelID) ?: return
        ch.remove("recheck_key"); ch.put("checked", System.currentTimeMillis()); putChannel(writableDatabase, ch)
    }
    fun position(key: String): Long = readableDatabase.rawQuery("SELECT position FROM playback WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    fun savePosition(key: String, position: Long, expectedDocument: Long? = null, expectedOwner: Long? = null) {
        val db = writableDatabase; db.beginTransaction()
        try {
            val row = video(key)
            // long: 校验换片、删除或退出账号后，仍存活的播放器不能把旧文件断点写回新索引。
            if (row != null && (expectedDocument == null || row.optLong("document_id") == expectedDocument) && (expectedOwner == null || ownerID() == expectedOwner)) {
                db.insertWithOnConflict("playback", null, ContentValues().apply {
                    put("key", key); put("position", position.coerceAtLeast(0)); put("updated", System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun clear() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("videos", "channels", "playback", "metadata")) db.delete(table, null, null)
            // long: 升级保留的旧表不参与业务，但退出账号时也要清掉旧索引，避免残留上一账号信息。
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='channel'", null).use { if (it.moveToFirst()) db.delete("channel", null, null) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
