package org.piramalswasthya.sakhi.ui.home_activity.badges

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Weeks-toward-next-tier ring. One-shot 600ms sweep each time a value is set
 * (never loops), honouring the system animator scale so reduced-motion devices
 * get an instant, static ring.
 */
class BadgeProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#33FF9F1C")
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FF9F1C")
    }
    private val arcBounds = RectF()
    private var displayedFraction = 0f
    private var animator: ValueAnimator? = null

    /** [current] of [target] (e.g. 3 of 4 weeks); animates from zero on each set. */
    fun setProgress(current: Int, target: Int) {
        val fraction = if (target <= 0) 0f else (current.toFloat() / target).coerceIn(0f, 1f)
        animator?.cancel()
        val animatorScale = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        )
        if (animatorScale == 0f) {
            displayedFraction = fraction
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, fraction).apply {
            duration = 600
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                displayedFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = width * 0.09f
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        val inset = stroke / 2 + 2
        arcBounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(arcBounds, -90f, 360f, false, trackPaint)
        canvas.drawArc(arcBounds, -90f, 360f * displayedFraction, false, progressPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
