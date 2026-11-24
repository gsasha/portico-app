package com.meda.portico.call

import com.meda.portico.core.call.TaskRemoval
import android.app.Notification
import android.app.NotificationChannel
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
import com.meda.portico.core.call.CallUi
import com.meda.portico.core.call.HoldNotice
import com.meda.portico.core.call.HoldNoticeModel
import com.meda.portico.core.call.SessionsState
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ring.RequestCodes
import com.meda.portico.home.HomeActivity
import com.meda.portico.signaling.LogcatLog

/**
 * The foreground service that keeps an answered call alive when the phone is locked or the screen
 * is left: Android 11+ silences a microphone used from the background unless a foreground service
 * of type `microphone` holds it. It does nothing else: the call itself lives in the
 * `AnswerCoordinator`, which starts this service when the microphone is allowed (RECORD_AUDIO must
 * be granted before `startForeground` with that type) and stops it on every end path.
 *
 * The ring itself still uses no foreground service. This one exists only between "the user pressed
 * Answer and allowed the microphone" and the call's end.
 *
 * M7 H2 (gated off): with a call on hold there is still ONE notification, the same channel and id, silent: "On a call: <door>" /
 * "<other door> on hold" (15.13). The coordinator stops this service only when its LAST door session has ended, so the first
 * call to end never takes the other call's microphone with it. While nothing is held the notification is built exactly as before.
 */
class CallService : Service() {

    private val log: HaLog = LogcatLog("Portico")

    private val lock = Any()
    private var destroyed = false

    /** The notification now on screen is the "a call is held" form (so a change back to the plain one is posted). */
    private var heldForm = false
    private var removeSessions: (() -> Unit)? = null
    private var removePlace: (() -> Unit)? = null
    private var removeTalk: (() -> Unit)? = null

    /** APP-02: what the notification now on screen shows (name, connect time, held form), so it is re-posted only when that changes. */
    private var shown: List<Any?>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val graph = PorticoApp.graph(this)
        removeSessions = graph.answer.addSessionsListener { refresh() }
        // APP-02: a call button's call and a Talk reach "connected" with no answer session; the notification's timer starts there.
        removePlace = graph.place.addListener { refresh() }
        removeTalk = graph.talk.addListener { refresh() }
    }

    override fun onDestroy() {
        synchronized(lock) { destroyed = true }
        removeSessions?.invoke()
        removeSessions = null
        removePlace?.invoke()
        removePlace = null
        removeTalk?.invoke()
        removeTalk = null
        super.onDestroy()
    }

    /**
     * Something about the call changed (call thread): re-post the one notification when what it says changed (the name, the moment
     * audio connected, a hold). Never starts the service, and never posts once no call is live, so a late callback cannot bring a
     * finished call's row back.
     */
    private fun refresh() {
        synchronized(lock) {
            if (destroyed) return
            val graph = PorticoApp.graph(this)
            val live = graph.answer.sessionsState().activeCount > 0 || graph.place.state().isLive || graph.talk.state().isLive
            if (!live) return
            try {
                val n = build(graph)
                val key = shownKey(graph)
                if (key == shown) return
                shown = key
                getSystemService(NotificationManager::class.java)?.notify(RequestCodes.CALL_NOTIFICATION_ID, n)
            } catch (e: Exception) {
                log.log(HaLog.Level.WARN, "call service: could not update the notification (${e.javaClass.simpleName})")
            }
        }
    }

    private fun shownKey(graph: com.meda.portico.AppGraph): List<Any?> {
        val bar = currentBar(graph)
        return listOf(bar.name, bar.connectedAtMs, heldForm, bar.phase)
    }

    private fun currentBar(graph: com.meda.portico.AppGraph) = com.meda.portico.core.callbar.CallBars.pick(
        com.meda.portico.core.callbar.CallBars.fromAnswer(graph.answer.state(), CallUi.doorName(graph.answer.state(), graph.doors)),
        com.meda.portico.core.callbar.CallBars.fromPlace(graph.place.state()),
        com.meda.portico.core.callbar.CallBars.fromTalk(graph.talk.state()),
    )

    private fun build(graph: com.meda.portico.AppGraph): Notification {
        val outgoing = graph.place.state().takeIf { it.isLive }
        val talking = graph.talk.state().takeIf { it.isLive }
        val notice = if (outgoing == null && talking == null) HoldNoticeModel.build(graph.answer.sessionsState(), graph.doors::nameFor) else null
        heldForm = notice != null
        val door = outgoing?.request?.name
            ?: talking?.request?.name
            ?: CallUi.doorName(graph.answer.state(), graph.doors) ?: getString(R.string.ring_door_generic)
        // An ANSWERED house ring is an outgoing-path session that reads as "On a call with <name>", not "Calling <name>".
        return notification(this, door, outgoing != null || talking != null, talking != null, notice, answeringHouse = outgoing?.snapshot?.answering == true, connectedAtMs = currentBar(graph).connectedAtMs)
    }

    /** the owner 2026-11-21: whether the microphone foreground service started (a refusal while locked would silence the call), and the keyguard state. Observation only. */
    private fun fgsJournal(graph: com.meda.portico.AppGraph, r: String) {
        try {
            val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked
            graph.pushJournal.diag("ui", "ans-fgs", "r" to r, "locked" to (locked ?: "unknown"))
        } catch (_: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = PorticoApp.graph(this)
        try {
            ensureChannel(this)
            val n = synchronized(lock) { build(graph).also { shown = shownKey(graph) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(RequestCodes.CALL_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(RequestCodes.CALL_NOTIFICATION_ID, n)
            }
            fgsJournal(graph, "ok")
        } catch (e: Exception) {
            // The call goes on in the foreground without it; the log says why it could not be kept alive.
            log.log(HaLog.Level.WARN, "call service: startForeground refused (${e.javaClass.simpleName})")
            fgsJournal(graph, "refused-" + e.javaClass.simpleName.lowercase().filter { it in 'a'..'z' }.take(30))
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** The user swiped Portico away from the recents: the process is about to go, so release the call now. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 0.6.82: the ring window closes its OWN task (finishAndRemoveTask) as Answer is pressed; Android reports that
        // removal here too, and it must not end the answer that has just begun.
        if (!TaskRemoval.releasesCalls(rootIntent?.component?.className, OWN_WINDOW_CLASSES)) {
            super.onTaskRemoved(rootIntent)
            return
        }
        PorticoApp.graph(this).answer.appStopping()
        PorticoApp.graph(this).place.appStopping()
        PorticoApp.graph(this).talk.appStopping()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        const val CHANNEL_ID = "portico_call_v1"

        /** Screens that live in a task of their own and close it themselves (see [TaskRemoval]). */
        private val OWN_WINDOW_CLASSES = setOf(com.meda.portico.ring.RingCallActivity::class.java.name)

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_call_name), NotificationManager.IMPORTANCE_LOW)
            ch.description = context.getString(R.string.channel_call_description)
            ch.setSound(null, null)
            ch.enableVibration(false)
            ch.setShowBadge(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }

        private fun notification(context: Context, door: String, outgoing: Boolean = false, talk: Boolean = false, notice: HoldNotice? = null, answeringHouse: Boolean = false, connectedAtMs: Long? = null): Notification {
            val open = PendingIntent.getActivity(
                context,
                RequestCodes.CALL_OPEN,
                // A talk has no call screen of its own: its button on Home is the screen.
                Intent(context, if (talk) HomeActivity::class.java else if (outgoing) OutgoingCallActivity::class.java else InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val hangup = PendingIntent.getBroadcast(
                context,
                RequestCodes.CALL_HANGUP,
                Intent(context, CallReceiver::class.java).setAction(CallReceiver.ACTION_HANGUP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val action = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource("android", android.R.drawable.ic_menu_close_clear_cancel),
                context.getString(R.string.call_notif_stop),
                hangup,
            ).build()
            val b = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_portico)
                .setContentTitle(notice?.title ?: context.getString(if (outgoing && !answeringHouse) R.string.call_out_notif_title else R.string.call_notif_title, door))
                // APP-02: "Call in progress" with the running duration beside it (the system draws the chronometer; the app
                // wakes nothing to tick it). Before audio connects there is no duration yet: "Connecting…".
                .setContentText(notice?.text ?: context.getString(if (connectedAtMs != null) R.string.call_notif_in_progress else R.string.call_notif_connecting))
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(open)
            if (notice == null && connectedAtMs != null) b.setWhen(connectedAtMs).setShowWhen(true).setUsesChronometer(true)
            // With only a held call left there is no live call for Hang up to end: no button, the tap opens the screen to resume it.
            if (notice == null || notice.hangUp) b.addAction(action)
            return b.build()
        }
    }
}
