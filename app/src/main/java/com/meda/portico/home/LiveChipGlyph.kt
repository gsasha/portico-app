package com.meda.portico.home

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import com.meda.portico.core.live.ChipLook
import com.meda.portico.core.live.LiveWhy

/** The icon BEFORE the word: the play symbol only when live is off, the red dot whenever live is wanted (playing or connecting, identical). */
enum class ChipLead { PLAY, DOT }

/** What sits AFTER the word, in one fixed-width slot: nothing, the loading spinner (connecting) or the fps text (playing). */
enum class ChipTrail { NONE, SPINNER, FPS }

/** Which icon and trailing marker the chip shows, decided from `:core`'s state alone (no Android), so a plain JVM test can pin every case. */
object LiveChipGlyphs {
    /** THE lead slot: the play symbol and the dot are both drawn in this one box (dp at a big tile; a small tile multiplies it by the chip scale), so the word never moves. */
    const val SLOT_DP = 8

    /** The gap between the lead slot and the word, one value for all states. */
    const val GAP_DP = 3

    /** The gap between the word and the trailing slot. */
    const val TRAIL_GAP_DP = 4

    /** The widest text the trailing slot must hold (the core's `--`, `N fps`, `0 fps`): its width, and the spinner's, fix the slot for every state. */
    const val TRAIL_SAMPLE = "000 fps"

    /** A notice that means "live cannot connect now" (not "it is connecting"): no spinner for these. */
    private val BLOCKED = setOf(LiveWhy.HOT, LiveWhy.AWAY, LiveWhy.NOT_SET_UP, LiveWhy.PHONE_LIMIT, LiveWhy.ROAMING)

    /** OFFER (live off, tap to start): play. ON and PENDING (live wanted): the same red dot. Roaming and none: no icon. */
    fun lead(chip: ChipLook): ChipLead? = when (chip) {
        ChipLook.OFFER -> ChipLead.PLAY
        ChipLook.ON, ChipLook.PENDING -> ChipLead.DOT
        ChipLook.NONE, ChipLook.ROAMING -> null
    }

    /**
     * ON: the fps text (none when the document does not ask for it, [fps] null or empty). PENDING: the spinner while the app is trying to
     * establish the stream (starting, waiting to retry, off screen and about to start); nothing when it cannot try (gave up: [canRetry];
     * or blocked by away / hot / not set up / the phone limit). Live off, roaming, none: nothing.
     */
    fun trail(chip: ChipLook, canRetry: Boolean, notice: LiveWhy?, fps: String?): ChipTrail = when (chip) {
        ChipLook.ON -> if (fps.isNullOrEmpty()) ChipTrail.NONE else ChipTrail.FPS
        ChipLook.PENDING -> if (canRetry || notice in BLOCKED) ChipTrail.NONE else ChipTrail.SPINNER
        ChipLook.OFFER, ChipLook.NONE, ChipLook.ROAMING -> ChipTrail.NONE
    }
}

/** The play symbol or the red dot in a square [slot] px box: intrinsic width and height ARE the slot, so both have the same bounds. */
class ChipLeadDrawable(private val lead: ChipLead, val slot: Int, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private val tri = Path()

    init { setBounds(0, 0, slot, slot) }

    override fun getIntrinsicWidth() = slot
    override fun getIntrinsicHeight() = slot

    override fun draw(canvas: Canvas) {
        val s = slot.toFloat()
        when (lead) {
            ChipLead.DOT -> { paint.color = 0xFFFF3B30.toInt(); canvas.drawCircle(s / 2, s / 2, s / 2, paint) }
            ChipLead.PLAY -> {
                tri.reset(); tri.moveTo(0f, 0f); tri.lineTo(s, s / 2); tri.lineTo(0f, s); tri.close() // spans the whole slot width
                canvas.drawPath(tri, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * The trailing slot after the word: [width] px wide in EVERY state (wide enough for [LiveChipGlyphs.TRAIL_SAMPLE]), showing the rotating
 * loading arc centred in it while connecting, or the fps text once live. Not a separate view, so the pill is one TextView.
 */
class ChipTrailDrawable(val width: Int, private val height: Int, private val paintSource: TextView) : Drawable(), Animatable {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = RectF()
    private var angle = 0f
    private var animator: ValueAnimator? = null

    var kind = ChipTrail.NONE
    var label: String = ""

    init { setBounds(0, 0, width, height) }

    override fun getIntrinsicWidth() = width
    override fun getIntrinsicHeight() = height

    override fun draw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        when (kind) {
            ChipTrail.NONE -> {}
            ChipTrail.FPS -> {
                text.textSize = paintSource.textSize
                text.typeface = paintSource.typeface
                text.color = paintSource.currentTextColor
                canvas.drawText(label, cx, cy - (text.ascent() + text.descent()) / 2f, text)
            }
            ChipTrail.SPINNER -> { // a 270 degree arc about as tall as the text, turning about the slot's centre
                val d = paintSource.textSize
                val w = d / 5f
                stroke.strokeWidth = w
                stroke.color = paintSource.currentTextColor
                arc.set(cx - d / 2 + w / 2, cy - d / 2 + w / 2, cx + d / 2 - w / 2, cy + d / 2 - w / 2)
                val n = canvas.save()
                canvas.rotate(angle, cx, cy)
                canvas.drawArc(arc, 0f, 270f, false, stroke)
                canvas.restoreToCount(n)
            }
        }
    }

    override fun setAlpha(alpha: Int) { text.alpha = alpha; stroke.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { text.colorFilter = cf; stroke.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT

    override fun isRunning() = animator != null

    override fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { angle = it.animatedValue as Float; invalidateSelf() }
            start()
        }
    }

    /** Cancels and drops the animator, so nothing keeps the drawable (and through its callback the view) alive. */
    override fun stop() {
        animator?.let { it.removeAllUpdateListeners(); it.cancel() }
        animator = null
    }
}

/**
 * The Live chip: a TextView with a lead drawable (play / dot, one slot) before the word and a trail drawable (spinner / fps, one fixed
 * width) after it. Both slots are built once, so a state change redraws inside identical boxes and the word starts at the same pixel in
 * every state. The spinner runs only while the view is attached, visible and in the connecting state, and is stopped on detach.
 */
class LiveChipView(context: Context, slotPx: Int, gapPx: Int, private val trailGapPx: Int, textColor: Int = 0xFFFFFFFF.toInt()) : TextView(context) {
    private val leads = ChipLead.values().associateWith { ChipLeadDrawable(it, slotPx, textColor) }
    private var trailDrawable: ChipTrailDrawable? = null
    var lead: ChipLead? = null
        private set
    var trail: ChipTrail = ChipTrail.NONE
        private set

    init { compoundDrawablePadding = gapPx }

    private fun trailSlot(): ChipTrailDrawable = trailDrawable ?: run {
        val w = Math.max(paint.measureText(LiveChipGlyphs.TRAIL_SAMPLE), textSize).toInt() + trailGapPx
        ChipTrailDrawable(w, textSize.toInt(), this).also { trailDrawable = it }
    }

    /** Sets the icon and the trailing marker; [fps] is the core's text, drawn only for [ChipTrail.FPS]. */
    fun show(newLead: ChipLead?, newTrail: ChipTrail, fps: String?) {
        val t = trailSlot()
        t.kind = newTrail
        t.label = fps.orEmpty()
        if (newLead != lead || (newTrail == ChipTrail.NONE) != (trail == ChipTrail.NONE) || trailDrawable == null || compoundDrawablesRelative[2] == null) {
            lead = newLead
            setCompoundDrawablesRelative(newLead?.let { leads.getValue(it) }, null, if (newTrail == ChipTrail.NONE) null else t, null)
        } else {
            t.invalidateSelf()
        }
        trail = newTrail
        sync()
    }

    private fun sync() {
        val t = trailDrawable ?: return
        if (trail == ChipTrail.SPINNER && isAttachedToWindow && visibility == View.VISIBLE && windowVisibility == View.VISIBLE) t.start() else t.stop()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); sync() }
    override fun onDetachedFromWindow() { trailDrawable?.stop(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); sync() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); sync() }
}
