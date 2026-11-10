package dev.tgproxy.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class MediaPresentationTest {
    @Test fun telegramSecondsUseLocalTimeAndMissingDateStaysEmpty() {
        assertEquals("2024-01-01", MediaPresentation.published(1704067200L, zone = ZoneId.of("Asia/Shanghai")))
        assertEquals("2024-01-01 08:00:00", MediaPresentation.published(1704067200L, full = true, zone = ZoneId.of("Asia/Shanghai")))
        assertEquals("2024-01-01 00:00:00", MediaPresentation.published(1704067200L, full = true, zone = ZoneId.of("UTC")))
        assertEquals("", MediaPresentation.published(0))
        assertEquals("", MediaPresentation.published(-1))
        assertEquals("", MediaPresentation.published(Long.MAX_VALUE))
    }
}
