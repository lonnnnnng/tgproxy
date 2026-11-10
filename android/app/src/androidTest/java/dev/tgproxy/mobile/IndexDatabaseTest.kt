package dev.tgproxy.mobile

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IndexDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun video(channel: Long, id: Long, name: String = "视频_01%\\.mp4") = JSONObject().put("key", "${channel}_$id")
        .put("channel_id", channel).put("message_id", id).put("name", name).put("date", id).put("size", 100)
    private fun channel(id: Long) = JSONObject().put("channel_id", id).put("title", "频道$id").put("ref", id.toString())
    private fun page(next: Long, newest: Long, done: Boolean, vararg items: JSONObject) = JSONObject()
        .put("next_id", next).put("newest_id", newest).put("done", done).put("items", JSONArray(items.toList()))

    @Test fun oldSchemaMigratesWithoutLosingSessionIndependentData() {
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE videos (key TEXT PRIMARY KEY,name TEXT NOT NULL,payload TEXT NOT NULL,date INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE channel (id INTEGER PRIMARY KEY,payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE playback (key TEXT PRIMARY KEY,position INTEGER NOT NULL)")
            db.execSQL("INSERT INTO videos VALUES (?,?,?,?)", arrayOf<Any>("1_20", "旧视频", video(1,20).toString(),20))
            db.execSQL("INSERT INTO channel VALUES (1,?)", arrayOf(channel(1).toString()))
            db.execSQL("INSERT INTO playback VALUES ('1_20',12000)")
            db.version = 1
        }
        try { MediaDatabase(context, name).use { db ->
            assertEquals(1, db.channels().length()); assertEquals(1L, db.count()); assertEquals(12000L, db.position("1_20"))
            assertEquals(20L, db.channel(1)!!.getLong("history_offset")); assertEquals(20L, db.channel(1)!!.getLong("latest_id"))
            assertEquals(1L, db.video("1_20")!!.getLong("channel_id"))
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun pagesAreIsolatedAtomicAndResumeBothDirections() {
        val name = "index-${UUID.randomUUID()}.db"
        try { MediaDatabase(context, name).use { db ->
            db.addChannel(channel(1)); db.addChannel(channel(2))
            db.applyPage(1, page(70,100,false,video(1,90)), false)
            db.applyPage(2, page(70,100,false,video(2,90,"另一个频道.mp4")), false)
            db.savePosition("1_90", 12000)
            assertEquals(2L, db.count()); assertEquals(2, db.videos().length()); assertEquals(1, db.videos(channelID=2).length())
            assertEquals(1, db.videos("_01%\\").length()); assertEquals(0, db.videos("' OR 1=1 --").length())
            db.applyPage(1, page(150,200,false,video(1,180)), true)
            assertEquals(100L, db.channel(1)!!.getLong("latest_id"))
            assertEquals(150L, db.channel(1)!!.getLong("refresh_offset"))
            // long: 失败页包含另一个频道的数据，整页必须回滚，不能只回滚游标或部分视频。
            try { db.applyPage(1,page(110,140,false,video(1,130),video(2,120)),true); fail("invalid page accepted") } catch (_: IllegalArgumentException) { }
            assertNull(db.video("1_130")); assertEquals(150L, db.channel(1)!!.getLong("refresh_offset"))
        }
        MediaDatabase(context,name).use { db ->
            assertEquals(150L,db.channel(1)!!.getLong("refresh_offset"))
            db.applyPage(1,page(0,0,true),true)
            assertEquals(200L,db.channel(1)!!.getLong("latest_id")); assertFalse(db.channel(1)!!.has("refresh_offset"))
            assertEquals(70L,db.channel(1)!!.getLong("history_offset"))
            assertEquals(12000L,db.position("1_90")); assertEquals(1,db.videos(recent=true).length())
            db.removeChannel(1); assertEquals(1L,db.count()); assertEquals(1,db.channels().length()); assertEquals(0L,db.position("1_90"))
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun pauseRequestedBeforeWorkerStartsStopsBeforeNetworkAndKeepsCursor() {
        val app = context.applicationContext as TgApplication
        val saved = app.database.channels()
        if (saved.length() == 0 || app.engine == null) return
        val id = saved.getJSONObject(0).getLong("channel_id")
        val before = app.database.channel(id)!!.toString()
        val count = app.database.count(id)
        app.prepareScan()
        app.cancelScan()
        assertEquals("paused", app.scanChannel(app.engine!!, id, false))
        assertEquals(before, app.database.channel(id).toString())
        assertEquals(count, app.database.count(id))
        app.prepareScan()
    }
    @Test fun publicationSortAndPlaybackHistoryKeepTheirOwnData() {
        val name = "presentation-${UUID.randomUUID()}.db"
        try { MediaDatabase(context, name).use { db ->
            db.addChannel(channel(1))
            // long: 消息 ID 与发布时间故意反序，避免排序误用 ID 仍能通过测试。
            db.applyPage(1, page(0, 20, true,
                video(1, 20, "B.mp4").put("date", 1704067200L),
                video(1, 10, "A.mp4").put("date", 1704153600L)), false)
            assertEquals("1_10", db.videos().getJSONObject(0).getString("key"))
            assertEquals("1_20", db.videos(sort = "oldest").getJSONObject(0).getString("key"))
            assertEquals("A.mp4", db.videos(sort = "name").getJSONObject(0).getString("name"))
            assertEquals(1704067200L, db.video("1_20")!!.getLong("date"))
            db.savePosition("1_20", 12000); db.savePosition("1_10", 0)
            assertEquals(2, db.videos(recent = true).length())
            assertEquals(12000L, db.position("1_20"))
            assertEquals(0L, db.position("1_10"))
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun recheckIsAtomicAndOwnerCannotChange() {
        val name = "recheck-${UUID.randomUUID()}.db"
        try { MediaDatabase(context, name).use { db ->
            db.bindOwner(11)
            try { db.bindOwner(22); fail("different account took over index") } catch (_: IllegalArgumentException) { }
            assertEquals(11L, db.ownerID())
            db.addChannel(channel(1))
            db.applyPage(1, page(0, 30, true, video(1, 10).put("document_id", 1), video(1, 20).put("document_id", 2), video(1,30)), false)
            db.savePosition("1_10", 9000); db.savePosition("1_20", 8000)
            val result = JSONObject().put("items", JSONArray().put(video(1,10,"renamed.mp4").put("document_id",3)))
                .put("missing_ids", JSONArray().put(20)).put("title", "新频道名")
            try { db.applyRecheck(1, JSONArray().put(10).put(20).put(30), result, "1_30"); fail("partial result accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(3L, db.count()); assertEquals(9000L, db.position("1_10"))
            db.applyRecheck(1, JSONArray().put(10).put(20), result, "1_20")
            assertEquals(2L, db.count()); assertNull(db.video("1_20")); assertEquals(0L, db.position("1_10"))
            db.savePosition("1_10", 99999, expectedDocument = 1, expectedOwner = 11)
            db.savePosition("1_20", 99999, expectedDocument = 2, expectedOwner = 11)
            assertEquals(0L, db.position("1_10")); assertEquals(0L, db.position("1_20"))
            db.savePosition("1_10", 1234, expectedDocument = 3, expectedOwner = 11)
            assertEquals(1234L, db.position("1_10"))
            assertEquals("renamed.mp4", db.video("1_10")!!.getString("name"))
            assertEquals("新频道名", db.channel(1)!!.getString("title"))
            assertEquals("1_30", db.recheckBatch(1, "1_20").getJSONObject(0).getString("key"))
            db.completeRecheck(1); assertFalse(db.channel(1)!!.has("recheck_key"))
        } } finally { context.deleteDatabase(name) }
    }
    @Test fun recentSortHonorsSelectionAndChannelUsesIndex() {
        val name = "sort-${UUID.randomUUID()}.db"
        try { MediaDatabase(context, name).use { db ->
            db.addChannel(channel(1))
            db.applyPage(1, page(0,20,true,video(1,10,"A.mp4"),video(1,20,"B.mp4")),false)
            db.savePosition("1_20",1000); db.savePosition("1_10",1000)
            db.writableDatabase.execSQL("UPDATE playback SET updated=CASE key WHEN '1_20' THEN 200 ELSE 100 END")
            assertEquals("1_20",db.videos(recent=true,sort="watched").getJSONObject(0).getString("key"))
            assertEquals("1_10",db.videos(recent=true,sort="oldest").getJSONObject(0).getString("key"))
            assertEquals("1_10",db.videos(recent=true,sort="name").getJSONObject(0).getString("key"))
            db.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT payload FROM videos WHERE channel_id=? ORDER BY date DESC LIMIT 51",arrayOf("1")).use { cursor ->
                val plans=mutableListOf<String>();while(cursor.moveToNext())plans.add(cursor.getString(3))
                assertTrue(plans.any{it.contains("SEARCH") && it.contains("video_channel")})
            }
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun largeLibraryChannelQueriesStayBounded() {
        val name="large-${UUID.randomUUID()}.db"
        try { MediaDatabase(context,name).use { db ->
            db.addChannel(channel(1));db.addChannel(channel(2))
            val items=JSONArray()
            repeat(20000){ items.put(video(1,it.toLong()+1,"bulk_${it}.mp4")) }
            db.applyPage(1,page(0,20000,true).put("items",items),false)
            db.applyPage(2,page(0,2,true,video(2,1),video(2,2)),false)
            val start=android.os.SystemClock.elapsedRealtime()
            assertEquals(2,db.videos(channelID=2,limit=51).length())
            assertEquals(2L,db.count(2));assertEquals(20002L,db.count())
            val elapsed=android.os.SystemClock.elapsedRealtime()-start
            InstrumentationRegistry.getInstrumentation().sendStatus(0,android.os.Bundle().apply { putString("stream","synthetic_rows=20002 channel_query_and_counts_ms=$elapsed\n") })
        } } finally { context.deleteDatabase(name) }
    }

}
