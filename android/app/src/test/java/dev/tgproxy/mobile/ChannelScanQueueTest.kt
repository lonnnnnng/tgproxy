package dev.tgproxy.mobile

import org.junit.Assert.*
import org.junit.Test

class ChannelScanQueueTest {
    @Test fun allRunsOnceInOrderAndFailureDoesNotBlockOtherChannels() {
        val queue = ChannelScanQueue()
        assertTrue(queue.begin(listOf(1L, 2L, 1L, 3L).map(::ChannelScanTask), all = true))
        assertFalse(queue.begin(listOf(ChannelScanTask(4))))
        val calls = mutableListOf<Long>()
        queue.execute { task, report ->
            assertTrue(queue.snapshot.running)
            calls += task.channelID
            report("更新中", 10)
            if (task.channelID == 2L) error("network unavailable")
            ChannelScanResult("已是最新", 10)
        }
        assertEquals(listOf(1L, 2L, 3L), calls)
        assertEquals(ScanPhase.ERROR, queue.snapshot.entries[2]!!.phase)
        assertEquals(ScanPhase.DONE, queue.snapshot.entries[3]!!.phase)
        assertFalse(queue.snapshot.running)
        queue.execute { _, _ -> error("must not run twice") }
        assertTrue(queue.begin(listOf(ChannelScanTask(2))))
        queue.execute { task, _ -> assertEquals(2L, task.channelID); ChannelScanResult("新增 1 个视频", 11) }
        assertEquals(ScanPhase.DONE, queue.snapshot.entries[2]!!.phase)
        assertNull(queue.snapshot.entries[2]!!.error)
        assertEquals(ScanPhase.DONE, queue.snapshot.entries[1]!!.phase)
        queue.forget(1)
        assertFalse(queue.snapshot.entries.containsKey(1L))
    }

    @Test fun pauseDuringChannelStopsRemainingQueueAndKeepsCommittedCount() {
        val queue = ChannelScanQueue()
        queue.begin(listOf(ChannelScanTask(1), ChannelScanTask(2)), all = true)
        val calls = mutableListOf<Long>()
        queue.execute { task, report ->
            calls += task.channelID
            queue.cancel()
            report("页已提交", 12)
            assertEquals(ScanPhase.STOPPING, queue.snapshot.entries[1]!!.phase)
            ChannelScanResult("done", 12)
        }
        assertEquals(listOf(1L), calls)
        assertFalse(queue.snapshot.running)
        assertEquals(12L, queue.snapshot.entries[1]!!.total)
        assertTrue(queue.snapshot.entries.values.all { it.phase == ScanPhase.PAUSED })
        assertTrue(queue.begin(listOf(ChannelScanTask(2))))
        queue.execute { _, _ -> ChannelScanResult("已是最新") }
        assertEquals(ScanPhase.DONE, queue.snapshot.entries[2]!!.phase)
    }

    @Test fun pauseBeforeWorkerStartsMakesNoRequest() {
        val queue = ChannelScanQueue()
        queue.begin(listOf(ChannelScanTask(1)))
        queue.cancel()
        queue.execute { _, _ -> error("must not request") }
        assertFalse(queue.snapshot.running)
        assertEquals(ScanPhase.PAUSED, queue.snapshot.entries[1]!!.phase)
    }
}
