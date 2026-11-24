package com.meda.portico.core.content

/** What a tap (or TalkBack's double tap) on a camera tile does. P3: a tap no longer resizes, it shows the tile's control strip. */
enum class TileAction { NONE, SHOW_CONTROLS, RESET_ZOOM }

/** Blocks B6 (design 6.3): the zoom arithmetic, pure. Scale 1x..4x; the picture always covers the viewport. */
object ZoomMath {
    const val MIN = 1f
    const val MAX = 4f

    /** A scale under this when the fingers lift is 1x (and the pan is dropped). */
    const val SNAP = 1.05f

    /** TalkBack's "Zoom in" / "Zoom out" steps. */
    val STEPS = floatArrayOf(1f, 2f, 4f)

    fun clampScale(s: Float): Float = if (s.isNaN()) MIN else s.coerceIn(MIN, MAX)

    fun snap(s: Float): Float = if (s < SNAP) MIN else s

    /** The pan (translation in viewport pixels) that keeps the picture covering a viewport [view] pixels long at [scale]. */
    fun clampPan(t: Float, view: Float, scale: Float): Float = t.coerceIn(view - view * scale, 0f)

    fun stepUp(s: Float): Float = STEPS.firstOrNull { it > s + 0.01f } ?: STEPS.last()

    fun stepDown(s: Float): Float = STEPS.lastOrNull { it < s - 0.01f } ?: STEPS.first()

    /** CENTER_CROP's scale for a picture [srcW]x[srcH] in a [viewW]x[viewH] viewport: the picture covers it. */
    fun cropScale(srcW: Int, srcH: Int, viewW: Int, viewH: Int): Float =
        maxOf(viewW.toFloat() / srcW, viewH.toFloat() / srcH)

    /**
     * The picture's placement in the viewport: the crop (what the tile looked like before zoom existed) then the zoom
     * [zoom] about the viewport's origin and the pan ([tx], [ty]). `x' = x * scale + dx`, `y' = y * scale + dy`. Depends
     * on the picture's size only through the crop, so a new still of another resolution lands with no jump.
     */
    fun placement(srcW: Int, srcH: Int, viewW: Int, viewH: Int, zoom: Float, tx: Float, ty: Float): Placement {
        val crop = cropScale(srcW, srcH, viewW, viewH)
        val ox = (viewW - srcW * crop) / 2f
        val oy = (viewH - srcH * crop) / 2f
        return Placement(crop * zoom, ox * zoom + tx, oy * zoom + ty)
    }

    data class Placement(val scale: Float, val dx: Float, val dy: Float)
}

/**
 * The state of one camera tile's touch (blocks B6, design 6.3), pure: no Android type, so every rule is tested on the JVM.
 * The view feeds it the raw events (and the scale detector's, since `ScaleGestureDetector` does the finger maths) and
 * draws [scale], [tx], [ty].
 *
 * - One finger, no move beyond [slopPx], lifted within [tapMaxMs]: a TAP. At 1x it shows the control strip (P3, it used to toggle the size); while zoomed it
 *   resets the zoom instead (the tap cannot do two jobs, so there is no double tap). [up] returns which.
 * - One finger moving beyond the slop at 1x: a scroll, not ours (the page's ScrollView takes it): never a tap.
 * - One finger moving while zoomed: a PAN of the picture, bounded so it still covers the viewport. [claiming] is true,
 *   so the view asks its parents not to intercept.
 * - A second finger: a PINCH, from then until every finger is up, even if one lifts: never a tap, and the finger left
 *   does not pan (no jump). [claiming] is true.
 * - [cancel] (the parent took the touch): not a tap, the zoom stays.
 * - Zoom is off ([zoomEnabled] false, a tile with no picture): pinch and steps do nothing; the tap still shows the strip.
 *
 * The viewport (the tile's size) never changes; only what is drawn inside it does.
 */
class TileGesture(private val slopPx: Float, private val tapMaxMs: Long = TAP_MAX_MS) {
    private enum class Phase { IDLE, PENDING, SCROLLING, PANNING, PINCHING, AFTER_PINCH }

    var scale = 1f
        private set
    var tx = 0f
        private set
    var ty = 0f
        private set
    var viewW = 0f
        private set
    var viewH = 0f
        private set

    /** Called after every change of [scale], [tx] or [ty]. */
    var onChange: (() -> Unit)? = null

    var zoomEnabled = true
        set(v) {
            field = v
            if (!v) resetZoom()
        }

    private var phase = Phase.IDLE
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var focusX = 0f
    private var focusY = 0f

    val zoomed: Boolean get() = scale > ZoomMath.MIN

    /** The touch is a pinch or a pan of ours: parents must not take it. */
    val claiming: Boolean get() = phase == Phase.PINCHING || phase == Phase.PANNING

    fun setViewport(w: Int, h: Int) {
        viewW = w.toFloat()
        viewH = h.toFloat()
        if (clampPan()) onChange?.invoke()
    }

    fun down(x: Float, y: Float, tMs: Long) {
        phase = Phase.PENDING
        downX = x
        downY = y
        lastX = x
        lastY = y
        downT = tMs
    }

    /** Another finger went down. */
    fun pointerDown() {
        if (phase != Phase.IDLE) phase = Phase.PINCHING
    }

    /** One finger moved (the first pointer; with two down the scale detector drives the picture). */
    fun move(x: Float, y: Float) {
        when (phase) {
            Phase.PENDING -> {
                if (Math.hypot((x - downX).toDouble(), (y - downY).toDouble()) <= slopPx) return
                if (zoomed) {
                    phase = Phase.PANNING
                    lastX = x
                    lastY = y
                } else {
                    phase = Phase.SCROLLING
                }
            }
            Phase.PANNING -> {
                tx += x - lastX
                ty += y - lastY
                lastX = x
                lastY = y
                clampPan()
                onChange?.invoke()
            }
            else -> Unit
        }
    }

    /** A finger lifted and [remaining] are still down. */
    fun pointerUp(remaining: Int) {
        if (phase == Phase.PINCHING && remaining <= 1) {
            phase = Phase.AFTER_PINCH
            settle()
        }
    }

    /** The scale detector began; [fx], [fy] is the fingers' midpoint in viewport pixels. */
    fun scaleBegin(fx: Float, fy: Float) {
        focusX = fx
        focusY = fy
    }

    /** The detector's step: [factor] since the last event, about [fx], [fy]; the midpoint's own movement pans. */
    fun scale(factor: Float, fx: Float, fy: Float) {
        if (!zoomEnabled || phase != Phase.PINCHING || factor.isNaN() || factor <= 0f) return
        zoomAbout(scale * factor, fx, fy)
        tx += fx - focusX
        ty += fy - focusY
        focusX = fx
        focusY = fy
        clampPan()
        onChange?.invoke()
    }

    fun scaleEnd() = settle()

    /** The last finger lifted: a TAP gives [TileAction.SHOW_CONTROLS] (1x) or [TileAction.RESET_ZOOM] (zoomed), anything else NONE. */
    fun up(tMs: Long): TileAction {
        val wasTap = phase == Phase.PENDING && tMs - downT in 0..tapMaxMs
        phase = Phase.IDLE
        settle()
        return if (wasTap) activate() else TileAction.NONE
    }

    fun cancel() {
        phase = Phase.IDLE
        settle()
    }

    /** The tap's job, also TalkBack's double tap (the click): resets a zoom if there is one, else shows the control strip. */
    fun activate(): TileAction {
        if (zoomed) {
            resetZoom()
            return TileAction.RESET_ZOOM
        }
        return TileAction.SHOW_CONTROLS
    }

    fun resetZoom() {
        if (scale == ZoomMath.MIN && tx == 0f && ty == 0f) return
        scale = ZoomMath.MIN
        tx = 0f
        ty = 0f
        onChange?.invoke()
    }

    /** TalkBack "Zoom in": the next of 1x, 2x, 4x about the viewport's centre. False if it did nothing. */
    fun zoomIn(): Boolean = stepTo(ZoomMath.stepUp(scale))

    /** TalkBack "Zoom out": the step below, 1x at the bottom. False if it did nothing. */
    fun zoomOut(): Boolean = stepTo(ZoomMath.stepDown(scale))

    private fun stepTo(target: Float): Boolean {
        if (!zoomEnabled || target == scale) return false
        zoomAbout(target, viewW / 2f, viewH / 2f)
        clampPan()
        onChange?.invoke()
        return true
    }

    /** Sets the scale keeping the picture point under ([fx], [fy]) where it is. */
    private fun zoomAbout(wanted: Float, fx: Float, fy: Float) {
        val s2 = ZoomMath.clampScale(wanted)
        val ratio = s2 / scale
        tx = fx - (fx - tx) * ratio
        ty = fy - (fy - ty) * ratio
        scale = s2
    }

    /** A small zoom is 1x again. */
    private fun settle() {
        if (scale > ZoomMath.MIN && scale < ZoomMath.SNAP) resetZoom()
    }

    private fun clampPan(): Boolean {
        val nx = ZoomMath.clampPan(tx, viewW, scale)
        val ny = ZoomMath.clampPan(ty, viewH, scale)
        val changed = nx != tx || ny != ty
        tx = nx
        ty = ny
        return changed
    }

    companion object {
        /** A finger that lifts later than this was a press, not a tap. */
        const val TAP_MAX_MS = 400L
    }
}

/**
 * Whether a touch on Home may turn the page (blocks B6, extending A2's swipe). A swipe is evaluated at the last finger's
 * lift only if the touch started outside the tab strip, started with no tile zoomed anywhere, and never had a second
 * finger: a pinch or a pan of a zoomed picture must not change the page. Pure.
 */
class SwipeGuard {
    private var active = false

    fun down(onTabStrip: Boolean, anyTileZoomed: Boolean) {
        active = !onTabStrip && !anyTileZoomed
    }

    /** A second finger touched the screen. */
    fun pointerDown() {
        active = false
    }

    /** The last finger lifted: true when the swipe may be evaluated. */
    fun up(): Boolean {
        val a = active
        active = false
        return a
    }

    fun cancel() {
        active = false
    }
}
