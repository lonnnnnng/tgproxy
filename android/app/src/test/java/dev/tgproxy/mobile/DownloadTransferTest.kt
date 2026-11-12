package dev.tgproxy.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Collections
import kotlin.concurrent.thread

class DownloadTransferTest {
    private class Server(val data: ByteArray, val response: (Long, Long) -> Pair<Int, Int> = { _, _ -> 206 to Int.MAX_VALUE }) : AutoCloseable {
        val socket = ServerSocket(0)
        val ranges = Collections.synchronizedList(mutableListOf<Pair<Long, Long>>())
        val matches = Collections.synchronizedList(mutableListOf<String?>())
        val source = DownloadSource("http://127.0.0.1:${socket.localPort}/stream/video", "test-token")
        @Volatile var etag = "\"document:1:${data.size}\""
        val worker = thread(isDaemon = true) {
            while (!socket.isClosed) runCatching {
                socket.accept().use { client ->
                    client.soTimeout = 3000
                    val reader = client.getInputStream().bufferedReader()
                    reader.readLine()
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers[line.substringBefore(":").lowercase()] = line.substringAfter(":").trim()
                    }
                    check(headers["authorization"] == "Bearer test-token")
                    val range = headers.getValue("range").removePrefix("bytes=").split("-").map(String::toLong)
                    val (start, end) = range
                    ranges += start to end; matches += headers["if-match"]
                    val (status, send) = response(start, end)
                    val output = client.getOutputStream()
                    val bytes = minOf(send.toLong(), end - start + 1).toInt()
                    output.write("HTTP/1.1 $status Test\r\nContent-Length: ${end-start+1}\r\nContent-Range: bytes $start-$end/${data.size}\r\nETag: $etag\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(data, start.toInt(), bytes); output.flush()
                }
            }
        }
        override fun close() { socket.close(); worker.join(3000) }
    }
    private fun directory() = Files.createTempDirectory("tgproxy-download-test").toFile()
    @Test fun truncatedBodyResumesExactBytesWithIdentityAndProducesIdenticalFile() {
        val data = ByteArray(200000) { (it * 31).toByte() }
        val directory = directory()
        try {
            val partial = File(directory, "sample.part")
            var fail = true
            Server(data) { _, _ -> 206 to if (fail) 17000 else Int.MAX_VALUE }.use { server ->
                try { DownloadTransfer(65536).copy(server.source, partial, data.size.toLong(), DownloadCancellation()) {}; fail("short body accepted") }
                catch (_: java.io.IOException) { }
                assertEquals(17000L, partial.length())
                fail = false
                DownloadTransfer(65536).copy(server.source, partial, data.size.toLong(), DownloadCancellation()) {}
                assertEquals(17000L, server.ranges[1].first)
                assertNotNull(server.matches[1])
                assertArrayEquals(data, partial.readBytes())
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun ignoredRangeAndChangedDocumentAreRejectedWithoutAppending() {
        val data = ByteArray(12000) { it.toByte() }
        val directory = directory()
        try {
            val partial = File(directory, "sample.part")
            Server(data) { _, _ -> 200 to Int.MAX_VALUE }.use { server ->
                try { DownloadTransfer().copy(server.source, partial, data.size.toLong(), DownloadCancellation()) {}; fail("200 accepted") }
                catch (_: java.io.IOException) { }
                assertEquals(0L, partial.length())
            }
            partial.writeBytes(data.copyOf(100))
            File(partial.path + ".etag").writeText("\"original\"")
            Server(data).use { server ->
                try { DownloadTransfer().copy(server.source, partial, data.size.toLong(), DownloadCancellation()) {}; fail("changed identity accepted") }
                catch (_: java.io.IOException) { }
                assertEquals(100L, partial.length())
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun cancellationKeepsPartialAndNextAttemptCanResume() {
        val data = ByteArray(200000) { (it * 3).toByte() }
        val directory = directory()
        try {
            val partial = File(directory, "sample.part")
            Server(data).use { server ->
                val cancellation = DownloadCancellation()
                try { DownloadTransfer(65536).copy(server.source, partial, data.size.toLong(), cancellation) {
                    if (it >= 65536) cancellation.cancel()
                }; fail("cancelled transfer completed") } catch (_: DownloadPaused) { }
                assertEquals(65536L, partial.length())
                DownloadTransfer(65536).copy(server.source, partial, data.size.toLong(), DownloadCancellation()) {}
                assertArrayEquals(data, partial.readBytes())
            }
        } finally { directory.deleteRecursively() }
    }
}
