package com.meda.portico.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import com.meda.portico.R
import com.meda.portico.core.content.CameraGrid
import com.meda.portico.core.content.CameraTile
import com.meda.portico.core.content.ImageSampling
import com.meda.portico.core.content.PartialPlan
import com.meda.portico.core.content.PictureState
import com.meda.portico.core.content.StillWidth
import com.meda.portico.core.content.StreamPick
import com.meda.portico.core.content.ViewLayout
import com.meda.portico.core.content.TileAction
import com.meda.portico.core.content.TileGesture
import com.meda.portico.core.content.ZoomMath
import com.meda.portico.core.ha.CameraSize
import com.meda.portico.live.RtspTileEvents
import com.meda.portico.core.live.ChipLook
import com.meda.portico.core.live.LiveKey
import com.meda.portico.core.live.LiveTileView
import com.meda.portico.core.live.LiveWhy
import com.meda.portico.core.live.TileLiveUi
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** What a tile needs from the live video of the screen (DEV-713): the shared EGL context for its renderer, and the renderer's binding to its stream. */
interface LiveSurfaces {
    /** Null when live video cannot start (no EGL): the tile stays on its still. */
    fun eglContext(): EglBase.Context?
    fun bind(key: LiveKey, renderer: SurfaceViewRenderer)
    fun unbind(key: LiveKey, renderer: SurfaceViewRenderer)

    /** DEV-713 S1b, an RTSP (away from home) stream: the decoder draws on this plain SurfaceView; [events] hears the first frame and the picture size. */
    fun bindSurface(key: LiveKey, view: SurfaceView, events: RtspTileEvents)
    fun unbindSurface(key: LiveKey, view: SurfaceView)
}

/**
 * The camera tiles of one screen (Home's page, the ring screen, the in-call screen): a black 16:9 box with the picture
 * and, over it, a word when there is no picture (loading, camera unavailable, no connection), and the camera's label
 * under it. Drawing only: which tiles, and in which picture state, is `:core`'s `HomeModel` / `CallerModel`.
 *
 * [columns] 2 chunks tiles two to a row (an odd last tile gets an empty spacer so it is not stretched), 1 is a call
 * screen's full-width tile; both through [rebuild]. Home draws blocks instead (DEV-658): it calls [reset], then
 * [addGrid] once per block, which lays the block's tiles out by `:core`'s [CameraGrid] (a big tile a full row, small ones
 * two to a row, a lone small one half width). Never `setBackgroundColor` on anything that holds video: these are
 * ImageViews, not a SurfaceView.
 *
 * Home's tiles (B6) are INTERACTIVE: a tap toggles big/small and a pinch zooms the picture inside the same box. The box
 * never changes size with the zoom: the ImageView is placed by a matrix (`:core`'s [ZoomMath.placement]: today's centre
 * crop, then the zoom and pan), and every touch rule is [TileGesture]'s (fed by [TileTouch]). The zoom of a tile is not
 * remembered: [resetZoom] (page change, leaving the screen), a size toggle (the tile is rebuilt) and rotation (a new
 * activity) all end it. The ring and in-call tiles are plain: centre crop, no touch.
 *
 * Live video (DEV-713 S1 Home; S2 the ring and in-call screens, which set [live] and [liveViews] too and are not interactive otherwise): when `:core`'s live state says a tile is streaming ([liveViews]), a
 * `SurfaceViewRenderer` sits in the tile's frame ABOVE the still and BELOW the words, a 1x1 view (never INVISIBLE or GONE: it would have no surface) until a frame has really been drawn;
 * the still stays under it as the poster and the fallback. A SurfaceView punches a hole in the window at its place in the draw order,
 * so the order matters: image, renderer, overlay words, notice, fps label, chip. The chip (top-left) is the live/still switch (a tap
 * on the tile stays big/small), the fps label (bottom-right) is drawn only while video plays and the document asks for it, the
 * notice (bottom strip) says why live is not running. Zoom is the still's: while a pinch is on the tile the renderer is hidden.
 * The renderers are views and die with the grid ([reset], [rebuild], [releaseRenderers]); the streams do not (LiveHome).
 */
class StillTiles(private val context: Context, private val container: LinearLayout, private val columns: Int, private val fullscreenTiles: Boolean = false) {

    /** What Home does with a tile's events. */
    interface Listener {
        /** A tap at 1x, or TalkBack's double tap (DEV-768 P3; it used to flip the size): show this tile's control strip for 3 s. */
        fun onTap(tile: CameraTile) {}

        /** The strip's resize button: open the Big / Small / Fullscreen menu on [anchor]. */
        fun onResizeButton(tile: CameraTile, anchor: View) {}

        /** The strip's settings button: open the "Video size" menu on [anchor]. */
        fun onSettingsButton(tile: CameraTile, anchor: View) {}

        /** TalkBack's custom action: resize to [layout] (the strip's buttons are only drawn for 3 s after a tap). */
        fun onResizeTo(tile: CameraTile, layout: ViewLayout) {}

        /** TalkBack's custom action: pick the stream. */
        fun onPickStream(tile: CameraTile, pick: StreamPick) {}

        /** The tile's zoom crossed a width step ([StillWidth]): the camera's next still should be asked for now. */
        fun onWidthChanged(cameraId: String)

        /** A tap on the LIVE chip of this tile: live <-> still (or, if live gave up, try again). Never a size toggle. */
        fun onLiveChip(tile: CameraTile) {}
    }

    var listener: Listener? = null

    /** Panel mode (0.6.99): the tallest a Big Home tile may be, in px; 0 = no cap. Read at every measure, so rotation needs no rebuild. */
    var maxTileHeightPx: () -> Int = { 0 }

    /** How the tiles reach the streams, and the live state to draw: Home's, and (S2) the ring and in-call screens'. Without them nothing live is drawn. */
    var live: LiveSurfaces? = null
    var liveViews: (String) -> LiveTileView? = { null }

    /** 0.6.53: a live stream's picture height was seen (decoder / renderer thread): the video-size menu names its rows from it. */
    var onVideoHeight: (LiveKey, Int) -> Unit = { _, _ -> }

    private class Views(
        val tile: CameraTile,
        val frame: AspectFrameLayout,
        val image: ImageView,
        val overlay: TextView,
        val gesture: TileGesture?,
        val chip: LiveChipView? = null,
        val notice: TextView? = null,
        val strip: View? = null,
    ) {
        /** The zoom last used to pick the still's width, to notice a crossing. */
        var bandZoom = 1f

        /** The live renderer of this tile, the stream it is bound to, and whether it has drawn a frame (until then it stays a 1x1 view). */
        var renderer: SurfaceViewRenderer? = null

        /** DEV-713 S1b: the plain SurfaceView of an RTSP stream, instead of [renderer]; [rendererKey] and [rendered] serve both. */
        var rtsp: RtspTileSurface? = null
        var rendererKey: LiveKey? = null
        var rendered = false

        /** The last live state drawn (for the chip's tap, the visibility of the renderer once its first frame is drawn). */
        var videoShown = false

        /** 0.6.49: the still has been faded out (the video shows through); back to false when the video is not shown or the renderer goes. */
        var stillHidden = false
        var chipLook = ChipLook.NONE
        var canRetry = false
    }

    private companion object {
        const val STRIP_BUTTON_DP = 36
        const val GAP_DP = 2

        /** The LIVE chip on a big tile (0.6.49: 12 sp / 1.5 = 8 sp, its padding and height scaled likewise); a small tile is [SMALL_CHIP] of that. */
        const val CHIP_SP = 8f
        const val CHIP_PAD_H_DP = 7
        const val CHIP_PAD_V_DP = 4
        const val CHIP_MIN_DP = 22
        const val CHIP_INSET_DP = 8
        const val SMALL_CHIP = 0.78f
        const val FADE_MS = 160L
    }

    private class Zoom(val size: CameraSize, val gesture: TileGesture)

    private val views = HashMap<String, Views>()

    /** The picture bytes already decoded into each tile (identity), so an unchanged one is not decoded again. */
    private val shown = HashMap<String, ByteArray>()

    /** Each interactive tile's zoom state, kept across a rebuild of the same tile at the same size. */
    private var zooms = HashMap<String, Zoom>()
    private var previousZooms = HashMap<String, Zoom>()

    private fun dp(n: Int): Int = (n * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun scaled(n: Int, k: Float): Int = (n * k * context.resources.displayMetrics.density + 0.5f).toInt()

    /** Replaces every tile. Call only when the tile list changed. */
    fun rebuild(tiles: List<CameraTile>) {
        container.removeAllViews()
        releaseRenderers()
        views.clear()
        shown.clear()
        for (group in tiles.chunked(columns)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (tile in group) row.addView(tile(tile, interactive = false), cell())
            if (columns > 1 && group.size == 1) row.addView(spacer(), spacerCell())
            container.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** Forgets every tile; Home calls it before it builds its blocks again. A tile that comes back at the same size keeps its zoom. */
    fun reset() {
        releaseRenderers()
        views.clear()
        shown.clear()
        previousZooms = zooms
        zooms = HashMap()
    }

    /**
     * Adds one block's camera grid to [into] (rows by [CameraGrid]) and remembers the tiles for [update]. With a [plan] (tablet mode's partial
     * layout) the first line is the big tile at 2/3 width with its side tiles stacked in the other third (same 16:9 ratio, so the column is as
     * high as the big tile); the cameras left over follow in the usual rows.
     */
    fun addGrid(into: LinearLayout, tiles: List<CameraTile>, plan: PartialPlan? = null) {
        val rows = if (plan == null) CameraGrid.rows(tiles.map { it.size }) else {
            if (plan.side.isEmpty()) {
                into.addView(horizontal().apply { addView(tile(tiles[plan.big], interactive = true), edgeCell(left = false, right = false)) }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                val line = horizontal()
                line.addView(tile(tiles[plan.big], interactive = true), edgeCell(left = false, right = true, weight = 2f))
                val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                for (i in plan.side) column.addView(tile(tiles[i], interactive = true), sideCell())
                line.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                into.addView(line, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            plan.rest
        }
        for (row in rows) {
            val line = horizontal()
            // 0.6.48: edge to edge: no outer side margin, a 2 dp gap between two tiles of a row; rows keep their small vertical gap.
            row.cells.forEachIndexed { k, i ->
                val last = k == row.cells.size - 1 && !row.lone
                line.addView(tile(tiles[i], interactive = true), edgeCell(left = k > 0, right = !last))
            }
            if (row.lone) line.addView(spacer(), LinearLayout.LayoutParams(0, 1, 1f))
            into.addView(line, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun horizontal() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    /** The zoom of a camera's tile (1 when it has none): what [StillWidth] and the decode size are chosen from. */
    fun zoomOf(cameraId: String): Float = views[cameraId]?.gesture?.scale ?: 1f

    /** Any tile zoomed: the page swipe is off (`SwipeGuard`). */
    fun anyZoomed(): Boolean = views.values.any { it.gesture?.zoomed == true }

    /** Every tile back to 1x (the page changed, or the screen is being left). */
    fun resetZoom() {
        for (v in views.values) v.gesture?.resetZoom()
    }

    /**
     * The empty half of a row that holds one small tile. NEVER give it a WRAP_CONTENT height: a plain View's onMeasure is
     * `getDefaultSize(0, spec)`, which for an AT_MOST spec returns the whole available height, not 0. Home's ScrollView
     * has fillViewport, and when the content (after a tile shrank) is shorter than the screen it measures the content
     * again EXACTLY screen-high, so every block gets AT_MOST(what is left): the spacer then claimed all of it, its row grew
     * to the rest of the screen and every row and button after it was pushed out of view (owner's 0.5.4 to 0.5.5 report:
     * "when I minimize a camera, the items following it disappear"). One pixel of height, as the buttons' spacer has.
     */
    private fun spacer() = View(context)

    private fun spacerCell() = LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }

    /** A side tile of the partial layout: [GAP_DP] / 2 dp off the big tile, 2 dp above and below. */
    private fun sideCell() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(GAP_DP) / 2, dp(2), 0, dp(2)) }

    /** A Home tile's cell: no margin at the screen edge, [GAP_DP] / 2 dp each side of the gap between two tiles, 2 dp above and below. */
    private fun edgeCell(left: Boolean, right: Boolean, weight: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
        setMargins(if (left) dp(GAP_DP) / 2 else 0, dp(2), if (right) dp(GAP_DP) / 2 else 0, dp(2))
    }

    private fun cell() =LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    private fun tile(tile: CameraTile, interactive: Boolean, fullscreen: Boolean = false): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val frame = AspectFrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            fitHeight = fullscreen // fullscreen: the largest 16:9 box the window holds, in either orientation
        }
        val image = ImageView(context).apply { scaleType = if (interactive) ImageView.ScaleType.MATRIX else ImageView.ScaleType.CENTER_CROP }
        val overlay = TextView(context).apply {
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        frame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 0.6.49: a video SurfaceView is composited by the system outside the view tree, so the outline clip cannot round it: the four corners are
        // painted over it in the page colour (a view above the video's hole draws normally). Not on the fullscreen picture.
        if (!fullscreen) frame.addView(CornerMaskView(context, AspectFrameLayout.CORNER_DP), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Home's tiles carry the live words, drawn over everything else in this order: notice, fps label, chip (all gone until live says so).
        // The ring and in-call tiles (DEV-713 S2) carry them too, when their screen has given the tiles a live hand-off ([live]).
        var notice: TextView? = null
        var chip: LiveChipView? = null
        if (interactive || live != null) {
            notice = liveText(11f, Gravity.CENTER).also {
                it.setBackground(GradientDrawable().apply { setColor(0x99000000.toInt()) })
                it.maxLines = 2
                it.setPadding(dp(8), dp(4), dp(8), dp(4))
                frame.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            }
            // 0.6.49: ONE chip (top-left): "● LIVE · 24 fps" in the same pill. A small tile gets a chip about a quarter smaller.
            val small = interactive && tile.size != CameraSize.BIG
            val k = if (small) SMALL_CHIP else 1f
            // 0.6.50: a fixed lead slot (play / red dot) before the word and a fixed-width trail slot (spinner / fps) after it, scaled by the same k.
            chip = LiveChipView(context, scaled(LiveChipGlyphs.SLOT_DP, k), scaled(LiveChipGlyphs.GAP_DP, k), scaled(LiveChipGlyphs.TRAIL_GAP_DP, k)).also {
                styleLive(it, CHIP_SP * k, Gravity.CENTER)
                it.setBackground(pill())
                it.setTypeface(it.typeface, android.graphics.Typeface.BOLD)
                it.setPadding(scaled(CHIP_PAD_H_DP, k), scaled(CHIP_PAD_V_DP, k), scaled(CHIP_PAD_H_DP, k), scaled(CHIP_PAD_V_DP, k))
                it.minHeight = scaled(CHIP_MIN_DP, k)
                val inset = scaled(CHIP_INSET_DP, k)
                frame.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(inset, inset, inset, inset) })
            }
        }
        // DEV-768 P3: the control strip (bottom-right, over everything else), shown for 3 s after a tap: resize and video size. Home's tiles only.
        var strip: View? = null
        if (interactive) strip = controlStrip(tile).also {
            frame.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
        if (interactive && !fullscreen && tile.size == CameraSize.BIG) frame.maxHeightPx = { maxTileHeightPx() }
        box.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        // An empty label draws no name line at all (the picture identifies itself); not over the fullscreen picture.
        if (tile.named && !fullscreen) box.addView(TextView(context).apply {
            text = tile.label
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, 0)
            if (interactive) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // the picture's own description says it
        })
        // Home (interactive) shows the label alone; HA's own name under it only on the ring and in-call tiles (never without a label: detail is null then).
        if (!interactive && tile.detail != null) {
            box.addView(TextView(context).apply {
                text = tile.detail
                textSize = 12f
                alpha = 0.7f
            })
        }
        val gesture = if (interactive) zoomFor(tile) else null
        val v = Views(tile, frame, image, overlay, gesture, chip, notice, strip)
        views[tile.id] = v
        if (gesture != null) wire(v, gesture)
        chip?.setOnClickListener { listener?.onLiveChip(tile) }
        return box
    }

    /** The two buttons of the strip, in a dark pill. GONE until [showStrip] says this tile's turn. Square 36 dp targets (a Small tile is only about 95 dp high). */
    private fun controlStrip(tile: CameraTile): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pill()
            visibility = View.GONE
        }
        fun button(icon: Int, description: Int, onClick: (View) -> Unit) = ImageButton(context).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = null
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = context.getString(description)
            setOnClickListener(onClick)
            row.addView(this, LinearLayout.LayoutParams(dp(STRIP_BUTTON_DP), dp(STRIP_BUTTON_DP)))
        }
        button(R.drawable.ic_tile_resize, R.string.home_strip_resize) { listener?.onResizeButton(tile, it) }
        button(R.drawable.ic_mdi_cog, R.string.home_strip_settings) { listener?.onSettingsButton(tile, it) }
        return row
    }

    /** Shows the strip of the tile with this camera id and hides every other ([cameraId] null: all hidden). */
    fun showStrip(cameraId: String?) {
        for ((id, v) in views) v.strip?.visibility = if (id == cameraId) View.VISIBLE else View.GONE
    }

    /** Fullscreen: adds one tile, centred, to [into] (the overlay's slot). The tile is Big (the box is the largest 16:9 the window holds). */
    fun addFullscreen(into: FrameLayout, tile: CameraTile) {
        into.addView(tile(tile, interactive = true, fullscreen = true), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** A small white label on the picture, gone until [applyLive] says otherwise. The background is a drawable, never `setBackgroundColor`. */
    private fun liveText(sp: Float, gravity: Int) = TextView(context).also { styleLive(it, sp, gravity) }

    private fun styleLive(t: TextView, sp: Float, gravity: Int) = t.apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        this.gravity = gravity
        visibility = View.GONE
    }

    private fun pill() = GradientDrawable().apply {
        setColor(0x99000000.toInt())
        cornerRadius = dp(12).toFloat()
    }

    private fun zoomFor(tile: CameraTile): TileGesture {
        val kept = previousZooms.remove(tile.id)?.takeIf { it.size == tile.size }
        val z = kept ?: Zoom(tile.size, TileGesture(ViewConfiguration.get(context).scaledTouchSlop.toFloat()))
        zooms[tile.id] = z
        return z.gesture
    }

    /** Touch, matrix, description and TalkBack actions of one interactive tile. */
    private fun wire(v: Views, g: TileGesture) {
        val tile = v.tile
        v.bandZoom = g.scale
        g.onChange = {
            applyMatrix(v)
            describe(v)
            if (StillWidth.changes(tile.size, v.bandZoom, g.scale)) {
                v.bandZoom = g.scale
                listener?.onWidthChanged(tile.id)
            }
        }
        v.frame.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            g.setViewport(r - l, b - t)
            applyMatrix(v)
        }
        v.frame.setOnTouchListener(TileTouch(context, g) { handle(v, it) })
        // TalkBack's double tap is a click, which never passes through the touch listener: the same job as a tap.
        v.frame.setOnClickListener { handle(v, g.activate()) }
        // The strip's buttons are drawn for 3 s after a tap, too short for TalkBack: the same choices as custom actions.
        for ((layout, label) in listOf(ViewLayout.BIG to R.string.home_resize_big, ViewLayout.SMALL to R.string.home_resize_small, ViewLayout.FULLSCREEN to R.string.home_resize_fullscreen)) {
            ViewCompat.addAccessibilityAction(v.frame, context.getString(R.string.home_tile_action_resize, context.getString(label))) { _, _ ->
                listener?.onResizeTo(tile, layout)
                true
            }
        }
        // the same rows as the gear menu (0.6.53); the resolutions are named "high" / "low" here because a tile does not know its streams' heights
        val rows = if (tile.hasSmall) listOf(StreamPick.AUTO to R.string.home_stream_auto_plain, StreamPick.BIG to R.string.home_stream_high, StreamPick.SMALL to R.string.home_stream_low) else emptyList()
        for ((pick, label) in rows + listOf(StreamPick.STILLS to R.string.home_stream_stills, StreamPick.OFF to R.string.home_stream_off)) {
            ViewCompat.addAccessibilityAction(v.frame, context.getString(R.string.home_tile_action_stream, context.getString(label))) { _, _ ->
                listener?.onPickStream(tile, pick)
                true
            }
        }
        ViewCompat.addAccessibilityAction(v.frame, context.getString(R.string.home_tile_action_zoom_in)) { _, _ -> g.zoomIn() }
        ViewCompat.addAccessibilityAction(v.frame, context.getString(R.string.home_tile_action_zoom_out)) { _, _ -> g.zoomOut() }
        ViewCompat.addAccessibilityAction(v.frame, context.getString(R.string.home_tile_action_reset_zoom)) { _, _ ->
            val was = g.zoomed
            g.resetZoom()
            was
        }
        describe(v)
    }

    private fun handle(v: Views, action: TileAction) {
        if (action == TileAction.SHOW_CONTROLS) listener?.onTap(v.tile)
        // RESET_ZOOM already changed the gesture, whose onChange redrew the tile.
    }

    /** The tile's spoken description and what a double tap will do, for the state it is in. */
    private fun describe(v: Views) {
        val g = v.gesture ?: return
        val label = if (v.tile.named) v.tile.label else context.getString(R.string.home_tile_unnamed)
        v.frame.contentDescription = when {
            g.zoomed -> context.getString(R.string.home_tile_desc_zoomed, label)
            fullscreenTiles -> context.getString(R.string.home_tile_desc_fullscreen, label)
            v.tile.size == CameraSize.BIG -> context.getString(R.string.home_tile_desc_big, label)
            else -> context.getString(R.string.home_tile_desc_small, label)
        }
        val hint = if (g.zoomed) R.string.home_tile_hint_reset_zoom else R.string.home_tile_action_controls
        ViewCompat.replaceAccessibilityAction(v.frame, AccessibilityActionCompat.ACTION_CLICK, context.getString(hint), null)
    }

    private val matrix = Matrix()

    /** Puts the picture in its box: today's centre crop, then the zoom and pan (the box itself never changes). */
    private fun applyMatrix(v: Views) {
        val g = v.gesture ?: return
        val d = v.image.drawable ?: return
        val sw = d.intrinsicWidth
        val sh = d.intrinsicHeight
        val w = v.frame.width
        val h = v.frame.height
        if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0) return
        val p = ZoomMath.placement(sw, sh, w, h, g.scale, g.tx, g.ty)
        matrix.setScale(p.scale, p.scale)
        matrix.postTranslate(p.dx, p.dy)
        v.image.imageMatrix = matrix
    }

    /** Puts each tile's picture, or its word, on screen; [picture] is the newest bytes held for a camera id. */
    fun update(tiles: List<CameraTile>, picture: (String) -> ByteArray?) {
        for (tile in tiles) {
            val v = views[tile.id] ?: continue
            val held = liveViews(tile.id)
            if (held?.ui?.notice == LiveWhy.ROAMING) {
                // A-10: the roaming block. No picture is drawn, and none is left behind: a frozen picture would read as a live one.
                v.image.setImageDrawable(null)
                shown.remove(tile.id)
                v.overlay.text = ""
                v.gesture?.zoomEnabled = false
                applyLive(v, tile, held)
                continue
            }
            if (tile.off) {
                // 0.6.53: the viewer turned this view off: no picture, no video; the name and how to turn it on (the tap shows the controls)
                v.image.setImageDrawable(null)
                shown.remove(tile.id)
                v.overlay.setText(R.string.home_picture_off)
                v.gesture?.zoomEnabled = false
                applyLive(v, tile, null)
                continue
            }
            v.gesture?.zoomEnabled = tile.picture == PictureState.PICTURE // no pinch on a tile with no picture
            when (tile.picture) {
                PictureState.PICTURE -> {
                    val bytes = picture(tile.id)
                    if (bytes != null && shown[tile.id] !== bytes) {
                        val bitmap = decode(bytes, v)
                        if (bitmap != null) {
                            v.image.setImageBitmap(bitmap)
                            applyMatrix(v)
                            shown[tile.id] = bytes
                        }
                    }
                    v.overlay.text = ""
                }
                PictureState.LOADING -> overlayOnly(v, tile.id, R.string.home_picture_loading)
                PictureState.CAMERA_UNAVAILABLE -> overlayOnly(v, tile.id, R.string.home_picture_unavailable)
                PictureState.OFFLINE -> overlayOnly(v, tile.id, R.string.home_picture_offline)
            }
            applyLive(v, tile, liveViews(tile.id))
        }
    }

    // ---- live video (DEV-713) ----------------------------------------------------------------

    /** The ids of the tiles that are at least partly on screen (not scrolled completely out of view) and laid out: Home's offscreen rule reads it. */
    fun visibleIds(): Set<String> {
        val r = Rect()
        return views.filterValues { v -> v.frame.width > 0 && v.frame.isShown && v.frame.getGlobalVisibleRect(r) && r.width() > 0 && r.height() > 0 }.keys
    }

    /** The ids of the tiles a pinch has zoomed: they show the still while zoomed. */
    fun zoomedIds(): Set<String> = views.filterValues { it.gesture?.zoomed == true }.keys

    /** Releases every live renderer (the grid is rebuilt, or Home stops). The streams are not touched. */
    fun releaseRenderers() {
        for (v in views.values) releaseRenderer(v)
    }

    private fun releaseRenderer(v: Views) {
        restoreStill(v)
        v.rtsp?.let { t ->
            val key = v.rendererKey
            v.rtsp = null
            v.rendererKey = null
            v.rendered = false
            if (key != null) live?.unbindSurface(key, t.view)
            t.release()
        }
        val r = v.renderer ?: return
        val key = v.rendererKey
        v.renderer = null
        v.rendererKey = null
        v.rendered = false
        if (key != null) live?.unbind(key, r)
        v.frame.removeView(r)
        try {
            r.release()
        } catch (_: Exception) {
            // already released
        }
    }

    /** The renderer sits at index 1: above the still (index 0), below the words. 1x1 (never INVISIBLE) until it has drawn a frame. */
    private fun ensureRenderer(v: Views, key: LiveKey) {
        if ((v.renderer != null || v.rtsp != null) && v.rendererKey == key) return
        releaseRenderer(v)
        val surfaces = live ?: return
        if (key.rtsp) return ensureSurface(v, key, surfaces)
        val egl = surfaces.eglContext() ?: return
        val r = SurfaceViewRenderer(context)
        try {
            r.init(
                egl,
                object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {
                        // on the render thread
                        v.frame.post {
                            if (v.renderer === r) {
                                v.rendered = true
                                showRenderer(v)
                            }
                        }
                    }

                    override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {
                        onVideoHeight(key, if (rotation == 90 || rotation == 270) videoWidth else videoHeight)
                    }
                },
            )
        } catch (_: Exception) {
            try {
                r.release()
            } catch (_: Exception) {
                // never initialised
            }
            return
        }
        r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL) // the still is centre-cropped: the video matches it
        r.setEnableHardwareScaler(true)
        v.frame.addView(r, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) // BELOW the still, full size from the start (0.6.49): the still covers the hole until a frame is drawn. VISIBLE always: a SurfaceView that is not VISIBLE has no surface
        v.renderer = r
        v.rendererKey = key
        v.rendered = false
        surfaces.bind(key, r)
    }

    /**
     * 0.6.49, the still-to-video switch without a blank frame: the renderer sits BELOW the still at full size from the start, so its surface
     * is never resized (the 0.6.48 1x1 view grew to the full tile at the first frame: a new surface buffer, a blank frame, a flicker). The
     * still stays opaque on top until a frame has really been drawn, then fades out over the finished video; if the video stops or is not
     * shown (a pinch), the still is back at once.
     */
    private fun showRenderer(v: Views) {
        val show = v.videoShown && v.rendered
        v.rtsp?.show(show)
        if (show) {
            if (!v.stillHidden) {
                v.stillHidden = true
                v.image.animate().alpha(0f).setDuration(FADE_MS).start()
            }
        } else {
            restoreStill(v)
        }
    }

    private fun restoreStill(v: Views) {
        if (!v.stillHidden && v.image.alpha == 1f) return
        v.stillHidden = false
        v.image.animate().cancel()
        v.image.alpha = 1f
    }

    /** DEV-713 S1b: the away route's view, a plain SurfaceView at index 1 (above the still, below the words), see [RtspTileSurface]. */
    private fun ensureSurface(v: Views, key: LiveKey, surfaces: LiveSurfaces) {
        val t = RtspTileSurface(context, v.frame, v.image)
        v.rtsp = t
        v.rendererKey = key
        v.rendered = false
        surfaces.bindSurface(
            key, t.view,
            object : RtspTileEvents {
                override fun onRendered() {
                    if (v.rendered) return // called for every frame; only the first after a (re)start matters
                    v.frame.post {
                        if (v.rtsp === t && !v.rendered) {
                            v.rendered = true
                            showRenderer(v)
                        }
                    }
                }

                override fun onVideoSize(width: Int, height: Int) {
                    onVideoHeight(key, height)
                    v.frame.post {
                        if (v.rtsp === t) {
                            t.setVideoSize(width, height)
                            showRenderer(v)
                        }
                    }
                }
            },
        )
    }

    private fun noticeText(why: LiveWhy): Int = when (why) {
        LiveWhy.AWAY -> R.string.live_notice_away
        LiveWhy.NOT_SET_UP -> R.string.live_notice_notsetup
        LiveWhy.NO_LIVE -> R.string.live_notice_nolive
        LiveWhy.TOO_MANY -> R.string.live_notice_toomany
        LiveWhy.DECODER -> R.string.live_notice_decoder
        LiveWhy.CONNECT -> R.string.live_notice_connect
        LiveWhy.SERVER -> R.string.live_notice_server
        LiveWhy.HOT -> R.string.live_notice_hot
        LiveWhy.PHONE_LIMIT -> R.string.live_notice_phonelimit
        LiveWhy.ROAMING -> R.string.roaming_notice
    }

    /** Draws the live words and the renderer of one tile from `:core`'s state ([lv] null: nothing live, a plain still). */
    private fun applyLive(v: Views, tile: CameraTile, lv: LiveTileView?) {
        val chip = v.chip ?: return
        val ui = lv?.ui ?: TileLiveUi.NONE
        val key = lv?.key
        if (key == null || ui.chip == ChipLook.NONE) releaseRenderer(v) else ensureRenderer(v, key)
        v.videoShown = ui.video
        // a stream that stopped and starts again must draw a fresh frame before it is shown (frames keep reporting, so this heals itself)
        if (ui.chip != ChipLook.ON && v.rtsp != null) v.rendered = false
        v.chipLook = ui.chip
        v.canRetry = lv?.canRetry == true
        showRenderer(v)
        if (ui.video) v.overlay.text = "" // the words of a missing still never sit over a playing video
        v.gesture?.zoomEnabled = tile.picture == PictureState.PICTURE || ui.video // a pinch on a playing tile shows the still
        val why = ui.notice
        v.notice?.let {
            if (why == null) {
                it.visibility = View.GONE
            } else {
                it.setText(noticeText(why))
                it.visibility = View.VISIBLE
            }
        }
        chip.show(LiveChipGlyphs.lead(ui.chip), LiveChipGlyphs.trail(ui.chip, v.canRetry, ui.notice, ui.fps), ui.fps)
        when (ui.chip) {
            ChipLook.NONE -> chip.visibility = View.GONE
            ChipLook.OFFER -> drawChip(chip, R.string.live_chip_label, R.string.live_chip_desc_offer, 0xFFDDDDDD.toInt())
            ChipLook.PENDING -> drawChip(
                chip, R.string.live_chip_label,
                if (v.canRetry) R.string.live_chip_desc_retry else R.string.live_chip_desc_pending, 0xFFDDDDDD.toInt(),
            )
            ChipLook.ON -> drawChip(chip, R.string.live_chip_label, R.string.live_chip_desc_on, Color.WHITE)
            ChipLook.ROAMING -> drawChip(chip, R.string.roaming_chip, R.string.roaming_chip_desc, Color.WHITE)
        }
    }

    /** The word only. The icon before it and the spinner / fps after it are the chip's own fixed slots ([LiveChipView.show]), never text. */
    private fun drawChip(chip: TextView, text: Int, description: Int, color: Int) {
        chip.text = context.getString(text)
        chip.setTextColor(color)
        chip.contentDescription = context.getString(description)
        chip.visibility = View.VISIBLE
    }

    /**
     * Decodes at the size the box needs ([ImageSampling]): a 1x tile gets about its own pixels, not the camera's, and
     * nothing decoded is over [ImageSampling.MAX_PIXELS]. A picture that cannot be decoded is simply not shown.
     */
    private fun decode(bytes: ByteArray, v: Views): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = v.frame.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val h = v.frame.height.takeIf { it > 0 } ?: (w * 9 / 16)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = ImageSampling.sampleSize(bounds.outWidth, bounds.outHeight, w, h, v.gesture?.scale ?: 1f)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun overlayOnly(v: Views, id: String, text: Int) {
        if (shown.remove(id) != null) v.image.setImageDrawable(null)
        v.overlay.setText(text)
    }
}
