package dev.tgproxy.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCheckpointTest {
    @Test fun newestSourceWinsIncludingCompletedPlayback() {
        assertEquals(300L, PlaybackCheckpoint.latest(PlaybackCheckpoint(300, 20), PlaybackCheckpoint(900, 10)).position)
        assertEquals(900L, PlaybackCheckpoint.latest(PlaybackCheckpoint(300, 10), PlaybackCheckpoint(900, 20)).position)
        assertEquals(0L, PlaybackCheckpoint.latest(PlaybackCheckpoint(900, 10), PlaybackCheckpoint(0, 20)).position)
    }

    @Test fun legacyPositionIsKeptWithoutInventingItsWatchTime() {
        assertEquals(PlaybackCheckpoint(500, 0), PlaybackCheckpoint.latest(null, PlaybackCheckpoint(500, 0)))
        assertEquals(PlaybackCheckpoint(300, 20), PlaybackCheckpoint.latest(PlaybackCheckpoint(300, 20), PlaybackCheckpoint(500, 0)))
        assertEquals(PlaybackCheckpoint(), PlaybackCheckpoint.latest(null, null))
    }
}
