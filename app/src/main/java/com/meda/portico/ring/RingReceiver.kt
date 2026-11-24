package com.meda.portico.ring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meda.portico.PorticoApp

/**
 * The ring's two broadcast actions (P2 plan §3.4). Not exported: only Portico's own PendingIntents
 * (the CallStyle decline button and the AlarmManager backstop) reach it, and a PendingIntent runs
 * with its creator's identity.
 *
 * - [ACTION_DECLINE]: stop ringing on this phone. HA is not told: the call goes on for the others.
 * - [ACTION_EXPIRE]: the backstop at `ringUntilMs`, for when no later message arrived and
 *   `setTimeoutAfter` did not already remove the notification.
 *
 * Both stop only the call named in the extras, and only if it is still the door's active ring:
 * a PendingIntent left over from an earlier call on the same door stops nothing.
 */
class RingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val door = intent.getStringExtra(EXTRA_DOOR) ?: return
        val call = intent.getStringExtra(EXTRA_CALL) ?: return
        val why = when (intent.action) {
            ACTION_DECLINE -> "declined"
            ACTION_EXPIRE -> "backstop expired"
            else -> return
        }
        PorticoApp.graph(context).ring.stopRing(door, call, why)
    }

    companion object {
        const val ACTION_DECLINE = "com.meda.portico.ring.DECLINE"
        const val ACTION_EXPIRE = "com.meda.portico.ring.EXPIRE"
        const val EXTRA_DOOR = "door_ref"
        const val EXTRA_CALL = "call_id"
    }
}
