package com.meda.portico.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meda.portico.PorticoApp

/**
 * After a reboot or an APK update the app is not running and (after an update) stays stopped
 * until something starts it (P2 plan §3.4). Receiving either broadcast starts the process, whose
 * `PorticoApp.onCreate` already starts the socket; this also forces an immediate connect, and
 * the registration machine registers on that connect. Not exported: these are system broadcasts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> PorticoApp.graph(context).onBootOrUpdate()
        }
    }
}
