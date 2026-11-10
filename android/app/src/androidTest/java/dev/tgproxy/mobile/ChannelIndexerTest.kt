package dev.tgproxy.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChannelIndexerTest {
    private fun page(next: Long, newest: Long, done: Boolean, vararg ids: Long) = JSONObject()
        .put("next_id", next).put("newest_id", newest).put("done", done).put("items", JSONArray(ids.map {
            JSONObject().put("key", "1_$it").put("channel_id", 1).put("message_id", it)
                .put("name", "sample-$it.mp4").put("size", 100).put("date", it)
        }))

    @Test fun firstUpdateStopsAfterOnePageThenRefreshAndHistoryKeepSeparateCursors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "channel-indexer-${UUID.randomUUID()}.db"
        try { MediaDatabase(context, name).use { db ->
            db.addChannel(JSONObject().put("channel_id", 1).put("title", "示例频道").put("ref", "1"))
            val indexer = ChannelIndexer(db)
            var calls = 0
            val first = indexer.scan(1, true, { false }, {}, { offset, min ->
                calls++; assertEquals(0L, offset); assertEquals(0L, min)
                page(100, 200, false, 180)
            }, { _, _, _ -> })
            assertEquals(1, calls)
            assertEquals(1L, JSONObject(first).getLong("scan_added"))
            assertEquals(100L, db.channel(1)!!.getLong("history_offset"))
            assertFalse(db.channel(1)!!.getBoolean("history_done"))
            assertEquals(200L, db.channel(1)!!.getLong("latest_id"))
            // long: 中途暂停只推进新增游标，下一次仍以旧水位追完，不能遗漏尚未读到的消息。
            var cancelled = false
            assertEquals("paused", indexer.scan(1, true, { cancelled }, {}, { offset, min ->
                assertEquals(0L, offset); assertEquals(200L, min); cancelled = true
                page(250, 300, false, 280)
            }, { _, _, _ -> }))
            assertEquals(200L, db.channel(1)!!.getLong("latest_id"))
            var added = -1L
            indexer.scan(1, true, { false }, {}, { offset, min ->
                assertEquals(250L, offset); assertEquals(200L, min)
                page(0, 300, true, 280, 220)
            }, { _, fresh, _ -> added = fresh })
            assertEquals(1L, added)
            assertEquals(300L, db.channel(1)!!.getLong("latest_id"))
            indexer.scan(1, false, { false }, {}, { offset, min ->
                assertEquals(100L, offset); assertEquals(0L, min); page(0, 0, true)
            }, { _, _, _ -> })
            assertTrue(db.channel(1)!!.getBoolean("history_done"))
            assertEquals(300L, db.channel(1)!!.getLong("latest_id"))
        } } finally { context.deleteDatabase(name) }
    }
}
