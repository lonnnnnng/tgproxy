package dev.tgproxy.mobile

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

class DownloadPaused : IOException()
class DownloadProtocolError(message: String) : IOException(message)

class DownloadCancellation {
    @Volatile var cancelled = false
        private set
    private var connection: HttpURLConnection? = null
    @Synchronized fun attach(value: HttpURLConnection?) {
        connection = value
        if (cancelled) { value?.disconnect(); throw DownloadPaused() }
    }
    @Synchronized fun cancel() { cancelled = true; connection?.disconnect() }
    fun check() { if (cancelled) throw DownloadPaused() }
}

data class DownloadSource(val url: String, val token: String, val expectedIdentity: String = "", val validate: () -> Unit = {})

/** long: 分段 Range 校验总长和起止位置，服务器忽略断点时拒绝拼接，防止静默生成损坏视频。 */
class DownloadTransfer(private val chunkSize: Long = 1024 * 1024) {
    fun copy(source: DownloadSource, partial: File, size: Long, cancellation: DownloadCancellation,
        progress: (Long) -> Unit) {
        require(size > 0 && chunkSize > 0)
        partial.parentFile?.mkdirs()
        val identityFile = File(partial.path + ".etag")
        var identity = identityFile.takeIf { it.isFile }?.readText().orEmpty()
        RandomAccessFile(partial, "rw").use { output ->
            if (output.length() > size || (output.length() > 0 && identity.isBlank())) output.setLength(0)
            var offset = output.length()
            var lastReport = System.nanoTime()
            output.seek(offset)
            progress(offset)
            while (offset < size) {
                cancellation.check(); source.validate()
                val end = minOf(offset + chunkSize - 1, size - 1)
                val connection = URL(source.url).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15000; connection.readTimeout = 35000
                    // long: 媒体令牌只发送到已解析的本机流，不跟随跳转向其他地址泄露。
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Authorization", "Bearer ${source.token}")
                    connection.setRequestProperty("Range", "bytes=$offset-$end")
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    if (identity.isNotEmpty()) connection.setRequestProperty("If-Match", identity)
                    cancellation.attach(connection)
                    if (connection.responseCode != 206) throw DownloadProtocolError("视频源响应 ${connection.responseCode}，请检查资源后重试")
                    if (connection.getHeaderField("Content-Range") != "bytes $offset-$end/$size")
                        throw DownloadProtocolError("视频源范围或文件大小已变化，请校验频道资源后重新下载")
                    val received = connection.getHeaderField("ETag").orEmpty()
                    if (received.isBlank() || (identity.isNotBlank() && identity != received) ||
                        (source.expectedIdentity.isNotBlank() && source.expectedIdentity != received))
                        throw DownloadProtocolError("视频文件身份已变化，请校验频道资源后重新下载")
                    if (identity.isEmpty()) { identityFile.writeText(received); identity = received }
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = end - offset + 1
                        while (remaining > 0) {
                            cancellation.check()
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) throw IOException("连接提前结束，可继续下载")
                            if (count == 0) continue
                            output.write(buffer, 0, count); offset += count; remaining -= count
                            if (System.nanoTime() - lastReport >= 500_000_000) {
                                output.fd.sync(); progress(offset); lastReport = System.nanoTime()
                            }
                        }
                        if (input.read() != -1) throw IOException("视频源返回了多余数据")
                    }
                    output.fd.sync()
                    progress(offset)
                } finally {
                    connection.disconnect()
                    runCatching { cancellation.attach(null) }
                }
            }
            output.fd.sync()
            cancellation.check(); source.validate()
        }
    }
}
