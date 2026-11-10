package dev.tgproxy.mobile

internal object AccountPolicy {
    // long: 只有旧授权本身能为无归属的旧索引背书，重置授权后的新登录不能自动接管已有数据。
    fun canBind(owner: Long, current: Long, videoCount: Long, restoredOriginalSession: Boolean): Boolean =
        current > 0 && (owner == current || (owner == 0L && (videoCount == 0L || restoredOriginalSession)))
}
