package com.meda.portico.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.meda.portico.PorticoApp
import com.meda.portico.R
import com.meda.portico.core.call.CallUi
import com.meda.portico.core.call.PlacePhase
import com.meda.portico.core.call.PlaceUi
import com.meda.portico.core.call.PlaceUiState

/**
 * The outgoing-call screen: "Ringing <name>", connected, or failed with the reason and a Try again only
 * where it can help. It reuses the in-call layout (`activity_call`) and [CallService], and like
 * [InCallActivity] it draws nothing of its own accord: it shows `PlaceCoordinator.state()` every time it
 * changes, so a second launch of this singleTop activity can never show a stale call.
 *
 * - It is opened by a press of a call button on Home and does nothing else by itself. It asks for the
 *   microphone, once per attempt, when the coordinator says it is waiting for it (`AWAITING_MIC`), before
 *   anyone is rung; the dialog's answer goes back to the coordinator, which ignores it unless a press waits.
 * - Cancel / Hang up is the first control and is always the coordinator's `hangUp()`.
 * - Try again is a new press of the same button (the user's), never automatic.
 * - Back leaves the screen; the call goes on (the notification returns to it).
 */
class OutgoingCallActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var remove: (() -> Unit)? = null
    private var state: PlaceUiState? = null
    private var micAskedFor = -1

    private val tick = object : Runnable {
        override fun run() {
            renderTimer()
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
        findViewById<Button>(R.id.call_hangup).setOnClickListener { PorticoApp.graph(this).place.hangUp() }
        findViewById<Button>(R.id.call_mute).setOnClickListener {
            val muted = state?.snapshot?.muted == true
            PorticoApp.graph(this).place.setMuted(!muted)
        }
        findViewById<Button>(R.id.call_close).setOnClickListener { finish() }
        findViewById<View>(R.id.call_mic_banner).background = android.graphics.drawable.ColorDrawable(getColor(R.color.panel_mic_banner_bg)) // in code: the layout test keeps android:background out of this screen
        findViewById<Button>(R.id.call_mic_banner_hangup).setOnClickListener { PorticoApp.graph(this).place.hangUp() }
        findViewById<Button>(R.id.call_retry).setOnClickListener { PorticoApp.graph(this).place.retry() }
        findViewById<Button>(R.id.call_settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        if (savedInstanceState != null) micAskedFor = savedInstanceState.getInt(KEY_MIC_ASKED, -1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_MIC_ASKED, micAskedFor)
    }

    override fun onStart() {
        super.onStart()
        com.meda.portico.panel.PanelScreen.keepOn(this) // DEV-807: Panel mode holds the screen on while this screen is in front
        PorticoApp.graph(this).outgoingScreenStarted() // DEV-807 follow-up: a panel call request waits for this screen to be up before it places the call
        remove = PorticoApp.graph(this).place.addListener { s -> handler.post { show(s) } }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onStop() {
        remove?.invoke()
        remove = null
        PorticoApp.graph(this).outgoingScreenStopped()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        state?.let { show(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        PorticoApp.graph(this).place.onMicResult(granted)
    }

    private fun show(s: PlaceUiState) {
        state = s
        val snap = s.snapshot
        findViewById<TextView>(R.id.call_door).text = getString(R.string.call_title, s.request?.name ?: "")

        // The microphone dialog: once per attempt, before anyone is rung.
        if (snap.phase == PlacePhase.AWAITING_MIC && micAskedFor != s.generation) {
            micAskedFor = s.generation
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                PorticoApp.graph(this).place.onMicResult(true)
            } else {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            }
        }

        val c = PlaceUi.controls(snap)
        findViewById<TextView>(R.id.call_status).text = statusText(s)
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
        val panel = PorticoApp.graph(this).panelMode()
        // DEV-807: Panel mode holds the screen on while this screen is in front, whatever the call says.
        if (c.keepScreenOn || com.meda.portico.core.panel.KeepScreenOn.wanted(panel, inFront = true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // DEV-807 slice 2: the "Microphone is open" banner on the outgoing screen too (a house call, a call button), for the whole call.
        findViewById<View>(R.id.call_mic_banner).visibility =
            if (com.meda.portico.core.panel.MicBanner.visible(panel, callLive = c.hangUp && !c.hangUpIsCancel)) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.call_mic_banner_text).setText(if (snap.muted) R.string.panel_mic_muted else R.string.panel_mic_open)
        renderTimer()

        if (PlaceUi.shouldFinish(s)) finish()
    }

    private fun statusText(s: PlaceUiState): String = when (PlaceUi.status(s.snapshot)) {
        PlaceUi.Status.NONE -> ""
        PlaceUi.Status.NEED_MIC -> getString(R.string.call_status_need_mic)
        PlaceUi.Status.PLACING -> getString(R.string.call_out_placing)
        PlaceUi.Status.ANSWERING -> getString(R.string.call_out_answering)
        PlaceUi.Status.RINGING_OUT -> getString(R.string.call_out_ringing, s.request?.name ?: "")
        PlaceUi.Status.CONNECTING -> getString(R.string.call_status_connecting)
        PlaceUi.Status.IN_CALL -> getString(R.string.call_status_in_call)
        PlaceUi.Status.IN_CALL_MUTED -> getString(R.string.call_status_muted)
        PlaceUi.Status.FAILURE -> s.snapshot.failure?.message ?: getString(R.string.call_out_failed)
        PlaceUi.Status.ENDED_USER -> getString(R.string.call_ended_user)
        PlaceUi.Status.ENDED_REMOTE -> getString(R.string.call_ended_remote)
        PlaceUi.Status.ENDED_NETWORK -> getString(R.string.call_ended_network)
        PlaceUi.Status.ENDED_CAP -> getString(R.string.call_ended_cap)
        PlaceUi.Status.ENDED_STOPPED -> getString(R.string.call_ended_stopped)
        PlaceUi.Status.ENDED_OTHER -> getString(R.string.call_out_failed)
    }

    private fun renderTimer() {
        val t = findViewById<TextView>(R.id.call_timer)
        val snap = state?.snapshot
        val at = snap?.connectedAtMs
        if (snap == null || at == null || !PlaceUi.controls(snap).timer) {
            t.visibility = View.GONE
            return
        }
        val (min, sec) = CallUi.elapsed(at, System.currentTimeMillis())
        t.text = getString(R.string.call_timer, min, sec)
        t.visibility = View.VISIBLE
    }

    private companion object {
        const val REQ_MIC = 42
        const val KEY_MIC_ASKED = "mic_asked_for"
    }
}
