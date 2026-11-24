package com.meda.portico.pause

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.meda.portico.R
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.pause.PauseRules
import com.meda.portico.core.pause.PauseState
import com.meda.portico.core.ring.Channel
import com.meda.portico.core.ring.RequestCodes
import com.meda.portico.setup.SetupActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pause's Android side effects that are not connections: the auto-resume alarm and the silent
 * ongoing notification. Never throws (each failure is one log line; the deadline is re-checked on
 * every process start, on boot and by every hot path anyway).
 *
 * THE ALARM is `setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)`: inexact (Doze may hold it a few
 * minutes), but it needs no exact-alarm permission, and within one boot nobody setting the clock
 * can move it. Across a reboot it is gone, so the boot receiver and every process start re-arm it
 * from the stored wall-clock deadline.
 */
class PauseNotices(private val context: Context, private val log: HaLog) {

    fun armAlarm(s: PauseState) {
        try {
            val left = PauseRules.remaining(s, System.currentTimeMillis())
            if (left <= 0L) {
                cancelAlarm()
                return
            }
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + left, alarmPi())
        } catch (e: Exception) {
            log.log(HaLog.Level.WARN, "pause: could not arm the auto-resume (${e.javaClass.simpleName}); the next check after the deadline resumes")
        }
    }

    fun cancelAlarm() {
        try {
            context.getSystemService(AlarmManager::class.java)?.cancel(alarmPi())
        } catch (e: Exception) {
            // nothing armed, or no AlarmManager: either way nothing will fire
        }
    }

    /** One silent, ongoing notification, so a pause cannot be forgotten. */
    fun post(s: PauseState) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val text = if (s.untilMs == 0L) {
                context.getString(R.string.pause_notification_text_resumed)
            } else {
                context.getString(R.string.pause_notification_text_until, hhmm(s.untilMs))
            }
            val resume = PendingIntent.getBroadcast(
                context, RequestCodes.PAUSE_RESUME,
                Intent(context, PauseReceiver::class.java).setAction(PauseReceiver.ACTION_RESUME),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val open = PendingIntent.getActivity(
                context, RequestCodes.PAUSE_OPEN,
                Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val action = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource("android", android.R.drawable.ic_media_play),
                context.getString(R.string.pause_resume), resume,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) action.setAuthenticationRequired(false)
            val n = Notification.Builder(context, Channel.PAUSED.id)
                .setSmallIcon(R.drawable.ic_stat_portico)
                .setContentTitle(context.getString(R.string.pause_notification_title))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .addAction(action.build())
                .build()
            nm.notify(RequestCodes.PAUSE_NOTIFICATION_ID, n)
        } catch (e: Exception) {
            log.log(HaLog.Level.WARN, "pause: could not post the paused notification (${e.javaClass.simpleName})")
        }
    }

    fun cancelNotification() {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(RequestCodes.PAUSE_NOTIFICATION_ID)
        } catch (e: Exception) {
            // nothing to cancel
        }
    }

    private fun alarmPi(): PendingIntent = PendingIntent.getBroadcast(
        context, RequestCodes.PAUSE_ALARM,
        Intent(context, PauseReceiver::class.java).setAction(PauseReceiver.ACTION_DEADLINE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /** "14:30" in the phone's time zone: Setup's top line and the notification say the same. */
        fun hhmm(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMs))
    }
}
