package dev.tgproxy.mobile

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.tgproxy.core.mobilecore.Mobilecore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PerformanceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private val label get() = InstrumentationRegistry.getArguments().getString("benchmarkLabel", "after")
    private fun report(value: String) = instrument.sendStatus(0, Bundle().apply { putString("stream", "$label $value\n") })
    private fun median(action: () -> Unit): Double {
        action()
        return List(5) { val start = SystemClock.elapsedRealtimeNanos(); action(); (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0 }.sorted()[2]
    }

    @Test fun largeLibraryMeasuresSortingSearchAndDeepOffset() {
        val name = "performance-media-${UUID.randomUUID()}.db"
        try { MediaDatabase(context, name).use { store ->
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                // long: 合成索引与真实媒体库隔离，刻意重复日期，覆盖排序的主键边界。
                db.compileStatement("INSERT INTO videos(key,channel_id,name,payload,date) VALUES(?,?,?,?,?)").use { insert ->
                    repeat(100000) { offset ->
                        val id = offset + 1L
                        val title = "影片_${id.toString().padStart(6, '0')}.mp4"
                        val video = JSONObject().put("key", "990000100_$id").put("channel_id", 990000100).put("message_id", id)
                            .put("name", title).put("date", id / 5).put("size", 100).put("document_id", id)
                        insert.bindString(1, video.getString("key")); insert.bindLong(2, 990000100); insert.bindString(3, title)
                        insert.bindString(4, video.toString()); insert.bindLong(5, id / 5); insert.executeInsert()
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            val first = median { assertEquals(51, store.videos(limit = 51).length()) }
            val deep = median { assertEquals(51, store.videos(offset = 90000, limit = 51).length()) }
            val names = median { assertEquals(51, store.videos(offset = 90000, limit = 51, sort = "name").length()) }
            val search = median { assertEquals(51, store.videos("影片_09", limit = 51).length()) }
            report("synthetic_rows=100000 first_page_ms=$first deep_offset_ms=$deep name_deep_ms=$names substring_ms=$search")
            if (label != "before") {
                val previous = store.videos(offset = 89999, limit = 1).getJSONObject(0)
                val cursor = VideoPageCursor("newest", previous.getLong("date").toString(), previous.getString("key"))
                val expected = store.videos(offset = 90000, limit = 50)
                val cost = median {
                    val result = store.videoPage(cursor = cursor)
                    assertEquals(expected.toString(), result.items.toString())
                }
                report("deep_cursor_ms=$cost")
            }
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun progressPublicationMeasuresFullHistoryReloads() {
        val name = "performance-download-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, "performance-download-${UUID.randomUUID()}").apply { mkdirs() }
        val fullReads = AtomicInteger()
        val store = object : DownloadStore(context, name, directory) {
            override fun list(): List<DownloadRecord> { fullReads.incrementAndGet(); return super.list() }
        }
        val fixture = File(directory, "fixture.mp4").apply { writeBytes(ByteArray(3 * 1024 * 1024) { (it * 31).toByte() }) }
        val core = Mobilecore.newEngine(0, "", "")
        val source = DownloadSource(core.mountValidationMedia(fixture.absolutePath), core.token())
        val downloads = Downloads(context.applicationContext as TgApplication, store) { source }
        try {
            val until = SystemClock.elapsedRealtime() + 10000
            while (!downloads.ready && SystemClock.elapsedRealtime() < until) Thread.sleep(20)
            assertTrue(downloads.ready)
            store.writableDatabase.beginTransaction()
            try {
                repeat(1000) { i ->
                    val video = JSONObject().put("key", "990000200_${i + 1}").put("name", "历史下载$i.mp4").put("size", 100).put("document_id", i + 1)
                    val item = store.add(1, video); store.update(item.id, DownloadState.COMPLETE, 100)
                }
                store.writableDatabase.setTransactionSuccessful()
            } finally { store.writableDatabase.endTransaction() }
            val video = JSONObject().put("key", "990000300_1").put("name", "进度刷新验证.mp4").put("size", fixture.length()).put("document_id", 1)
            val item = store.add(1, video)
            val finished = CountDownLatch(1)
            val start = SystemClock.elapsedRealtime()
            downloads.start { finished.countDown() }
            assertTrue(finished.await(20, TimeUnit.SECONDS))
            assertEquals(DownloadState.COMPLETE, store.get(item.id)!!.state)
            assertEquals(fixture.length(), store.completed(item.id)!!.length())
            report("download_history=1000 full_history_reads=${fullReads.get()} transfer_and_publish_ms=${SystemClock.elapsedRealtime() - start}")
            if (label != "before") assertTrue("进度回调仍重复解析全部历史记录", fullReads.get() <= 6)
        } finally { downloads.shutdown(); core.close(); store.close(); context.deleteDatabase(name); directory.deleteRecursively() }
    }
}
