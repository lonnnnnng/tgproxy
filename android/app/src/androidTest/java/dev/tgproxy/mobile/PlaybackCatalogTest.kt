package dev.tgproxy.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlaybackCatalogTest {
    @Test fun sameChannelNeighborsPreferLocalAndKeepAccountBoundary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mediaName = "catalog-media-" + UUID.randomUUID() + ".db"
        val downloadName = "catalog-download-" + UUID.randomUUID() + ".db"
        val directory = File(context.cacheDir, "catalog-" + UUID.randomUUID()).apply { mkdirs() }
        val media = MediaDatabase(context, mediaName)
        val store = DownloadStore(context, downloadName, directory)
        val downloads = Downloads(context.applicationContext as TgApplication, store) { error("not used") }
        try {
            val until = System.currentTimeMillis() + 5000
            while (!downloads.ready && System.currentTimeMillis() < until) Thread.sleep(20)
            assertTrue(downloads.ready)
            media.bindOwner(1)
            val channel = JSONObject().put("channel_id", 990000400).put("title", "测试频道").put("ref", "test")
            val rows = (1..3).map { id -> JSONObject().put("key", "990000400_" + id).put("channel_id", 990000400)
                .put("message_id", id).put("document_id", id).put("name", "选片0" + id + ".mp4").put("date", id / 2).put("size", 16) }
            media.replace(JSONObject(channel.toString()).put("items", JSONArray(rows)))
            val items = rows.map { row -> store.add(1, row).also { item -> store.file(item.id).writeBytes(ByteArray(16)); store.update(item.id, DownloadState.COMPLETE, 16) } }
            val foreign = store.add(2, rows[0])
            store.file(foreign.id).writeBytes(ByteArray(16)); store.update(foreign.id, DownloadState.COMPLETE, 16)
            val old = downloads.revision; downloads.refresh()
            while (downloads.revision == old && System.currentTimeMillis() < until + 5000) Thread.sleep(20)
            val catalog = PlaybackCatalog(media, downloads)
            val indexed = PlaybackScope(1, 990000400, "", "name", false)
            assertEquals(null to ("download:" + items[1].id), catalog.neighbors(rows[0].getString("key"), rows[0].getString("key"), indexed))
            val center = catalog.neighbors("download:" + items[1].id, rows[1].getString("key"), indexed)
            assertEquals("download:" + items[0].id, center.first); assertEquals("download:" + items[2].id, center.second)
            val page = catalog.page(rows[0].getString("key"), indexed, document = 1)
            assertTrue(page.getJSONArray("items").getJSONObject(0).getBoolean("current"))
            val offline = indexed.copy(offline = true)
            assertEquals(3, catalog.page("download:" + items[0].id, offline).getJSONArray("items").length())
            media.clear(); media.bindOwner(2)
            assertEquals(3, catalog.page("download:" + items[0].id, offline).getJSONArray("items").length())
            try { catalog.neighbors("download:" + items[0].id, rows[0].getString("key"), indexed); fail("旧账号读到了新索引") } catch (_: IllegalStateException) { }
        } finally { downloads.shutdown(); media.close(); store.close(); context.deleteDatabase(mediaName); context.deleteDatabase(downloadName); directory.deleteRecursively() }
    }
}
