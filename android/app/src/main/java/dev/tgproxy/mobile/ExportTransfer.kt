package dev.tgproxy.mobile

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class ExportCancelled : IOException()
class ExportCancellation {
    @Volatile var cancelled = false
        private set
    @Volatile var bytesCopied = 0L
        private set
    fun cancel() { cancelled = true }
    fun check() { if (cancelled) throw ExportCancelled() }
    internal fun copied(value: Long) { bytesCopied = value }
}

open class ExportTransfer(private val reportIntervalMs: Long = 500) {
    open fun copy(input: InputStream, output: OutputStream, size: Long, cancellation: ExportCancellation, progress: (Long) -> Unit) {
        require(size > 0)
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        var reported = System.nanoTime()
        progress(0)
        while (copied < size) {
            cancellation.check()
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - copied).toInt())
            if (count < 0) throw IOException("源文件长度已变化")
            if (count == 0) continue
            output.write(buffer, 0, count); copied += count
            // long: 界面采样会节流，取消时仍需保留最后一个完整写入块的字节数，不能退回上次采样。
            cancellation.copied(copied)
            cancellation.check()
            if (System.nanoTime() - reported >= reportIntervalMs * 1_000_000) { progress(copied); reported = System.nanoTime() }
        }
        // long: 只有完整复制源文件才能报告完成，取消或长度变化留下的是需要处理的副本。
        if (input.read() != -1) throw IOException("源文件长度已变化")
        output.flush(); cancellation.check(); progress(copied)
    }
}
