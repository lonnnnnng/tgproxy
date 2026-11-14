package dev.tgproxy.mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class ExportTransferTest {
    private val data = ByteArray(200000) { (it * 17).toByte() }

    @Test fun completeCopyPreservesBytesAndReportsMonotonicProgress() {
        val output = ByteArrayOutputStream()
        val cancellation = ExportCancellation()
        val samples = mutableListOf<Long>()
        ExportTransfer(0).copy(ByteArrayInputStream(data), output, data.size.toLong(), cancellation, samples::add)
        assertArrayEquals(data, output.toByteArray())
        assertEquals(0L, samples.first()); assertEquals(data.size.toLong(), samples.last())
        assertTrue(samples.zipWithNext().all { it.first <= it.second })
        assertEquals(data.size.toLong(), cancellation.bytesCopied)
    }

    @Test fun cancellationKeepsKnownPartialBytesWithoutReportingSuccess() {
        val output = ByteArrayOutputStream()
        val cancellation = ExportCancellation()
        try {
            ExportTransfer(0).copy(ByteArrayInputStream(data), output, data.size.toLong(), cancellation) {
                if (it > 0) cancellation.cancel()
            }
            fail("取消后仍返回完成")
        } catch (_: ExportCancelled) { }
        assertEquals(65536, output.size())
        assertEquals(output.size().toLong(), cancellation.bytesCopied)
        assertArrayEquals(data.copyOf(output.size()), output.toByteArray())
    }

    @Test fun changedSourceLengthIsRejectedForShortAndLongFiles() {
        for (actual in listOf(data.copyOf(100), data + byteArrayOf(7))) {
            try {
                ExportTransfer().copy(ByteArrayInputStream(actual), ByteArrayOutputStream(), data.size.toLong(), ExportCancellation()) {}
                fail("长度变化被当作完整导出")
            } catch (_: IOException) { }
        }
    }

    @Test fun destinationFailureDoesNotPreventAnotherCopy() {
        val broken = object : OutputStream() {
            override fun write(value: Int) { throw IOException("fixture write failure") }
            override fun write(buffer: ByteArray, offset: Int, count: Int) { throw IOException("fixture write failure") }
        }
        try {
            ExportTransfer().copy(ByteArrayInputStream(data), broken, data.size.toLong(), ExportCancellation()) {}
            fail("写入失败被当作成功")
        } catch (_: IOException) { }
        val output = ByteArrayOutputStream()
        ExportTransfer().copy(ByteArrayInputStream(data), output, data.size.toLong(), ExportCancellation()) {}
        assertArrayEquals(data, output.toByteArray())
    }
}
