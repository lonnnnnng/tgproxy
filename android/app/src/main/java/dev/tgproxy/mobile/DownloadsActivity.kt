package dev.tgproxy.mobile

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors

class DownloadsActivity : AppCompatActivity() {
    private val app get() = application as TgApplication
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var ongoing: MaterialButton
    private lateinit var completed: MaterialButton
    private val handler = Handler(Looper.getMainLooper())
    private val exports = Executors.newSingleThreadExecutor()
    private var completeOnly = false
    private var exportID: String? = null
    private var lastRevision = -1L
    private val rows = mutableMapOf<String, Row>()
    private data class Row(val status: TextView, val detail: TextView, val progress: LinearProgressIndicator, val action: MaterialButton)
    private val save = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val id = exportID; exportID = null
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && id != null && uri != null) {
            message("正在另存文件…")
            exports.execute {
                val outcome = runCatching {
                    val source = app.downloads.store.completed(id) ?: error("文件已移除")
                    contentResolver.openOutputStream(uri, "w")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                        ?: error("无法打开保存位置")
                }
                runOnUiThread { if (!isDestroyed) message(if (outcome.isSuccess) "文件已另存" else "另存失败，请检查目标空间后重试") }
            }
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (app.downloads.revision != lastRevision) render()
            else updateRows(app.downloads.records)
            handler.postDelayed(this, 700)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        completeOnly = savedInstanceState?.getBoolean("complete") ?: intent.getBooleanExtra("complete", false)
        exportID = savedInstanceState?.getString("export")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color(com.google.android.material.R.attr.colorSurface)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(12), 0) }
        toolbar.addView(icon(R.drawable.ic_chevron_left, "返回我的") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(text("下载管理", 15).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(button("全部暂停") { app.downloads.pauseAll(finished = ::result) })
        root.addView(toolbar)
        summary = text("", 11, true).apply { setPadding(dp(20), dp(2), dp(20), dp(10)) }
        root.addView(summary)
        val tabs = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), 0) }
        ongoing = button("进行中") { completeOnly = false; lastRevision = -1; render() }
        completed = button("已完成") { completeOnly = true; lastRevision = -1; render() }
        tabs.addView(ongoing, LinearLayout.LayoutParams(0, dp(48), 1f)); tabs.addView(completed, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(tabs)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(16)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(text("下载保存在本机，卸载 App 会删除。已完成文件可在更多菜单中另存。", 11, true).apply {
            setPadding(dp(20), dp(10), dp(20), dp(12))
        })
        render()
    }
    override fun onStart() { super.onStart(); app.downloads.refresh(); handler.post(poll) }
    override fun onStop() { handler.removeCallbacks(poll); super.onStop() }
    override fun onDestroy() { exports.shutdown(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("complete", completeOnly); outState.putString("export", exportID); super.onSaveInstanceState(outState) }
    private fun render() {
        val items = app.downloads.records
        val visible = items.filter { (it.state == DownloadState.COMPLETE) == completeOnly }
        val ids = visible.map { it.id }
        if (rows.keys.toList() != ids || lastRevision == -1L) {
            list.removeAllViews(); rows.clear()
            visible.forEach { item -> addRow(item) }
            if (visible.isEmpty()) list.addView(text(if (completeOnly) "还没有下载完成的视频" else "暂无下载任务\n在资源详情页点击“下载视频”即可加入", 13, true).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(52), 0, dp(40)); setLineSpacing(dp(8).toFloat(), 1f)
            })
        }
        lastRevision = app.downloads.revision
        updateRows(items)
    }
    private fun addRow(item: DownloadRecord) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(8)) }
        row.addView(text(item.name, 13).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        val status = text("", 11, true).apply { setPadding(0, dp(5), 0, 0) }; row.addView(status)
        val progress = LinearProgressIndicator(this).apply { max = 100; trackThickness = dp(2) }
        row.addView(progress, LinearLayout.LayoutParams(-1, dp(2)).apply { topMargin = dp(8) })
        val bottom = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val detail = text("", 11, true)
        bottom.addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
        val action = button("") { operate(item.id) }
        bottom.addView(action)
        bottom.addView(icon(R.drawable.ic_more, "${item.name} 下载选项") { more(it, item.id) }, LinearLayout.LayoutParams(dp(40), dp(48)))
        row.addView(bottom); list.addView(row)
        list.addView(View(this).apply { setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant)) }, LinearLayout.LayoutParams(-1, dp(1)))
        rows[item.id] = Row(status, detail, progress, action)
    }
    private fun updateRows(items: List<DownloadRecord>) {
        val done = items.count { it.state == DownloadState.COMPLETE }
        summary.text = "${items.size - done} 项未完成 · $done 项已完成 · 占用 ${MediaPresentation.size(items.sumOf { it.bytes })}"
        ongoing.text = "进行中 ${items.size - done}"; ongoing.isSelected = !completeOnly
        completed.text = "已完成 $done"; completed.isSelected = completeOnly
        ongoing.alpha = if (completeOnly) .6f else 1f; completed.alpha = if (completeOnly) 1f else .6f
        items.forEach { item -> rows[item.id]?.let { row ->
            row.status.text = DownloadPresentation.status(item) + if (item.error.isNotBlank()) " · ${item.error}" else ""
            row.status.setTextColor(color(if (item.state == DownloadState.FAILED) com.google.android.material.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant))
            row.detail.text = DownloadPresentation.detail(item) + if (item.state == DownloadState.RUNNING) " · ${MediaPresentation.size(app.downloads.bytesPerSecond)}/秒" else ""
            row.progress.progress = (item.bytes * 100 / item.size.coerceAtLeast(1)).toInt()
            row.progress.visibility = if (item.state == DownloadState.COMPLETE) View.GONE else View.VISIBLE
            row.action.text = when (item.state) {
                DownloadState.RUNNING, DownloadState.QUEUED -> "暂停"
                DownloadState.PAUSED -> "继续"
                DownloadState.FAILED -> "重试"
                DownloadState.COMPLETE -> "播放"
                DownloadState.DELETING -> "删除中"
            }
            row.action.contentDescription = "${row.action.text}下载：${item.name}"
            row.action.isEnabled = item.state != DownloadState.DELETING
        } }
    }
    private fun operate(id: String) {
        val item = app.downloads.records.firstOrNull { it.id == id } ?: return
        when (item.state) {
            DownloadState.RUNNING, DownloadState.QUEUED -> app.downloads.pause(id, ::result)
            DownloadState.COMPLETE -> startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", "download:$id").putExtra("title", item.name))
            else -> app.downloads.resume(id, ::result)
        }
    }
    private fun more(anchor: View, id: String) {
        val item = app.downloads.records.firstOrNull { it.id == id } ?: return
        PopupMenu(this, anchor).apply {
            if (item.state == DownloadState.COMPLETE) menu.add("另存到手机目录").setOnMenuItemClickListener {
                exportID = id
                save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(item.mime).putExtra(Intent.EXTRA_TITLE, DownloadPresentation.safeName(item)))
                true
            }
            menu.add(if (item.state == DownloadState.COMPLETE) "删除下载文件" else "取消并删除任务").setOnMenuItemClickListener {
                MaterialAlertDialogBuilder(this@DownloadsActivity).setTitle("删除这个下载？")
                    .setMessage("删除本机下载文件和断点，频道资源不受影响。已经另存的文件会保留。")
                    .setNegativeButton("保留", null).setPositiveButton("删除") { _, _ ->
                        if (PlaybackService.currentKey == "download:$id") PlaybackService.stopPlayback(this@DownloadsActivity)
                        app.downloads.remove(id, ::result)
                    }.show(); true
            }
            show()
        }
    }
    private fun result(error: String?) { if (error != null) message(error) else render() }
    private fun message(value: String) { Snackbar.make(root, value, Snackbar.LENGTH_LONG).show() }
    private fun text(value: String, size: Int, muted: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color(if (muted) com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorOnSurface))
    }
    private fun button(label: String, action: () -> Unit) = MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = label; textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0; minHeight = dp(48)
        setOnClickListener { action() }
    }
    private fun icon(resource: Int, description: String, action: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(resource); imageTintList = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
        val value = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        setBackgroundResource(value.resourceId); contentDescription = description; setOnClickListener { action(this) }
    }
    private fun color(attr: Int) = MaterialColors.getColor(this, attr, "TGProxy")
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
