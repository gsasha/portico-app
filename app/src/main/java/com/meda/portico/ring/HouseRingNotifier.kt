package com.meda.portico.ring

import android.app.Notification
import android.app.ActivityOptions
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.meda.portico.R
import com.meda.portico.core.call.HouseRing
import com.meda.portico.core.call.HouseRingCoordinator
import com.meda.portico.core.call.HouseRingUi
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ring.RequestCodes

/**
 * The notification of a HOUSE ring (M7 3b-B): the door ring's shape without a door. One notification, on the door ring's channel
 * (the user's "ring" choices apply: a lowered or blocked channel is the user's choice, and Setup already says so), CATEGORY_CALL, a
 * full-screen intent to [HouseRingActivity], Answer (an ACTIVITY PendingIntent: a broadcast that starts an activity is a
 * notification trampoline) and Decline. It times out by itself at the ring's end, so it can never outlive its call.
 *
 * A house call never rings through Android's Do Not Disturb: that is decided before this is reached
 * ([HouseRingCoordinator]'s `dndOn`), so nothing is posted then. The caller's name is the document's, or "Someone": never an id.
 */
class HouseRingNotifier(
    private val context: Context,
    private val log: HaLog,
    /** 0.6.74: opens the ring screen directly while the phone is unlocked and in use (needs "Display over other apps"). */
    private val direct: DirectRingLauncher? = null,
) : HouseRingUi {

    private val nm: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)

    override fun show(ring: HouseRing) {
        try {
            Channels.ensure(context)
            val name = ring.name ?: context.getString(R.string.house_someone)
            val full = activityPi(RequestCodes.HOUSE_RING_FULL, HouseRingActivity.ACTION_SHOW, ring.callId)
            val answer = activityPi(RequestCodes.HOUSE_RING_ANSWER, HouseRingActivity.ACTION_ANSWER, ring.callId)
            val decline = activityPi(RequestCodes.HOUSE_RING_DECLINE, HouseRingActivity.ACTION_DECLINE, ring.callId)
            val b = Notification.Builder(context, Channels.ringId(context))
                .setSmallIcon(R.drawable.ic_stat_portico)
                .setContentTitle(context.getString(R.string.house_ring_title, name))
                .setContentText(context.getString(R.string.house_ring_text))
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(full)
                .setFullScreenIntent(full, true)
                .setTimeoutAfter((ring.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(1_000))
                .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.sym_action_call), context.getString(R.string.ring_answer), answer).build())
                .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel), context.getString(R.string.ring_decline), decline).build())
            val n = b.build()
            n.flags = n.flags or Notification.FLAG_INSISTENT
            nm?.notify(RequestCodes.HOUSE_RING_NOTIFICATION_ID, n)
            // 0.6.74: after the notification; locked or screen off, the launcher does nothing and the full-screen intent keeps the screen.
            direct?.onRing(
                Intent(context, HouseRingActivity::class.java)
                    .setAction(HouseRingActivity.ACTION_SHOW)
                    .putExtra(HouseRingActivity.EXTRA_CALL, ring.callId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            log.log(HaLog.Level.WARN, "house: the ring notification could not be posted (${e.javaClass.simpleName})")
        }
    }

    override fun dismiss(callId: String, why: HouseRingCoordinator.Dismiss) {
        try {
            nm?.cancel(RequestCodes.HOUSE_RING_NOTIFICATION_ID)
        } catch (e: Exception) {
            log.log(HaLog.Level.WARN, "house: the ring notification could not be cancelled (${e.javaClass.simpleName})")
        }
    }

    private fun activityPi(code: Int, action: String, callId: String): PendingIntent {
        val intent = Intent(context, HouseRingActivity::class.java)
            .setAction(action)
            .putExtra(HouseRingActivity.EXTRA_CALL, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // Android 14+: the sender must opt in to a background start for the full-screen intent (the door ring's own rule).
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val opts = ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
            PendingIntent.getActivity(context, code, intent, flags, opts)
        } else {
            PendingIntent.getActivity(context, code, intent, flags)
        }
    }
}
