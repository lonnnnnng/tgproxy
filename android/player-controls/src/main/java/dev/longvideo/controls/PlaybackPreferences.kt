package dev.longvideo.controls

import android.content.Context
import androidx.core.content.edit

/** long: 只保存用户明确选择的偏好，临时快播不能污染下一次播放的倍率。 */
class PlaybackPreferences(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences("longvideo_playback", Context.MODE_PRIVATE)
    var speed: Float
        get() = storage.getFloat("speed", 1f).takeIf { it.isFinite() && it in 0.25f..4f } ?: 1f
        set(value) { if (value.isFinite() && value in 0.25f..4f) storage.edit { putFloat("speed", value) } }
    var aspect: Int
        get() = storage.getInt("aspect", 0).coerceIn(0, 4)
        set(value) { storage.edit { putInt("aspect", value.coerceIn(0, 4)) } }
}
