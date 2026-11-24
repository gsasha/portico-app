package com.meda.portico.home

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.meda.portico.core.content.HoldFillLook
import com.meda.portico.core.content.HoldState

/**
 * The green fill that sweeps left to right over a hold-to-confirm button (0.5.10-alpha), drawn into the button's view overlay
 * so it sits over the icon and text of any button (an [android.widget.ImageButton] or a text one) and is clipped to the
 * button's rounded corners. A translucent layer up to [progress], and at its leading edge an opaque bar with a ring of the
 * button's own ink either side, so it reads on the default tonal look and on every palette swatch (colours: `:core`'s
 * [HoldFillLook], contrast pinned by `HoldFillLookTest`). Drawing only.
 */
internal class HoldFillDrawable(
    private val density: Float,
    dark: Boolean,
    private val ring: Int,
    private val cornerDp: Int,
) : Drawable() {
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HoldFillLook.layer(dark) }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HoldFillLook.green(dark) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ring }
    private val clip = Path()
    private val rect = RectF()

    var progress: Float = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidateSelf()
            }
        }

    override fun draw(canvas: Canvas) {
        if (progress <= 0f) return
        val b = bounds
        if (b.isEmpty) return
        val r = cornerDp * density
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        val x = b.left + b.width() * progress
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), x, b.bottom.toFloat(), layerPaint)
        val half = HoldFillLook.EDGE_DP * density / 2f
        val ringW = HoldFillLook.RING_DP * density
        canvas.drawRect(x - half - ringW, b.top.toFloat(), x + half + ringW, b.bottom.toFloat(), ringPaint)
        canvas.drawRect(x - half, b.top.toFloat(), x + half, b.bottom.toFloat(), edgePaint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * The finger side of hold-to-confirm: owns every touch on one Home button that "asks first". `:core`'s [HoldState] decides
 * (fire exactly once at [com.meda.portico.core.content.HoldToConfirm.HOLD_MS], a release before it is a hint); this only feeds
 * it the clock and the events and paints the result.
 *
 * - DOWN starts the hold, presses the button (ripple) and asks the parents not to take the touch
 *   (`requestDisallowInterceptTouchEvent(true)`, so the ScrollView does not steal a still finger and cancel the fill).
 * - MOVE off the button (beyond the touch slop), UP off the button, CANCEL (a takeover, the system), the view leaving the window,
 *   losing window focus or no longer being shown: the fill resets and nothing runs.
 * - A frame callback ([View.postOnAnimation], one per display frame) advances the fill and fires. Every callback is removed on
 *   cancel and on detach, so nothing outlives the view.
 * - The up after a fire is swallowed (the machine says NONE), so the action runs once per hold.
 */
internal class HoldToConfirmTouch(
    private val view: View,
    private val fill: HoldFillDrawable,
    private val onFire: () -> Unit,
    private val onHint: () -> Unit,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : View.OnTouchListener {
    private val state = HoldState()
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val resetFill = Runnable { fill.progress = 0f }
    private val frame = object : Runnable {
        override fun run() {
            if (state.phase != HoldState.Phase.HOLDING) return
            if (!view.isAttachedToWindow || !view.hasWindowFocus() || !view.isShown) return abort()
            val now = clock()
            val outcome = state.tick(now)
            fill.progress = state.progress(now)
            if (outcome == HoldState.Outcome.FIRE) fire() else view.postOnAnimation(this)
        }
    }

    init {
        view.setOnTouchListener(this)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = abort()
        })
    }

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                state.cancel()
                stop()
                state.down(clock())
                fill.progress = 0f
                v.isPressed = true
                v.drawableHotspotChanged(e.x, e.y)
                v.parent?.requestDisallowInterceptTouchEvent(true)
                v.postOnAnimation(frame)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!inside(v, e)) abort() else v.drawableHotspotChanged(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                if (!inside(v, e)) {
                    abort()
                } else {
                    val now = clock()
                    val outcome = state.up(now)
                    fill.progress = state.progress(now)
                    if (outcome == HoldState.Outcome.FIRE) {
                        fire()
                        finish()
                    } else {
                        finish()
                        if (outcome == HoldState.Outcome.HINT) onHint()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> abort()
            else -> Unit // a second finger changes nothing
        }
        return true
    }

    private fun inside(v: View, e: MotionEvent): Boolean =
        e.x >= -slop && e.y >= -slop && e.x <= v.width + slop && e.y <= v.height + slop

    /** The one place the action fires from: a haptic, then the press. Called only on the machine's FIRE. */
    private fun fire() {
        fill.progress = 1f
        val haptic = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        view.performHapticFeedback(haptic)
        onFire()
    }

    /** The touch is over without an action: the machine cancels (a fire stays fired), then [finish]. */
    private fun abort() {
        state.cancel()
        finish()
    }

    /** No callbacks, not pressed, parents free; the fill resets now, or (after a fire) a moment later so the full green is seen. */
    private fun finish() {
        stop()
        if (state.phase == HoldState.Phase.FIRED) view.postDelayed(resetFill, FIRED_FILL_MS) else fill.progress = 0f
    }

    private fun stop() {
        view.removeCallbacks(frame)
        view.removeCallbacks(resetFill)
        view.isPressed = false
        view.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private companion object {
        /** After a fire the full green stays this long, so the person sees it complete; then it resets. */
        const val FIRED_FILL_MS = 250L
    }
}
