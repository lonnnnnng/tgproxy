package dev.longvideo.controls

import android.content.Context
import android.app.Dialog
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.provider.Settings
import android.util.AttributeSet
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** long: 控制层只接受 Media3 Player；媒体地址、鉴权、播放服务及其释放均由宿主负责。 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class VideoControlsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val preferences = PlaybackPreferences(context)
    private val video = inflate(context, R.layout.pc_player, this).findViewById<PlayerView>(R.id.pc_player)
    private val speedButton = video.findViewById<TextView>(androidx.media3.ui.R.id.exo_playback_speed)
    private val aspectButton = video.findViewById<TextView>(R.id.pc_ratio)
    private val titleView = video.findViewById<TextView>(R.id.pc_title)
    private val feedback = label().apply { visibility = GONE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val unlock = label().apply {
        text = context.getString(R.string.pc_unlock)
        contentDescription = text
        visibility = GONE
        isClickable = true; isFocusable = true
        setOnClickListener { setLocked(false) }
    }
    private val gestureSurface = GestureSurface(context)
    private var hostWindow: Window? = null
    private var initialBrightness: Float? = null
    private var selectedSpeed = preferences.speed
    private var boostedPlayer: Player? = null
    private var activeDialog: Dialog? = null
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val clearFeedback = Runnable { feedback.visibility = GONE }
    private val hideUnlock = Runnable { unlock.visibility = GONE }
    var isLocked = false
        private set
    var onBack: (() -> Unit)? = null
    var onRotate: (() -> Unit)? = null
    var onInfo: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
        set(value) { field = value; updateNavigation() }
    var onNext: (() -> Unit)? = null
        set(value) { field = value; updateNavigation() }
    var onQueue: (() -> Unit)? = null
    var onToggleContinuous: (() -> Unit)? = null
    var continuousPlaying = false
    private var previousAvailable = false
    private var nextAvailable = false
    fun setNavigation(previous: Boolean, next: Boolean) {
        previousAvailable = previous; nextAvailable = next; updateNavigation()
    }
    var title: CharSequence
        get() = titleView.text
        set(value) { titleView.text = value }
    var source: CharSequence = ""
        set(value) {
            field = value
            video.findViewById<TextView>(R.id.pc_source).apply { text = value; visibility = if (value.isEmpty()) GONE else VISIBLE }
        }

    var player: Player? = null
        set(value) {
            if (field === value) return
            cancelInteraction()
            field?.removeListener(listener)
            field = value
            video.player = value
            if (value != null) {
                selectedSpeed = preferences.speed
                if (value.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) value.setPlaybackSpeed(selectedSpeed)
                value.addListener(listener)
            }
            updateSpeed()
            applyAspect()
        }

    private val listener = object : Player.Listener {
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            // long: MediaController 和官方倍速菜单共用同一状态来源；长按期间的 2× 仅临时生效。
            if (boostedPlayer != null) return
            selectedSpeed = playbackParameters.speed
            preferences.speed = selectedSpeed
            updateSpeed()
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) { post { applyAspect() } }
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) { updateSpeed() }
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) endBoost() }
    }

    init {
        setBackgroundColor(Color.BLACK)
        video.setShowNextButton(false)
        video.setShowPreviousButton(false)
        video.setShowSubtitleButton(true)
        video.overlayFrameLayout?.addView(gestureSurface, LayoutParams(-1, -1))
        addView(feedback, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(88) })
        addView(unlock, LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dp(20) })
        unlock.minimumHeight = dp(48)
        video.findViewById<View>(R.id.pc_back).setOnClickListener { onBack?.invoke() }
        video.findViewById<View>(R.id.pc_rotate).setOnClickListener { cancelInteraction(); onRotate?.invoke() }
        video.findViewById<View>(R.id.pc_more).setOnClickListener { showMore() }
        video.findViewById<View>(R.id.pc_lock).setOnClickListener { setLocked(true) }
        aspectButton.setOnClickListener { showAspectPicker() }
        speedButton.setOnClickListener { showSpeedPicker() }
        video.findViewById<View>(R.id.pc_previous).setOnClickListener { cancelInteraction(); onPrevious?.invoke() }
        video.findViewById<View>(R.id.pc_next).setOnClickListener { cancelInteraction(); onNext?.invoke() }
        updateNavigation()
        updateSpeed()
        applyAspect()
    }

    fun attachWindow(window: Window) { hostWindow = window }
    private fun updateNavigation() {
        video.findViewById<View>(R.id.pc_previous).apply { visibility = if (onPrevious == null) GONE else VISIBLE; isEnabled = previousAvailable; alpha = if (isEnabled) 1f else .35f }
        video.findViewById<View>(R.id.pc_next).apply { visibility = if (onNext == null) GONE else VISIBLE; isEnabled = nextAvailable; alpha = if (isEnabled) 1f else .35f }
        // long: 选片按钮出现时收窄图形间隔，保留每个按钮的完整命中范围，窄手机也能显示前后切换。
        val navigation = onPrevious != null || onNext != null
        val compact = navigation && width > 0 && width - paddingLeft - paddingRight < dp(336)
        val gap = dp(if (compact) 4 else if (navigation) 8 else 24)
        video.findViewById<View>(androidx.media3.ui.R.id.exo_rew).let { view ->
            (view.layoutParams as? LinearLayout.LayoutParams)?.let { if (it.marginEnd != gap) { it.marginEnd = gap; view.layoutParams = it } }
        }
        video.findViewById<View>(androidx.media3.ui.R.id.exo_ffwd).let { view ->
            (view.layoutParams as? LinearLayout.LayoutParams)?.let { if (it.marginStart != gap) { it.marginStart = gap; view.layoutParams = it } }
        }
        // long: 前后集同时出现时按实际可用宽度缩小图形容器，触控仍不低于48dp，避免窄窗口越界。
        for (id in listOf(androidx.media3.ui.R.id.exo_rew, androidx.media3.ui.R.id.exo_ffwd, androidx.media3.ui.R.id.exo_play_pause)) {
            val view = video.findViewById<View>(id)
            val target = dp(if (id == androidx.media3.ui.R.id.exo_play_pause) { if (compact) 64 else 72 } else { if (compact) 48 else 56 })
            if (view.layoutParams.width != target || view.layoutParams.height != target) {
                view.layoutParams = view.layoutParams.apply { width = target; height = target }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateNavigation()
    }

    /** long: 宿主进入后台必须结束临时手势，不能让后台播放停留在 2× 或覆盖系统亮度。 */
    fun onHostStop() {
        cancelInteraction()
        activeDialog?.dismiss()
        restoreBrightness()
        removeCallbacks(clearFeedback)
        removeCallbacks(hideUnlock)
        feedback.visibility = GONE
    }

    fun setLocked(locked: Boolean) {
        cancelInteraction()
        isLocked = locked
        video.useController = !locked
        if (locked) {
            video.hideController()
            revealUnlock()
            showFeedback(context.getString(R.string.pc_locked))
        } else {
            removeCallbacks(hideUnlock)
            unlock.visibility = GONE
            video.showController()
        }
    }

    fun showControls() {
        if (isLocked) revealUnlock() else video.showController()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelInteraction()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        cancelInteraction()
        activeDialog?.dismiss()
        super.onConfigurationChanged(newConfig)
        post { applyAspect() }
    }

    override fun onDetachedFromWindow() {
        onHostStop()
        player = null
        hostWindow = null
        super.onDetachedFromWindow()
    }

    private fun updateSpeed() {
        val label = String.format(Locale.ROOT, "%.2f", selectedSpeed).trimEnd('0').trimEnd('.')
        speedButton.text = context.getString(R.string.pc_speed_value, label)
        speedButton.contentDescription = speedButton.text
        speedButton.isEnabled = player?.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH) == true
    }

    private fun beginBoost() {
        val current = player ?: return
        if (isLocked || !current.isPlaying || !current.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return
        selectedSpeed = current.playbackParameters.speed
        boostedPlayer = current
        current.setPlaybackSpeed(2f)
        video.hideController()
        showFeedback(context.getString(R.string.pc_boost), sticky = true)
    }

    private fun endBoost() {
        val current = boostedPlayer ?: return
        // long: 先恢复再解除临时状态，避免恢复回调把临时倍率当成用户偏好写入。
        if (current.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) current.setPlaybackSpeed(selectedSpeed)
        boostedPlayer = null
        feedback.visibility = GONE
    }

    private fun cancelInteraction() { gestureSurface.cancel(); endBoost() }

    private fun seek(forward: Boolean) {
        val current = player ?: return
        if (!current.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || !current.isCurrentMediaItemSeekable) {
            showFeedback(context.getString(R.string.pc_unseekable)); return
        }
        val duration = current.duration
        val maximum = if (duration == C.TIME_UNSET) Long.MAX_VALUE else duration.coerceAtLeast(0)
        val position = (current.currentPosition + if (forward) 10000 else -10000).coerceIn(0, maximum)
        current.seekTo(position)
        showFeedback(context.getString(if (forward) R.string.pc_seek_forward else R.string.pc_seek_back))
    }

    private fun revealUnlock() {
        unlock.visibility = VISIBLE
        removeCallbacks(hideUnlock)
        // long: TalkBack 用户需要稳定的解锁入口，不能在焦点到达前自动隐藏。
        if (!accessibility.isTouchExplorationEnabled) postDelayed(hideUnlock, 4000)
    }

    private fun showFeedback(message: String, sticky: Boolean = false) {
        removeCallbacks(clearFeedback)
        feedback.text = message
        feedback.visibility = VISIBLE
        if (!sticky) postDelayed(clearFeedback, 1200)
    }

    private fun showAspectPicker() {
        cancelInteraction()
        val options = resources.getStringArray(R.array.pc_ratios)
        showOptions(context.getString(R.string.pc_ratio), options.toList(), preferences.aspect) { index ->
            preferences.aspect = index; applyAspect()
        }
    }

    private fun showSpeedPicker() {
        val speeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)
        showOptions(context.getString(R.string.pc_speed), speeds.map { "${it}×" }, speeds.indexOf(selectedSpeed)) { index ->
            player?.let { if (it.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) it.setPlaybackSpeed(speeds[index]) }
        }
    }

    private fun showMore() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        actions.add(context.getString(R.string.pc_audio) to { video.findViewById<View>(androidx.media3.ui.R.id.exo_settings).performClick(); Unit })
        actions.add(context.getString(R.string.pc_subtitles) to {
            video.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle).let { if (it.isEnabled) it.performClick() else showFeedback(context.getString(R.string.pc_no_subtitles)) }; Unit
        })
        actions.add(context.getString(R.string.pc_info) to { onInfo?.invoke(); Unit })
        actions.add(context.getString(R.string.pc_help) to { showHelp() })
        onQueue?.let { actions.add(context.getString(R.string.pc_select_video) to it) }
        onToggleContinuous?.let { actions.add((context.getString(R.string.pc_continuous) + if (continuousPlaying) "：开启" else "：关闭") to it) }
        showOptions(context.getString(R.string.pc_more), actions.map { it.first }) { index -> actions[index].second() }
    }

    private fun showOptions(title: String, labels: List<String>, selected: Int = -1, action: (Int) -> Unit) {
        cancelInteraction()
        val dialog = BottomSheetDialog(dialogContext())
        val column = LinearLayout(dialog.context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(24))
            addView(TextView(context).apply { text = title; textSize = 18f; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, dp(12)) })
        }
        labels.forEachIndexed { index, label ->
            column.addView(TextView(dialog.context).apply {
                text = if (index == selected) "$label · ${context.getString(R.string.pc_selected)}" else label
                textSize = 14f; setTextColor(if (index == selected) 0xFFB9CCFF.toInt() else Color.WHITE)
                minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isFocusable = true
                val ripple = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                setBackgroundResource(ripple.resourceId)
                // long: 关闭面板会移除选项 View，后续操作须排到仍在窗口中的播放器，不能丢在已卸载的选项队列。
                setOnClickListener { dialog.dismiss(); this@VideoControlsView.post { action(index) } }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        dialog.setContentView(ScrollView(dialog.context).apply { addView(column) })
        showDialog(dialog)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun applyAspect() {
        val mode = preferences.aspect
        aspectButton.text = resources.getStringArray(R.array.pc_ratios)[mode]
        aspectButton.contentDescription = context.getString(R.string.pc_ratio) + "：" + aspectButton.text
        video.resizeMode = when (mode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        val size = player?.videoSize ?: VideoSize.UNKNOWN
        val ratio = when (mode) {
            3 -> 16f / 9f
            4 -> 4f / 3f
            else -> if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
        }
        video.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame).setAspectRatio(ratio)
    }

    private fun showHelp() {
        showDialog(MaterialAlertDialogBuilder(dialogContext()).setTitle(R.string.pc_help).setMessage(R.string.pc_help_text)
            .setPositiveButton(R.string.pc_close, null).create())
    }

    private fun showDialog(dialog: Dialog) {
        cancelInteraction()
        activeDialog?.dismiss()
        activeDialog = dialog
        video.controllerShowTimeoutMs = 0
        dialog.setOnDismissListener {
            activeDialog = null
            video.controllerShowTimeoutMs = 4000
        }
        dialog.show()
    }

    private fun dialogContext() = ContextThemeWrapper(context, com.google.android.material.R.style.Theme_Material3_Dark)

    private fun brightness(): Float {
        val window = hostWindow ?: return 0.5f
        val value = window.attributes.screenBrightness
        return if (value >= 0) value else Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
    }

    private fun changeBrightness(value: Float) {
        val window = hostWindow ?: return
        if (initialBrightness == null) initialBrightness = window.attributes.screenBrightness
        val level = value.coerceIn(0.02f, 1f)
        window.attributes = window.attributes.apply { screenBrightness = level }
        showFeedback(context.getString(R.string.pc_brightness, (level * 100).roundToInt()), sticky = true)
    }

    private fun restoreBrightness() {
        initialBrightness?.let { original -> hostWindow?.let { it.attributes = it.attributes.apply { screenBrightness = original } } }
        initialBrightness = null
    }

    private fun changeVolume(value: Float) {
        val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (audio.isVolumeFixed || maximum == 0) { showFeedback(context.getString(R.string.pc_volume_fixed)); return }
        val volume = value.roundToInt().coerceIn(0, maximum)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            showFeedback(context.getString(R.string.pc_volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maximum), sticky = true)
        } catch (_: SecurityException) { showFeedback(context.getString(R.string.pc_volume_fixed)) }
    }

    private fun label() = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply { setColor(0xE6222730.toInt()); cornerRadius = dp(12).toFloat() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private inner class GestureSurface(context: Context) : View(context) {
        private var startX = 0f
        private var startY = 0f
        private var startBrightness = 0.5f
        private var startVolume = 0f
        private var axis = 0
        private var cancelled = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean { performClick(); return true }
            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (!isLocked && !cancelled) seek(event.x >= width / 2f)
                return true
            }
            override fun onLongPress(event: MotionEvent) { if (!cancelled && axis == 0) beginBoost() }
        })

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun cancel() {
            cancelled = true
            axis = 0
            val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            detector.onTouchEvent(event)
            event.recycle()
        }

        override fun performClick(): Boolean {
            super.performClick()
            if (isLocked) revealUnlock()
            else if (video.isControllerFullyVisible) video.hideController() else video.showController()
            return true
        }

        // long: 单击由 GestureDetector 的确认回调调用 performClick，避免第一击抢先隐藏双击目标。
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (accessibility.isTouchExplorationEnabled) return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                cancelled = false; axis = 0
                startX = event.x; startY = event.y
                startBrightness = brightness()
                startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            }
            if (event.pointerCount > 1) { cancel(); endBoost(); return true }
            if (!cancelled && !isLocked && event.actionMasked == MotionEvent.ACTION_MOVE && boostedPlayer == null) {
                val dx = event.x - startX
                val dy = event.y - startY
                if (axis == 0 && maxOf(abs(dx), abs(dy)) > slop) axis = if (abs(dy) > abs(dx)) 1 else 2
                if (axis == 1) {
                    video.hideController()
                    // long: 总位移相对于按下时的值计算，避免每个 MOVE 四舍五入导致小幅滑动无效。
                    val delta = -dy / (height * 0.65f).coerceAtLeast(1f)
                    if (startX < width / 2f) changeBrightness(startBrightness + delta)
                    else changeVolume(startVolume + delta * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                }
            }
            if (!cancelled) detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                endBoost()
                if (axis == 1) postDelayed(clearFeedback, 900)
            }
            return true
        }
    }
}
