package com.meda.portico.stay

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.meda.portico.PorticoApp
import com.meda.portico.R
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ring.Channel
import com.meda.portico.core.ring.RequestCodes
import com.meda.portico.core.stay.StayConnectedPolicy
import com.meda.portico.home.HomeActivity
import com.meda.portico.ring.Channels
import com.meda.portico.signaling.LogcatLog

/**
 * "Stay connected": a foreground service of type `connectedDevice` whose only job is to make
 * Android treat the process as running, so the Home Assistant WebSocket survives the app being in
 * the background. It holds no socket and runs no timer: the socket belongs to `SignalingClient`,
 * and what the service does with it (hold on Wi-Fi/Ethernet, release on mobile data) is decided
 * by `:core`'s `StayConnectedPolicy` through `AppGraph.stay`.
 *
 * Android 12+ refuses to START a foreground service from the background, so this is started only
 * from allowed contexts (the app opening, boot or update, a push, a tap on a notification) and,
 * while the switch is on, simply stays started. [START_STICKY]: if Android kills the process the
 * service is brought back; if that restart is refused the catch below leaves the app working as
 * it did before this service existed.
 *
 * FAIL SAFE: if `startForeground` throws, the failure is logged once and the service stops itself.
 * A permission-class failure (Android 14+ refuses `connectedDevice` without one of its
 * prerequisite permissions, CHANGE_NETWORK_STATE here) is sticky: not started again by this
 * process until the switch is toggled, a screen opens, or boot/update. A transient "not now"
 * (`ForegroundServiceStartNotAllowedException`, or any failure of the null-intent sticky restart
 * made from the background) is not remembered, so the next allowed context starts it. No crash,
 * no loop; the socket lives as long as the process, as before.
 */
class ConnectionService : Service() {

    private val log: HaLog = LogcatLog("Portico")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = PorticoApp.graph(this)
        try {
            Channels.ensure(this)
            val n = notification(this, graph.stayNoteText(), graph.stayHomeName())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(RequestCodes.CONNECTION_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(RequestCodes.CONNECTION_NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            if (StayConnectedPolicy.isPermanentRefusal(e, restartedWithoutIntent = intent == null)) {
                log.log(HaLog.Level.WARN, "stay connected: startForeground refused (${e.javaClass.simpleName}); carrying on without the service")
                graph.stayServiceRefused()
            } else {
                // "Not now" (a background start, or the sticky restart): the next allowed context retries.
                log.log(HaLog.Level.INFO, "stay connected: startForeground not allowed now (${e.javaClass.simpleName}); will retry at the next allowed context")
            }
            stopSelf()
            return START_NOT_STICKY
        }
        graph.stayServiceStarted()
        return START_STICKY
    }

    override fun onDestroy() {
        PorticoApp.graph(this).stayServiceStopped()
        super.onDestroy()
    }

    companion object {
        fun notification(context: Context, text: StayConnectedPolicy.Text, homeName: String?): Notification {
            val name = homeName ?: context.getString(R.string.stay_note_home_fallback)
            val line = when (text) {
                StayConnectedPolicy.Text.CONNECTED -> context.getString(R.string.stay_note_connected, name)
                StayConnectedPolicy.Text.CONNECTING -> context.getString(R.string.stay_note_connecting, name)
                StayConnectedPolicy.Text.WAITING_FOR_WIFI -> context.getString(R.string.stay_note_waiting_wifi)
                StayConnectedPolicy.Text.PUSH_ONLY -> context.getString(R.string.stay_note_push_only)
            }
            val open = PendingIntent.getActivity(
                context, RequestCodes.CONNECTION_OPEN,
                Intent(context, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // A quiet status line: LOW channel (no sound, no vibration), ongoing, never a ring or a CallStyle.
            return Notification.Builder(context, Channel.CONNECTION.id)
                .setSmallIcon(R.drawable.ic_stat_portico)
                .setContentTitle(context.getString(R.string.stay_note_title))
                .setContentText(line)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        }

        fun post(context: Context, text: StayConnectedPolicy.Text, homeName: String?) {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(RequestCodes.CONNECTION_NOTIFICATION_ID, notification(context, text, homeName))
        }
    }
}
