package com.meda.portico.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meda.portico.PorticoApp

/** The ongoing-call notification's Stop button. Not exported: only Portico's own PendingIntent reaches it. */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_HANGUP) {
            CallActions.hangUpAll(PorticoApp.graph(context)) // whichever call is live; the others are idle and ignore it
        }
    }

    companion object {
        const val ACTION_HANGUP = "com.meda.portico.call.HANGUP"
    }
}
