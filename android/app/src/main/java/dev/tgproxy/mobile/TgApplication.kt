package dev.tgproxy.mobile

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.tgproxy.core.mobilecore.Engine
import dev.tgproxy.core.mobilecore.Mobilecore
import org.json.JSONObject
import org.json.JSONArray
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
    val scanQueue = ChannelScanQueue()
    @Volatile private var singleScanning = false
    val scanning get() = singleScanning || scanQueue.snapshot.running
    @Volatile var scanMessage = "索引任务等待开始"
        internal set
    @Volatile private var scanCancelled = false
    @Volatile var accountIssue: String? = null
        private set
    @Volatile private var accountVerified = false
    private var trustExistingSession = false
    private var savedSession = ""
    @Volatile var legacyOwnershipPending = false
        private set
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
            try {
                val saved = vault.read()
                trustExistingSession = saved.optString("session").isNotEmpty() && !saved.optBoolean("owner_unconfirmed")
                savedSession = saved.optString("session")
                engine = create(saved)
            }
            catch (_: Exception) { startupError = "无法读取账号或启动内核，请重置本机账号后重试" }
        }
        main.post(accountMonitor)
    }

    private val accountMonitor = object : Runnable {
        override fun run() {
            worker.execute {
                runCatching { engine?.let { synchronizeAccount(it) } }
                // long: 长时间扫描占用队列时只保留一个待执行检查，不能每两秒无限追加监控任务。
                main.postDelayed(this, 2000)
            }
        }
    }
    private fun synchronizeAccount(core: Engine) {
        val status = JSONObject(core.status()); val id = status.optLong("account_id")
        if (id <= 0 || !status.optBoolean("connected")) return
        if (accountVerified && accountIssue == null) return
        val owner = database.ownerID()
        if (!AccountPolicy.canBind(owner, id, if (owner == 0L) database.count() else 0, trustExistingSession)) {
            accountVerified = false
            legacyOwnershipPending = owner == 0L
            accountIssue = if (legacyOwnershipPending) "这份旧版索引尚未记录账号归属，请确认当前登录账号是原账号。" else "当前账号与本机索引不一致。请重新登录原账号，或退出此设备后切换账号。"
            main.post { PlaybackService.stopPlayback(this, revokeSession = true) }
            return
        }
        database.bindOwner(id)
        accountVerified = true; accountIssue = null; legacyOwnershipPending = false
        val session = core.session()
        if (session.isNotEmpty() && session != savedSession) {
            vault.write(vault.read().put("session", session).put("account_id", id)); savedSession = session
        }
    }
    fun confirmLegacyOwner(expectedID: Long, finished: (String?) -> Unit) {
        if (busy) { finished("请等待当前操作结束"); return }
        busy = true
        worker.execute {
            var failure: String? = null
            try {
                val core = engine ?: error("内核未就绪")
                val state = JSONObject(core.status())
                check(legacyOwnershipPending && expectedID > 0 && state.optLong("account_id") == expectedID && state.optBoolean("connected") && database.ownerID() == 0L)
                // long: 无归属的旧版数据无法自动证明所属账号，只能在用户明确确认后绑定一次。
                database.bindOwner(expectedID)
                trustExistingSession = true
                vault.write(vault.read().apply { remove("owner_unconfirmed") })
                synchronizeAccount(core)
            } catch (_: Exception) { failure = "无法确认归属，请检查当前账号后重试" }
            busy = false; main.post { finished(failure) }
        }
    }
    fun requireMediaAccount() { check(accountVerified && accountIssue == null) { accountIssue ?: "正在确认账号，请稍后重试" } }
    fun mediaReady(): Boolean = accountVerified && accountIssue == null

    fun perform(action: (Engine) -> String, finished: (String?, String?) -> Unit) {
        if (busy) { finished(null, "请等待当前操作结束"); return }
        busy = true
        worker.execute {
            var value: String? = null
            var error: String? = null
            try {
                val core = engine ?: error("内核尚未就绪")
                value = action(core)
                synchronizeAccount(core)
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
                // long: 旧版首次升级先确认原会话身份，再允许保留索引的重新授权。
                val saved = JSONObject().put("api_id", id).put("api_hash", hash)
                if (database.ownerID() == 0L && database.count() > 0) saved.put("owner_unconfirmed", true)
                val replacement = create(saved)
                try { vault.write(saved) } catch (failure: Exception) { replacement.close(); throw failure }
                engine?.close(); engine = replacement; startupError = null
                accountVerified = false; accountIssue = null; legacyOwnershipPending = false; trustExistingSession = false; savedSession = ""
            } catch (failure: Exception) { error = failure.message ?: "无法保存 API 凭证，请检查填写内容" }
            busy = false; main.post { finished(error) }
        }
    }

    fun credentials(): JSONObject = try {
        val saved = vault.read()
        JSONObject().put("api_id", saved.optLong("api_id")).put("api_hash", saved.optString("api_hash"))
    } catch (_: Exception) { JSONObject() }

    // long: 每页先提交视频和原始消息游标，再请求下一页；进程退出或用户取消后可从最后完整页恢复。
    fun scanChannel(core: Engine, channelID: Long, refresh: Boolean,
        report: (String, Long?) -> Unit = { _, _ -> }): String {
        var found = 0L
        singleScanning = true
        scanMessage = "正在连接频道"
        core.updateScanProgress(0, true)
        try {
            return ChannelIndexer(database).scan(channelID, refresh, { scanCancelled }, ::requireMediaAccount,
                { offset, minID -> JSONObject(core.scanPage(channelID, offset, minID)) }) { read, added, total ->
                found = read
                core.updateScanProgress(found, true)
                scanMessage = if (refresh) "更新中 · 新增 $added 个" else "扫描中 · 已读取 $found 个"
                report(scanMessage, total)
            }
        } finally { core.updateScanProgress(found, false); singleScanning = false }
    }

    // long: 在通知出现前开启任务，之后到达的暂停不会被工作线程入口重置。
    fun prepareScan() { scanCancelled = false; engine?.prepareScan() }
    fun cancelScan() { scanQueue.cancel(); scanCancelled = true; engine?.cancelScan() }

    fun recheckChannel(core: Engine, channelID: Long, report: (String, Long?) -> Unit = { _, _ -> }): String {
        requireMediaAccount()
        var after = database.channel(channelID)?.optString("recheck_key").orEmpty()
        var checked = 0L; singleScanning = true
        try {
            if (scanCancelled) return "paused"
            scanMessage = "正在校验频道信息"
            val channel = try { JSONObject(core.refreshChannel(channelID)) }
                catch (failure: Exception) { if (scanCancelled) return "paused" else throw failure }
            database.addChannel(channel)
            while (!scanCancelled) {
                val rows = database.recheckBatch(channelID, after)
                if (rows.length() == 0) { database.completeRecheck(channelID); return "checked" }
                val ids = JSONArray().apply { for (i in 0 until rows.length()) put(rows.getJSONObject(i).getLong("message_id")) }
                val result = try { JSONObject(core.recheckMessages(channelID, ids.toString())) }
                    catch (failure: Exception) { if (scanCancelled) return "paused" else throw failure }
                after = rows.getJSONObject(rows.length() - 1).getString("key")
                database.applyRecheck(channelID, ids, result, after)
                checked += rows.length(); scanMessage = "已校验 $checked 条资源，确认的变更已保存"
                report("校验中 · 已检查 $checked 条", database.count(channelID))
            }
            return "paused"
        } finally { singleScanning = false }
    }

    fun executeScanQueue(core: Engine) {
        scanQueue.execute { task, report ->
            val value = if (task.mode == ScanMode.RECHECK) recheckChannel(core, task.channelID, report)
                else scanChannel(core, task.channelID, task.mode == ScanMode.UPDATE, report)
            if (value == "paused") scanQueue.cancel()
            val added = if (value.startsWith("{")) JSONObject(value).optLong("scan_added") else 0L
            val label = when (task.mode) {
                ScanMode.UPDATE -> if (added > 0) "新增 $added 个视频" else "已是最新"
                ScanMode.HISTORY -> "历史扫描完成"
                ScanMode.RECHECK -> "资源校验完成"
            }
            ChannelScanResult(label, database.count(task.channelID))
        }
        scanMessage = scanQueue.snapshot.summary
    }

    fun recoverAuthorization(finished: (String?) -> Unit) {
        // long: 只重置损坏授权，保留媒体库归属；未绑定的旧索引必须由用户明确退出后清理。
        if (busy) { finished("请等待当前操作结束"); return }
        busy = true
        worker.execute {
            var failure: String? = null
            try {
                val saved = runCatching { vault.read() }.getOrDefault(JSONObject()).apply {
                    remove("session")
                    if (database.ownerID() == 0L && database.count() > 0) put("owner_unconfirmed", true)
                }
                val replacement = create(saved)
                try { vault.reset(saved) } catch (e: Exception) { replacement.close(); throw e }
                engine?.close(); engine = replacement; savedSession = ""; trustExistingSession = false
                accountVerified = false; accountIssue = null; startupError = null
            } catch (_: Exception) { failure = "无法重置授权，请重试" }
            busy = false; main.post { finished(failure) }
        }
    }

    fun logout(finished: (String?) -> Unit) {
        PlaybackService.stopPlayback(this, revokeSession = true)
        cancelScan()
        if (busy) { finished("正在取消操作，请稍后再退出"); return }
        busy = true
        worker.execute {
            var error: String? = null
            try { engine?.close(); engine = null; vault.clear(); database.clear(); scanQueue.clear(); engine = create(JSONObject()); startupError = null }
            catch (_: Exception) { error = "账号清理未完成，请重试" }
            accountVerified = false; accountIssue = null; legacyOwnershipPending = false; trustExistingSession = false; savedSession = ""
            busy = false; main.post { finished(error) }
        }
    }

    fun validationMedia(core: Engine = engine ?: error("播放内核尚未恢复")): String {
        val target = File(cacheDir, "validation.mp4")
        if (!target.exists()) assets.open("validation.mp4").use { input -> target.outputStream().use { input.copyTo(it) } }
        return core.mountValidationMedia(target.absolutePath)
    }
}
