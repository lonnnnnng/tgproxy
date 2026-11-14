package dev.tgproxy.mobile

import androidx.media3.common.PlaybackException

class PlaybackRetryPolicy {
    var attempts = 0
        private set
    fun reset() { attempts = 0 }
    fun nextDelay(errorCode: Int, httpStatus: Int? = null, retryAfterMs: Long = 0): Long? {
        val network = errorCode in setOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_IO_UNSPECIFIED) ||
            (errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && (httpStatus == 408 || httpStatus == 429 || httpStatus in 500..599))
        // long: 权限、换片和解码错误不会因重连而修复；每次终止错误最多尝试三次，避免后台无限重试。
        if (!network || attempts >= 3) return null
        val delay = longArrayOf(1000, 2000, 4000)[attempts++]
        return maxOf(delay, retryAfterMs.coerceIn(0, 30000))
    }
}
