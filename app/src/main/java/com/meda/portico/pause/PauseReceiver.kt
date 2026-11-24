package com.meda.portico.pause

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meda.portico.PorticoApp

/**
 * Pause's two broadcasts. Not exported, no intent filter: only Portico's own PendingIntents (the
 * auto-resume alarm and the paused notification's Resume action) reach it, and a PendingIntent
 * runs with its creator's identity.
 *
 * - [ACTION_DEADLINE]: the auto-resume alarm. Re-checks the stored deadline rather than trusting
 *   the alarm (a pause resumed by hand and paused again leaves a stale one; a clock change can
 *   make it early).
 * - [ACTION_RESUME]: the notification's Resume button.
 */
class PauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = PorticoApp.graph(context)
        when (intent.action) {
            ACTION_DEADLINE -> graph.onPauseAlarm()
            ACTION_RESUME -> graph.resume("the notification's Resume")
        }
    }

    companion object {
        const val ACTION_DEADLINE = "com.meda.portico.pause.DEADLINE"
        const val ACTION_RESUME = "com.meda.portico.pause.RESUME"
    }
}
