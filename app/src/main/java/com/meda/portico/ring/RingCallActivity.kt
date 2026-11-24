package com.meda.portico.ring

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.meda.portico.AppGraph
import com.meda.portico.PorticoApp
import com.meda.portico.R
import com.meda.portico.call.stationCall
import com.meda.portico.core.callwindow.CallWindowModel
import com.meda.portico.core.callwindow.CallWindowPhase
import com.meda.portico.core.callwindow.CallWindowStatus
import com.meda.portico.core.callwindow.CallWindowStep
import com.meda.portico.core.callwindow.CallWindowView
import com.meda.portico.core.callwindow.CloseReason
import com.meda.portico.core.content.ButtonTile
import com.meda.portico.core.content.CallerView
import com.meda.portico.core.content.PictureState
import com.meda.portico.core.content.PressRoute
import com.meda.portico.core.content.RingPage
import com.meda.portico.core.content.StillSchedule
import com.meda.portico.core.push.PushJournalFormat
import com.meda.portico.core.ring.DoorUnlockRule
import com.meda.portico.core.ring.LinkState
import com.meda.portico.core.ring.RingPress
import com.meda.portico.core.ring.RingSoundController
import com.meda.portico.home.ActionButtons
import com.meda.portico.home.HomeActivity
import com.meda.portico.home.RunLine
import com.meda.portico.home.StillTiles
import com.meda.portico.home.currentLinkState
import com.meda.portico.home.reconnectingNow
import com.meda.portico.live.CallerLiveController
import java.util.TimeZone

/**
 * 0.6.75: the CALL WINDOW of a door call, Android's dialer look: full screen, dark, over the lock screen, screen on. It is the ring window
 * for BOTH ring paths (the notification's full-screen intent while locked, and [DirectRingLauncher] while the phone is in use).
 * 0.6.76: it is the RINGING screen ONLY. Answering hands the call to the app's main screen ([HomeActivity], its call bar) and closes this window;
 * there is no in-call phase here (no End call, route, volume, timer or "Connected").
 *
 * - Top: the door's name (the one the ring's notification shows) and "Incoming door call" with the status.
 * - Middle: the Ring page's content as the editor defined it: [RingPage.content] (`:core`, the single resolver also behind Home's ring
 *   panel) gives the cameras and buttons; the cameras are drawn by [StillTiles] with the same stills schedule as Home, and the live video by
 *   [CallerLiveController]; the buttons by [ActionButtons], with the lock-screen rule ([DoorUnlockRule]) and the confirm dialog Home's ring
 *   panel has. Nothing here is required: a page with no cameras and no buttons leaves the middle empty and the call controls stay.
 * - Bottom: two round buttons, Decline (red) and Answer (green).
 *   - Answer starts [HomeActivity] with the ring's own ACTION_ANSWER (door_ref and call_id extras; NEW_TASK + CLEAR_TOP + SINGLE_TOP +
 *     REORDER_TO_FRONT) and then closes this window ([CloseReason.ANSWERED]). Home presses the answer (`graph.answer.press`, the coordinator's one
 *     path, which ignores a second press), asks for the microphone, shows the call bar and the failure line, and sets itself over the lock screen
 *     for as long as the call lasts, so it works on a locked phone with no unlock request. NEW_TASK is essential: this window has its own task
 *     (taskAffinity) and `finishAndRemoveTask` would take a Home started inside it along.
 *   - Decline is `graph.ring.stopRing` (the notification's Decline, via [RingReceiver]).
 * - It draws nothing of its own accord: every change of the ring state or the answer state runs `:core`'s [CallWindowModel.step], which says what
 *   to show or that the window is done. A ring that ends (answered elsewhere, cancelled, expired, outranked, declined) or is answered (by the
 *   notification's Answer, which opens Home directly) closes it ([close]); an answer that fails while the window is up keeps it for
 *   [CallWindowModel.FAILED_LINGER_MS] with the failure's words and a Close button (a failure after the window's own Answer is Home's result line).
 *   Back closes it (the ring goes on, the notification stays). Its own task (taskAffinity), finishAndRemoveTask: closing never brings Home forward
 *   by itself and never leaves a blank task.
 * - It rings, when the ring is its to ring (the notification went on the quiet channel, see [RingWindowSignals]): [RingWindowAlert] plays
 *   the ring channel's own sound and vibration in a loop; [RingSoundController] starts it once per ring and stops it on answer, decline, any end
 *   of the ring, a volume key and destruction.
 * - It never asks for the unlock by itself: a button of the Ring page asks when the phone is locked; Answer and Decline need none.
 */
class RingCallActivity : AppCompatActivity() {
    private lateinit var graph: AppGraph
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var stillTiles: StillTiles
    private lateinit var actionButtons: ActionButtons
    private val stills = StillSchedule(STILL_EVERY_MS)
    private var live: CallerLiveController? = null

    private val alert by lazy { RingWindowAlert(this) }
    private val sound by lazy { RingSoundController(alert, RingWindowSignals.ownership::owns, alert::silenced) }
    private val openedFor = HashSet<String>()

    private var removers = emptyList<() -> Unit>()
    private var view: CallWindowView? = null
    private var seen: CallWindowPhase? = null
    private var shownCaller: CallerView? = null
    private var drawn = false
    private var closing = false
    private var notice: Pair<String, Long>? = null

    private val contentListener: () -> Unit = { runOnUiThread { refresh() } }

    private val stillsTick = object : Runnable {
        override fun run() {
            requestStills()
            view?.let { v -> if (v.phase != CallWindowPhase.FAILED) findViewById<TextView>(R.id.cw_status)?.text = statusText(v) } // the link may have changed since the last redraw
            handler.postDelayed(this, STILL_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = PorticoApp.graph(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false) // edge to edge: the bars draw over the window, the insets pad it
        setContentView(R.layout.activity_call_window)
        val root = findViewById<View>(R.id.cw_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        stillTiles = StillTiles(this, findViewById(R.id.cw_stills), 1)
        live = try {
            CallerLiveController(graph, stillTiles, { shownCaller }, { view?.let { it.phase != CallWindowPhase.FAILED } == true }, ::refresh)
        } catch (_: Throwable) {
            null
        }
        actionButtons = ActionButtons(this)
        findViewById<ImageButton>(R.id.cw_answer).setOnClickListener { answerPressed() }
        findViewById<ImageButton>(R.id.cw_decline).setOnClickListener { declinePressed() }
        findViewById<Button>(R.id.cw_close).setOnClickListener { close(CloseReason.CLOSED_BY_USER) }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = if (held) finishAndRemoveTask() else close(CloseReason.BACK) // a window held for the return goes at once on Back
            },
        )
        // Listening from creation to destruction (not start to stop): a window that is not in front still closes itself when its ring is over.
        removers = listOf(
            graph.ring.addChangeListener { runOnUiThread { refresh() } },
            graph.answer.addListener { runOnUiThread { refresh() } },
        )
        if (savedInstanceState == null) journal(CallWindowModel.OPENED_LINE)
        markOpen(intent)
        com.meda.portico.call.WinLog.open(graph, "ring", intent.getStringExtra(EXTRA_CALL), "locked" to com.meda.portico.call.WinLog.locked(this), "act" to (intent.action?.substringAfterLast('.')?.lowercase() ?: "none"))
        refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // singleTask: a second ring arrives here; the window follows the app's state, which the new ring changed
        markOpen(intent)
        refresh()
    }

    /** Tells the notifier's watchdog this window is up for the ring (so a silently blocked launch can be told from a shown window). */
    private fun markOpen(i: Intent) {
        val c = i.getStringExtra(EXTRA_CALL) ?: return
        openedFor.add(c)
        RingWindowSignals.ownership.windowOpened(c)
    }

    override fun onStart() {
        super.onStart()
        com.meda.portico.panel.PanelScreen.keepOn(this) // DEV-807: Panel mode holds the screen on while this screen is in front
        graph.content.listen(contentListener)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        com.meda.portico.call.WinLog.front(graph, "ring", view?.callId, "locked" to com.meda.portico.call.WinLog.locked(this))
        stills.reset()
        handler.post(stillsTick)
        live?.resumed()
    }

    override fun onPause() {
        handler.removeCallbacks(stillsTick)
        live?.paused()
        super.onPause()
    }

    override fun onStop() {
        graph.content.unlisten(contentListener)
        super.onStop()
    }

    override fun onDestroy() {
        removers.forEach { it() }
        removers = emptyList()
        handler.removeCallbacksAndMessages(null)
        sound.reset() // the ring never outlives its window
        openedFor.forEach { RingWindowSignals.ownership.windowClosed(it) }
        openedFor.clear()
        if (isFinishing && !closing) {
            closing = true
            journal(CallWindowModel.closedLine(CloseReason.SYSTEM))
            com.meda.portico.call.WinLog.close(graph, "ring", view?.callId, "system")
        }
        com.meda.portico.call.WinLog.gone(graph, "ring", view?.callId, isFinishing)
        super.onDestroy()
    }

    /** A volume key silences the ring, as on any phone; the key itself still does its work. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) sound.stop()
        return super.onKeyDown(keyCode, event)
    }

    // ---- what to show ---------------------------------------------------------------------------

    /** Reads the app's state once and applies `:core`'s decision. Main thread. Never throws: the call must not depend on this window. */
    private fun refresh() {
        if (closing || isDestroyed) return
        try {
            val call = graph.stationCall()
            val answer = graph.answer.state()
            val step = CallWindowModel.step(call, answer, call?.let { ringPage(it.doorRef) }, seen)
            when (step) {
                is CallWindowStep.Finish -> close(step.reason)
                is CallWindowStep.Show -> {
                    show(step.view)
                    sound.onWindow(step.view.callId, step.view.phase == CallWindowPhase.RINGING)
                }
            }
        } catch (_: Exception) {
            // drawing is decoration; a failure here never ends or blocks the call
        }
    }

    /** The Ring page's content for the door: `:core`'s one resolver ([RingPage.content]), the same the Home ring panel uses. */
    private fun ringPage(doorRef: String): CallerView? {
        val c = graph.content
        return RingPage.content(c.profile, graph.doors.endpointIdFor(doorRef), graph.currentLinkState(), c.support, c.runningIds(), c.pictureIds())
    }

    private fun show(v: CallWindowView) {
        seen = v.phase
        view = v
        findViewById<TextView>(R.id.cw_label).setText(R.string.cw_label)
        findViewById<TextView>(R.id.cw_name).text = v.doorName ?: getString(R.string.ring_door_generic)
        findViewById<TextView>(R.id.cw_status).text = statusText(v)
        findViewById<View>(R.id.cw_row_ring).visibility = if (v.showDecline) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cw_answer_box).visibility = if (v.showAnswer) View.VISIBLE else View.GONE
        findViewById<View>(R.id.cw_close).visibility = if (v.showClose) View.VISIBLE else View.GONE
        if (v.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        drawCaller(v)
        if (v.phase == CallWindowPhase.FAILED && !failedTimerSet) {
            failedTimerSet = true
            handler.postDelayed({ close(CloseReason.FAILURE_SHOWN) }, CallWindowModel.FAILED_LINGER_MS)
        }
    }

    private var failedTimerSet = false

    private fun statusText(v: CallWindowView): String = when (v.status) {
        // the link is down while the ring is up: say what is happening (the owner's words), whatever the ring's own line was
        CallWindowStatus.INCOMING, CallWindowStatus.INCOMING_RING_ONLY ->
            if (graph.reconnectingNow()) getString(R.string.home_status_reconnecting)
            else getString(if (v.status == CallWindowStatus.INCOMING) R.string.cw_status_incoming else R.string.cw_status_ring_only)
        CallWindowStatus.FAILED -> v.failureMessage ?: getString(R.string.call_ended_failed)
    }

    /** The Ring page's cameras and buttons. Rebuilt only when they changed. */
    private fun drawCaller(v: CallWindowView) {
        val next = CallerView(null, v.cameras, v.buttons)
        if (!drawn || next != shownCaller) {
            stillTiles.rebuild(v.cameras)
            val box = findViewById<LinearLayout>(R.id.cw_buttons)
            box.removeAllViews()
            for (tile in v.buttons) box.addView(actionButtons.tile(tile, ::onButton))
            shownCaller = next
            drawn = true
        }
        stillTiles.update(v.cameras) { graph.content.picture(it) }
        val now = SystemClock.elapsedRealtime()
        val text = notice?.takeIf { now - it.second < RunLine.SHOWN_MS }?.first ?: RunLine.text(this, graph.content)
        findViewById<TextView>(R.id.cw_result).apply {
            visibility = if (text == null) View.GONE else View.VISIBLE
            this.text = text ?: ""
        }
    }

    private fun requestStills() {
        try {
            if (shownCaller == null || graph.currentLinkState() != LinkState.CONNECTED || graph.isPaused()) {
                stills.reset()
                return
            }
            // a tile that is playing live video asks for no stills (and again as soon as it stops)
            val ids = shownCaller?.cameras?.filter { (it.picture == PictureState.PICTURE || it.picture == PictureState.LOADING) && live?.playing(it.id) != true }?.map { it.id } ?: return
            stills.tick(ids, SystemClock.elapsedRealtime()) { graph.requestSnapshot(it) }
        } catch (_: Exception) {
        }
    }

    // ---- presses --------------------------------------------------------------------------------

    /**
     * The user's press of Answer: the main screen takes the call. Home gets the ring's own ACTION_ANSWER (it presses the answer, the coordinator's one
     * path), then this window closes. The start comes first so the screen never shows the lock screen or the launcher in between; if it cannot be
     * started the window stays up (and journals why) so the ring is not lost.
     */
    private fun answerPressed() {
        val v = view ?: return
        if (closing) return
        try {
            graph.pushJournal.diag("ui", "ring-tap", "call" to PushJournalFormat.DiagId(v.callId), "locked" to (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked ?: false))
        } catch (_: Throwable) {
        }
        // the owner 2026-11-21: locked, the answer does not wait for the unlock (Setup "Answer without unlocking"): this window, still on screen,
        // presses Answer and the show-when-locked in-call screen takes over; setting off, the unlock prompt comes first. See [LockedAnswer].
        com.meda.portico.call.LockedAnswer.decide(this, graph, v.callId, { route ->
            try {
                if (route == com.meda.portico.core.ring.AnswerLockPolicy.Route.IN_CALL_OVER_LOCK) {
                    com.meda.portico.call.LockedAnswer.openInCall(this, graph, v.doorRef, v.callId)
                } else {
                    startActivity(homeIntent(v.doorRef, v.callId))
                }
                close(CloseReason.ANSWERED)
            } catch (e: Exception) {
                journal("ring screen: answer hand-off failed ${e.javaClass.simpleName}")
                com.meda.portico.call.WinLog.close(graph, "ring", v.callId, "handoff-failed", "err" to e.javaClass.simpleName.lowercase())
            }
        }, { journal("ring screen: answer needs the unlock (cancelled)") })
    }

    /** The intent the Home ring panel always took: the Answer action with the ring's extras, into the app's own task, Home reused if it is open. */
    private fun homeIntent(doorRef: String, callId: String): Intent =
        Intent(this, HomeActivity::class.java)
            .setAction(HomeActivity.ACTION_ANSWER)
            .putExtra(HomeActivity.EXTRA_DOOR, doorRef)
            .putExtra(HomeActivity.EXTRA_CALL, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

    /** Decline stops the ring on this phone only (the notification's Decline, [RingReceiver]); HA is not told; the window closes. */
    private fun declinePressed() {
        val v = view ?: return
        graph.ring.stopRing(v.doorRef, v.callId, "declined from the call window")
        close(CloseReason.DECLINED)
    }

    private fun onButton(tile: ButtonTile) {
        if (PressRoute.of(tile) != PressRoute.ACTION) return // a call or talk button is Home's; the Ring page's buttons are actions (a door, a lock, a script)
        whenUnlocked { ActionButtons.press(this, graph, tile) { text -> notice = text to SystemClock.elapsedRealtime(); refresh() } }
    }

    /** The A-02 rule ([DoorUnlockRule]): Answer and Decline need no unlock, any Ring page button does. Locked, [run] runs once the person has unlocked. */
    private fun whenUnlocked(run: () -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (!DoorUnlockRule.needsUnlock(km?.isKeyguardLocked ?: true, RingPress.DOOR_ACTION)) return run()
        val say = { notice = getString(R.string.ring_unlock_needed) to SystemClock.elapsedRealtime(); refresh() }
        if (km == null) return say()
        km.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = run()
                override fun onDismissCancelled() = say()
                override fun onDismissError() = say()
            },
        )
    }

    // ---- closing --------------------------------------------------------------------------------

    /** Closes the window once, stops the ring, journals why, and removes its task (no blank task). */
    private var held = false

    private fun close(reason: CloseReason) {
        if (closing) return
        if (reason in HOLD_FOR_RETURN && graph.panelMode()) return holdForReturn(reason)
        closing = true
        sound.reset()
        journal(CallWindowModel.closedLine(reason))
        com.meda.portico.call.WinLog.close(graph, "ring", view?.callId, com.meda.portico.core.ring.RingEnvWords.word(reason.word), "held" to false)
        handler.removeCallbacksAndMessages(null)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        finishAndRemoveTask()
    }

    /**
     * DEV-807 (Panel mode): a ring that ended without an answer (missed, declined, a failure shown) does not take the window away: the 10 s bar
     * counts down on it, a touch restarts it, and the return to Fully takes it away ([com.meda.portico.panel.PanelFrontHost]). The ring is silent
     * and the Answer and Decline buttons are gone; Back still closes it at once.
     */
    private fun holdForReturn(reason: CloseReason) {
        closing = true
        held = true
        sound.reset()
        journal(CallWindowModel.closedLine(reason))
        com.meda.portico.call.WinLog.close(graph, "ring", view?.callId, com.meda.portico.core.ring.RingEnvWords.word(reason.word), "held" to true)
        handler.removeCallbacksAndMessages(null)
        findViewById<View>(R.id.cw_row_ring).visibility = View.GONE
        findViewById<View>(R.id.cw_answer_box).visibility = View.GONE
        findViewById<View>(R.id.cw_close).visibility = View.GONE
        findViewById<TextView>(R.id.cw_status).setText(R.string.panel_ring_ended)
        graph.panelRingEnded()
    }

    private fun journal(line: String) {
        try {
            graph.pushJournal.add("${PushJournalFormat.time(System.currentTimeMillis(), TimeZone.getDefault())} $line")
        } catch (_: Throwable) {
            // observation only
        }
    }

    companion object {
        /** The ring's intents open this window with these (the values are the ones Home's ring intents have always carried). */
        const val ACTION_SHOW = "com.meda.portico.ring.SHOW"
        const val EXTRA_DOOR = "door_ref"
        const val EXTRA_CALL = "call_id"

        /** About one still per camera per 1.5 s (as the in-call screen); the tick is finer so a refused or late request is retried soon. */
        private const val STILL_EVERY_MS = 1_500L
        private const val STILL_TICK_MS = 250L

        /** Panel mode keeps the window for the return countdown after these ([holdForReturn]). */
        private val HOLD_FOR_RETURN = setOf(CloseReason.RING_ENDED, CloseReason.DECLINED, CloseReason.FAILURE_SHOWN)
    }
}
