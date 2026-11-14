package dev.tgproxy.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class MediaDatabase(context: Context, name: String = "media.db") : SQLiteOpenHelper(context, name, null, 4) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE videos (key TEXT PRIMARY KEY, channel_id INTEGER NOT NULL, name TEXT NOT NULL, payload TEXT NOT NULL, date INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE channels (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE playback (key TEXT PRIMARY KEY, position INTEGER NOT NULL, updated INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        createIndexes(db)
    }
    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS video_date ON videos(date DESC,key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS video_channel ON videos(channel_id,date DESC,key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS video_oldest ON videos(date ASC,key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS video_channel_oldest ON videos(channel_id,date ASC,key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS video_name ON videos(name COLLATE NOCASE,key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS video_channel_name ON videos(channel_id,name COLLATE NOCASE,key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS video_channel_key ON videos(channel_id,key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS playback_updated ON playback(updated DESC,key)")
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
        }
        if (oldVersion < 3) db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        if (oldVersion < 4) {
            // long: 只重建排序索引，保留视频、频道和观看记录；同排序值的主键也进入索引以支持深页游标。
            db.execSQL("DROP INDEX IF EXISTS video_date")
            db.execSQL("DROP INDEX IF EXISTS video_channel")
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
            val previous = video(row.getString("key"))
            // long: 任何索引写入遇到换片都清掉旧断点，普通扫描重试也不能让新文档继承旧文件的位置。
            if (previous != null && previous.optLong("document_id") != row.optLong("document_id"))
                db.delete("playback", "key=?", arrayOf(row.getString("key")))
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
        val query = videoQuery(search, channelID, recent, sort, null)
        val arguments = query.arguments + listOf(limit.coerceIn(1, 1000).toString(), offset.coerceAtLeast(0).toString())
        return readableDatabase.rawQuery("SELECT v.payload " + query.sql + " LIMIT ? OFFSET ?", arguments.toTypedArray()).use {
            JSONArray().apply { while (it.moveToNext()) put(JSONObject(it.getString(0))) }
        }
    }
    private data class VideoQuery(val sql: String, val arguments: List<String>, val value: String, val sort: String)
    private fun videoQuery(search: String, channelID: Long, recent: Boolean, requestedSort: String, cursor: VideoPageCursor?): VideoQuery {
        val sort = when (requestedSort) { "name", "oldest" -> requestedSort; "watched" -> if (recent) "watched" else "newest"; else -> "newest" }
        val value = when (sort) { "name" -> "v.name COLLATE NOCASE"; "watched" -> "p.updated"; else -> "v.date" }
        val key = if (sort == "watched") "p.key" else "v.key"
        val descending = sort in setOf("newest", "watched")
        val filters = mutableListOf<String>()
        val arguments = mutableListOf<String>()
        // long: 空搜索无需 LIKE 扫描；有输入时仍按字面子串匹配，不改变百分号、下划线与反斜线语义。
        if (search.isNotEmpty()) {
            filters.add("v.name LIKE ? ESCAPE '\\'")
            arguments.add("%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
        }
        if (channelID > 0) { filters.add("v.channel_id=?"); arguments.add(channelID.toString()) }
        if (cursor != null) {
            require(cursor.sort == sort) { "分页排序已经变化，请重新查询" }
            // long: 先按排序值定位范围，再以唯一主键处理同日期和同名条目，深页无需逐个跳过前页。
            val compare = if (descending) "<" else ">"
            filters.add("$value $compare=? AND ($value $compare ? OR $key>?)")
            arguments.add(cursor.value); arguments.add(cursor.value); arguments.add(cursor.key)
        }
        val from = if (recent) "FROM playback p JOIN videos v ON p.key=v.key" else "FROM videos v"
        val where = if (filters.isEmpty()) "" else " WHERE " + filters.joinToString(" AND ")
        return VideoQuery(from + where + " ORDER BY $value " + (if (descending) "DESC" else "ASC") + ",$key ASC", arguments, value, sort)
    }
    fun videoPage(search: String = "", channelID: Long = 0, recent: Boolean = false, sort: String = "newest",
        cursor: VideoPageCursor? = null, limit: Int = 50, offsetFallback: Int = 0): VideoPage {
        val query = videoQuery(search, channelID, recent, sort, cursor)
        val size = limit.coerceIn(1, 1000)
        val offset = if (cursor == null) offsetFallback.coerceAtLeast(0) else 0
        val arguments = query.arguments + listOf((size + 1).toString(), offset.toString())
        return readableDatabase.rawQuery("SELECT v.payload,v.key," + query.value + " " + query.sql + " LIMIT ? OFFSET ?", arguments.toTypedArray()).use { result ->
            val rows = JSONArray()
            var last: VideoPageCursor? = null
            var more = false
            while (result.moveToNext()) {
                if (rows.length() == size) { more = true; break }
                rows.put(JSONObject(result.getString(0)))
                last = VideoPageCursor(query.sort, result.getString(2), result.getString(1))
            }
            VideoPage(rows, if (more) last else null)
        }
    }
    fun video(key: String): JSONObject? = readableDatabase.rawQuery("SELECT payload FROM videos WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }
    fun <T> withOwner(owner: Long, read: () -> T): T {
        val db = readableDatabase; db.beginTransaction()
        try {
            // long: 播放列表在同一归属快照查询，账号切换不能让旧播放器取到新账号的频道内容。
            check(owner > 0 && ownerID() == owner) { "当前账号与播放列表不一致" }
            val value = read(); db.setTransactionSuccessful(); return value
        } finally { db.endTransaction() }
    }
    fun adjacentVideo(key: String, channelID: Long, search: String, sort: String, forward: Boolean): JSONObject? {
        val query = videoQuery(search, channelID, false, sort, null)
        val current = readableDatabase.rawQuery("SELECT " + query.value + ",v.key FROM videos v WHERE v.key=?", arrayOf(key)).use {
            if (it.moveToFirst()) VideoPageCursor(query.sort, it.getString(0), it.getString(1)) else null
        } ?: return null
        if (forward) return videoPage(search, channelID, sort = query.sort, cursor = current, limit = 1).items.optJSONObject(0)
        val descending = query.sort == "newest"
        val compare = if (descending) ">" else "<"
        val base = query.sql.substringBefore(" ORDER BY ")
        val where = if (base.contains(" WHERE ")) " AND " else " WHERE "
        // long: 前一条反向查询并反转主键顺序，重名和同秒发布的视频也只移动一个位置。
        val clause = query.value + " $compare=? AND (" + query.value + " $compare ? OR v.key<?)"
        val sql = "SELECT v.payload " + base + where + clause + " ORDER BY " + query.value + (if (descending) " ASC" else " DESC") + ",v.key DESC LIMIT 1"
        val arguments = query.arguments + listOf(current.value, current.value, current.key)
        return readableDatabase.rawQuery(sql, arguments.toTypedArray()).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
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
    fun checkpoint(key: String, expectedDocument: Long? = null, expectedOwner: Long? = null): PlaybackCheckpoint? {
        val db = readableDatabase
        db.beginTransaction()
        try {
            // long: 归属、文档和观看位置在同一数据库快照读取，账号清理不能夹在两次查询之间串用记录。
            val video = video(key) ?: return null
            if ((expectedDocument != null && video.optLong("document_id") != expectedDocument) || (expectedOwner != null && ownerID() != expectedOwner)) return null
            val checkpoint = db.rawQuery("SELECT position,updated FROM playback WHERE key=?", arrayOf(key)).use {
                if (it.moveToFirst()) PlaybackCheckpoint(it.getLong(0).coerceAtLeast(0), it.getLong(1).coerceAtLeast(0)) else null
            }
            db.setTransactionSuccessful()
            return checkpoint
        } finally { db.endTransaction() }
    }
    fun savePosition(key: String, position: Long, expectedDocument: Long? = null, expectedOwner: Long? = null,
        updated: Long = System.currentTimeMillis()): Boolean {
        val db = writableDatabase; db.beginTransaction()
        try {
            val row = video(key)
            val newer = db.rawQuery("SELECT updated FROM playback WHERE key=?", arrayOf(key)).use { it.moveToFirst() && it.getLong(0) > updated }
            var saved = false
            // long: 校验换片、删除或退出账号后，仍存活的播放器不能把旧文件断点写回新索引。
            if (!newer && row != null && (expectedDocument == null || row.optLong("document_id") == expectedDocument) && (expectedOwner == null || ownerID() == expectedOwner)) {
                check(db.insertWithOnConflict("playback", null, ContentValues().apply {
                    put("key", key); put("position", position.coerceAtLeast(0)); put("updated", updated.coerceAtLeast(0))
                }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "观看记录未能保存" }
                saved = true
            }
            db.setTransactionSuccessful()
            return saved
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
