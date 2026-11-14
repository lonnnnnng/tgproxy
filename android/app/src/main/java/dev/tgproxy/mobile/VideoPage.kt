package dev.tgproxy.mobile

import org.json.JSONArray
import org.json.JSONObject

data class VideoPageCursor(val sort: String, val value: String, val key: String) {
    fun encode(): String = JSONObject().put("sort", sort).put("value", value).put("key", key).toString()

    companion object {
        fun decode(raw: String): VideoPageCursor? = runCatching {
            val item = JSONObject(raw)
            val sort = item.getString("sort")
            require(sort in setOf("newest", "oldest", "name", "watched"))
            val value = item.getString("value")
            if (sort != "name") require(value.toLongOrNull() != null)
            VideoPageCursor(sort, value, item.getString("key").also { require(it.isNotEmpty()) })
        }.getOrNull()
    }
}

data class VideoPage(val items: JSONArray, val next: VideoPageCursor?)
