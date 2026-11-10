package dev.tgproxy.mobile

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.google.android.material.color.MaterialColors

/** long: 图形保持 18dp，触控范围独立保留；只旋转图形，避免涟漪和点击区域一起转动。 */
class ChannelUpdateButton(context: Context) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val icon = ImageView(context).apply {
        setImageResource(R.drawable.ic_sync)
        imageTintList = ColorStateList.valueOf(MaterialColors.getColor(this,
            com.google.android.material.R.attr.colorOnSurfaceVariant))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var rotationAnimator: ObjectAnimator? = null
    private var spinning = false
    internal val isSpinning get() = rotationAnimator?.isRunning == true

    init {
        minimumWidth = dp(44); minimumHeight = dp(48)
        isClickable = true; isFocusable = true
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        setBackgroundResource(value.resourceId)
        addView(icon, LayoutParams(dp(18), dp(18), android.view.Gravity.CENTER))
    }

    fun bind(description: String, running: Boolean, enabled: Boolean) {
        contentDescription = description
        isEnabled = enabled
        icon.alpha = if (enabled) 1f else .35f
        spinning = running
        updateAnimation()
    }

    fun stopAnimation() {
        rotationAnimator?.cancel(); rotationAnimator = null; icon.rotation = 0f
    }

    private fun updateAnimation() {
        if (!spinning || !isAttachedToWindow || windowVisibility != View.VISIBLE) { stopAnimation(); return }
        if (rotationAnimator != null) return
        rotationAnimator = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 360f).apply {
            duration = 1000; repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
    override fun onDetachedFromWindow() { stopAnimation(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }
}
