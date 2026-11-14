package dev.tgproxy.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlaybackHistoryTest {
    @Test fun localAndOnlineShareLatestHistoryButRespectDocumentAndAccount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mediaName = "history-media-${UUID.randomUUID()}.db"
        val downloadName = "history-download-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, "history-${UUID.randomUUID()}").apply { mkdirs() }
        val media = MediaDatabase(context, mediaName)
        val downloads = DownloadStore(context, downloadName, directory)
        val history = PlaybackHistory(media, downloads)
        val video = JSONObject().put("key", "990000001_1").put("channel_id", 990000001).put("message_id", 1)
            .put("document_id", 501).put("name", "观看记录测试.mp4").put("date", 100).put("size", 16)
        val channel = JSONObject().put("channel_id", 990000001).put("title", "独立测试频道").put("ref", "test")
        fun putVideo(row: JSONObject) = media.replace(JSONObject(channel.toString()).put("items", JSONArray().put(row)))
        try {
            media.bindOwner(1); putVideo(video)
            val item = downloads.add(1, video)
            downloads.file(item.id).writeBytes(ByteArray(16)); downloads.update(item.id, DownloadState.COMPLETE, 16)
            history.saveRemote(item.key, 1000, 501, 1, 100)
            assertEquals(1000L, history.forLocal(downloads.get(item.id)!!).position)
            history.saveLocal(item.id, 2000, 501, 1, 200)
            assertEquals(2000L, media.position(item.key))
            assertEquals(item.key, media.videos(recent = true).getJSONObject(0).getString("key"))
            history.saveRemote(item.key, 3000, 501, 1, 300)
            history.saveLocal(item.id, 900, 501, 1, 250)
            assertEquals(3000L, history.forVideo(video, 1).position)
            history.saveLocal(item.id, 0, 501, 1, 400)
            assertEquals(0L, media.position(item.key)); assertEquals(0L, downloads.get(item.id)!!.position)

            // long: 换片后的索引和另一个账号都不能继承旧下载的观看位置。
            putVideo(JSONObject(video.toString()).put("document_id", 502))
            history.saveLocal(item.id, 5000, 501, 1, 500)
            assertNull(media.checkpoint(item.key, 502, 1))
            media.clear(); media.bindOwner(2); putVideo(video)
            history.saveRemote(item.key, 7000, 501, 2, 700)
            history.saveLocal(item.id, 6000, 501, 1, 800)
            assertEquals(7000L, media.position(item.key))
            assertEquals(6000L, history.forLocal(downloads.get(item.id)!!).position)
        } finally { media.close(); downloads.close(); context.deleteDatabase(mediaName); context.deleteDatabase(downloadName); directory.deleteRecursively() }
    }

    @Test fun downloadSchemaUpgradeKeepsCompletedFileAndLegacyPosition() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "download-v1-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, "download-v1-${UUID.randomUUID()}").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val video = JSONObject().put("key", "990000001_1").put("document_id", 501).put("name", "旧版下载.mp4").put("size", 16)
        val old = object : android.database.sqlite.SQLiteOpenHelper(context, name, null, 1) {
            override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
                db.execSQL("CREATE TABLE downloads (id TEXT PRIMARY KEY,owner INTEGER NOT NULL,video_key TEXT NOT NULL,document INTEGER NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,bytes INTEGER NOT NULL,error TEXT NOT NULL,created INTEGER NOT NULL,position INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE UNIQUE INDEX download_identity ON downloads(owner,video_key,document)")
            }
            override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, from: Int, to: Int) = Unit
        }
        try {
            old.writableDatabase.execSQL("INSERT INTO downloads VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(id, 1, "990000001_1", 501, video.toString(), "COMPLETE", 16, "", 100, 1234))
        } finally { old.close() }
        try {
            File(directory, "$id.video").writeBytes(ByteArray(16))
            DownloadStore(context, name, directory).use { store ->
                val item = store.get(id)!!
                assertTrue(item.valid); assertEquals(1234L, item.position); assertEquals(0L, item.watched)
                assertNotNull(store.completed(id)); assertEquals(0, store.recover())
            }
        } finally { context.deleteDatabase(name); directory.deleteRecursively() }
    }
}
