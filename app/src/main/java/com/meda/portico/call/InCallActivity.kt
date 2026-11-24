package com.meda.portico.call

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.meda.portico.PorticoApp
import com.meda.portico.R
import com.meda.portico.core.call.CallUi
import com.meda.portico.core.call.CallUiState
import com.meda.portico.core.call.HoldConfig
import com.meda.portico.core.call.HoldText
import com.meda.portico.core.call.InCallHoldModel
import com.meda.portico.core.call.InCallHoldView
import com.meda.portico.core.call.SwitchResult
import com.meda.portico.core.call.Phase
import com.meda.portico.core.content.ButtonTile
import com.meda.portico.core.content.CallScreen
import com.meda.portico.core.content.CallerModel
import com.meda.portico.core.content.CallerView
import com.meda.portico.core.content.PictureState
import com.meda.portico.core.content.StillSchedule
import com.meda.portico.core.ring.DoorUnlockRule
import com.meda.portico.core.ring.CallDoorTitle
import com.meda.portico.core.ring.InCallLock
import com.meda.portico.core.content.ButtonState
import com.meda.portico.core.ring.LinkState
import com.meda.portico.core.ring.RingPress
import com.meda.portico.home.ActionButtons
import com.meda.portico.home.RunLine
import com.meda.portico.home.StillTiles
import com.meda.portico.home.currentLinkState
import com.meda.portico.home.reconnectingNow
import com.meda.portico.live.CallerLiveController

/**
 * The in-call screen. It draws nothing of its own accord: it shows `AnswerCoordinator.state()`, the
 * single source, every time it changes, so a second launch of this singleTop activity can never
 * show a stale call (ship checklist 3) and the activity may die and come back without losing the call.
 *
 * - It asks for the microphone, once per answer attempt, when the coordinator says it is waiting for
 *   it (`AWAITING_MIC`): a declared permission with no request is a bug (checklist 4). The dialog's
 *   answer goes back to the coordinator, which ignores it unless a press is waiting.
 * - Hang up is the first control and is always the coordinator's `hangUp()`; it works in every phase.
 * - Back leaves the screen; the call goes on (the notification returns to it).
 * - M7 H2 (gated off by `HoldConfig.HOLD_ENABLED`): while a call is on hold, a strip under Hang up and Mute shows it - the door,
 *   how long it has been held and how long is left - with one Switch button (a "Resume <door>" button when the live call ended)
 *   and "End held call". Everything about it is decided in core's `InCallHoldModel`; with nothing held every view of it is gone
 *   and the screen is what it was. Hang up ends the live call only.
 * - A2: while the call is live, the caller's camera stills and its custom `call_buttons` (the document's `callers`
 *   entry for this call's door, else `*`, else nothing) are drawn under Hang up and Mute. They are decoration over the
 *   coordinator's state: nothing about the call depends on them, and a failure drawing them is swallowed.
 */
class InCallActivity : AppCompatActivity() {
    private val audioControls by lazy { com.meda.portico.call.AudioControlsView(this) }

    private val handler = Handler(Looper.getMainLooper())
    private var remove: (() -> Unit)? = null
    private var state: CallUiState? = null
    private var micAskedFor = -1
    private var lastEndLogged = -1

    private lateinit var stillTiles: StillTiles
    private lateinit var actionButtons: ActionButtons
    private val stills = StillSchedule(STILL_EVERY_MS)

    /** DEV-713 S2: the caller's first camera live (it continues from the ring screen). Optional and fenced: null (or any failure inside it) leaves the stills as they were. */
    private var live: CallerLiveController? = null
    private var shownCaller: CallerView? = null
    private var drawn = false
    private var notice: Pair<String, Long>? = null
    private var removeSessions: (() -> Unit)? = null
    private var holdView: InCallHoldView = InCallHoldView.NOTHING

    /** The last hold view that was drawn, to tell a switch from a refresh (the switch is announced aloud). */
    private var drawnHold: InCallHoldView? = null
    private var switchNotice: Pair<String, Long>? = null

    /** the owner 2026-11-21: one unlock request (button or automatic) and one door-confirmation prompt at a time. */
    private var unlockPending = false
    private var bioPending = false
    private var resumed = false
    private var titleLogged: String? = null

    /** Called on the link's thread when content, a picture or a press result changes. */
    private val contentListener: () -> Unit = { runOnUiThread { renderCaller() } }

    private val stillsTick = object : Runnable {
        override fun run() {
            requestStills()
            handler.postDelayed(this, STILL_TICK_MS)
        }
    }

    /** Remote hang-up: the screen closes itself after 3 s (see [EndAutoClose]); a touch or Close closes at once. */
    private val autoClose by lazy {
        com.meda.portico.core.call.EndAutoClose(
            schedule = { d, run ->
                val r = Runnable { run() }
                handler.postDelayed(r, d)
                com.meda.portico.core.call.EndAutoClose.Cancel { handler.removeCallbacks(r) }
            },
            nowMs = { android.os.SystemClock.elapsedRealtime() },
            close = { ms, callId ->
                val graph = PorticoApp.graph(this)
                try {
                    graph.pushJournal.diag("ui", "call-end-autoclose", listOf<Pair<String, Any?>>("call" to callId?.let { com.meda.portico.core.push.PushJournalFormat.DiagId(it) }, "ms" to ms))
                } catch (_: Throwable) {
                }
                WinLog.close(graph, "incall", callId, "autoclose", "ms" to ms)
                finish()
            },
        )
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        autoClose.touched()
    }

    private val tick = object : Runnable {
        override fun run() {
            renderTimer()
            renderHold()
            renderUnlock()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContentView(R.layout.activity_call)
        audioControls.bind(true)
        stillTiles = StillTiles(this, findViewById(R.id.call_stills), 1)
        live = try {
            CallerLiveController(PorticoApp.graph(this), stillTiles, { shownCaller }, ::callLiveNow, ::renderCaller)
        } catch (_: Throwable) {
            null
        }
        actionButtons = ActionButtons(this)
        findViewById<Button>(R.id.call_hangup).setOnClickListener { PorticoApp.graph(this).answer.hangUp() }
        findViewById<Button>(R.id.call_mute).setOnClickListener {
            val muted = state?.snapshot?.muted == true
            PorticoApp.graph(this).answer.setMuted(!muted)
        }
        findViewById<Button>(R.id.call_close).setOnClickListener { finish() }
        findViewById<Button>(R.id.call_unlock).setOnClickListener { requestUnlock(InCallLock.Source.BUTTON) }
        findViewById<View>(R.id.call_mic_banner).background = android.graphics.drawable.ColorDrawable(getColor(R.color.panel_mic_banner_bg)) // in code: the layout test keeps android:background out of this screen
        findViewById<Button>(R.id.call_mic_banner_hangup).setOnClickListener { PorticoApp.graph(this).answer.hangUp() }
        // The strip is one node; the button inside it does the same as a tap on the strip.
        findViewById<View>(R.id.call_held_strip).setOnClickListener { switchToHeld() }
        findViewById<Button>(R.id.call_held_switch).setOnClickListener { switchToHeld() }
        findViewById<Button>(R.id.call_held_end).setOnClickListener {
            holdView.strip?.let { PorticoApp.graph(this).answer.endHeld(it.callId) }
        }
        // "Try again" is a new press of Answer: the user's, like the first.
        findViewById<Button>(R.id.call_retry).setOnClickListener {
            state?.key?.let { PorticoApp.graph(this).answer.press(it.doorRef, it.callId) }
        }
        findViewById<Button>(R.id.call_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        if (savedInstanceState != null) micAskedFor = savedInstanceState.getInt(KEY_MIC_ASKED, -1)
        WinLog.open(PorticoApp.graph(this), "incall", PorticoApp.graph(this).answer.state().key?.callId, "locked" to WinLog.locked(this))
    }

    override fun onDestroy() {
        autoClose.cancel()
        WinLog.gone(PorticoApp.graph(this), "incall", state?.key?.callId, isFinishing)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_MIC_ASKED, micAskedFor)
    }

    override fun onStart() {
        super.onStart()
        com.meda.portico.panel.PanelScreen.keepOn(this) // DEV-807: Panel mode holds the screen on while this screen is in front
        audioControls.attach()
        remove = PorticoApp.graph(this).answer.addListener { s -> handler.post { show(s) } }
        removeSessions = PorticoApp.graph(this).answer.addSessionsListener { handler.post { renderHold() } }
        PorticoApp.graph(this).content.listen(contentListener)
    }

    override fun onResume() {
        super.onResume()
        WinLog.front(PorticoApp.graph(this), "incall", state?.key?.callId, "locked" to WinLog.locked(this))
        resumed = true
        renderUnlock()
        maybeAutoUnlock()
        stills.reset()
        handler.post(tick)
        handler.post(stillsTick)
        live?.resumed() // after the call UI is up: it only arms a timer, the streams start later and on their own thread
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(tick)
        handler.removeCallbacks(stillsTick)
        live?.paused() // the renderers go, the streams close within the lease's grace
        super.onPause()
    }

    override fun onStop() {
        audioControls.detach()
        PorticoApp.graph(this).content.unlisten(contentListener)
        remove?.invoke()
        remove = null
        removeSessions?.invoke()
        removeSessions = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Nothing to take from the intent: the screen follows the coordinator.
        state?.let { show(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        PorticoApp.graph(this).answer.onMicResult(granted)
    }

    private fun show(s: CallUiState) {
        state = s
        val graph = PorticoApp.graph(this)
        val snap = s.snapshot
        findViewById<TextView>(R.id.call_door).text = getString(R.string.call_title, doorTitle(s, graph) ?: getString(R.string.ring_door_generic))

        // The microphone dialog: once per attempt.
        if (snap.phase == Phase.AWAITING_MIC && micAskedFor != s.generation) {
            micAskedFor = s.generation
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                graph.answer.onMicResult(true)
            } else {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
        }

        val c = CallUi.controls(snap)
        findViewById<TextView>(R.id.call_status).text = statusText(s) + forcedPathNote(graph)
        findViewById<Button>(R.id.call_hangup).apply {
            visibility = if (c.hangUp) View.VISIBLE else View.GONE
            setText(if (c.hangUpIsCancel) R.string.call_cancel else R.string.call_hangup)
        }
        findViewById<Button>(R.id.call_mute).apply {
            visibility = if (c.mute) View.VISIBLE else View.GONE
            setText(if (snap.muted) R.string.call_unmute else R.string.call_mute)
        }
        findViewById<Button>(R.id.call_retry).visibility = if (c.retry) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.call_close).visibility = if (c.close) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.call_settings).visibility = if (c.openSettings) View.VISIBLE else View.GONE
        val panel = graph.panelMode()
        // DEV-807: Panel mode holds the screen on while this screen is in front, whatever the call says.
        if (c.keepScreenOn || com.meda.portico.core.panel.KeepScreenOn.wanted(panel, inFront = true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // DEV-807: the "Microphone is open" banner, for the whole call in Panel mode (a call, not a ring or a cancel before it connects).
        findViewById<View>(R.id.call_mic_banner).visibility =
            if (com.meda.portico.core.panel.MicBanner.visible(panel, callLive = c.hangUp && !c.hangUpIsCancel)) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.call_mic_banner_text).setText(if (snap.muted) R.string.panel_mic_muted else R.string.panel_mic_open)
        renderTimer()
        renderHold()
        renderCaller()
        renderUnlock()
        maybeAutoUnlock()

        // A hang-up the user asked for, or nothing to show: leave. Everything else stays up with its reason.
        // A held call keeps the screen open (its switcher is how the person resumes it).
        autoClose.onState(s, graph.answer.sessionsState().held != null)
        if (CallUi.shouldFinish(s, graph.answer.sessionsState().held != null)) {
            WinLog.close(graph, "incall", s.key?.callId, "done", "phase" to snap.phase, "end" to snap.endReason)
            finish()
        } else if (snap.phase == Phase.ENDED && lastEndLogged != s.generation) {
            // a call that ended but the screen stays (remote hang-up, a failure): say so, with the reason it stayed
            lastEndLogged = s.generation
            WinLog.close(graph, "incall", s.key?.callId, "stays", "phase" to snap.phase, "end" to snap.endReason, "fail" to (snap.failure != null))
        }
    }

    // ---- M7 H2: the held call ------------------------------------------------------------------------

    private fun doorOfCall(callId: String): String {
        val graph = PorticoApp.graph(this)
        val ref = graph.answer.sessionsState().sessions.lastOrNull { it.key.callId == callId }?.key?.doorRef
        return ref?.let { graph.doors.nameFor(it) } ?: HoldText.GENERIC_DOOR
    }

    /** Draws the switcher for the sessions as they are now. With nothing held, every view of it is gone. */
    private fun renderHold() {
        try {
            drawHold()
        } catch (_: Exception) {
            // The strip is an addition: whatever goes wrong drawing it must never take Hang up or Mute with it.
        }
    }

    private fun drawHold() {
        val graph = PorticoApp.graph(this)
        val legacy = state ?: graph.answer.state()
        val view = InCallHoldModel.build(legacy, graph.answer.sessionsState(), System.currentTimeMillis(), HoldConfig.HOLD_LIMIT_MS, graph.doors::nameFor)
        holdView = view
        val strip = view.strip
        val stripView = findViewById<View>(R.id.call_held_strip)
        val endHeld = findViewById<Button>(R.id.call_held_end)
        if (strip == null) {
            stripView.visibility = View.GONE
            endHeld.visibility = View.GONE
        } else {
            stripView.visibility = View.VISIBLE
            endHeld.visibility = View.VISIBLE
            findViewById<TextView>(R.id.call_held_title).text = strip.title
            findViewById<TextView>(R.id.call_held_detail).text = strip.detail
            findViewById<TextView>(R.id.call_held_reason).apply {
                visibility = if (strip.disabledReason == null) View.GONE else View.VISIBLE
                text = strip.disabledReason ?: ""
            }
            findViewById<Button>(R.id.call_held_switch).apply {
                text = strip.switchLabel
                isEnabled = strip.switchEnabled
            }
            stripView.contentDescription = strip.description
        }
        // The live call ended while another is held: say so where the status line is ("Gate call ended.").
        view.liveEndedLine?.let { findViewById<TextView>(R.id.call_status).text = it }
        val now = SystemClock.elapsedRealtime()
        val lines = listOfNotNull(
            view.quietEndLine,
            view.interruptedLine,
            switchNotice?.takeIf { now - it.second < NOTICE_MS }?.first,
        )
        findViewById<TextView>(R.id.call_hold_notice).apply {
            visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
            text = lines.joinToString("\n")
        }
        InCallHoldModel.announcement(drawnHold, view, ::doorOfCall)?.let { window.decorView.announceForAccessibility(it) }
        drawnHold = view
    }

    /** The press of Switch (or Resume): the coordinator holds the live call first, then resumes this one. Nothing else starts it. */
    private fun switchToHeld() {
        val strip = holdView.strip ?: return
        if (!strip.switchEnabled) {
            switchNotice = (strip.disabledReason ?: HoldText.WAIT_TO_SWITCH) to SystemClock.elapsedRealtime()
            renderHold()
            return
        }
        val liveName = holdView.liveCallId?.let(::doorOfCall)
        PorticoApp.graph(this).answer.resume(strip.callId) { r -> handler.post { switchDone(r, liveName, strip.doorName) } }
    }

    private fun switchDone(r: SwitchResult, liveName: String?, heldName: String) {
        HoldText.switchFailed(r, liveName, heldName)?.let { switchNotice = it to SystemClock.elapsedRealtime() }
        renderHold()
    }

    // ---- A2: the caller's stills and buttons ------------------------------------------------

    /** Only while the call is live (not before it, not once it ended): the caller's entry for this call's door. */
    private fun callerView(): CallerView? {
        val s = state ?: return null
        val doorRef = s.key?.doorRef ?: return null
        if (!CallUi.controls(s.snapshot).hangUp) return null
        val graph = PorticoApp.graph(this)
        val c = graph.content
        return CallerModel.build(c.profile, graph.doors.endpointIdFor(doorRef), CallScreen.IN_CALL, graph.currentLinkState(), c.support, c.runningIds(), c.pictureIds())
    }

    private fun renderCaller() {
        // The extras are decoration: whatever goes wrong in drawing them must never take Hang up or Mute with it.
        try {
            drawCaller()
        } catch (_: Exception) {
        }
    }

    private fun drawCaller() {
        val graph = PorticoApp.graph(this)
        val view = callerView()
        if (!drawn || view != shownCaller) {
            stillTiles.rebuild(view?.cameras ?: emptyList())
            val extra = findViewById<android.widget.LinearLayout>(R.id.call_extra)
            extra.removeAllViews()
            for (tile in view?.buttons ?: emptyList()) extra.addView(actionButtons.tile(tile, ::onExtraPress))
            shownCaller = view
            drawn = true
        }
        if (view != null) stillTiles.update(view.cameras) { graph.content.picture(it) }
        val now = SystemClock.elapsedRealtime()
        val text = notice?.takeIf { now - it.second < RunLine.SHOWN_MS }?.first ?: RunLine.text(this, graph.content)
        findViewById<TextView>(R.id.call_result).apply {
            visibility = if (text == null) View.GONE else View.VISIBLE
            this.text = text ?: ""
        }
    }

    private fun onExtraPress(tile: ButtonTile) {
        val graph = PorticoApp.graph(this)
        val locked = InCallUnlock.isLocked(this)
        val decision = InCallLock.doorPress(locked)
        InCallUnlock.journal(graph, "door-press", "locked" to locked, "route" to decision.word)
        val run = {
            ActionButtons.press(this, graph, tile) { text ->
                notice = text to SystemClock.elapsedRealtime()
                renderCaller()
            }
        }
        if (decision == InCallLock.DoorPress.RUN) return run()
        if (tile.state != ButtonState.READY || bioPending) return // nothing to confirm; or a sheet is already up
        bioPending = true
        DoorBiometric.prompt(this, graph, tile.label) { r ->
            bioPending = false
            when (InCallLock.afterBio(r)) {
                InCallLock.AfterBio.RUN_ACTION -> run() // the button's usual path, its confirm dialog included
                InCallLock.AfterBio.ASK_KEYGUARD -> whenUnlocked(run) // no secure lock or biometric here: the old rule
                InCallLock.AfterBio.NOTHING -> if (r == InCallLock.BioResult.ERROR) { notice = getString(R.string.call_bio_error) to SystemClock.elapsedRealtime(); renderCaller() }
            }
        }
    }

    // ---- the owner 2026-11-21: Unlock while the call goes on ---------------------------------------------

    /** The Unlock control is shown whenever the keyguard is locked, and gone once it is not. */
    private fun renderUnlock() {
        findViewById<Button>(R.id.call_unlock).visibility = if (InCallLock.unlockControlVisible(InCallUnlock.isLocked(this))) View.VISIBLE else View.GONE
    }

    /** The one automatic request per call: locked, "Unlock when answering" on, the screen up and in front. Never blocks the call. */
    private fun maybeAutoUnlock() {
        if (!resumed || unlockPending) return
        val s = state ?: return
        if (s.snapshot.phase == Phase.ENDED) return
        val graph = PorticoApp.graph(this)
        val callId = s.key?.callId
        val setting = try { graph.callAudio.unlockWhenAnswering() } catch (_: Exception) { InCallLock.DEFAULT_UNLOCK_WHEN_ANSWERING }
        if (!InCallLock.autoRequest(InCallUnlock.isLocked(this), setting, callId, lastAutoCallId)) return
        lastAutoCallId = callId
        requestUnlock(InCallLock.Source.AUTO)
    }

    private fun requestUnlock(source: InCallLock.Source) {
        if (unlockPending) return
        unlockPending = true
        val graph = PorticoApp.graph(this)
        InCallUnlock.request(this, graph, state?.key?.callId, source) { r ->
            unlockPending = false
            if (InCallLock.afterUnlock(r) == InCallLock.AfterUnlock.OPEN_FULL_APP) openFullApp(graph) else renderUnlock()
        }
    }

    /**
     * The phone is unlocked: the full app takes the same call. A plain launch of Home (no Answer action, so nothing presses Answer a second
     * time; the call, its service and its audio belong to the coordinator and are not touched); Home's call bar shows the call. This screen
     * then closes. Home brings the existing instance forward when there is one.
     */
    private fun openFullApp(graph: com.meda.portico.AppGraph) {
        try {
            startActivity(Intent(this, com.meda.portico.home.HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            InCallUnlock.journal(graph, "unlock-app", "call" to state?.key?.callId?.let { com.meda.portico.core.push.PushJournalFormat.DiagId(it) }, "r" to "ok")
            finish()
        } catch (e: Exception) {
            InCallUnlock.journal(graph, "unlock-app", "call" to state?.key?.callId?.let { com.meda.portico.core.push.PushJournalFormat.DiagId(it) }, "r" to "err", "err" to e.javaClass.simpleName.lowercase())
        }
    }

    /** The door's name for the title: the document's block title for this door, else the doors list's name (the driver's), else null. Journalled once per call and source. */
    private fun doorTitle(s: CallUiState, graph: com.meda.portico.AppGraph): String? {
        val ref = s.key?.doorRef
        val doorId = ref?.let { graph.doors.doorIdFor(it) }
        val blocks = try { graph.content.profile.pages.flatMap { it.blockList } } catch (_: Exception) { emptyList() }
        val t = CallDoorTitle.choose(CallDoorTitle.blockTitleFor(doorId, blocks), ref?.let { graph.doors.nameFor(it) })
        val mark = "${s.key?.callId}/${t.source.word}"
        if (titleLogged != mark) {
            titleLogged = mark
            InCallUnlock.journal(graph, "title", "call" to s.key?.callId?.let { com.meda.portico.core.push.PushJournalFormat.DiagId(it) }, "src" to t.source.word)
        }
        return t.text
    }

    /**
     * The A-02 rule ([DoorUnlockRule]): this window shows over the lock screen and answering needs no unlock, but the caller's
     * buttons (a door, a gate, a lock) do. Locked, [run] runs once the person has unlocked (SEC-01).
     */
    private fun whenUnlocked(run: () -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (!DoorUnlockRule.needsUnlock(km?.isKeyguardLocked ?: true, RingPress.DOOR_ACTION)) return run()
        val say = { notice = getString(R.string.ring_unlock_needed) to SystemClock.elapsedRealtime(); renderCaller() }
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

    private fun requestStills() {
        try {
            askForStills()
        } catch (_: Exception) {
        }
    }

    /** Stills only while the call is live and the link is up; one request per camera at a time (the repository's guard). */
    private fun askForStills() {
        val graph = PorticoApp.graph(this)
        if (shownCaller == null || graph.currentLinkState() != LinkState.CONNECTED || graph.isPaused()) {
            stills.reset()
            return
        }
        // a tile that is playing live video asks for no stills (and again as soon as it stops)
        val ids = shownCaller?.cameras?.filter { (it.picture == PictureState.PICTURE || it.picture == PictureState.LOADING) && live?.playing(it.id) != true }?.map { it.id } ?: return
        stills.tick(ids, SystemClock.elapsedRealtime()) { graph.requestSnapshot(it) }
    }

    /** The live rule of this screen: video only while the call is live (the same rule as the caller's stills). */
    private fun callLiveNow(): Boolean = state?.let { CallUi.controls(it.snapshot).hangUp } == true

    /** DEV-811 2.7: while Setup's "Call audio path" is not Automatic, the call screen says so (it applies on the home network only; the journal says if it was ignored). */
    private fun forcedPathNote(graph: com.meda.portico.AppGraph): String = try {
        val c = graph.callAudio.pathChoice()
        if (c == com.meda.portico.core.call.PathChoice.AUTO || !callLiveNow()) "" else "\n" + getString(R.string.call_audio_path_forced, c.name.lowercase().replaceFirstChar { it.uppercase() })
    } catch (_: Exception) {
        ""
    }

    private fun statusText(s: CallUiState): String = when (CallUi.status(s.snapshot)) {
        CallUi.Status.NONE -> ""
        CallUi.Status.NEED_MIC -> getString(R.string.call_status_need_mic)
        CallUi.Status.CONNECTING ->
            if (PorticoApp.graph(this).reconnectingNow()) getString(R.string.home_status_reconnecting) else getString(R.string.call_status_connecting)
        CallUi.Status.IN_CALL -> getString(R.string.call_status_in_call)
        CallUi.Status.IN_CALL_MUTED -> getString(R.string.call_status_muted)
        CallUi.Status.FAILURE -> s.snapshot.failure?.message ?: getString(R.string.call_ended_failed)
        CallUi.Status.ENDED_USER -> getString(R.string.call_ended_user)
        CallUi.Status.ENDED_REMOTE -> getString(R.string.call_ended_remote)
        CallUi.Status.ENDED_NETWORK -> getString(R.string.call_ended_network)
        CallUi.Status.ENDED_CAP -> getString(R.string.call_ended_cap)
        CallUi.Status.ENDED_STOPPED -> getString(R.string.call_ended_stopped)
        CallUi.Status.ENDED_OTHER -> getString(R.string.call_ended_failed)
    }

    private fun renderTimer() {
        val t = findViewById<TextView>(R.id.call_timer)
        val snap = state?.snapshot
        val at = snap?.connectedAtMs
        if (snap == null || at == null || !CallUi.controls(snap).timer) {
            t.visibility = View.GONE
            return
        }
        val (min, sec) = CallUi.elapsed(at, System.currentTimeMillis())
        t.text = getString(R.string.call_timer, min, sec)
        t.visibility = View.VISIBLE
    }

    private companion object {
        /** The call that already had its one automatic unlock request (process-wide: a recreated screen must not ask again). */
        @Volatile var lastAutoCallId: String? = null

        const val REQ_MIC = 41
        const val KEY_MIC_ASKED = "mic_asked_for"

        /** How long a "could not switch" line stays up. */
        const val NOTICE_MS = 8_000L

        /** About one still per camera per 1.5 s (design O3); the tick is finer so a refused or late request is retried soon. */
        const val STILL_EVERY_MS = 1_500L
        const val STILL_TICK_MS = 250L
    }
}
