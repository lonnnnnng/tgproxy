package dev.tgproxy.mobile

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject

class VideoDetailActivity : AppCompatActivity() {
    private val app get() = application as TgApplication
    private lateinit var content: LinearLayout
    private var video: JSONObject? = null
    private val queries = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var playButton: MaterialButton? = null
    private var downloadButton: MaterialButton? = null
    private var downloadNote: TextView? = null
    private var owner = 0L
    private var lastLocalID: String? = null
    private var pendingDownload: String? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        pendingDownload?.let(::enqueueDownload); pendingDownload = null
    }
    private val poll = object : Runnable {
        override fun run() { refreshDownload(); handler.postDelayed(this, 800) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDownload = savedInstanceState?.getString("pendingDownload")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(com.google.android.material.R.attr.colorSurface))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        root.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_chevron_left)
            imageTintList = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurface))
            val ripple = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            contentDescription = "返回视频列表"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24)) }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(text("正在读取视频信息…", 13, true))
        val key = intent.getStringExtra("key") ?: ""
        queries.execute {
            val loaded = runCatching {
                val item = app.database.video(key)
                owner = app.database.ownerID()
                Triple(item, item?.let { app.database.channel(it.optLong("channel_id"))?.optString("title") } ?: "Telegram 频道", item?.let { app.database.videos(limit = 5, channelID = it.optLong("channel_id")) } ?: org.json.JSONArray())
            }
            runOnUiThread {
                if (!isDestroyed) {
                    content.removeAllViews()
                    loaded.onSuccess { (item, channel, related) ->
                        video = item
                        if (item == null) content.addView(text("视频已从本机索引移除", 16)) else renderVideo(root, item, channel, related)
                    }.onFailure { content.addView(text("读取视频失败，请返回后重试", 14)) }
                }
            }
        }
    }

    private fun renderVideo(root: View, item: JSONObject, channel: String, related: org.json.JSONArray) {
        content.addView(text(item.optString("name", "未命名视频"), 20).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextIsSelectable(true); setLineSpacing(dp(3).toFloat(), 1f)
            ViewCompat.setAccessibilityHeading(this, true)
        })
        content.addView(text(channel, 13, true).apply { setPadding(0, dp(10), 0, dp(24)) })
        metadata("文件大小", MediaPresentation.size(item.optLong("size")))
        metadata("文件格式", item.optString("mime_type").ifEmpty { item.optString("mime") })
        metadata("发布时间", MediaPresentation.published(item.optLong("date"), full = true))
        val key = item.getString("key")
        playButton = MaterialButton(this).apply {
            isAllCaps = false; setIconResource(R.drawable.ic_play)
            setOnClickListener {
                val local = downloadRecord()?.takeIf { it.state == DownloadState.COMPLETE }
                if (local != null) {
                    startActivity(Intent(this@VideoDetailActivity, PlayerActivity::class.java).putExtra("key", "download:${local.id}").putExtra("title", item.optString("name")))
                } else if (!app.mediaReady() || app.engine?.let { JSONObject(it.status()).optBoolean("connected") } != true) {
                    Snackbar.make(root, "先连接 Telegram，再播放视频", Snackbar.LENGTH_LONG).show()
                } else startActivity(Intent(this@VideoDetailActivity, PlayerActivity::class.java).putExtra("key", key).putExtra("title", item.optString("name")))
            }
        }
        content.addView(playButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(20) })
        downloadButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false; text = "下载视频"; setIconResource(R.drawable.ic_download); iconSize = dp(18)
            setOnClickListener {
                val record = downloadRecord()
                if (record?.state in listOf(DownloadState.RUNNING, DownloadState.QUEUED, DownloadState.COMPLETE)) {
                    startActivity(Intent(this@VideoDetailActivity, DownloadsActivity::class.java).putExtra("complete", record?.state == DownloadState.COMPLETE))
                } else if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    pendingDownload = key; notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else enqueueDownload(key)
            }
        }
        content.addView(downloadButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        downloadNote = text("下载后可离线播放，在“我的 → 下载管理”中查看。", 11, true).apply { setPadding(0, dp(4), 0, dp(6)) }
        content.addView(downloadNote)
        content.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "查看此频道的视频"; isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@VideoDetailActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("browse_channel", item.optLong("channel_id")))
                finish()
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        playButton?.text = "播放视频"; refreshPosition(); refreshDownload()
        val rows = (0 until related.length()).map(related::getJSONObject).filter { it.optString("key") != key }.take(4)
        if (rows.isNotEmpty()) {
            content.addView(text("同频道视频", 12, true).apply { setPadding(0, dp(28), 0, dp(8)) })
            rows.forEach { other ->
                content.addView(text(other.optString("name"), 13).apply {
                    minHeight = dp(52); gravity = Gravity.CENTER_VERTICAL; maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, dp(8))
                    val ripple = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                    setBackgroundResource(ripple.resourceId); isFocusable = true
                    setOnClickListener { startActivity(Intent(this@VideoDetailActivity, VideoDetailActivity::class.java).putExtra("key", other.getString("key"))); finish() }
                })
                content.addView(View(this).apply { setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant)) }, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
    }

    override fun onResume() { super.onResume(); refreshPosition(); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }
    override fun onDestroy() { queries.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("pendingDownload", pendingDownload); super.onSaveInstanceState(outState) }
    private fun downloadRecord(): DownloadRecord? {
        val item = video ?: return null
        return app.downloads.records.firstOrNull { it.owner == owner && it.key == item.optString("key") && it.document == item.optLong("document_id") }
    }
    private fun enqueueDownload(key: String) {
        downloadButton?.isEnabled = false
        app.downloads.enqueue(key) { error ->
            if (!isDestroyed) {
                downloadButton?.isEnabled = true; refreshDownload()
                Snackbar.make(content, error ?: "已加入下载，可在“我的 → 下载管理”查看", Snackbar.LENGTH_LONG).show()
            }
        }
    }
    private fun refreshDownload() {
        val item = downloadRecord()
        val localID = item?.takeIf { it.state == DownloadState.COMPLETE }?.id
        if (localID != lastLocalID) { lastLocalID = localID; refreshPosition() }
        downloadButton?.text = when (item?.state) {
            DownloadState.RUNNING -> "下载中 · ${item.bytes * 100 / item.size.coerceAtLeast(1)}% · 查看"
            DownloadState.QUEUED -> "等待下载 · 查看"
            DownloadState.COMPLETE -> "已下载 · 管理下载"
            DownloadState.PAUSED -> "继续下载"
            DownloadState.FAILED -> "重试下载"
            DownloadState.DELETING -> "正在删除…"
            null -> "下载视频"
        }
        downloadButton?.contentDescription = "${downloadButton?.text}：${video?.optString("name").orEmpty()}"
        downloadNote?.text = item?.let { "${DownloadPresentation.status(it)} · ${DownloadPresentation.detail(it)}" }
            ?: "下载后可离线播放，在“我的 → 下载管理”中查看。"
    }
    private fun refreshPosition() {
        val key = video?.optString("key") ?: return
        queries.execute {
            // long: 已完成下载优先离线播放，按钮上的断点必须来自同一个本机文件。
            val local = downloadRecord()?.takeIf { it.state == DownloadState.COMPLETE }
            val position = local?.let { app.downloads.store.get(it.id)?.position } ?: app.database.position(key)
            runOnUiThread { if (!isDestroyed) playButton?.text = if (position > 0) "继续播放 · ${MediaPresentation.position(position)}" else "播放视频" }
        }
    }
    private fun metadata(label: String, value: String) {
        if (value.isBlank()) return
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(36)
            addView(text(label, 12, true), LinearLayout.LayoutParams(dp(80), -2))
            addView(text(value, 12).apply { setTextIsSelectable(true) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
    }
    private fun text(value: String, size: Int, muted: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat()
        setTextColor(color(if (muted) com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorOnSurface))
    }
    private fun color(attr: Int) = MaterialColors.getColor(this, attr, "TGProxy")
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
