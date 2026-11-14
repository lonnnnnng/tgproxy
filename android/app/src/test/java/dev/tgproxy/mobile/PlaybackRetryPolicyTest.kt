package dev.tgproxy.mobile

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class PlaybackRetryPolicyTest {
    @Test fun terminalNetworkFailuresHaveBoundedBackoff() {
        val policy = PlaybackRetryPolicy()
        assertEquals(1000L, policy.nextDelay(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(2000L, policy.nextDelay(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals(4000L, policy.nextDelay(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 502))
        assertNull(policy.nextDelay(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        policy.reset()
        assertEquals(1000L, policy.nextDelay(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }
    @Test fun permissionIdentityAndDecoderErrorsDoNotRetry() {
        val policy = PlaybackRetryPolicy()
        for (status in listOf(401, 403, 404, 412)) assertNull(policy.nextDelay(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, status))
        assertNull(policy.nextDelay(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(0, policy.attempts)
        assertEquals(5000L, policy.nextDelay(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 429, 5000))
    }
}
