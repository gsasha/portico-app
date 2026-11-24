package com.meda.portico.ring

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.meda.portico.PorticoApp
import com.meda.portico.R
import com.meda.portico.call.OutgoingCallActivity
import com.meda.portico.core.call.HouseRing

/**
 * The ring screen of a HOUSE call: "<Name> is calling", Answer and Decline (M7 3b-B). It is the door ring screen's layout with the
 * door things hidden (no stills, no buttons, no hold line): a house call has no door and nothing to open.
 *
 * - The caller's name is the document's (`callers` or the roster), never an id or a reference; a caller it does not name is
 *   "Someone".
 * - Answer is the person's press (this button, or the notification's Answer action, which opens this screen and presses once on its
 *   first delivery): the ONLY thing that starts an answer. Showing the screen, a re-delivered intent or a rotation never does.
 *   The answer then runs as a placed call does (`PlaceCoordinator`), on [OutgoingCallActivity].
 * - Decline tells the house (`portico/house/decline`) and closes. A ring that ends (answered elsewhere, cancelled, outranked by a
 *   door, expired) closes the screen by itself.
 * - Shows over the lock screen like the door ring; it never asks to dismiss the keyguard (that would demand the unlock).
 */
class HouseRingActivity : AppCompatActivity() {
    private val audioControls by lazy { com.meda.portico.call.AudioControlsView(this) }

    private var callId: String? = null
    private var remove: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContentView(R.layout.activity_ring)
        audioControls.bind(false)
        // The door ring's extras do not exist for a house call.
        for (id in listOf(R.id.ring_hold_answer, R.id.ring_hold_reason, R.id.ring_oncall, R.id.ring_stills, R.id.ring_extra, R.id.ring_result)) findViewById<View>(id).visibility = View.GONE
        findViewById<TextView>(R.id.ring_explain).setText(R.string.house_ring_explain)
        findViewById<Button>(R.id.ring_stop).setText(R.string.ring_decline)
        findViewById<Button>(R.id.ring_answer).setOnClickListener { answer() }
        findViewById<Button>(R.id.ring_stop).setOnClickListener { decline() }
        take(intent)
        // The notification's Answer action, on its first delivery only: a recreated activity gets the same intent again and must not answer twice.
        if (savedInstanceState == null) act(intent.action)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
        act(intent.action)
    }

    private fun take(i: Intent) {
        callId = i.getStringExtra(EXTRA_CALL)
    }

    private fun act(action: String?) {
        when (action) {
            ACTION_ANSWER -> answer()
            ACTION_DECLINE -> decline()
        }
    }

    override fun onStart() {
        super.onStart()
        com.meda.portico.panel.PanelScreen.keepOn(this) // DEV-807: Panel mode holds the screen on while this screen is in front
        audioControls.attach()
        remove = PorticoApp.graph(this).houseRing.addListener { s -> runOnUiThread { render(s.ring) } }
    }

    override fun onStop() {
        audioControls.detach()
        remove?.invoke()
        remove = null
        super.onStop()
    }

    private fun render(ring: HouseRing?) {
        if (isFinishing || isDestroyed) return
        // The ring is over (answered elsewhere, ended, outranked, expired): nothing to answer here.
        if (ring == null || ring.callId != callId) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            finish()
            return
        }
        findViewById<TextView>(R.id.ring_door).text = getString(R.string.house_ring_title, ring.name ?: getString(R.string.house_someone))
        findViewById<TextView>(R.id.ring_state).setText(R.string.house_ring_state)
        findViewById<Button>(R.id.ring_answer).visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The user pressed Answer: the coordinator withdraws the ring and starts the answer, which is shown on the call screen. */
    private fun answer() {
        val c = callId ?: return
        PorticoApp.graph(this).houseRing.answer(c)
        startActivity(Intent(this, OutgoingCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun decline() {
        val c = callId ?: return
        PorticoApp.graph(this).houseRing.decline(c)
        finish()
    }

    companion object {
        const val ACTION_SHOW = "com.meda.portico.ring.HOUSE_SHOW"
        const val ACTION_ANSWER = "com.meda.portico.ring.HOUSE_ANSWER"
        const val ACTION_DECLINE = "com.meda.portico.ring.HOUSE_DECLINE"
        const val EXTRA_CALL = "house_call_id"
    }
}
