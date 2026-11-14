package dev.tgproxy.mobile

import android.content.Intent
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun awaitValidation(timeout: Long = 15000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeout
    while (!condition()) { if (System.currentTimeMillis() > deadline) fail("真机界面检查超时"); Thread.sleep(50) }
}

/** long: 真机页面使用一条可识别的合成下载，结束后按自己的 UUID 清理，并核对用户原有数据数量。 */
internal class ValidationDownloadFixture(prefix: String) : AutoCloseable {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    val app = context.applicationContext as TgApplication
    val filename = "$prefix-${UUID.randomUUID()}.mp4"
    val data = context.assets.open("validation.mp4").use { it.readBytes() }
    private val beforeDownloads: Int
    private val beforeExports: Int
    private val beforeVideos: Long
    val source: DownloadRecord
    init {
        awaitValidation { app.downloads.ready && app.exports.ready && !app.exports.running }
        beforeDownloads = app.downloads.store.list().size
        beforeExports = exportCount(); beforeVideos = app.database.count()
        source = app.downloads.store.add(990000081, JSONObject().put("key", "ui-fixture-${UUID.randomUUID()}")
            .put("document_id", 990000082).put("name", filename).put("size", data.size).put("mime", "video/mp4"))
        app.downloads.store.directory.mkdirs(); app.downloads.store.file(source.id).writeBytes(data)
        app.downloads.store.update(source.id, DownloadState.COMPLETE, data.size.toLong())
        app.downloads.refresh(); awaitValidation { app.downloads.record(source.id)?.state == DownloadState.COMPLETE }
    }
    private fun exportCount() = app.exports.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM exports", null).use { it.moveToFirst(); it.getInt(0) }
    fun results(): List<ExportRecord> = app.exports.store.readableDatabase.rawQuery("SELECT id FROM exports WHERE download_id=?", arrayOf(source.id)).use {
        buildList { while (it.moveToNext()) app.exports.store.get(it.getString(0))?.let(::add) }
    }
    fun failedResult(): ExportRecord {
        val uri = ExportTestProvider.uri()
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(data, 0, 123) }
        val record = app.exports.store.add(source, uri, false)
        app.exports.store.update(record.id, ExportState.FAILED, 123, "验证副本未完成，请重新选择保存位置", touched = true)
        app.exports.refresh(); awaitValidation { app.exports.latest?.id == record.id }
        return app.exports.store.get(record.id)!!
    }
    override fun close() {
        for (item in results()) {
            if (ExportPresentation.pending(item)) {
                val cancelled = CountDownLatch(1)
                app.exports.cancel(item.id) { cancelled.countDown() }
                assertTrue(cancelled.await(15, TimeUnit.SECONDS))
                awaitValidation { app.exports.current?.id != item.id }
            }
            val uri = android.net.Uri.parse(item.uri)
            if (uri.authority == "dev.tgproxy.mobile.poc.test.exports") context.contentResolver.delete(uri, null, null)
            else {
                // long: 真实 SAF 只清理由系统新建、名称精确匹配本次 UUID 的验收文件，不能删除用户已有文件。
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                assertEquals("目标名称变化，保留文件交由人工核对", filename, name)
                assertTrue(DocumentsContract.deleteDocument(context.contentResolver, uri))
                context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }?.let {
                    val modes = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                        (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                    context.contentResolver.releasePersistableUriPermission(uri, modes)
                }
            }
        }
        app.exports.store.writableDatabase.delete("exports", "download_id=?", arrayOf(source.id))
        app.exports.refresh()
        val removed = CountDownLatch(1); val failure = AtomicReference<String?>()
        app.downloads.remove(source.id) { failure.set(it); removed.countDown() }
        assertTrue(removed.await(15, TimeUnit.SECONDS)); assertNull(failure.get())
        awaitValidation { app.downloads.record(source.id) == null && app.exports.latest?.download != source.id }
        assertEquals(beforeDownloads, app.downloads.store.list().size); assertEquals(beforeExports, exportCount())
        assertEquals(beforeVideos, app.database.count())
        assertFalse(app.downloads.store.file(source.id).exists())
    }
}
