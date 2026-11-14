package dev.tgproxy.mobile

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ExportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15000
        while (!condition()) { if (System.currentTimeMillis() > deadline) fail("导出检查超时"); Thread.sleep(50) }
    }
    private fun callback(action: ((String?) -> Unit) -> Unit): String? {
        val result = AtomicReference<String?>()
        val done = CountDownLatch(1)
        action { result.set(it); done.countDown() }
        assertTrue("导出命令没有回调", done.await(15, TimeUnit.SECONDS))
        return result.get()
    }
    private class Gate : ExportTransfer(0) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val once = AtomicBoolean(true)
        override fun copy(input: InputStream, output: OutputStream, size: Long, cancellation: ExportCancellation, progress: (Long) -> Unit) {
            super.copy(input, output, size, cancellation) { bytes ->
                progress(bytes)
                if (bytes > 0 && once.getAndSet(false)) {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                    cancellation.check()
                }
            }
        }
    }
    private inner class Fixture(transfer: ExportTransfer = ExportTransfer(), waitReady: Boolean = true,
        storeFactory: (Context, String) -> ExportStore = { ctx, name -> ExportStore(ctx, name) }) : AutoCloseable {
        val downloadName = "export-source-${UUID.randomUUID()}.db"
        val storeName = "export-result-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, "export-source-${UUID.randomUUID()}").apply { mkdirs() }
        val data = ByteArray(200000) { (it * 19).toByte() }
        val downloads = DownloadStore(context, downloadName, directory)
        val source = downloads.add(990000071, JSONObject().put("key", "export-fixture-${UUID.randomUUID()}")
            .put("document_id", 990000072).put("name", "合成导出验证.mp4").put("size", data.size).put("mime", "video/mp4")).also {
            downloads.file(it.id).writeBytes(data)
            downloads.update(it.id, DownloadState.COMPLETE, data.size.toLong())
        }
        val store = storeFactory(context, storeName)
        // long: 复制来源、结果库和文件提供器均独立，不能启动真实下载或改变手机上已有授权与索引。
        val exports = Exports(context, store, downloads, transfer, startService = {})
        private val targets = mutableListOf<Uri>()
        private var stopped = false
        init { if (waitReady) await { exports.ready } }
        fun target(kind: String = "") = ExportTestProvider.uri(kind).also { targets.add(it) }
        fun enqueue(uri: Uri): ExportRecord {
            assertNull(callback { exports.enqueue(source.id, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, it) })
            return store.list(1).first()
        }
        fun history(page: Int): List<ExportRecord> {
            val done = CountDownLatch(1)
            val result = AtomicReference<List<ExportRecord>>()
            val error = AtomicReference<String?>()
            exports.history(page) { items, failure -> result.set(items); error.set(failure); done.countDown() }
            assertTrue(done.await(15, TimeUnit.SECONDS)); assertNull(error.get())
            return result.get()
        }
        fun stopController() {
            if (stopped) return
            await { !exports.running }; history(0)
            exports.shutdown(); stopped = true
        }
        override fun close() {
            stopController(); store.close(); downloads.close()
            targets.forEach { context.contentResolver.delete(it, null, null) }
            context.deleteDatabase(storeName); context.deleteDatabase(downloadName)
            check(directory.parentFile == context.cacheDir && directory.name.startsWith("export-source-"))
            directory.deleteRecursively()
        }
    }

    @Test fun completeCopyPreservesExactBytesAndDurableResult() {
        Fixture().use { fixture ->
            val uri = fixture.target()
            val item = fixture.enqueue(uri)
            fixture.exports.start {}
            await { !fixture.exports.running && fixture.store.get(item.id)?.state == ExportState.COMPLETE }
            assertArrayEquals(fixture.data, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            fixture.stopController(); fixture.store.close()
            ExportStore(context, fixture.storeName).use { reopened ->
                reopened.recover()
                val record = reopened.get(item.id)!!
                assertEquals(ExportState.COMPLETE, record.state); assertEquals(uri.toString(), record.uri)
                assertEquals(fixture.data.size.toLong(), record.bytes); assertTrue(record.touched)
                assertEquals(fixture.source.id, record.download)
            }
        }
    }

    @Test fun cancellationKeepsPartialUntilExplicitCleanupAndCannotDeleteActiveTarget() {
        val gate = Gate()
        Fixture(gate).use { fixture ->
            try {
                val uri = fixture.target()
                val item = fixture.enqueue(uri)
                fixture.exports.start {}
                assertTrue(gate.entered.await(15, TimeUnit.SECONDS))
                assertNull(callback { fixture.exports.cancel(item.id, it) })
                assertEquals(ExportState.CANCELLING, fixture.store.get(item.id)!!.state)
                assertNotNull(callback { fixture.exports.cleanup(item.id, it) })
                gate.release.countDown()
                await { !fixture.exports.running && fixture.store.get(item.id)?.state == ExportState.CANCELLED }
                val record = fixture.store.get(item.id)!!
                val partial = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                assertEquals(65536L, record.bytes); assertTrue(record.touched)
                assertArrayEquals(fixture.data.copyOf(65536), partial)
                assertNull(callback { fixture.exports.cleanup(item.id, it) })
                assertFalse(fixture.store.get(item.id)!!.touched)
                assertArrayEquals(fixture.data, fixture.downloads.completed(fixture.source.id)!!.readBytes())
            } finally { gate.release.countDown() }
        }
    }

    @Test fun existingUnknownAndDeniedTargetsFailWithoutBlockingNextExport() {
        Fixture().use { fixture ->
            val existing = fixture.target()
            val original = byteArrayOf(2, 3, 5, 7)
            context.contentResolver.openOutputStream(existing, "wt")!!.use { it.write(original) }
            val failures = listOf(fixture.enqueue(existing), fixture.enqueue(fixture.target("unknown")), fixture.enqueue(fixture.target("deny")))
            val goodUri = fixture.target()
            val good = fixture.enqueue(goodUri)
            fixture.exports.start {}
            await { !fixture.exports.running && fixture.store.get(good.id)?.state == ExportState.COMPLETE }
            failures.forEach { assertEquals(ExportState.FAILED, fixture.store.get(it.id)!!.state); assertFalse(fixture.store.get(it.id)!!.touched) }
            assertArrayEquals(original, context.contentResolver.openInputStream(existing)!!.use { it.readBytes() })
            assertArrayEquals(fixture.data, context.contentResolver.openInputStream(goodUri)!!.use { it.readBytes() })
        }
    }

    @Test fun serviceStopCoversMoreThanHundredQueuedResultsAndIgnoresOldLease() {
        val gate = Gate()
        Fixture(gate).use { fixture ->
            try {
                repeat(131) { fixture.store.add(fixture.source, fixture.target(), false) }
                fixture.exports.refresh()
                await { fixture.exports.records.size == 50 }
                val oldLease = fixture.exports.start {}
                assertTrue(gate.entered.await(15, TimeUnit.SECONDS))
                val currentLease = fixture.exports.start {}
                fixture.exports.serviceStopped(oldLease, "旧回调不得停止新任务")
                assertTrue(fixture.exports.running)
                assertEquals(ExportState.RUNNING, fixture.exports.current!!.state)
                fixture.exports.serviceStopped(currentLease, "合成服务中断")
                await {
                    fixture.store.readableDatabase.rawQuery("SELECT COUNT(*) FROM exports WHERE state IN ('QUEUED','RUNNING')", null)
                        .use { it.moveToFirst(); it.getInt(0) == 0 }
                }
                gate.release.countDown(); await { !fixture.exports.running }
                val pages = (0..2).map(fixture::history)
                assertEquals(listOf(50, 50, 31), pages.map { it.size })
                assertEquals(131, pages.flatten().map { it.id }.distinct().size)
                assertTrue(pages.flatten().all { it.state == ExportState.INTERRUPTED })
            } finally { gate.release.countDown() }
        }
    }

    @Test fun reopeningInterruptsOnlyPendingStatesAndKeepsPartialMetadata() {
        Fixture().use { fixture ->
            val records = ExportState.entries.associateWith { state ->
                fixture.store.add(fixture.source, fixture.target(), true).also {
                    fixture.store.update(it.id, state, 123, "fixture result", touched = true)
                }
            }
            fixture.stopController(); fixture.store.close()
            ExportStore(context, fixture.storeName).use { reopened ->
                reopened.recover()
                records.forEach { (state, before) ->
                    val record = reopened.get(before.id)!!
                    assertEquals(if (state in setOf(ExportState.QUEUED, ExportState.RUNNING, ExportState.CANCELLING)) ExportState.INTERRUPTED else state, record.state)
                    assertEquals(123L, record.bytes); assertTrue(record.touched); assertTrue(record.persisted)
                }
            }
        }
    }

    @Test fun initialReadFailureCanBeRefreshed() {
        val once = AtomicBoolean(true)
        Fixture(waitReady = false, storeFactory = { ctx, name -> object : ExportStore(ctx, name) {
            override fun list(limit: Int, offset: Int): List<ExportRecord> {
                if (once.getAndSet(false)) throw SQLiteException("fixture read failure")
                return super.list(limit, offset)
            }
        } }).use { fixture ->
            await { fixture.exports.problem != null }; assertFalse(fixture.exports.ready)
            fixture.exports.refresh(); await { fixture.exports.ready && fixture.exports.problem == null }
        }
    }

    @Test fun stateWriteFailureReleasesWorkerAndAllowsFreshExportAfterRecovery() {
        val once = AtomicBoolean(true)
        Fixture(storeFactory = { ctx, name -> object : ExportStore(ctx, name) {
            override fun update(id: String, state: ExportState, bytes: Long, error: String, touched: Boolean?) {
                if (state == ExportState.RUNNING && once.getAndSet(false)) throw SQLiteException("fixture state write failure")
                super.update(id, state, bytes, error, touched)
            }
        } }).use { fixture ->
            val interrupted = fixture.enqueue(fixture.target())
            fixture.exports.start {}
            await { !fixture.exports.running && fixture.exports.problem != null }
            fixture.exports.refresh(); await { fixture.exports.problem == null }
            assertEquals(ExportState.INTERRUPTED, fixture.store.get(interrupted.id)!!.state)
            val good = fixture.enqueue(fixture.target())
            fixture.exports.start {}
            await { !fixture.exports.running && fixture.store.get(good.id)?.state == ExportState.COMPLETE }
        }
    }
}
