package dev.tgproxy.mobile

data class PlaybackCheckpoint(val position: Long = 0, val updated: Long = 0) {
    companion object {
        fun latest(remote: PlaybackCheckpoint?, local: PlaybackCheckpoint?): PlaybackCheckpoint {
            if (remote == null) return local ?: PlaybackCheckpoint()
            if (local == null) return remote
            // long: 看完时位置归零仍是一次新观看，不能用较大的旧位置覆盖结束状态。
            return when {
                remote.updated > local.updated -> remote
                local.updated > remote.updated -> local
                local.position > 0 -> local
                else -> remote
            }
        }
    }
}
