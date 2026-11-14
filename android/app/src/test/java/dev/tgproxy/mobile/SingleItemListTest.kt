package dev.tgproxy.mobile

import org.junit.Assert.*
import org.junit.Test

class SingleItemListTest {
    @Test fun progressOverlayDoesNotReadOrCopyHistoricalItems() {
        var reads = 0
        val base = object : AbstractList<Int>() {
            override val size = 100000
            override fun get(index: Int): Int { reads++; return index }
        }
        val updated = SingleItemList(base, 90000, -1)
        assertEquals(100000, updated.size); assertEquals(0, reads)
        assertEquals(-1, updated[90000]); assertEquals(0, reads)
        assertEquals(42, updated[42]); assertEquals(1, reads)
        assertEquals(90000, base[90000])
    }
}
