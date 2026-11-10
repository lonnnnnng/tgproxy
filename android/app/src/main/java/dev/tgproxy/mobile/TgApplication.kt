package dev.tgproxy.mobile

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.tgproxy.core.mobilecore.Engine
import dev.tgproxy.core.mobilecore.Mobilecore
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class TgApplication : Application() {
    lateinit var database: MediaDatabase
        private set
    lateinit var vault: SessionVault
        private set
    @Volatile var engine: Engine? = null
        private set
    @Volatile var startupError: String? = null
        private set
    @Volatile var busy = false
        private set
    @Volatile var scanning = false
        internal set
    @Volatile var scanMessage = "索引任务等待开始"
        internal set
    @Volatile private var scanCancelled = false
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            getSharedPreferences("appearance", MODE_PRIVATE).getInt("theme", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
        vault = SessionVault(this)
        database = MediaDatabase(this)
        initialize()
    }

    private fun create(saved: JSONObject): Engine {
        // long: SQLite 保存完整频道索引；播放时只把当前条目注册进内存，避免启动时截断到 1000 条。
        return Mobilecore.newEngine(saved.optLong("api_id"), saved.optString("api_hash"), saved.optString("session"))
    }

    private fun initialize() {
        worker.execute {
            try { engine = create(vault.read()) }
            catch (_: Exception) { startupError = "无法读取账号或启动内核，请重置本机账号后重试" }
        }
    }

    fun perform(action: (Engine) -> String, finished: (String?, String?) -> Unit) {
        if (busy) { finished(null, "请等待当前操作结束"); return }
        busy = true
        worker.execute {
            var value: String? = null
            var error: String? = null
            try {
                val core = engine ?: error("内核尚未就绪")
                value = action(core)
                val session = core.session()
                if (session.isNotEmpty()) vault.write(vault.read().put("session", session))
            } catch (failure: Exception) {
                // long: Go 已将 Telegram 错误转为不含凭据的提示；不记录异常堆栈、会话或播放 URL。
                error = failure.message?.take(180) ?: "操作未完成，请重试"
            }
            busy = false
            main.post { finished(value, error) }
        }
    }

    fun configure(id: Long, hash: String, finished: (String?) -> Unit) {
        if (!((id == 0L && hash.isEmpty()) || (id in 1..2147483647 && hash.length == 32 && hash.all { it in "0123456789abcdefABCDEF" }))) { finished("API ID 或 API Hash 格式无效"); return }
        if (busy) { finished("请等待当前操作结束"); return }
        busy = true
        worker.execute {
            var error: String? = null
            try {
                val saved = JSONObject().put("api_id", id).put("api_hash", hash)
                val replacement = create(saved)
                try { vault.write(saved) } catch (failure: Exception) { replacement.close(); throw failure }
                engine?.close(); engine = replacement; startupError = null
            } catch (_: Exception) { error = "无法保存 API 凭证，请检查填写内容" }
            busy = false; main.post { finished(error) }
        }
    }

    fun credentials(): JSONObject = try {
        val saved = vault.read()
        JSONObject().put("api_id", saved.optLong("api_id")).put("api_hash", saved.optString("api_hash"))
    } catch (_: Exception) { JSONObject() }

    // long: 每页先提交视频和原始消息游标，再请求下一页；进程退出或用户取消后可从最后完整页恢复。
    fun scanChannel(core: Engine, channelID: Long, refresh: Boolean): String {
        val channel = database.channel(channelID) ?: error("频道已移除")
        var offset = if (refresh) channel.optLong("refresh_offset") else channel.optLong("history_offset")
        val minID = if (refresh) channel.optLong(if (channel.has("refresh_offset")) "refresh_base" else "latest_id") else 0L
        var found = 0L
        scanning = true
        scanMessage = "正在连接频道"
        core.updateScanProgress(0, true)
        try {
            while (true) {
                if (scanCancelled) return "paused"
                val page = try { JSONObject(core.scanPage(channelID, offset, minID)) }
                catch (failure: Exception) { if (scanCancelled) return "paused" else throw failure }
                database.applyPage(channelID, page, refresh)
                found += page.getJSONArray("items").length()
                core.updateScanProgress(found, true)
                val saved = database.channel(channelID) ?: error("频道已移除")
                scanMessage = "已索引 $found 个视频，当前频道共 ${database.count(channelID)} 个"
                if (page.getBoolean("done")) return saved.toString()
                offset = if (refresh) saved.optLong("refresh_offset") else saved.optLong("history_offset")
                if (offset <= 0L) error("频道扫描游标无效")
            }
        } finally { core.updateScanProgress(found, false); scanning = false }
    }

    // long: 在通知出现前开启任务，之后到达的暂停不会被工作线程入口重置。
    fun prepareScan() { scanCancelled = false }
    fun cancelScan() { scanCancelled = true; engine?.cancelScan() }

    fun logout(finished: (String?) -> Unit) {
        PlaybackService.stopPlayback(this, revokeSession = true)
        cancelScan()
        if (busy) { finished("正在取消操作，请稍后再退出"); return }
        busy = true
        worker.execute {
            var error: String? = null
            try { engine?.close(); engine = null; vault.clear(); database.clear(); engine = create(JSONObject()); startupError = null }
            catch (_: Exception) { error = "账号清理未完成，请重试" }
            busy = false; main.post { finished(error) }
        }
    }

    fun validationMedia(): String {
        val target = File(cacheDir, "validation.mp4")
        if (!target.exists()) assets.open("validation.mp4").use { input -> target.outputStream().use { input.copyTo(it) } }
        return engine!!.mountValidationMedia(target.absolutePath)
    }
}
