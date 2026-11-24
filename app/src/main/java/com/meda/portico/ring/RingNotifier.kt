package com.meda.portico.ring

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.os.Build
import com.meda.portico.R
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.panel.RingPath
import com.meda.portico.home.HomeActivity
import com.meda.portico.core.ring.Channel
import com.meda.portico.core.ring.RingAlertChannel
import com.meda.portico.core.ring.RingScreenPolicy
import com.meda.portico.core.ring.CallStyleFallback
import com.meda.portico.core.ring.NoteKind
import com.meda.portico.core.ring.NotificationSpec
import com.meda.portico.core.ring.PiAction
import com.meda.portico.core.ring.RequestCodes
import com.meda.portico.core.ring.TextKey

/**
 * Posts what [NotificationSpec] says, the old intercom app's shape (`Ring.java:594-700`, P2 plan
 * §1.4, §3.4): CallStyle + a full-screen intent, NO foreground service.
 *
 * The ring:
 * - `Notification.CallStyle.forIncomingCall(person, decline, answer)` on Android 12+, CATEGORY_CALL,
 *   ongoing, FLAG_INSISTENT. CallStyle is accepted without a foreground service because a
 *   full-screen intent is attached (memory `android-call-screen-over-lockscreen` ③).
 * - `setFullScreenIntent(pi, true)` to [RingCallActivity] (0.6.75: the dialer-style call window, no longer Home), the same PendingIntent as the content
 *   intent. On Android 14+ the PendingIntent is created with
 *   `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` (the sender must opt in, memory checklist ②).
 * - 0.6.76: Answer opens the MAIN screen ([HomeActivity], ACTION_ANSWER; an ACTIVITY PendingIntent: a broadcast that then starts an activity is a
 *   notification trampoline). Home presses the answer, asks for the microphone and shows the call bar; the call window is the ringing screen only.
 *   Decline is a broadcast to [RingReceiver]: it stops ringing on this phone only.
 * - 0.6.76: on an unlocked phone with the overlay permission ([DirectRingLauncher.decide] = DIRECT_OVERLAY) the notification goes on the quiet
 *   [Channel.RING_WINDOW] (no heads-up banner over the window), the call window rings itself ([RingWindowAlert]), and if the direct launch fails
 *   or no window comes up the same notification id is re-posted on the loud ring channel. Locked or screen off: the ring channel, as before.
 * - `setTimeoutAfter(ringUntilMs - now)` AND an AlarmManager cancel at `ringUntilMs`
 *   ([armBackstop]): an ongoing notification must have a way out that needs no later message
 *   (memory `backstop-must-not-need-what-failed`).
 * - If `notify()` refuses the CallStyle (it throws, it does not drop silently), a plain
 *   notification with Open / Stop actions is posted instead: the ring must not be lost. PR-G:
 *   [CallStyleFallback] then hands the outcome to [recordCallStyle] (the app persists it in
 *   [RingState]), so Setup shows the refusal instead of only the log.
 * - The activity is never started directly. Locked or screen off, the full-screen intent takes
 *   the screen; unlocked, Android shows a heads-up (memory checklist ①: a background start while
 *   locked is DEFERRED to the unlock).
 *
 * Alarm API: `setAndAllowWhileIdle(RTC_WAKEUP)`. It needs no permission and fires in Doze. It is
 * inexact, which is fine for a backstop: the exact cap is `setTimeoutAfter`, enforced by
 * system_server. `SCHEDULE_EXACT_ALARM` is denied by default for new installs on Android 14 and
 * `USE_EXACT_ALARM` is reserved for alarm and calendar apps, so neither is declared.
 */
class RingNotifier(
    private val context: Context,
    private val log: HaLog,
    /** PR-G: null = the CallStyle was accepted; else the refusal's class name. */
    private val recordCallStyle: (String?) -> Unit = {},
    /** 0.6.74: opens the ring screen directly while the phone is unlocked and in use (needs "Display over other apps"). */
    private val direct: DirectRingLauncher? = null,
    /** DEV-807 (Panel mode): how a door ring ended up (call id, door ref, path): `wall ring-front` and the heartbeat's `last_ring_*`. Never throws into the ring. */
    private val ringFront: (String, String, RingPath) -> Unit = { _, _, _ -> },
) : RingPoster {

    private val nm: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    override fun post(spec: NotificationSpec, slot: Int?, doorName: String?) {
        Channels.ensure(context)
        val door = doorName ?: context.getString(R.string.ring_door_generic)
        when (spec.kind) {
            NoteKind.RING -> postRing(spec, slot ?: error("a ring needs a door slot"), door)
            else -> postStatus(spec, slot, door)
        }
    }

    override fun cancelRing(slot: Int) {
        nm.cancel(RequestCodes.ringNotificationId(slot))
        synchronized(quietCalls) { quietCalls.remove(slot) }?.let { RingWindowSignals.ownership.release(it) }
    }

    /** 0.6.76: slot -> the call whose notification is on the quiet channel (the call window rings it). Guards the fallback against a ring that has ended. */
    private val quietCalls = HashMap<Int, String>()

    override fun armBackstop(slot: Int, doorRef: String, callId: String, atMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, receiverPi(slot, PiAction.EXPIRE, RingReceiver.ACTION_EXPIRE, doorRef, callId))
    }

    override fun cancelBackstop(slot: Int) {
        val pi = PendingIntent.getBroadcast(
            context,
            RequestCodes.requestCode(slot, PiAction.EXPIRE),
            Intent(context, RingReceiver::class.java).setAction(RingReceiver.ACTION_EXPIRE),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        context.getSystemService(AlarmManager::class.java)?.cancel(pi)
        pi.cancel()
    }

    private fun postRing(spec: NotificationSpec, slot: Int, door: String) {
        val doorRef = spec.doorRef!!
        val callId = spec.callId!!
        // 0.6.76: decided BEFORE posting. A direct launch (unlocked phone) puts the notification on the quiet channel (no heads-up banner over the
        // call window; the window rings); everything else keeps the ring channel and its full-screen intent.
        val launcher = if (spec.fullScreen) direct else null
        val decision = launcher?.decide() ?: RingScreenPolicy.Decision.FULL_SCREEN_INTENT_ONLY
        val quiet = RingAlertChannel.forRing(spec.channel, decision, launchIssued = launcher != null) == Channel.RING_WINDOW
        if (quiet) {
            RingWindowSignals.ownership.claim(callId)
            synchronized(quietCalls) { quietCalls.put(slot, callId) }?.takeIf { it != callId }?.let { RingWindowSignals.ownership.release(it) }
        }
        postRingNotification(spec, slot, door, doorRef, callId, quiet)
        reportPath(callId, doorRef, if (quiet) RingPath.DIRECT else fsiOrBanner())
        if (quiet && launcher != null) {
            launcher.journalLine(RingScreenPolicy.QUIET_LINE)
            launcher.launch(
                showIntent(RingCallActivity.ACTION_SHOW, doorRef, callId),
                windowUp = { RingWindowSignals.ownership.isOpen(callId) },
                onFailed = { backToLoud(spec, slot, door, doorRef, callId, launcher) },
                onPath = { reportPath(callId, doorRef, it) },
            )
        }
    }

    private fun reportPath(callId: String, doorRef: String, path: RingPath) {
        try { ringFront(callId, doorRef, path) } catch (_: Throwable) { }
    }

    /**
     * The full-screen intent's fate (DEV-807): it takes the screen only when the device is locked or the screen is off; on an unlocked, in-use
     * device Android shows a banner instead. Unknown reads as locked (the policy's own rule).
     */
    private fun fsiOrBanner(): RingPath = try {
        val interactive = context.getSystemService(android.os.PowerManager::class.java)?.isInteractive ?: false
        val locked = context.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked ?: true
        if (!interactive || locked) RingPath.FSI else RingPath.BANNER
    } catch (_: Throwable) {
        RingPath.FSI
    }

    /** The launch failed or no window came up: the ring (if it is still this call's) goes back on the loud ring channel, a fresh alert. */
    private fun backToLoud(spec: NotificationSpec, slot: Int, door: String, doorRef: String, callId: String, launcher: DirectRingLauncher) {
        val still = synchronized(quietCalls) { if (quietCalls[slot] == callId) quietCalls.remove(slot) != null else false }
        RingWindowSignals.ownership.release(callId)
        if (!still) return // the ring ended (cancelRing) or another call took the slot
        launcher.journalLine(RingScreenPolicy.BANNER_RESTORED_LINE)
        nm.cancel(RequestCodes.ringNotificationId(slot)) // a fresh post alerts; an update of the quiet one might not
        postRingNotification(spec, slot, door, doorRef, callId, quiet = false)
    }

    private fun postRingNotification(spec: NotificationSpec, slot: Int, door: String, doorRef: String, callId: String, quiet: Boolean) {
        val full = activityPi(slot, PiAction.FULL_SCREEN, RingCallActivity::class.java, RingCallActivity.ACTION_SHOW, doorRef, callId)
        // 0.6.76: Answer goes straight to the MAIN screen (Home presses the answer, asks for the microphone and shows the call bar), never through the call window.
        val answer = activityPi(slot, PiAction.ANSWER, HomeActivity::class.java, HomeActivity.ACTION_ANSWER, doorRef, callId)
        val decline = receiverPi(slot, PiAction.DECLINE, RingReceiver.ACTION_DECLINE, doorRef, callId)
        val channel = if (quiet) Channel.RING_WINDOW else spec.channel

        fun base(): Notification.Builder = Notification.Builder(context, Channels.idOf(context, channel))
            .setSmallIcon(R.drawable.ic_stat_portico)
            .setContentTitle(text(spec.title, door))
            .setContentText(text(spec.text, door))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(spec.ongoing)
            .setAutoCancel(spec.autoCancel)
            .setContentIntent(full)
            .apply {
                if (spec.fullScreen) setFullScreenIntent(full, true)
                spec.timeoutMs?.let { setTimeoutAfter(it) }
            }

        fun finish(b: Notification.Builder): Notification = b.build().also {
            if (spec.insistent && !quiet) it.flags = it.flags or Notification.FLAG_INSISTENT // the quiet channel makes no sound; the window rings
        }

        val id = RequestCodes.ringNotificationId(slot)
        // D-20261121-ee09: what this ring will sound like (the loud channel plays it itself; the quiet one leaves it to the window) and why it could be silent.
        try {
            RingEnvProbe.journal(
                com.meda.portico.PorticoApp.graph(context), context, "ring-post", Channel.RING,
                "call" to com.meda.portico.core.push.PushJournalFormat.DiagId(callId), "on" to channel.name.lowercase(), "insist" to (spec.insistent && !quiet),
                "fsi" to spec.fullScreen, "cs" to (spec.callStyle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S),
            )
        } catch (_: Throwable) {
        }
        fun postPlain() {
            val plain = base()
                .addAction(action(android.R.drawable.sym_action_call, context.getString(R.string.ring_action_open), answer))
                .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, context.getString(R.string.ring_action_stop), decline))
            nm.notify(id, finish(plain))
        }
        if (!spec.callStyle || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            postPlain()
            return
        }
        val refusedBy = CallStyleFallback.post(
            callStyle = {
                val person = Person.Builder().setName(text(TextKey.RING_TITLE, door)).setImportant(true).build()
                nm.notify(id, finish(base().setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))))
            },
            plain = ::postPlain,
            record = recordCallStyle,
        )
        if (refusedBy != null) log.log(HaLog.Level.WARN, "portico ring: CallStyle refused ($refusedBy); posted a plain ring")
    }

    private fun postStatus(spec: NotificationSpec, slot: Int?, door: String) {
        val b = Notification.Builder(context, Channels.idOf(context, spec.channel))
            .setSmallIcon(R.drawable.ic_stat_portico)
            .setContentTitle(text(spec.title, door))
            .setContentText(text(spec.text, door))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(spec.ongoing)
            .setAutoCancel(spec.autoCancel)
            .setOnlyAlertOnce(spec.onlyAlertOnce)
        spec.timeoutMs?.let { b.setTimeoutAfter(it) }
        val id = if (slot == null) RequestCodes.TEST_NOTIFICATION_ID else RequestCodes.statusNotificationId(slot)
        nm.notify(id, b.build())
    }

    private fun action(icon: Int, label: String, pi: PendingIntent): Notification.Action {
        val b = Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource("android", icon), label, pi)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAuthenticationRequired(false)
        return b.build()
    }

    /**
     * The intent of the ring screen, 0.6.75: the CALL WINDOW ([RingCallActivity], the dialer-style full-screen window). The full-screen
     * intent's, the content intent's, the Answer action's and the direct launch's: one intent shape, so one window per ring.
     */
    private fun showIntent(intentAction: String, doorRef: String, callId: String, target: Class<*> = RingCallActivity::class.java): Intent =
        Intent(context, target)
            .setAction(intentAction)
            .putExtra(RingCallActivity.EXTRA_DOOR, doorRef)
            .putExtra(RingCallActivity.EXTRA_CALL, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun activityPi(slot: Int, action: PiAction, target: Class<*>, intentAction: String, doorRef: String, callId: String): PendingIntent {
        // The content and full-screen intents open the call window; the Answer action opens Home (APP-03: Home takes the answer; CLEAR_TOP +
        // SINGLE_TOP bring an open Settings or About back to it without a second copy of Home, NEW_TASK joins the app's own task).
        val intent = showIntent(intentAction, doorRef, callId, target)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val code = RequestCodes.requestCode(slot, action)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val opts = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
            PendingIntent.getActivity(context, code, intent, flags, opts)
        } else {
            PendingIntent.getActivity(context, code, intent, flags)
        }
    }

    private fun receiverPi(slot: Int, action: PiAction, intentAction: String, doorRef: String, callId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            RequestCodes.requestCode(slot, action),
            Intent(context, RingReceiver::class.java)
                .setAction(intentAction)
                .putExtra(RingReceiver.EXTRA_DOOR, doorRef)
                .putExtra(RingReceiver.EXTRA_CALL, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun text(key: TextKey, door: String): String = context.getString(Texts.res(key), door)
}

/** [TextKey] -> string resource. Every string takes the door name as `%1$s` (it may ignore it). */
object Texts {
    fun res(key: TextKey): Int = when (key) {
        TextKey.RING_TITLE -> R.string.ring_title
        TextKey.RING_TEXT -> R.string.ring_text
        TextKey.RING_TEXT_BOTH_BUSY -> R.string.ring_text_both_busy
        TextKey.ANSWERED_TITLE -> R.string.answered_title
        TextKey.ANSWERED_TEXT -> R.string.answered_text
        TextKey.MISSED_TITLE -> R.string.missed_title
        TextKey.MISSED_TEXT -> R.string.missed_text
        TextKey.TEST_TITLE -> R.string.test_title
        TextKey.TEST_TEXT -> R.string.test_text
    }
}
