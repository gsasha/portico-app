package com.meda.portico.call

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.provider.Settings
import android.view.animation.LinearInterpolator

/**
 * APP-02: the wavy "sound flows" animation on an active call button: three sine lines drifting sideways, drawn over the
 * button's fill and under its icon. Cosmetic, and built not to cost anything it need not:
 *
 *  - it animates only while the drawable is VISIBLE: a View tells its background drawable [setVisible] when it, its window or
 *    the activity is hidden (the screen off, the app in the background), and then the animator is cancelled;
 *  - it does not animate when the system "remove animations" setting is on (animator duration scale 0): one still frame is
 *    drawn instead;
 *  - APP-03: [pulse] makes the wave swell and settle (a station call that rings and is not answered yet);
 *  - no wake lock, no timer of its own: a [ValueAnimator] frame callback only invalidates; the phase comes from the
 *    uptime clock, so a Home redraw that builds a new drawable continues the wave where it was instead of restarting it.
 */
class SoundWaveDrawable(context: Context, private val ink: Int, private val pulse: Boolean = false) : Drawable() {
    private val app = context.applicationContext
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        color = ink
    }
    private val path = Path()
    private val clip = Path()
    private var animator: ValueAnimator? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val r = 8f * density
        clip.reset()
        clip.addRoundRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), r, r, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        val phase = if (animator?.isRunning == true) (SystemClock.uptimeMillis() % PERIOD_MS) / PERIOD_MS.toFloat() else 0.25f
        for (i in 0 until LINES) {
            path.reset()
            val base = b.top + h * (0.30f + 0.20f * i)
            // APP-03: a call that is only ringing swells and settles twice a period; one in progress keeps a steady wave.
            val swell = if (pulse) 0.55f + 0.45f * Math.sin(phase * 4.0 * Math.PI).toFloat() else 1f
            val amp = h * 0.07f * swell * (if (pulse) 1.6f else 1f)
            var x = 0f
            while (x <= w) {
                val y = base + amp * Math.sin(((x / w) * 2.0 * Math.PI * 1.5) - (phase + i * 0.17f) * 2.0 * Math.PI).toFloat()
                if (x == 0f) path.moveTo(b.left + x, y) else path.lineTo(b.left + x, y)
                x += 2f * density
            }
            paint.alpha = ALPHAS[i]
            canvas.drawPath(path, paint)
        }
        canvas.restoreToCount(save)
    }

    /** Called by the owning View as it is shown, hidden, attached or detached: the whole battery policy is here. */
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible && animationsEnabled()) start() else stop()
        return changed
    }

    private fun animationsEnabled(): Boolean = try {
        Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Exception) {
        true
    }

    private fun start() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidateSelf() }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val PERIOD_MS = 1600L
        private const val LINES = 3
        private val ALPHAS = intArrayOf(200, 140, 90)
    }
}
