package dev.tgproxy.mobile

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors

class DownloadsActivity : AppCompatActivity() {
    private val app get() = application as TgApplication
    private lateinit var root: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var layout: LinearLayoutManager
    private lateinit var empty: TextView
    private lateinit var summary: TextView
    private lateinit var ongoing: MaterialButton
    private lateinit var completed: MaterialButton
    private lateinit var bulk: MaterialButton
    private lateinit var moreRows: MaterialButton
    private lateinit var exportBox: LinearLayout
    private lateinit var exportName: TextView
    private lateinit var exportSummary: TextView
    private lateinit var exportProgress: LinearProgressIndicator
    private lateinit var exportAction: MaterialButton
    private val handler = Handler(Looper.getMainLooper())
    private val queries = Executors.newSingleThreadExecutor()
    private val adapter = DownloadAdapter()
    private var completeOnly = false
    private var exportID: String? = null
    private var lastRevision = -1L
    private var lastProgress = -1L
    private var renderRequest = 0
    private var visibleLimit = PAGE_SIZE
    private var filteredCount = 0
    private var restoreLayout: Parcelable? = null
    private val save = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val id = exportID; exportID = null
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && id != null && uri != null) {
            // long: 保存位置交给应用级任务，页面旋转或离开后仍能取消并看到最终结果。
            app.exports.enqueue(id, uri, result.data?.flags ?: 0) { error ->
                if (!isDestroyed) { message(error ?: "已加入另存队列"); updateExports() }
            }
        }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (app.downloads.revision != lastRevision) render()
            updateChrome()
            if (app.downloads.progressRevision != lastProgress) {
                lastProgress = app.downloads.progressRevision
                val index = app.downloads.activeRecord?.id?.let(adapter::positionOf) ?: -1
                val first = layout.findFirstVisibleItemPosition()
                val last = layout.findLastVisibleItemPosition()
                // long: 只有当前下载且位于屏幕上的行更新进度；离屏行重新绑定时会读到最新样本。
                if (index >= 0 && first >= 0 && index in first..last) adapter.notifyItemChanged(index, PROGRESS)
            }
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        completeOnly = savedInstanceState?.getBoolean("complete") ?: intent.getBooleanExtra("complete", false)
        exportID = savedInstanceState?.getString("export")
        visibleLimit = savedInstanceState?.getInt("limit", PAGE_SIZE)?.coerceAtLeast(PAGE_SIZE) ?: PAGE_SIZE
        @Suppress("DEPRECATION")
        val savedLayout = savedInstanceState?.getParcelable<Parcelable>("download_scroll")
        restoreLayout = savedLayout
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color(com.google.android.material.R.attr.colorSurface)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(12), 0) }
        toolbar.addView(icon(R.drawable.ic_chevron_left, "返回我的") { finish() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(text("下载管理", 15).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        bulk = button("全部继续") {
            if (app.downloads.stats.active > 0) app.downloads.pauseAll(finished = ::result) else app.downloads.resumeAll(finished = ::result)
        }
        toolbar.addView(icon(R.drawable.ic_more, "下载和另存操作") { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add("另存记录").setOnMenuItemClickListener { showExportHistory(); true }
                menu.add("全部继续").setOnMenuItemClickListener { app.downloads.resumeAll(finished = ::result); true }
                menu.add("重试失败任务").setOnMenuItemClickListener { app.downloads.resumeAll(failedOnly = true, finished = ::result); true }
                menu.add("全部暂停").setOnMenuItemClickListener { app.downloads.pauseAll(finished = ::result); true }
                show()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(toolbar)
        summary = text("", 11, true).apply {
            setPadding(0, dp(2), dp(8), dp(10))
            setOnClickListener { if (app.downloads.problem != null) app.downloads.refresh() }
        }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(12), 0)
            addView(summary, LinearLayout.LayoutParams(0, -2, 1f)); addView(bulk)
        })
        exportBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        exportName = text("", 12).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        exportSummary = text("", 11, true).apply { setPadding(0, dp(4), 0, dp(4)) }
        exportProgress = LinearProgressIndicator(this).apply { max = 100; trackThickness = dp(2) }
        exportAction = button("") {
            if (app.exports.problem != null) app.exports.refresh()
            else app.exports.latest?.let { item ->
                when {
                    ExportPresentation.pending(item) -> app.exports.cancel(item.id, ::exportResult)
                    item.state == ExportState.COMPLETE -> openExport(item)
                    else -> requestExport(item.download)
                }
            }
        }
        exportBox.addView(exportName); exportBox.addView(exportSummary)
        exportBox.addView(exportProgress, LinearLayout.LayoutParams(-1, dp(2)))
        exportBox.addView(LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(button("另存记录") { showExportHistory() }); addView(exportAction)
        })
        root.addView(exportBox)
        val tabs = LinearLayout(this).apply { setPadding(dp(12), 0, dp(12), 0) }
        ongoing = button("进行中") { selectTab(false) }
        completed = button("已完成") { selectTab(true) }
        tabs.addView(ongoing, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(completed, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(tabs)
        layout = LinearLayoutManager(this)
        list = RecyclerView(this).apply {
            id = R.id.download_list
            layoutManager = layout; adapter = this@DownloadsActivity.adapter
            itemAnimator = null
            clipToPadding = false; setPadding(dp(20), 0, dp(20), dp(16))
        }
        empty = text("正在读取下载记录…", 13, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(20), dp(52), dp(20), dp(40))
        }
        root.addView(FrameLayout(this).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(empty, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        moreRows = button("显示更多") { visibleLimit += PAGE_SIZE; render() }
        root.addView(moreRows, LinearLayout.LayoutParams(-1, -2))
        root.addView(text("下载保存在本机，卸载 App 会删除。已完成文件可在更多菜单中另存。", 11, true).apply {
            setPadding(dp(20), dp(10), dp(20), dp(12))
        })
        render()
    }

    private fun selectTab(complete: Boolean) {
        completeOnly = complete; visibleLimit = PAGE_SIZE; restoreLayout = null
        list.scrollToPosition(0); render()
    }
    override fun onStart() { super.onStart(); app.downloads.refresh(); app.exports.refresh(); handler.post(poll) }
    override fun onStop() { handler.removeCallbacks(poll); super.onStop() }
    override fun onDestroy() { renderRequest++; queries.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("complete", completeOnly); outState.putString("export", exportID)
        outState.putInt("limit", visibleLimit); outState.putParcelable("download_scroll", layout.onSaveInstanceState())
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val source = app.downloads.stateRecords
        val revision = app.downloads.revision
        val complete = completeOnly
        val limit = visibleLimit
        val request = ++renderRequest
        lastRevision = revision
        // long: 历史记录在后台筛选并分批展示，进度样本不会触发过滤或重建整份列表。
        queries.execute {
            val matching = source.filter { (it.state == DownloadState.COMPLETE) == complete }
            val page = matching.take(limit)
            handler.post {
                if (isDestroyed || request != renderRequest || complete != completeOnly) return@post
                filteredCount = matching.size
                adapter.submitList(page) {
                    if (!isDestroyed && request == renderRequest) {
                        restoreLayout?.let { layout.onRestoreInstanceState(it); restoreLayout = null }
                        updateChrome()
                    }
                }
            }
        }
    }

    private fun updateChrome() {
        val stats = app.downloads.stats
        summary.text = app.downloads.problem ?: "${stats.total - stats.completed} 项未完成 · ${stats.completed} 项已完成 · 占用 ${MediaPresentation.size(stats.bytes)}"
        summary.contentDescription = if (app.downloads.problem != null) "${summary.text}，重新读取下载记录" else summary.text
        summary.isClickable = app.downloads.problem != null
        summary.isFocusable = summary.isClickable; summary.minimumHeight = if (summary.isClickable) dp(48) else 0
        bulk.text = if (stats.active > 0) "全部暂停" else "全部继续"
        bulk.isEnabled = app.downloads.ready && (stats.active > 0 || stats.resumable > 0)
        ongoing.text = "进行中 ${stats.total - stats.completed}"; ongoing.isSelected = !completeOnly
        completed.text = "已完成 ${stats.completed}"; completed.isSelected = completeOnly
        ongoing.alpha = if (completeOnly) .6f else 1f; completed.alpha = if (completeOnly) 1f else .6f
        empty.text = if (!app.downloads.ready) "正在读取下载记录…" else if (completeOnly) "还没有下载完成的视频" else "暂无下载任务\n在资源详情页点击“下载视频”即可加入"
        empty.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
        moreRows.visibility = if (adapter.itemCount < filteredCount) View.VISIBLE else View.GONE
        moreRows.text = "显示更多 · ${adapter.itemCount} / $filteredCount"
        updateExports()
    }

    private fun updateExports() {
        val item = app.exports.latest
        val problem = app.exports.problem
        exportBox.visibility = if (item != null || problem != null) View.VISIBLE else View.GONE
        exportName.text = if (problem != null) "另存视频" else item?.name.orEmpty()
        exportSummary.text = problem ?: item?.let { ExportPresentation.detail(it) + if (it.error.isNotBlank()) "\n${it.error}" else "" }.orEmpty()
        exportSummary.setTextColor(color(if (problem != null || item?.state == ExportState.FAILED) com.google.android.material.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant))
        exportProgress.visibility = if (item != null && ExportPresentation.pending(item)) View.VISIBLE else View.GONE
        exportProgress.progress = item?.let { (it.bytes.toDouble() / it.size.coerceAtLeast(1) * 100).toInt().coerceIn(0, 100) } ?: 0
        exportAction.text = when {
            problem != null -> "重新读取"
            item?.state == ExportState.CANCELLING -> "取消中"
            item != null && ExportPresentation.pending(item) -> "取消另存"
            item?.state == ExportState.COMPLETE -> "打开副本"
            else -> "重新选位置"
        }
        exportAction.isEnabled = problem != null || (item?.valid == true && item.state != ExportState.CANCELLING)
        exportAction.contentDescription = "${exportAction.text}：${item?.name ?: "另存记录"}"
    }

    private fun showExportHistory(page: Int = 0) {
        app.exports.history(page) { records, error ->
            if (isDestroyed) return@history
            if (error != null) { message(error); return@history }
            if (records.isEmpty()) { message(if (page == 0) "还没有另存记录" else "没有更多另存记录"); return@history }
            val dialog = MaterialAlertDialogBuilder(this).setTitle(if (page == 0) "另存记录" else "另存记录 · 第 ${page + 1} 页")
                .setItems(records.map { "${it.name}\n${ExportPresentation.detail(it)}" }.toTypedArray()) { _, index ->
                    showExport(app.exports.current?.takeIf { it.id == records[index].id } ?: records[index])
                }.setNegativeButton("关闭", null)
            if (page > 0) dialog.setNeutralButton("上一页") { _, _ -> showExportHistory(page - 1) }
            if (records.size == PAGE_SIZE) dialog.setPositiveButton("下一页") { _, _ -> showExportHistory(page + 1) }
            dialog.show()
        }
    }

    private fun showExport(item: ExportRecord) {
        val dialog = MaterialAlertDialogBuilder(this).setTitle(item.name)
            .setMessage(ExportPresentation.detail(item) + if (item.error.isNotBlank()) "\n\n${item.error}" else "")
            .setNegativeButton("关闭", null)
        if (item.valid && item.state != ExportState.CANCELLING) {
            when {
                ExportPresentation.pending(item) -> dialog.setPositiveButton("取消另存") { _, _ -> app.exports.cancel(item.id, ::exportResult) }
                item.state == ExportState.COMPLETE -> dialog.setPositiveButton("打开副本") { _, _ -> openExport(item) }
                else -> {
                    dialog.setPositiveButton("重新选位置") { _, _ -> requestExport(item.download) }
                    if (item.touched) dialog.setNeutralButton("清理副本") { _, _ ->
                        MaterialAlertDialogBuilder(this).setTitle("清理未完成的副本？")
                            .setMessage("删除这次另存写入的目标文件。视频「${item.name}」的本机下载会保留。")
                            .setNegativeButton("保留", null).setPositiveButton("清理") { _, _ -> app.exports.cleanup(item.id, ::exportResult) }.show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun openExport(item: ExportRecord) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.uri), item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            .onFailure { message("无法打开副本，请在文件管理器中查看，或重新选择保存位置") }
    }

    private fun requestExport(id: String) {
        val item = app.downloads.record(id)?.takeIf { it.valid && it.state == DownloadState.COMPLETE }
            ?: return message("源视频尚未下载完成或已移除")
        exportID = id
        save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            .setType(item.mime).putExtra(Intent.EXTRA_TITLE, DownloadPresentation.safeName(item)))
    }
    private fun exportResult(error: String?) { if (!isDestroyed) { if (error != null) message(error); updateExports() } }

    private class Row(view: View, val name: TextView, val status: TextView, val detail: TextView,
        val progress: LinearProgressIndicator, val action: MaterialButton, val more: ImageButton) : RecyclerView.ViewHolder(view) {
        var id = ""
    }
    private inner class DownloadAdapter : ListAdapter<DownloadRecord, Row>(DIFF) {
        private var positions = emptyMap<String, Int>()
        init { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        fun positionOf(id: String) = positions[id] ?: -1
        override fun onCurrentListChanged(previousList: List<DownloadRecord>, currentList: List<DownloadRecord>) {
            positions = currentList.mapIndexed { index, item -> item.id to index }.toMap()
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row {
            val box = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                setPadding(0, dp(12), 0, dp(8))
            }
            val name = text("", 13).apply { setTypeface(typeface, Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            val status = text("", 11, true).apply { setPadding(0, dp(5), 0, 0) }
            val progress = LinearProgressIndicator(this@DownloadsActivity).apply { max = 100; trackThickness = dp(2) }
            val bottom = LinearLayout(parent.context).apply { gravity = Gravity.CENTER_VERTICAL }
            val detail = text("", 11, true)
            val action = button("") {}
            box.addView(name); box.addView(status)
            box.addView(progress, LinearLayout.LayoutParams(-1, dp(2)).apply { topMargin = dp(8) })
            // long: 大字体或窄窗口把大小/速度独立成行，让操作按钮保持完整触控宽度。
            if (resources.configuration.fontScale > 1.2f || resources.configuration.screenWidthDp < 360) {
                box.addView(detail); bottom.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            } else bottom.addView(detail, LinearLayout.LayoutParams(0, -2, 1f))
            bottom.addView(action)
            val moreButton = icon(R.drawable.ic_more, "下载选项") {}
            val row = Row(box, name, status, detail, progress, action, moreButton)
            action.setOnClickListener { operate(row.id) }
            moreButton.setOnClickListener { more(it, row.id) }
            bottom.addView(moreButton, LinearLayout.LayoutParams(dp(48), dp(48)))
            box.addView(bottom)
            box.addView(View(parent.context).apply { setBackgroundColor(color(com.google.android.material.R.attr.colorOutlineVariant)) }, LinearLayout.LayoutParams(-1, dp(1)))
            return row
        }
        override fun onBindViewHolder(holder: Row, position: Int) {
            val item = app.downloads.record(getItem(position).id) ?: getItem(position)
            holder.id = item.id; holder.name.text = item.name
            holder.more.contentDescription = "下载选项：${item.name}"
            bindState(holder, item)
        }
        override fun onBindViewHolder(holder: Row, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position)
            else bindState(holder, app.downloads.record(getItem(position).id) ?: getItem(position))
        }
    }
    private fun bindState(row: Row, item: DownloadRecord) {
        row.status.text = DownloadPresentation.status(item) + if (item.error.isNotBlank()) " · ${item.error}" else ""
        row.status.setTextColor(color(if (item.state == DownloadState.FAILED) com.google.android.material.R.attr.colorError else com.google.android.material.R.attr.colorOnSurfaceVariant))
        row.detail.text = DownloadPresentation.detail(item) + if (item.state == DownloadState.RUNNING) " · ${MediaPresentation.size(app.downloads.bytesPerSecond)}/秒" else ""
        row.progress.progress = (item.bytes.toDouble() / item.size.coerceAtLeast(1) * 100).coerceIn(0.0, 100.0).toInt()
        row.progress.visibility = if (item.state == DownloadState.COMPLETE) View.GONE else View.VISIBLE
        row.action.text = when (item.state) {
            DownloadState.RUNNING, DownloadState.QUEUED -> "暂停"
            DownloadState.PAUSED -> "继续"
            DownloadState.FAILED -> "重试"
            DownloadState.COMPLETE -> "播放"
            DownloadState.DELETING -> "删除中"
        }
        row.action.contentDescription = "${row.action.text}下载：${item.name}"
        row.action.isEnabled = item.state != DownloadState.DELETING && item.valid
    }

    private fun operate(id: String) {
        val item = app.downloads.record(id) ?: return
        when (item.state) {
            DownloadState.RUNNING, DownloadState.QUEUED -> app.downloads.pause(id, ::result)
            DownloadState.COMPLETE -> startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", "download:$id").putExtra("title", item.name).putExtra("queue_offline", true))
            else -> app.downloads.resume(id, ::result)
        }
    }
    private fun more(anchor: View, id: String) {
        val item = app.downloads.record(id) ?: return
        PopupMenu(this, anchor).apply {
            if (item.state == DownloadState.COMPLETE) menu.add("另存到手机目录").setOnMenuItemClickListener {
                requestExport(id)
                true
            }
            menu.add(if (item.state == DownloadState.COMPLETE) "删除下载文件" else "取消并删除任务").setOnMenuItemClickListener {
                MaterialAlertDialogBuilder(this@DownloadsActivity).setTitle("删除这个下载？")
                    .setMessage(if (app.downloads.store.canLocateFiles(id)) "删除本机下载文件和断点，频道资源不受影响。已经另存的文件会保留。"
                        else "记录路径已损坏，将只移除这条记录。无法定位的私有文件会保留。")
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
        text = label; textSize = 12f; isAllCaps = false; minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48)
        insetTop = 0; insetBottom = 0
        setOnClickListener { action() }
    }
    private fun icon(resource: Int, description: String, action: (View) -> Unit) = ImageButton(this).apply {
        setImageResource(resource); imageTintList = ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
        val value = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        setBackgroundResource(value.resourceId); contentDescription = description; setOnClickListener { action(this) }
    }
    private fun color(attr: Int) = MaterialColors.getColor(this, attr, "TGProxy")
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE_SIZE = 50
        private val PROGRESS = Any()
        private val DIFF = object : DiffUtil.ItemCallback<DownloadRecord>() {
            override fun areItemsTheSame(old: DownloadRecord, new: DownloadRecord) = old.id == new.id
            override fun areContentsTheSame(old: DownloadRecord, new: DownloadRecord) =
                old.state == new.state && old.bytes == new.bytes && old.error == new.error && old.name == new.name && old.size == new.size && old.valid == new.valid
        }
    }
}
