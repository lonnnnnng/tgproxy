package dev.tgproxy.mobile

import org.json.JSONArray
import org.json.JSONObject

data class PlaybackScope(val owner: Long, val channel: Long, val search: String, val sort: String, val offline: Boolean)

class PlaybackCatalog(private val database: MediaDatabase, private val downloads: Downloads) {
    private fun local(scope: PlaybackScope): List<DownloadRecord> {
        val items = downloads.stateRecords.filter { it.valid && it.owner == scope.owner && it.state == DownloadState.COMPLETE &&
            (scope.channel == 0L || it.video.optLong("channel_id") == scope.channel) &&
            (scope.search.isEmpty() || it.name.contains(scope.search, ignoreCase = true)) }
        val comparator = when (scope.sort) {
            "name" -> compareBy<DownloadRecord, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            "oldest" -> compareBy<DownloadRecord> { it.video.optLong("date") }
            else -> compareByDescending<DownloadRecord> { it.video.optLong("date") }
        }
        return items.sortedWith(comparator.thenBy { it.key }.thenBy { it.id })
    }
    private fun preferred(row: JSONObject, owner: Long): String {
        val local = downloads.find(owner, row.getString("key"), row.optLong("document_id"))
        if (local?.state == DownloadState.COMPLETE && local.valid) {
            val file = downloads.store.file(local.id)
            if (file.isFile && file.length() == local.size) return "download:" + local.id
        }
        return row.getString("key")
    }
    fun neighbors(current: String, original: String, scope: PlaybackScope): Pair<String?, String?> {
        if (scope.offline) {
            val items = local(scope)
            val index = items.indexOfFirst { "download:" + it.id == current }
            if (index < 0) return null to null
            return items.getOrNull(index - 1)?.let { "download:" + it.id } to items.getOrNull(index + 1)?.let { "download:" + it.id }
        }
        val rows = database.withOwner(scope.owner) {
            database.adjacentVideo(original, scope.channel, scope.search, scope.sort, false) to
                database.adjacentVideo(original, scope.channel, scope.search, scope.sort, true)
        }
        return rows.first?.let { preferred(it, scope.owner) } to rows.second?.let { preferred(it, scope.owner) }
    }
    fun page(current: String, scope: PlaybackScope, cursor: VideoPageCursor? = null, localPage: Int = 0, document: Long? = null): JSONObject {
        val rows = JSONArray()
        var next: String? = null
        var more = false
        if (scope.offline) {
            val all = local(scope)
            val offset = localPage.coerceIn(0, 1000000) * 50
            all.drop(offset).take(50).forEach { item ->
                rows.put(JSONObject().put("key", "download:" + item.id).put("name", item.name.take(500)).put("current", current == "download:" + item.id))
            }
            more = all.size > offset + 50
        } else {
            val page = database.withOwner(scope.owner) { database.videoPage(scope.search, scope.channel, sort = scope.sort, cursor = cursor) }
            for (index in 0 until page.items.length()) {
                val item = page.items.getJSONObject(index)
                val key = preferred(item, scope.owner)
                rows.put(JSONObject().put("key", key).put("name", item.optString("name").take(500))
                    .put("current", current == key || (current == item.getString("key") && (document == null || document == item.optLong("document_id")))))
            }
            next = page.next?.encode(); more = next != null
        }
        return JSONObject().put("items", rows).put("next_cursor", next ?: "").put("more", more)
            .put("offline", scope.offline).put("sort", scope.sort).put("search", scope.search)
    }
}
