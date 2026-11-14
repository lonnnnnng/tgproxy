package dev.tgproxy.mobile

import org.junit.Assert.fail
import org.junit.Test

class DownloadStoragePolicyTest {
    @Test fun remainingBytesAndDatabaseReserveAreBothRequired() {
        DownloadStoragePolicy.check(100, 40, 76, 16)
        expectLowSpace { DownloadStoragePolicy.check(100, 40, 75, 16) }
        expectLowSpace { DownloadStoragePolicy.check(100, 100, 15, 16) }
    }

    @Test fun invalidPartialAndHugeSizesCannotBypassSpaceProtection() {
        expectLowSpace { DownloadStoragePolicy.check(100, 101, 115, 16) }
        expectLowSpace { DownloadStoragePolicy.check(Long.MAX_VALUE, 0, Long.MAX_VALUE, 16) }
        expectLowSpace { DownloadStoragePolicy.check(100, 0, -1, 16) }
    }

    private fun expectLowSpace(action: () -> Unit) {
        try { action(); fail("空间预检接受了不足以保存视频和断点的空间") } catch (_: DownloadSpaceError) { }
    }
}
