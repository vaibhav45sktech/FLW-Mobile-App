package org.piramalswasthya.sakhi.ui.home_activity.badges

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * One-shot confetti burst for the badge ceremony. Deliberately code-drawn (no
 * Lottie asset needed) and deliberately FINITE: it spawns once, animates ~2.2s on
 * postInvalidateOnAnimation, then stops touching the frame clock entirely — the
 * low-end-device rule is no looping animations, ever.
 */
class ConfettiView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Particle(
        var x: Float,
        var y: Float,
        var velocityX: Float,
        var velocityY: Float,
        var rotation: Float,
        var rotationSpeed: Float,
        val size: Float,
        val color: Int,
        /** 0=rect, 1=circle — two shapes read as "confetti" without bitmap cost. */
        val shape: Int,
    )

    companion object {
        private const val DURATION_MS = 2_200L
        private const val PARTICLE_COUNT = 90
        private const val GRAVITY_PX_PER_S2 = 1_400f

        /** Palette pulled from the badge art: golds, orange, purple, blue. */
        private val COLORS = intArrayOf(
            Color.parseColor("#FFC93C"),
            Color.parseColor("#FF9F1C"),
            Color.parseColor("#F2542D"),
            Color.parseColor("#7B2FBE"),
            Color.parseColor("#3D5AFE"),
            Color.parseColor("#FFF3D6"),
        )
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particles = mutableListOf<Particle>()
    private var startedAt = 0L
    private var lastFrameAt = 0L
    private var running = false

    /** Fires the burst from the horizontal centre, just above vertical centre. */
    fun start() {
        if (width == 0 || height == 0) {
            post { start() }
            return
        }
        particles.clear()
        val originX = width / 2f
        val originY = height * 0.38f
        repeat(PARTICLE_COUNT) {
            // Upward-biased cone so pieces arc over the badge and rain down.
            val angle = Math.toRadians(Random.nextDouble(200.0, 340.0))
            val speed = Random.nextDouble(500.0, 1_500.0).toFloat()
            particles += Particle(
                x = originX + Random.nextInt(-40, 41),
                y = originY,
                velocityX = (cos(angle) * speed).toFloat(),
                velocityY = (sin(angle) * speed).toFloat(),
                rotation = Random.nextFloat() * 360f,
                rotationSpeed = Random.nextFloat() * 720f - 360f,
                size = Random.nextDouble(8.0, 22.0).toFloat(),
                color = COLORS[Random.nextInt(COLORS.size)],
                shape = Random.nextInt(2),
            )
        }
        startedAt = System.currentTimeMillis()
        lastFrameAt = startedAt
        running = true
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return

        val now = System.currentTimeMillis()
        val elapsed = now - startedAt
        val deltaSeconds = ((now - lastFrameAt).coerceAtMost(48L)) / 1000f
        lastFrameAt = now

        if (elapsed >= DURATION_MS) {
            running = false
            particles.clear()
            return // final frame drawn empty; the frame clock is released
        }

        // Fade the whole system out over the last 30%.
        val fadeStart = DURATION_MS * 0.7f
        val alpha = if (elapsed <= fadeStart) 255
        else (255 * (1f - (elapsed - fadeStart) / (DURATION_MS - fadeStart))).toInt()

        particles.forEach { p ->
            p.velocityY += GRAVITY_PX_PER_S2 * deltaSeconds
            p.x += p.velocityX * deltaSeconds
            p.y += p.velocityY * deltaSeconds
            p.rotation += p.rotationSpeed * deltaSeconds

            paint.color = p.color
            paint.alpha = alpha
            canvas.save()
            canvas.rotate(p.rotation, p.x, p.y)
            if (p.shape == 0) {
                canvas.drawRect(
                    p.x - p.size / 2, p.y - p.size / 4,
                    p.x + p.size / 2, p.y + p.size / 4, paint
                )
            } else {
                canvas.drawCircle(p.x, p.y, p.size / 2.5f, paint)
            }
            canvas.restore()
        }
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        particles.clear()
        super.onDetachedFromWindow()
    }
}
