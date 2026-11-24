package com.meda.portico.home

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.meda.portico.R
import com.meda.portico.core.content.ButtonTile
import com.meda.portico.core.content.CameraTile
import com.meda.portico.core.content.CameraModes
import com.meda.portico.core.content.CameraState
import com.meda.portico.core.content.CameraViewport
import com.meda.portico.core.content.ControlStrip
import com.meda.portico.core.content.HomeView
import com.meda.portico.core.content.StreamHeights
import com.meda.portico.core.content.StreamLabels
import com.meda.portico.core.content.StreamPick
import com.meda.portico.core.content.ViewLayout
import com.meda.portico.core.content.ViewportKey
import com.meda.portico.core.content.ViewportMemory
import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.live.LiveKey
import com.meda.portico.core.live.LiveTileView

/**
 * DEV-768 P3, the glue of the camera viewport controls on Home: the control strip a tap shows for 3 s, its two menus (resize, video size),
 * and the fullscreen overlay with the block's own buttons over the picture. THIN on purpose: every decision (which items, which stream, what a
 * pick does, how long the strip stays, what survives a rotation) is `:core`'s [CameraViewport] / [ViewportMemory] / [ControlStrip], tested on
 * the JVM; this class only draws it and calls [Host].
 *
 * Fullscreen is NOT a re-parent of the page tile's video view: a second [StillTiles] (the overlay's) builds a fresh tile for the same camera and
 * the page's tile lets go of the stream while it is fullscreen ([pageLiveViews] returns null for it). The stream session (LiveHome) never
 * changes key on entering or leaving (the stream is [stream], the same on both sides), so there is no restart: only the renderer is bound
 * to the other view. The still stays under the video as the poster, so the swap is a still for a moment, never a blank tile.
 */
class ViewportController(
    private val activity: AppCompatActivity,
    private val host: Host,
    private val surfaces: LiveSurfaces,
    restored: String?,
) {
    interface Host {
        fun view(): HomeView
        fun liveViews(cameraId: String): LiveTileView?

        /** 0.6.53: the camera's state as the gear menu sees it: the saved last live resolution, the LIVE chip (live or stills) and the off switch. */
        fun cameraState(tile: CameraTile): CameraState

        /** Saves [state] (resolution + off in CameraChoices, live/stills in LiveMemory) and draws and plans again. */
        fun applyState(tile: CameraTile, state: CameraState)

        /** The Big / Small of the resize menu: remember it (SizeMemory) and lay the page out again. */
        fun setTileSize(tile: CameraTile, size: CameraSize)

        /** Something that changes what a tile asks for (the stream): look at the screen again. */
        fun streamChanged()

        /** The page was drawn from a new state: draw again now. */
        fun redraw()

        fun press(tile: ButtonTile)
        fun picture(cameraId: String): ByteArray?
        fun journal(body: String)

        /** Tablet (panel) mode: the resize menu offers the Partial layout (a big camera with up to two small ones of its block at its right). */
        fun partialAvailable(): Boolean = false
    }

    private val memory = ViewportMemory.decode(restored)
    private val strip = ControlStrip()
    private val handler = Handler(Looper.getMainLooper())
    private val hideStrip = Runnable { paintStrip() }

    /** The page's tiles: set by the activity (they are its own [StillTiles]). */
    lateinit var pageTiles: StillTiles

    private var fullTiles: StillTiles? = null
    private var overlay: FrameLayout? = null
    private var slot: FrameLayout? = null
    private var buttonBar: LinearLayout? = null
    private var shownButtons: List<ButtonTile>? = null
    private var barsHidden = false
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = leaveFullscreen()
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, back)
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    fun encode(): String = memory.encode()

    /** The selected page's id, set by [begin] at every draw (read for every tile of every draw, so it is never rebuilt from the model here). */
    private var page: String? = null

    /** Called first in every draw of Home. */
    fun begin(view: HomeView) {
        page = view.selectedId
    }

    private fun pageId(): String? = page

    private fun key(cameraId: String): ViewportKey? = pageId()?.let { ViewportKey(it, cameraId) }

    private fun pageTile(cameraId: String): CameraTile? = host.view().cameras.firstOrNull { it.id == cameraId }

    /** The camera whose block is drawn in the partial layout now, or null (none, another page, or not tablet mode). Read by the activity when it builds the view ([CameraViewport.withPartial]). */
    fun partialCameraId(): String? = memory.partial?.takeIf { it.pageId == pageId() && host.partialAvailable() }?.cameraId

    private fun fullscreenCamera(): String? = memory.fullscreen?.takeIf { it.pageId == pageId() }?.cameraId

    // ---- what the live code is told ---------------------------------------------------------

    /** The stream this camera's viewport asks for: `:core`'s verdict from the pick, the tile's size and whether a small stream exists. */
    fun stream(tile: CameraTile, pageId: String? = this.page): CameraSize {
        val pick = host.cameraState(tile).pick // the saved last live resolution (0.6.53: kept on the phone, per camera)
        return CameraViewport.stream(pick, tile.size, tile.hasSmall)
    }

    /** Live state the page's tile draws: none while that camera is fullscreen (the overlay's tile holds the stream). */
    fun pageLiveViews(cameraId: String): LiveTileView? = if (cameraId == fullscreenCamera()) null else host.liveViews(cameraId)

    private fun fullLiveViews(cameraId: String): LiveTileView? = if (cameraId == fullscreenCamera()) host.liveViews(cameraId) else null

    fun zoomedIds(): Set<String> = fullTiles?.zoomedIds() ?: emptySet()

    // ---- the strip --------------------------------------------------------------------------

    fun onTap(tile: CameraTile) {
        strip.show(tile.id, now())
        paintStrip()
    }

    /** Draws the strip on the tile it belongs to and schedules its end. */
    fun paintStrip() {
        val t = now()
        val id = strip.visibleId(t)
        pageTiles.showStrip(id)
        fullTiles?.showStrip(id)
        handler.removeCallbacks(hideStrip)
        strip.nextCheckAt(t)?.let { handler.postDelayed(hideStrip, (it - t).coerceAtLeast(0) + 16) }
    }

    // ---- menus ------------------------------------------------------------------------------

    /** 0.6.49: icons, not words (the words are each entry's contentDescription), in a panel that opens upward from [anchor] ([IconMenu]). */
    fun resizeMenu(tile: CameraTile, anchor: View) {
        val cur = layoutOf(tile.id)
        val block = host.view().blocks.firstOrNull { b -> b.cameras.any { it.id == tile.id } }
        val entries = CameraViewport.resizeItems(cur, partialOffered = host.partialAvailable() && (block?.cameras?.size ?: 0) > 1).map { item ->
            val exit = item.layout == ViewLayout.FULLSCREEN && item.checked
            val label = when {
                exit -> R.string.home_resize_exit
                item.layout == ViewLayout.BIG -> R.string.home_resize_big
                item.layout == ViewLayout.SMALL -> R.string.home_resize_small
                item.layout == ViewLayout.PARTIAL -> R.string.home_resize_partial
                else -> R.string.home_resize_fullscreen
            }
            val icon = when {
                exit -> R.drawable.ic_view_fullscreen_exit
                item.layout == ViewLayout.BIG -> R.drawable.ic_view_big
                item.layout == ViewLayout.SMALL -> R.drawable.ic_view_small
                item.layout == ViewLayout.PARTIAL -> R.drawable.ic_view_partial
                else -> R.drawable.ic_view_fullscreen
            }
            IconMenu.Entry(item.layout.ordinal, icon, null, activity.getString(label), item.checked)
        }
        openIcons(anchor, entries) { resize(tile, ViewLayout.entries[it]) }
    }

    /**
     * The video size chooser (0.6.53): a YouTube-style text list. Each stream is named by its real vertical resolution ("1080p", "360p"), see
     * [StreamLabels]; until a stream's height has been seen (it plays, or has played) its row says "High" (main) / "Low" (small). Auto is the
     * word "Auto", with the quality it resolves to in brackets when known ("Auto (720p)"). On a still the row "stills" is shown, informational.
     */
    fun settingsMenu(tile: CameraTile, anchor: View) {
        val page = pageTile(tile.id) ?: return
        val spec = CameraViewport.streamMenu(host.cameraState(page), page.size, page.hasSmall) { heights.height(tile.id, it, page.serverHeight(it)) }
        host.journal(CameraViewport.menuLabelsLine(tile.id, { heights.height(tile.id, it) }, { page.serverHeight(it) }))
        val entries = ArrayList<IconMenu.Entry>()
        for (item in spec.items) {
            val text = when (item.pick) {
                StreamPick.AUTO -> item.quality?.let { activity.getString(R.string.home_stream_auto, it) } ?: activity.getString(R.string.home_stream_auto_plain)
                StreamPick.BIG -> item.quality ?: activity.getString(R.string.home_stream_high)
                StreamPick.SMALL -> item.quality ?: activity.getString(R.string.home_stream_low)
                StreamPick.STILLS -> activity.getString(R.string.home_stream_stills)
                StreamPick.OFF -> activity.getString(R.string.home_stream_off)
            }
            val description = when (item.pick) {
                StreamPick.AUTO, StreamPick.STILLS, StreamPick.OFF -> text
                else -> activity.getString(R.string.home_stream_desc, text)
            }
            entries.add(IconMenu.Entry(item.pick.ordinal, null, text, description, item.checked, list = true))
        }
        openIcons(anchor, entries) { pick(tile, StreamPick.entries[it]) }
    }

    /** The picture heights seen so far, per camera and stream variant (fed by the tiles' decoders, see [videoHeight]). */
    private val heights = StreamHeights()

    /** A tile's decoder or renderer reported the picture size of the stream [key] (any thread): remembered for the menu's labels. */
    fun videoHeight(key: LiveKey, height: Int) {
        heights.record(key.cameraId, key.size, height)
    }

    private fun openIcons(anchor: View, entries: List<IconMenu.Entry>, onPick: (Int) -> Unit) {
        strip.hold(true, now())
        IconMenu(activity).show(anchor, entries, onPick) {
            strip.hold(false, now())
            paintStrip()
        }
    }

    // ---- actions ----------------------------------------------------------------------------

    private fun layoutOf(cameraId: String): ViewLayout {
        val size = pageTile(cameraId)?.size ?: CameraSize.BIG
        return ViewLayout.of(size, cameraId == fullscreenCamera(), partial = cameraId == partialCameraId())
    }

    /** A row of the gear menu was chosen: [CameraModes.choose] says what the camera is set to now; [Host.applyState] saves and applies it. */
    fun pick(tile: CameraTile, row: StreamPick) {
        val page = pageTile(tile.id) ?: return
        val before = host.cameraState(page)
        val after = CameraModes.choose(before, row)
        if (after != before) {
            host.journal(CameraViewport.pickLine(tile.id, row, CameraViewport.stream(after.pick, page.size, page.hasSmall), after.live && !after.off))
            host.applyState(page, after)
        }
        strip.show(tile.id, now())
        paintStrip()
    }

    fun resize(tile: CameraTile, target: ViewLayout) {
        val page = pageTile(tile.id) ?: return
        val k = key(tile.id) ?: return
        val from = layoutOf(tile.id)
        val r = CameraViewport.resize(from, target, page.size)
        val wasFullscreen = from == ViewLayout.FULLSCREEN
        if (r.fullscreen) memory.setFullscreen(k) else if (wasFullscreen) leaveFullscreenQuietly()
        // Partial belongs to one camera: choosing it sets it; any other choice on that camera (or a size change anywhere) ends it.
        if (r.partial) memory.setPartial(k) else if (from == ViewLayout.PARTIAL || r.fullscreen || r.newTileSize != null) memory.setPartial(null)
        val to = if (r.fullscreen) ViewLayout.FULLSCREEN else if (r.partial) ViewLayout.PARTIAL else ViewLayout.of(r.newTileSize ?: page.size, false)
        if (to != from) host.journal(CameraViewport.resizeLine(tile.id, from, to, stream(page)))
        strip.show(tile.id, now())
        val size = r.newTileSize
        if (size != null) host.setTileSize(page, size) else host.redraw()
        paintStrip()
    }

    // ---- fullscreen -------------------------------------------------------------------------

    fun isFullscreen(): Boolean = fullscreenCamera() != null

    /** The camera that is fullscreen now, or null. */
    fun fullscreenCameraId(): String? = fullscreenCamera()

    /** The id among [wanted] whose still is drawn fullscreen (asked for at the Big width), or null. */
    fun fullscreenStillOf(wanted: List<CameraTile>): String? = fullscreenCamera()?.takeIf { id -> wanted.any { it.id == id } }

    private fun leaveFullscreen() {
        val id = fullscreenCamera() ?: return
        pageTile(id)?.let { resize(it, ViewLayout.FULLSCREEN) } ?: run {
            leaveFullscreenQuietly()
            host.redraw()
        }
    }

    private fun leaveFullscreenQuietly() {
        memory.setFullscreen(null)
        tearDownOverlay()
    }

    /** Called by the page after every draw: drops what is not open any more, and builds, updates or removes the fullscreen overlay. */
    fun sync(view: HomeView) {
        val pg = view.selectedId
        val open = if (pg == null) emptySet() else view.cameras.map { ViewportKey(pg, it.id) }.toSet()
        if (!view.rosterSelected && memory.retain(open)) strip.hide()
        val id = fullscreenCamera()
        if (id == null) {
            tearDownOverlay()
            back.isEnabled = false
            paintStrip()
            return
        }
        val tile = view.cameras.firstOrNull { it.id == id } ?: return
        back.isEnabled = true
        val tiles = ensureOverlay(tile)
        overlay?.setPadding(0, 0, 0, com.meda.portico.panel.PanelScreen.stripPx(activity)) // Panel mode: the Close/countdown strip is below the picture and its buttons
        tiles.update(listOf(tile.copy(size = CameraSize.BIG))) { host.picture(it) }
        val block = view.blocks.firstOrNull { b -> b.cameras.any { it.id == id } }
        val buttons = block?.buttons ?: emptyList()
        if (buttons != shownButtons) fillButtons(buttons)
        hideBars(true)
        paintStrip()
    }

    private fun ensureOverlay(tile: CameraTile): StillTiles {
        fullTiles?.let { return it }
        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true // nothing under the overlay may get a touch
            elevation = 100f
        }
        val s = FrameLayout(activity)
        root.addView(s, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // 0.6.62: no scrim on the row (the owner): the buttons float over the video with only their own backgrounds.
            val p = (8 * activity.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(exitButton(), FrameLayout.LayoutParams(dp(EXIT_BUTTON_DP), dp(EXIT_BUTTON_DP), Gravity.TOP or Gravity.END).apply { setMargins(dp(12), dp(12), dp(12), dp(12)) })
        activity.addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val tiles = StillTiles(activity, LinearLayout(activity), 1, fullscreenTiles = true)
        tiles.listener = pageTiles.listener
        tiles.live = surfaces
        tiles.liveViews = { fullLiveViews(it) }
        tiles.addFullscreen(s, tile.copy(size = CameraSize.BIG))
        overlay = root
        slot = s
        buttonBar = bar
        shownButtons = null
        fullTiles = tiles
        return tiles
    }

    /**
     * 0.6.63 (the owner): one tap leaves fullscreen. Top-right (the LIVE chip is top-left, the gear/size strip bottom-right). A round 48 dp target with the
     * "exit full screen" glyph; the semi-transparent disc is the button's own background, nothing sits behind any row. The tap is the Back press:
     * [leaveFullscreen], the one exit path.
     */
    private fun exitButton(): View = ImageButton(activity).apply {
        setImageResource(R.drawable.ic_view_fullscreen_exit)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = dp(12)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x99000000.toInt())
        }
        contentDescription = activity.getString(R.string.home_exit_fullscreen_button)
        setOnClickListener { leaveFullscreen() }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    private fun fillButtons(buttons: List<ButtonTile>) {
        val bar = buttonBar ?: return
        bar.removeAllViews()
        // 0.6.49: in landscape the buttons keep their compact width (wrap), centred, instead of stretching across the screen; portrait is unchanged.
        val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (buttons.isNotEmpty()) for (row in ActionButtons(activity).blockRows(buttons, host::press, compact = landscape)) bar.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        bar.visibility = if (buttons.isEmpty()) View.GONE else View.VISIBLE
        shownButtons = buttons
    }

    private fun tearDownOverlay() {
        val tiles = fullTiles ?: return
        tiles.releaseRenderers() // the stream's slot is free before the page's tile binds to it
        slot?.removeAllViews()
        (overlay?.parent as? ViewGroup)?.removeView(overlay)
        fullTiles = null
        overlay = null
        slot = null
        buttonBar = null
        shownButtons = null
        hideBars(false)
    }

    private fun hideBars(hide: Boolean) {
        if (barsHidden == hide) return
        barsHidden = hide
        com.meda.portico.panel.PanelScreen.systemBars(activity, fullscreenVideo = hide) // Panel mode keeps them hidden after fullscreen too
    }

    /** Home stopped (another app, the screen off): the overlay's renderers go with the page's. The memory stays: the screen comes back. */
    fun onStop() {
        handler.removeCallbacks(hideStrip)
        strip.hide()
        fullTiles?.releaseRenderers()
        fullTiles?.resetZoom()
    }

    /** The page changed: the viewports of the old page are closed. */
    fun onPageChange() {
        memory.clear()
        strip.hide()
        leaveFullscreenQuietly()
    }

    private companion object {
        const val NOTE_ID = 101
        const val EXIT_BUTTON_DP = 48
    }
}
