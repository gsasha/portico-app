package com.meda.portico.home

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.PopupMenu
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import com.meda.portico.AppGraph
import com.meda.portico.PorticoApp
import com.meda.portico.R
import com.meda.portico.call.CallActions
import com.meda.portico.call.OutgoingCallActivity
import com.meda.portico.call.stationCall
import com.meda.portico.core.call.CallUi
import com.meda.portico.core.call.CallUiState
import com.meda.portico.core.call.Phase
import com.meda.portico.core.callbar.StationCall
import com.meda.portico.core.ring.DoorUnlockRule
import com.meda.portico.core.ring.RingPress
import com.meda.portico.core.call.PlaceRequest
import com.meda.portico.core.call.TalkPhase
import com.meda.portico.core.call.TalkRequest
import com.meda.portico.core.call.TalkUiState
import com.meda.portico.core.content.Banner
import com.meda.portico.core.content.PageUpdate
import com.meda.portico.core.content.ScrollReveal
import com.meda.portico.core.content.CameraState
import com.meda.portico.core.content.CameraViewport
import com.meda.portico.core.content.CameraTile
import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.content.ViewLayout
import com.meda.portico.core.content.StreamPick
import com.meda.portico.core.content.StillWidth
import com.meda.portico.core.content.SwipeGuard
import com.meda.portico.core.content.ButtonState
import com.meda.portico.core.content.ButtonTile
import com.meda.portico.core.content.Pager
import com.meda.portico.core.content.CallerView
import com.meda.portico.core.content.StationPanel
import com.meda.portico.core.content.StationTab
import com.meda.portico.core.content.StationTabMemory
import com.meda.portico.core.content.PressRoute
import com.meda.portico.core.content.StillSchedule
import com.meda.portico.core.content.Swipe
import com.meda.portico.core.content.HomeModel
import com.meda.portico.core.content.TopLine
import com.meda.portico.core.content.HomeView
import com.meda.portico.core.content.LayoutJournal
import com.meda.portico.core.content.PictureState
import com.meda.portico.core.content.RosterRow
import com.meda.portico.core.content.RowState
import com.meda.portico.core.live.LiveGlobal
import com.meda.portico.core.live.LiveKey
import com.meda.portico.core.live.LiveTileIn
import com.meda.portico.live.LiveHome
import com.meda.portico.live.LiveInput
import com.meda.portico.core.ring.ConnectionModel
import com.meda.portico.core.ring.Health
import com.meda.portico.core.ring.LinkState
import com.meda.portico.core.ring.StatusLabel
import com.meda.portico.pause.PauseNotices
import com.meda.portico.setup.SettingsStatusSplit
import com.meda.portico.setup.SetupActivity

/**
 * Home (2026-10-12): the cameras and buttons Home Assistant built for this phone (portico-ha content
 * design). Every rule about what is shown and when a button works is `:core`'s [HomeModel]; this class
 * only draws its [HomeView] and asks [AppGraph] to press a button or fetch a picture.
 *
 * - The connection status is in the top bar, never something to scroll to (the intercom app's lesson): a
 *   dot whose colour is the health (green only when Home Assistant is answering) over a word for the
 *   route ("direct", "Tailscale") or the reason. Paused is not a fault: the title says so, amber. Every
 *   rule is `:core`'s [ConnectionModel]. Tapping the status opens Settings, where the per-path reasons are.
 * - One top line: title, status, and a hamburger button at the top right whose menu is Refresh / Settings / About
 *   (About is a dialog, no network).
 * - Blocks (DEV-658): the selected page is drawn block by block: a heading, a camera grid (a big tile a full row, small ones
 *   two to a row) and rows of buttons (`:core`'s CameraGrid and ButtonRows). A button draws its icon (HomeIcons: its own,
 *   else its kind's) before its text, which the server may hide when there is an icon; its colour is a palette name, drawn
 *   in the light or the dark swatch of the system theme; one that is off is grey, with its reason where the server gave one.
 * - The whole screen scrolls (the checklist: a growable screen must).
 * - Not connected: the saved tiles stay, every button is off, no picture is shown, and a line says so.
 *   Nothing is queued: a button pressed with no connection sends nothing and says so.
 * - A button with "asks first" opens a confirmation before anything is sent. The plugin does not rely on
 *   that; it is the user's safety net.
 * - Pages (A2): a document with more than one page shows its page names as tabs IN the top line (the title's slot; PAUSED takes it
 *   while paused) and a swipe (left or right, mostly horizontal, started anywhere but the tabs) that moves between pages. Only the selected page is drawn
 *   and only its cameras are fetched. The selection is kept by page id across a refresh and a rotation.
 * - APP-03: a call FROM THE STATION has no screen of its own. The ring's full-screen intent, its notification and its Answer action open
 *   this screen (over the lock screen, only while that call lasts); the calling door's Talk button shows the call exactly as a call the
 *   phone placed does (green, white icon, no animation) and the call bar below it holds Answer and Decline, then End.
 *   Answer, Decline and End need no unlock; every other press while the phone is locked asks for it first ([DoorUnlockRule]).
 * - app-05 (D-20261110-78f9): the ring goes into the page itself. A NEW ring selects the tab that holds the calling door's Talk button
 *   (`:core`'s StationTab / StationTabMemory) and gives the user's tab back when the call is gone, unless they changed tab meanwhile. While the
 *   call RINGS the door's Ring page content is a panel above the page (StationPanel): its cameras (stills) and its buttons (`ring_buttons`
 *   and `call_buttons` merged). Answering closes the panel (the ring view is over); the call goes on as one started from the phone.
 *   Pressing a panel button needs the unlock and keeps its confirm.
 * - Pictures: one still per camera of the selected page every [SNAPSHOT_EVERY_MS] while this screen is visible and
 *   connected, fetched over the same socket (so over the tailnet too), one request in flight per camera. Nothing
 *   runs while the screen is not visible.
 *
 * Logs nothing.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var graph: AppGraph
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var title: TextView
    private lateinit var statusDot: TextView
    private lateinit var statusLabel: TextView
    private var titleColor = 0
    private lateinit var banner: TextView
    private lateinit var result: TextView
    private lateinit var blocks: LinearLayout
    private lateinit var blocksEmpty: TextView
    private lateinit var tabsScroll: HorizontalScrollView
    private lateinit var tabsRow: LinearLayout
    private lateinit var stillTiles: StillTiles
    private lateinit var blockViews: BlockViews

    /** DEV-768 P3: the camera viewport controls (control strip, its menus, fullscreen). All rules are `:core`'s; see [ViewportController]. */
    private lateinit var viewport: ViewportController
    private lateinit var rosterViews: RosterViews

    /** app-05: the Ring page of a station call (cameras and buttons), drawn above the page; stills only. [shownPanel] is what is drawn now. */
    private lateinit var panel: LinearLayout
    private lateinit var panelButtons: LinearLayout
    private lateinit var panelTiles: StillTiles
    private lateinit var panelActions: ActionButtons
    private var shownPanel: CallerView? = null

    /** app-05: the tab a station call moved Home to, and the user's tab to give back. Saved across a rotation. */
    private var tabMemory = StationTabMemory()

    /** DEV-713: the live streams of the tiles (held outside the view tree), and whether this screen is started (between onStart and onStop). */
    private lateinit var liveHome: LiveHome
    private var started = false
    private var pathUnknownNoted = false

    /** 0.6.50: a tile scrolled out of view keeps its stream 30 s ([OffscreenGrace]); cleared on stop (the background ends every stream at once). */
    private val offscreenGrace = com.meda.portico.core.live.OffscreenGrace()
    private val liveSync = Runnable { if (started) syncLive() }

    /** The page shown, by id (null: the first). Kept across refreshes and rotation; an id the document dropped falls back to the first. */
    private var selectedPageId: String? = null
    private val stills = StillSchedule(SNAPSHOT_EVERY_MS)
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private val swipeGuard = SwipeGuard()

    private var shown: HomeView? = null
    private var notice: Pair<String, Long>? = null

    /** Talk (blocks B7b): the attempt the microphone was last asked for, and the listener's remover. */
    private var talkMicAskedFor = -1
    private var talkRemover: (() -> Unit)? = null

    /** APP-03: the answer of a station call (its listener, the microphone attempt, the last end already worded) and the ring's change listener. */
    private var answerRemover: (() -> Unit)? = null
    private var ringRemover: (() -> Unit)? = null
    private var answerMicAskedFor = -1
    private var announcedGeneration = -1
    private var overLock = false

    /** Called on the answer thread when the answer of a station call changes. */
    private val answerListener: (CallUiState) -> Unit = { s ->
        runOnUiThread {
            askAnswerMic(s)
            announceAnswerEnd(s)
            render()
        }
    }

    /** Called on the call thread when this phone's talk changes: the microphone is asked for once per attempt, the screen redrawn on the main thread. */
    private val talkListener: (TalkUiState) -> Unit = { s ->
        runOnUiThread {
            askTalkMic(s)
            render()
        }
    }

    /** Called on the link's thread when content changes: the screen is redrawn on the main thread. */
    private val contentListener: () -> Unit = {
        runOnUiThread {
            // A real answer from Home Assistant: forget this phone's size choices its edits made out of date (never from a cached or empty document).
            if (graph.content.answered) graph.sizes.reconcile(graph.content.profile)
            if (graph.content.answered) graph.liveChoices.reconcile(graph.content.profile) // DEV-713: the same rule for the LIVE chip's choices
            render()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            requestPicturesIfDue()
            render() // before the live plan: a station ring's tab switch (the page the pre-warm warmed) is made here
            syncLive()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        graph = PorticoApp.graph(this)
        title = findViewById(R.id.home_title)
        titleColor = title.currentTextColor
        statusDot = findViewById(R.id.home_status_dot)
        statusLabel = findViewById(R.id.home_status_label)
        findViewById<View>(R.id.home_status).setOnClickListener { openSetup(SettingsStatusSplit.HOME_STATUS_LINE_TARGET) }
        banner = findViewById(R.id.home_banner)
        result = findViewById(R.id.home_result)
        blocks = findViewById(R.id.home_blocks)
        blocksEmpty = findViewById(R.id.home_blocks_empty)
        tabsScroll = findViewById(R.id.home_tabs)
        tabsRow = findViewById(R.id.home_tabs_row)
        stillTiles = StillTiles(this, blocks, 1)
        // 0.6.99 (D-20261122-b45c): in Panel mode a Big camera is scaled to fit the screen with its buttons (letterboxed, never cropped)
        stillTiles.maxTileHeightPx = {
            val dm = resources.displayMetrics
            val dp = com.meda.portico.core.panel.PanelLayout.maxTileHeightDp(try { graph.panelMode() } catch (_: Exception) { false }, (dm.heightPixels / dm.density).toInt())
            (dp * dm.density).toInt()
        }
        stillTiles.listener = object : StillTiles.Listener {
            override fun onTap(tile: CameraTile) = viewport.onTap(tile)
            override fun onResizeButton(tile: CameraTile, anchor: View) = viewport.resizeMenu(tile, anchor)
            override fun onSettingsButton(tile: CameraTile, anchor: View) = viewport.settingsMenu(tile, anchor)
            override fun onResizeTo(tile: CameraTile, layout: ViewLayout) = viewport.resize(tile, layout)
            override fun onPickStream(tile: CameraTile, pick: StreamPick) = viewport.pick(tile, pick)
            override fun onWidthChanged(cameraId: String) = stills.reset(cameraId) // the sharper (or lighter) still is due at the next tick
            override fun onLiveChip(tile: CameraTile) = liveChip(tile)
        }
        // DEV-713: live video. A tile that streams draws its renderer; a scroll looks at the screen again after 300 ms.
        // the graph's ONE LiveHome (shared with the ring pre-warm: a stream it started before this screen was up is adopted, not restarted)
        liveHome = graph.homeLive.acquire(this) {
            if (started && !isFinishing && !isDestroyed) render()
        }
        stillTiles.live = liveHome
        viewport = ViewportController(this, viewportHost, liveHome, savedInstanceState?.getString(KEY_VIEWPORT))
        viewport.pageTiles = stillTiles
        stillTiles.onVideoHeight = viewport::videoHeight
        stillTiles.liveViews = { viewport.pageLiveViews(it) } // none for the camera that is fullscreen: the overlay's tile holds its stream
        findViewById<View>(R.id.home_scroll).setOnScrollChangeListener { _, _, _, _, _ ->
            handler.removeCallbacks(liveSync)
            handler.postDelayed(liveSync, LIVE_SCROLL_DEBOUNCE_MS)
        }
        blockViews = BlockViews(this, stillTiles, ActionButtons(this))
        panel = findViewById(R.id.home_station_panel)
        panelButtons = findViewById(R.id.home_station_buttons)
        panelTiles = StillTiles(this, findViewById(R.id.home_station_stills), 1)
        panelActions = ActionButtons(this)
        rosterViews = RosterViews(this)
        selectedPageId = savedInstanceState?.getString(KEY_PAGE)
        tabMemory = StationTabMemory(StationTabMemory.State(savedInstanceState?.getString(KEY_CALL), savedInstanceState?.getString(KEY_RESTORE), savedInstanceState?.getString(KEY_SELECTED)))
        findViewById<View>(R.id.home_menu).setOnClickListener { showMenu(it) }
        // APP-03: opened by a station ring. The Answer action presses only on the first delivery: a recreated activity (rotation, a process
        // restore) gets the same intent again and must not answer a second time.
        takeRing(intent, answerNow = savedInstanceState == null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeRing(intent, answerNow = true)
    }

    /**
     * APP-03: the ring's PendingIntents (and the call bar's Answer button). A ring intent turns the screen on and shows this one over the
     * lock screen for as long as the station call lasts ([paintStation] turns that off again). Answer is the user's press: only the
     * Answer action presses, and the coordinator ignores a second press for a call already being answered.
     */
    private fun takeRing(i: Intent, answerNow: Boolean) {
        if (i.action != ACTION_SHOW && i.action != ACTION_ANSWER) return
        showOverLock(true)
        val d = i.getStringExtra(EXTRA_DOOR)
        val c = i.getStringExtra(EXTRA_CALL)
        if (i.action == ACTION_ANSWER && answerNow && d != null && c != null) {
            // 0.6.76: the call window's Answer and the notification's Answer both land here: the call is shown on this screen (the call bar).
            try {
                graph.pushJournal.add("${com.meda.portico.core.push.PushJournalFormat.time(System.currentTimeMillis(), java.util.TimeZone.getDefault())} ${com.meda.portico.core.callwindow.CallWindowModel.ANSWERED_HOME_LINE}")
            } catch (_: Throwable) {
                // observation only
            }
            // the owner 2026-11-21: this arrives with the phone locked from the notification's Answer; with "Answer without unlocking" on, the
            // show-when-locked in-call screen takes the call (this screen, whose lock flag is set late in code, is not relied on to be
            // shown); off, the unlock prompt comes first. Unlocked: the press, as before.
            com.meda.portico.call.LockedAnswer.decide(this, graph, c, { route ->
                if (route == com.meda.portico.core.ring.AnswerLockPolicy.Route.IN_CALL_OVER_LOCK) {
                    try { com.meda.portico.call.LockedAnswer.openInCall(this, graph, d, c) } catch (_: Exception) { } // it presses first; a failed start of the screen leaves the call as answered
                } else {
                    graph.answer.press(d, c)
                }
            }, {})
        }
    }

    /** Shows this screen over the lock screen and turns the screen on (the window flags below Android 8.1); never requests an unlock itself. */
    private fun showOverLock(on: Boolean) {
        if (overLock == on) return
        overLock = on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedPageId?.let { outState.putString(KEY_PAGE, it) }
        viewport.encode().takeIf { it.isNotEmpty() }?.let { outState.putString(KEY_VIEWPORT, it) } // a rotation keeps the picks and fullscreen; nothing is stored
        tabMemory.state.let { st ->
            st.callId?.let { outState.putString(KEY_CALL, it) }
            st.restoreTo?.let { outState.putString(KEY_RESTORE, it) }
            st.selected?.let { outState.putString(KEY_SELECTED, it) }
        }
    }

    override fun onDestroy() {
        offscreenGrace.clear()
        graph.homeLive.release(this) // the live thread ends with the last holder: HomeLiveGrace holds one for 30 s after onStop, a rotation's successor takes over within 3 s
        super.onDestroy()
    }

    /**
     * The page swipe. The activity only watches the touches (it never consumes one, so scrolling and buttons behave as
     * before): a mostly horizontal drag that starts outside the tab strip, which scrolls sideways by itself, picks
     * the next or previous page by `:core`'s [Swipe]. B6: never while a picture is zoomed or when a second finger touched
     * (a pinch, a pan of a zoomed picture): `:core`'s [SwipeGuard] decides.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.rawX
                swipeDownY = ev.rawY
                val strip = Rect()
                val onStrip = tabsScroll.visibility == View.VISIBLE && tabsScroll.getGlobalVisibleRect(strip) && strip.contains(ev.rawX.toInt(), ev.rawY.toInt())
                swipeGuard.down(onStrip || viewport.isFullscreen(), stillTiles.anyZoomed() || panelTiles.anyZoomed())
            }
            MotionEvent.ACTION_POINTER_DOWN -> swipeGuard.pointerDown()
            MotionEvent.ACTION_UP -> {
                if (swipeGuard.up()) {
                    val direction = Swipe.direction(ev.rawX - swipeDownX, ev.rawY - swipeDownY, dp(SWIPE_MIN_DP).toFloat())
                    if (direction != 0) {
                        val view = currentView()
                        val to = Pager.step(view.tabs.size, view.selected, direction)
                        if (to != view.selected) selectPage(view.tabs[to].id)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> swipeGuard.cancel()
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * What the camera viewport controls (DEV-768 P3) need from Home. A tap on a tile no longer flips its size: it shows the control strip
     * (:core's ControlStrip), whose resize menu sets the size through [setTileSize]. As the toggle did, the choice is stored here (SizeMemory,
     * per phone, nothing is sent) and the grid is rebuilt in a POST: a rebuild after the touch is over is the safer ordinary one. It was NOT the
     * cause of "when I minimize a camera, the items following it disappear" (0.5.4 to 0.5.6): that was the half-width spacer's height, see
     * StillTiles.spacer. Every still of the page is due at once so no tile waits blank for its next turn (the new tile needs its own width anyway).
     */
    private val viewportHost = object : ViewportController.Host {
        override fun view(): HomeView = currentView()

        override fun partialAvailable(): Boolean = graph.panelMode()

        override fun liveViews(cameraId: String) = liveHome.views[cameraId]

        override fun cameraState(tile: CameraTile) =
            CameraState(graph.cameraChoices.pick(tile.id), graph.liveChoices.effective(tile.id, tile.live), graph.cameraChoices.isOff(tile.id))

        override fun applyState(tile: CameraTile, state: CameraState) {
            graph.cameraChoices.set(tile.id, state.pick, state.off)
            graph.liveChoices.choose(tile.id, tile.live, state.live)
            redraw() // the off state is drawn, the stills and the live plan follow
        }

        override fun setTileSize(tile: CameraTile, size: CameraSize) {
            val pageId = currentView().selectedId ?: return
            graph.sizes.set(pageId, tile.id, tile.defaultSize, size)
            redraw()
        }

        override fun streamChanged() = syncLive() // the tile's stream follows the viewport's pick: the old key closes, the new one opens

        override fun redraw() {
            blocks.post {
                if (isFinishing || isDestroyed) return@post
                stills.reset() // every camera of the page: the resized one at its new width, the rest repainted and refreshed
                render()
                blocks.requestLayout()
                requestPicturesIfDue()
                syncLive()
            }
        }

        override fun press(tile: ButtonTile) = onPress(tile)

        override fun picture(cameraId: String) = graph.content.picture(cameraId)

        override fun journal(body: String) = graph.pushJournal.cameraView(body)
    }

    private fun selectPage(id: String) {
        if (id == selectedPageId) return
        stillTiles.resetZoom() // zoom is for one visit to one page
        viewport.onPageChange() // and so is a viewport pick, and fullscreen
        selectedPageId = id
        stills.reset() // the new page's cameras are asked for at once
        render()
        requestPicturesIfDue()
    }

    private fun showMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, MENU_SETTINGS, 1, R.string.home_menu_settings)
        menu.menu.add(0, MENU_STATUS, 2, R.string.home_menu_status)
        menu.menu.add(0, MENU_ABOUT, 3, R.string.home_menu_about)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SETTINGS -> openSetup(SettingsStatusSplit.Screen.SETTINGS)
                MENU_STATUS -> openSetup(SettingsStatusSplit.Screen.STATUS)
                MENU_ABOUT -> showAbout()
            }
            true
        }
        menu.show()
    }

    /** APP-04: Settings and Status are two screens of SetupActivity, chosen by an intent extra. */
    private fun openSetup(screen: SettingsStatusSplit.Screen) {
        whenUnlocked { startActivity(Intent(this, SetupActivity::class.java).putExtra(SettingsStatusSplit.EXTRA_SCREEN, screen.value)) }
    }

    /** Facts the APK already knows; no network. The signer is deliberately not shown. */
    private fun showAbout() {
        val info = packageManager.getPackageInfo(packageName, 0)
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val homeName = currentView().homeName
        val lines = listOfNotNull(
            homeName?.let { getString(R.string.about_home, it) },
            getString(R.string.about_version, info.versionName ?: "?", PackageInfoCompat.getLongVersionCode(info).toString()),
            getString(if (debuggable) R.string.about_build_debug else R.string.about_build_release),
            getString(if (graph.tailnetBuilt) R.string.about_tailnet_yes else R.string.about_tailnet_no),
            getString(R.string.about_icons),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(R.string.about_ok, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        com.meda.portico.panel.PanelScreen.keepOn(this) // DEV-807: Panel mode holds the screen on while this screen is in front
        graph.uiStarted() // the socket is there for this screen; "Stay connected" may start (the app is visible)
        graph.content.listen(contentListener)
        talkRemover = graph.talk.addListener(talkListener)
        answerRemover = graph.answer.addListener(answerListener)
        ringRemover = graph.ring.addChangeListener { runOnUiThread { render() } }
        graph.refreshContent()
        stills.reset()
        graph.ringPrewarm.onHomeStarted() // a ring pre-warm hands its running streams to this screen's first plan
        graph.homeLiveGrace.back() // 0.6.73: back within the 30 s grace the streams were never stopped; the first plan below adopts them
        started = true
        handler.post(tick)
    }

    override fun onStop() {
        stillTiles.resetZoom() // zoom is not remembered: leaving the screen ends it
        viewport.onStop()
        panelTiles.resetZoom()
        handler.removeCallbacks(tick)
        // DEV-713: the renderers go first (a stopped screen has no surface). 0.6.73: the sessions are not closed here but left to
        // HomeLiveGrace for 30 s (a quick return adopts them); a rotation is not a background (the next instance adopts them at once).
        started = false
        offscreenGrace.clear() // no pending 30 s stop outlives the screen
        handler.removeCallbacks(liveSync)
        stillTiles.releaseRenderers()
        if (!isChangingConfigurations) graph.homeLiveGrace.leave(liveHome, liveHome.views.values.any { it.key != null })
        graph.ringPrewarm.onHomeStopped()
        graph.content.unlisten(contentListener)
        talkRemover?.invoke()
        talkRemover = null
        answerRemover?.invoke()
        answerRemover = null
        ringRemover?.invoke()
        ringRemover = null
        graph.uiStopped()
        super.onStop()
    }

    // ---- state -----------------------------------------------------------------------------

    private fun linkState(): LinkState = try {
        LinkState.valueOf(graph.signaling.state.name)
    } catch (_: IllegalArgumentException) {
        LinkState.STOPPED
    }

    /** APP-03: the call from the station, read once per [currentView]. */
    private var stationNow: StationCall? = null

    private fun station(): StationCall? = graph.stationCall().also { stationNow = it }

    private fun currentView(): HomeView {
        val c = graph.content
        val calling = graph.place.state().takeIf { it.isLive }?.request?.actionId
        val built = HomeModel.build(c.profile, linkState(), c.support, c.answered, c.runningIds(), c.pictureIds(), calling, selectedPageId, graph.sizes, graph.talk.state(), graph.doors.all, station())
        // 0.6.58: the network class's video row is the cap. Off: every camera tile is off (no live, no stills); the per-camera choices work inside the cap.
        val offIds = if (graph.videoVerdict().picturesOff) built.cameras.map { it.id }.toSet() else graph.cameraChoices.offIds()
        return CameraViewport.withPartial(HomeModel.withOff(built, offIds), (if (::viewport.isInitialized) viewport.partialCameraId() else null)) // tablet mode's partial layout, if one is chosen
    }

    /**
     * Asks for the selected page's cameras, each at most once per [SNAPSHOT_EVERY_MS] and never twice at once. The width
     * asked for is `:core`'s [StillWidth] for the tile's size and zoom; a change of it only changes the NEXT request.
     */
    private fun requestPicturesIfDue() {
        if (linkState() != LinkState.CONNECTED || graph.isPaused()) {
            stills.reset() // the first one after the link comes up is not delayed
            return
        }
        val wanted = currentView().cameras.filter { !it.off && (it.picture == PictureState.PICTURE || it.picture == PictureState.LOADING) } // an off tile is not polled (0.6.53)
        val sizes = wanted.associate { it.id to it.size }.toMutableMap()
        viewport.fullscreenStillOf(wanted)?.let { sizes[it] = CameraSize.BIG } // the overlay draws it big
        // A tile that is playing live video asks for no stills (and again as soon as it stops, or while a pinch has zoomed it).
        val pageIds = wanted.filter { liveHome.views[it.id]?.ui?.video != true }.map { it.id }
        // app-05: the station call's cameras are asked for in the same pass (one schedule: two passes would forget each other's cameras).
        val callTiles = shownPanel?.cameras?.filter { it.picture == PictureState.PICTURE || it.picture == PictureState.LOADING } ?: emptyList()
        val callSizes = callTiles.associate { it.id to it.size }
        val ids = (pageIds + callTiles.map { it.id }).distinct()
        stills.tick(ids, SystemClock.elapsedRealtime()) {
            val width = StillWidth.forCamera(sizes[it], stillTiles.zoomOf(it), callSizes[it], panelTiles.zoomOf(it))
            graph.requestSnapshot(it, width)
        }
    }

    // ---- live video (DEV-713) ------------------------------------------------------------------

    /**
     * Tells the live code what the screen looks like now: the switch, whether this screen is started and not paused, the link and
     * its path (`graph.linkPath`: DIRECT at home, TAILNET away, the only read of the path), and per camera tile whether it wants
     * live (this phone's choice, else the document's `live`) and whether it is on screen. `:core`'s plan decides; nothing is sent
     * from here. About once a second, on a scroll, after a chip tap and after a size change.
     */
    private fun syncLive() {
        val view = currentView()
        val visible = stillTiles.visibleIds()
        val smallOnly = graph.videoVerdict().smallOnly
        val global = LiveGlobal(
            switchOn = graph.liveVideoEnabled(),
            shown = started,
            paused = graph.isPaused(),
            connected = linkState() == LinkState.CONNECTED,
            path = graph.linkPath,
            rtspReady = liveHome.rtspReady,
            roamingBlocked = graph.roamingBlocked(),
        )
        val fullscreenNow = viewport.fullscreenCameraId()
        val prewarmed = graph.ringPrewarm.protectedCameras() // handed over by the ring pre-warm: kept until the tiles are laid out
        val nowMs = SystemClock.elapsedRealtime()
        val tiles = view.cameras.map { t ->
            LiveTileIn(
                cameraId = t.id,
                // the STREAM the viewport asks for (its pick, else Auto); the tile's size still decides the layout and the still. 0.6.58: "Live video small" caps it to the small stream
                size = if (smallOnly && t.hasSmall) com.meda.portico.core.ha.CameraSize.SMALL else viewport.stream(t, view.selectedId),
                available = t.picture != PictureState.CAMERA_UNAVAILABLE && t.picture != PictureState.OFFLINE,
                wantsLive = graph.liveChoices.effective(t.id, t.live),
                off = t.off,
                // fullscreen: the others are hidden under it, so they stop at once (no grace); otherwise a tile that left the viewport keeps its stream 30 s
                onScreen = if (fullscreenNow != null) (t.id == fullscreenNow).also { if (!it) offscreenGrace.forget(t.id) } else offscreenGrace.onScreen(t.id, t.id in visible || t.id in prewarmed, nowMs),
                rtsp = t.liveRtsp,
            )
        }
        // 0.6.51 diagnostic: a connected link with no known path opens no live session; say so once in the Journal (and again if it recurs)
        val idleLine = com.meda.portico.core.live.LivePlan.pathUnknownLine(global, tiles.any { it.available && it.wantsLive })
        if (idleLine != null && !pathUnknownNoted) graph.pushJournal.cameraView(idleLine)
        pathUnknownNoted = idleLine != null
        liveHome.update(LiveInput(global, tiles, view.showFps, stillTiles.zoomedIds() + viewport.zoomedIds()))
    }

    /** A tap on a tile's LIVE chip: live <-> still, remembered only when it differs from the document; a tile that gave up asks again. */
    private fun liveChip(tile: CameraTile) {
        if (liveHome.views[tile.id]?.ui?.notice == com.meda.portico.core.live.LiveWhy.ROAMING) {
            graph.allowRoamingTrip() // A-10: the tap lifts the roaming block for this trip; the choice of live or still is not touched
            syncLive()
            return
        }
        val wants = graph.liveChoices.effective(tile.id, tile.live)
        if (wants && liveHome.views[tile.id]?.canRetry == true) liveHome.views[tile.id]?.key?.let { liveHome.restart(it) } else graph.liveChoices.toggle(tile.id, tile.live)
        syncLive()
    }

    // ---- drawing ---------------------------------------------------------------------------

    private fun render() {
        var view = currentView()
        // app-05: a new station ring moves Home to the tab that holds the calling door's Talk button; the end of the call gives the user's tab back.
        val pick = tabMemory.update(stationNow, view.selectedId) { StationTab.pageFor(graph.content.profile, graph.doors.all, stationNow) }
        if (pick != null) {
            selectedPageId = pick
            stillTiles.resetZoom()
            stills.reset()
            view = currentView()
        }
        viewport.begin(view)
        paintStatus(view)
        banner.text = bannerText(view.banner)
        banner.visibility = if (view.banner == Banner.NONE) View.GONE else View.VISIBLE
        selectedPageId = view.selectedId
        if (view != shown) {
            // 0.6.48: a call starting or ending changes only the buttons: keep the camera tiles (views, renderers, streams) as they are.
            if (PageUpdate.onlyButtonsChanged(shown, view) && blockViews.rebuildButtons(view.blocks, ::onPress)) {
                blocks.requestLayout()
                blocks.invalidate()
            } else {
                rebuild(view)
            }
            shown = view
        }
        stillTiles.update(view.cameras) { graph.content.picture(it) }
        viewport.sync(view) // after the page: a camera that went fullscreen has let go of its stream by now
        paintPanel()
        updateResult()
        paintStation()
        placeCallBar()
    }

    private var watchedBar: View? = null

    /**
     * The in-call controls row (mute / route / End: [com.meda.portico.call.CallBarView]) is drawn in the page, directly below the row of
     * the call's own Talk (or call) button, where `:core`'s CallBarPlacement finds one on the shown page; otherwise (no such button on this
     * page, the roster page) it stays docked at the bottom of the screen as before. Called after every render and when the host has
     * installed the bar. Whenever the bar is (re)laid out in the page while shown, the screen scrolls it fully into view.
     */
    fun placeCallBar() {
        try {
            if (!::blockViews.isInitialized) return
            val bar = com.meda.portico.call.CallBarHost.barOf(this) ?: return
            val anchor = blockViews.callAnchor
            if (anchor == null) {
                com.meda.portico.call.CallBarHost.dock(this)
                return
            }
            val (flow, index) = anchor
            if (bar.parent !== flow) {
                (bar.parent as? ViewGroup)?.removeView(bar)
                flow.setAddon(bar, index)
                scrollBarIntoView(bar) // newly put in the page: bring it fully into view once laid out
            }
            if (watchedBar !== bar) {
                watchedBar = bar
                bar.addOnLayoutChangeListener(View.OnLayoutChangeListener { v, l, t, r, b, oldL, oldT, oldR, oldB ->
                    if (v.visibility == View.VISIBLE && v.parent is ButtonFlowLayout && (t != oldT || b != oldB || r - l != oldR - oldL)) scrollBarIntoView(v)
                })
            }
        } catch (_: Exception) {
            // A placement that fails must never take the page down; the bar is then wherever it was.
        }
    }

    /** Scrolls the page just enough that all of [bar] (and a little air under it) is inside the viewport. */
    private fun scrollBarIntoView(bar: View) {
        // Explicit (the 0.6.47 framework call did not reliably move it): from the bar's real position once laid out, and again
        // after the tiles above it have settled (their pictures and other page changes move the bar).
        for (delay in longArrayOf(0L, 350L, 1200L)) bar.postDelayed({ revealBar(bar) }, delay)
    }

    private fun revealBar(bar: View) {
        try {
            if (!bar.isAttachedToWindow || bar.visibility != View.VISIBLE || bar.height == 0) return
            val scroll = findViewById<android.widget.ScrollView>(R.id.home_scroll) ?: return
            val r = android.graphics.Rect(0, 0, bar.width, bar.height + dp(8))
            scroll.offsetDescendantRectToMyCoords(bar, r) // into the scrolled content's coordinates
            val viewport = scroll.height - scroll.paddingTop - scroll.paddingBottom
            val d = ScrollReveal.delta(r.top - scroll.scrollY, r.bottom - scroll.scrollY, viewport)
            if (d != 0) scroll.smoothScrollBy(0, d)
        } catch (_: Exception) {
            // Scrolling is a convenience; never take the page down.
        }
    }

    /**
     * app-05: the Ring page content of the station call as a panel above the page: the calling door's cameras and, from `:core`'s
     * [StationPanel], its buttons; shown only while the call rings, gone once it is answered. Decoration: a failure while drawing it must
     * never take the call bar or the page with it. A press goes through [whenUnlocked] and [ActionButtons.press] (a door, gate or lock
     * button keeps its confirmation; the lock-screen rule is the page's).
     */
    private fun paintPanel() {
        try {
            val call = stationNow
            val c = graph.content
            val next = StationPanel.build(c.profile, call?.let { graph.doors.endpointIdFor(it.doorRef) }, call, linkState(), c.support, c.runningIds(), c.pictureIds())
            if (next != shownPanel) {
                panelTiles.rebuild(next?.cameras ?: emptyList())
                panelButtons.removeAllViews()
                for (tile in next?.buttons ?: emptyList()) panelButtons.addView(panelActions.tile(tile, ::onPanelPress))
                shownPanel = next
                panel.visibility = if (next == null) View.GONE else View.VISIBLE
            }
            if (next != null) panelTiles.update(next.cameras) { graph.content.picture(it) }
        } catch (_: Exception) {
        }
    }

    private fun onPanelPress(tile: ButtonTile) {
        whenUnlocked { ActionButtons.press(this, graph, tile, ::say) }
    }

    /** APP-03: while a station call rings or lasts the screen may show over the lock screen and stays on; otherwise both are undone. */
    private fun paintStation() {
        val on = stationNow != null
        if (!on) showOverLock(false)
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The status dot and word, and the title (which says PAUSED, amber, while paused). Reads caches only. */
    private fun paintStatus(view: HomeView) {
        val status = ConnectionModel.of(linkState(), graph.linkPath, graph.signaling.everConnected)
        val color = when (status.health) {
            Health.OK -> COLOR_OK
            Health.WARN -> COLOR_WARN
            Health.BAD -> COLOR_BAD
        }
        statusDot.text = status.glyph
        statusDot.setTextColor(color)
        statusLabel.setText(
            when (status.label) {
                StatusLabel.DIRECT -> R.string.home_status_direct
                StatusLabel.TAILNET -> R.string.home_status_tailnet
                StatusLabel.CONNECTED -> R.string.home_status_connected
                StatusLabel.CONNECTING -> R.string.home_status_connecting
                StatusLabel.OFFLINE -> R.string.home_status_offline
                StatusLabel.NOT_SET_UP -> R.string.home_status_not_set_up
                StatusLabel.TOKEN_REFUSED -> R.string.home_status_token_refused
                StatusLabel.SIGNED_OUT -> R.string.home_status_signed_out
                StatusLabel.ADDRESS_REFUSED -> R.string.home_status_address_refused
                StatusLabel.PAUSED -> R.string.home_status_paused
                StatusLabel.RECONNECTING -> R.string.home_status_reconnecting
            },
        )
        // Healthy is quiet (a muted route name); anything else shares the dot's colour.
        statusLabel.setTextColor(if (status.health == Health.OK) COLOR_MUTED else color)

        // ONE top line: the title slot is the PAUSED words (amber), the page selector (two or more pages) or one plain-text name.
        val chosen = TopLine.choose(graph.isPaused(), view.tabs.size, view.pageLabel, view.homeName)
        tabsScroll.visibility = if (chosen == TopLine.Tabs) View.VISIBLE else View.GONE
        title.visibility = if (chosen == TopLine.Tabs) View.GONE else View.VISIBLE
        if (chosen == TopLine.Paused) {
            val until = graph.pauseKeeper.state().untilMs
            title.text = if (until == 0L) getString(R.string.home_title_paused_resumed) else getString(R.string.home_title_paused_until, PauseNotices.hhmm(until))
            title.setTextColor(COLOR_WARN)
        } else {
            // The selected page's label, else the home's name (plain text, a TextView, never HTML); the app's own title only with neither.
            when (chosen) {
                is TopLine.Title -> title.text = chosen.text
                TopLine.Fallback -> title.setText(R.string.home_title)
                else -> Unit
            }
            title.setTextColor(titleColor)
        }
    }

    private fun bannerText(b: Banner): String = when (b) {
        Banner.NONE -> ""
        Banner.NOT_CONFIGURED -> getString(R.string.home_banner_not_configured)
        Banner.PAUSED -> getString(R.string.home_banner_paused)
        Banner.AUTH_REJECTED -> getString(R.string.home_banner_auth_rejected)
        Banner.SIGNED_OUT -> getString(R.string.home_banner_signed_out)
        Banner.URL_REFUSED -> getString(R.string.home_banner_url_refused)
        Banner.OFFLINE -> if (graph.reconnectingNow()) getString(R.string.home_status_reconnecting) else getString(R.string.home_banner_offline)
        Banner.CONNECTING -> if (graph.reconnectingNow()) getString(R.string.home_status_reconnecting) else getString(R.string.home_banner_connecting)
        Banner.NO_SERVER_SUPPORT -> getString(R.string.home_banner_no_server_support)
        Banner.NOT_ENROLLED -> getString(R.string.home_banner_not_enrolled)
        Banner.NO_CONTENT -> getString(R.string.home_banner_no_content)
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun rebuild(view: HomeView) {
        blocksEmpty.visibility = if (view.blocks.isEmpty()) View.VISIBLE else View.GONE
        rebuildTabs(view)
        val roster = view.roster
        // 0.6.49: the page root has no side padding (the camera grid is edge to edge); the roster page pads itself.
        val sidePad = if (view.rosterSelected && roster != null) dp(16) else 0
        blocks.setPadding(sidePad, 0, sidePad, 0)
        if (view.rosterSelected && roster != null) {
            blocksEmpty.visibility = View.GONE // the roster page is not an empty page
            // M7 3b-A: the roster page (only ever present when the server says house calls are on for this phone).
            stillTiles.reset()
            blockViews.releaseAnchor() // the controls row must leave the old button flow first (placeCallBar docks it at the bottom here)
            rosterViews.rebuild(blocks, roster, ::onRosterCall)
        } else {
            blockViews.rebuild(blocks, view.blocks, ::onPress)
        }
        // Whatever called this (a touch, a tick, a content update), the new tree is measured and drawn from the top.
        blocks.requestLayout()
        blocks.invalidate()
        journalMeasured()
    }

    /** After the next layout pass, one journal line with the heights the screen, the page body and each block really got (a block that vanished is a 0 or missing here). */
    private fun journalMeasured() {
        val scroll = findViewById<View>(R.id.home_scroll)
        blocks.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (blocks.viewTreeObserver.isAlive) blocks.viewTreeObserver.removeOnGlobalLayoutListener(this)
                LayoutJournal.add(LayoutJournal.measuredLine(scroll.height, blocks.height, (0 until blocks.childCount).map { blocks.getChildAt(it).height }))
            }
        })
    }

    /** One text per page in the top line, the selected one (by id, `:core`'s [TopLine.highlighted]) bold and underlined. Shown or hidden by [paintStatus]. */
    private fun rebuildTabs(view: HomeView) {
        tabsRow.removeAllViews()
        val on = TopLine.highlighted(view.tabs, view.selectedId)
        view.tabs.forEachIndexed { i, tab ->
            tabsRow.addView(TextView(this).apply {
                text = tab.label
                textSize = 18f
                gravity = android.view.Gravity.CENTER
                minHeight = dp(48)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isSelected = on[i]
                if (on[i]) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                } else {
                    alpha = 0.7f
                }
                setOnClickListener { selectPage(tab.id) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // ---- pressing a button -----------------------------------------------------------------

    private fun onPress(tile: ButtonTile) {
        // One decision, pure and tested in :core ([PressRoute]): a call button aimed at the gate is the Talk session (DEV-692), never a house call.
        when (PressRoute.of(tile)) {
            PressRoute.PLACE, PressRoute.REOPEN_CALL -> return whenUnlocked { pressCall(tile) } // never a confirm step: nobody is asked "are you sure" before a call
            PressRoute.TALK_START -> return whenUnlocked { pressTalk(tile) } // nor before a talk
            PressRoute.TALK_STOP -> return pressTalk(tile) // stopping a call needs no unlock
            PressRoute.NONE -> return
            PressRoute.ACTION -> Unit
        }
        // A block button that asks first only gets here once held for a second (or by TalkBack's action): no dialog on Home.
        whenUnlocked { if (tile.confirm) ActionButtons.pressHeld(this, graph, tile, ::say) else ActionButtons.press(this, graph, tile, ::say) }
    }

    /**
     * APP-03 (the A-02 rule, [DoorUnlockRule]): the screen can be over the lock screen for a station call, and taking, declining or ending
     * the call needs no unlock but any other press does. Unlocked, [run] runs at once; locked, it runs once the person has unlocked.
     * Fails closed: with no keyguard service the phone is treated as locked and the person is told.
     */
    private fun whenUnlocked(run: () -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (!DoorUnlockRule.needsUnlock(km?.isKeyguardLocked ?: true, RingPress.DOOR_ACTION)) return run()
        if (km == null) return say(getString(R.string.ring_unlock_needed))
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = run()
            override fun onDismissCancelled() = say(getString(R.string.ring_unlock_needed))
            override fun onDismissError() = say(getString(R.string.ring_unlock_needed))
        })
    }

    /**
     * A Talk button (blocks B7b). INERT: only a READY button starts a talk, and the server calls none ready today (every Talk
     * button is `available: false, talk_off`, drawn disabled, so a tap does not even arrive). The same press of a talk that is
     * connecting or in the call cancels or stops it. Any other state sends nothing, however the click got here. The
     * microphone is asked for in [askTalkMic], as the call screens do.
     */
    private fun pressTalk(tile: ButtonTile) {
        when (tile.state) {
            ButtonState.READY -> graph.talk.press(TalkRequest(tile.id, tile.label, tile.door))
            // The call under way on this button: this phone's own talk, or a station call answered on it. Whichever is live ends; the others are idle.
            ButtonState.TALK_CONNECTING, ButtonState.TALK_IN_CALL -> CallActions.hangUpAll(graph)
            else -> Unit
        }
    }

    /** The microphone dialog for a talk: once per attempt, before anything is sent (the call screens' own pattern). */
    private fun askTalkMic(s: TalkUiState) {
        if (s.snapshot.phase != TalkPhase.AWAITING_MIC || talkMicAskedFor == s.generation) return
        talkMicAskedFor = s.generation
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            graph.talk.onMicResult(true)
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_TALK_MIC)
        }
    }

    /** APP-03: the microphone dialog for an answered station call: once per attempt, before audio starts (the in-call screen's old pattern). */
    private fun askAnswerMic(s: CallUiState) {
        if (s.snapshot.phase != Phase.AWAITING_MIC || answerMicAskedFor == s.generation) return
        answerMicAskedFor = s.generation
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            graph.answer.onMicResult(true)
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_ANSWER_MIC)
        }
    }

    /**
     * APP-03: the bar goes away at once when a call ends, so a call that failed or was cut says why in the result line (the old in-call
     * screen kept the reason up). One line per attempt. A hang-up the person asked for says nothing.
     */
    private fun announceAnswerEnd(s: CallUiState) {
        if (s.snapshot.phase != Phase.ENDED || s.key == null || announcedGeneration == s.generation) return
        announcedGeneration = s.generation
        val text = when (CallUi.status(s.snapshot)) {
            CallUi.Status.FAILURE -> s.snapshot.failure?.message ?: getString(R.string.call_ended_failed)
            CallUi.Status.ENDED_REMOTE -> getString(R.string.call_ended_remote)
            CallUi.Status.ENDED_NETWORK -> getString(R.string.call_ended_network)
            CallUi.Status.ENDED_CAP -> getString(R.string.call_ended_cap)
            CallUi.Status.ENDED_STOPPED -> getString(R.string.call_ended_stopped)
            CallUi.Status.ENDED_OTHER -> getString(R.string.call_ended_failed)
            else -> null
        }
        if (text != null) say(text)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_TALK_MIC -> graph.talk.onMicResult(granted)
            REQ_ANSWER_MIC -> graph.answer.onMicResult(granted)
        }
    }

    /**
     * A call button: the press itself places the call (microphone first, on the call screen, then
     * `portico/content/run`). A live call's own button just reopens the call screen. Only a press gets here.
     */
    private fun pressCall(tile: ButtonTile) {
        if (tile.state == ButtonState.CALLING) {
            startActivity(Intent(this, OutgoingCallActivity::class.java))
            return
        }
        if (tile.state != ButtonState.READY) return
        graph.place.press(PlaceRequest(tile.id, tile.detail ?: tile.label))
        startActivity(Intent(this, OutgoingCallActivity::class.java))
    }

    /**
     * A roster row's Call button: the press places the call (microphone first, on the call screen, then `portico/content/run`
     * with the row's action id and nothing else). A live call's own row just reopens the call screen. Only a press gets here.
     */
    private fun onRosterCall(row: RosterRow) {
        when (row.state) {
            RowState.CALLING -> startActivity(Intent(this, OutgoingCallActivity::class.java))
            RowState.READY -> whenUnlocked {
                graph.place.press(PlaceRequest(row.actionId, row.name))
                startActivity(Intent(this, OutgoingCallActivity::class.java))
            }
            RowState.DISABLED -> Unit
        }
    }

    private fun say(text: String) {
        notice = text to SystemClock.elapsedRealtime()
        render()
    }

    private fun updateResult() {
        val now = SystemClock.elapsedRealtime()
        val local = notice?.takeIf { now - it.second < RunLine.SHOWN_MS }
        val text = local?.first ?: RunLine.text(this, graph.content)
        result.visibility = if (text == null) View.GONE else View.VISIBLE
        result.text = text ?: ""
    }

    companion object {
        /** APP-03: the ring's PendingIntents (and the call bar's Answer button) open this screen with these. */
        const val ACTION_SHOW = "com.meda.portico.ring.SHOW"
        const val ACTION_ANSWER = "com.meda.portico.ring.ANSWER"
        const val EXTRA_DOOR = "door_ref"
        const val EXTRA_CALL = "call_id"

        // Colours that read on the default light theme (the intercom app's bar is dark and uses brighter ones).
        private val COLOR_OK: Int = Color.parseColor("#2E7D32")
        private val COLOR_WARN: Int = Color.parseColor("#EF6C00")
        private val COLOR_BAD: Int = Color.parseColor("#C62828")
        private val COLOR_MUTED: Int = Color.parseColor("#757575")
        private const val MENU_SETTINGS = 2
        private const val MENU_ABOUT = 3
        private const val MENU_STATUS = 5
        private const val REQ_TALK_MIC = 43
        private const val REQ_ANSWER_MIC = 44
        private const val TICK_MS = 1_000L
        private const val SNAPSHOT_EVERY_MS = 4_000L
        private const val LIVE_SCROLL_DEBOUNCE_MS = 300L
        private const val SWIPE_MIN_DP = 96
        private const val KEY_PAGE = "home_page"
        private const val KEY_VIEWPORT = "home_viewport"
        private const val KEY_CALL = "home_station_call"
        private const val KEY_RESTORE = "home_station_restore"
        private const val KEY_SELECTED = "home_station_selected"
    }
}
