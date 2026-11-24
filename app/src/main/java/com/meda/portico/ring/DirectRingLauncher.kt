package com.meda.portico.ring

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.panel.RingPath
import com.meda.portico.core.push.PushJournalFormat
import com.meda.portico.core.ring.RingScreenPolicy
import java.util.TimeZone

/**
 * 0.6.74 (experimental): opens the ring screen directly while the phone is UNLOCKED and in use, in addition to the ring notification.
 * Android shows a full-screen intent only when the device is locked or the screen is off; an app holding "Display over other apps" may start
 * an activity from the background, and since Android 15 only while it has a visible TYPE_APPLICATION_OVERLAY window.
 *
 * The sequence ([RingScreenPolicy] decides whether it runs at all; locked, screen off, or no permission = nothing here, the full-screen
 * intent keeps the screen exactly as before):
 *  1. add a 1x1 px transparent, untouchable, unfocusable TYPE_APPLICATION_OVERLAY window;
 *  2. after [OVERLAY_SETTLE_MS] (the window must be shown, not merely added) startActivity(the ring screen's intent);
 *  3. remove the window [REMOVE_AFTER_START_MS] after the start, in a finally; a hard timer [HARD_REMOVE_MS] removes it whatever happens.
 * A start that throws is journalled ("ring screen: direct failed <class>"). 0.6.76: a door ring decides BEFORE it posts ([decide]) and posts
 * on the quiet channel only for a direct launch ([launch]); a launch that fails, throws or leaves no window within [WINDOW_WATCHDOG_MS] runs
 * the caller's fallback (the ring back on the loud channel), so the ring is never silent and never invisible.
 * Everything is inside try/catch; this class never throws into the ring path. Android 15+ may BLOCK a start without throwing; the journal
 * then still says "direct (overlay)" (the start was issued) and logcat has the system's "Abort background activity starts" line; the
 * watchdog catches that case.
 */
class DirectRingLauncher(
    context: Context,
    private val log: HaLog,
    private val journal: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val app: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    // main-thread only
    private var overlay: View? = null

    /**
     * The decision, BEFORE anything is posted (0.6.76: it picks the notification's channel). A non-direct decision is journalled here with its
     * why; an unknown state counts as locked. Never throws.
     */
    fun decide(): RingScreenPolicy.Decision = try {
        val can = canDrawOverlays()
        val interactive = app.getSystemService(PowerManager::class.java)?.isInteractive ?: false
        val locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true // unknown = locked = leave it to the FSI
        val sdk = Build.VERSION.SDK_INT
        val d = RingScreenPolicy.decide(can, interactive, locked, sdk)
        if (d != RingScreenPolicy.Decision.DIRECT_OVERLAY) note(RingScreenPolicy.notificationLine(can, interactive, locked, sdk))
        d
    } catch (e: Throwable) {
        failed(e)
        RingScreenPolicy.Decision.FULL_SCREEN_INTENT_ONLY
    }

    /** House rings: call after the notification is posted; that notification keeps its channel (the house ring screen is not the call window). */
    fun onRing(ringScreen: Intent) {
        try {
            if (decide() == RingScreenPolicy.Decision.DIRECT_OVERLAY) main.post { launchOnMain(ringScreen, null) }
        } catch (e: Throwable) {
            failed(e)
        }
    }

    /**
     * 0.6.76: the door ring, after [decide] said DIRECT_OVERLAY and the notification went on the quiet channel. Issues the launch on the main
     * thread. If it cannot be issued (the overlay window, the start throwing) or the window is not up [WINDOW_WATCHDOG_MS] after the start
     * ([windowUp] false: Android 15 can block a background start without throwing), [onFailed] runs ONCE, on the main thread, so the caller
     * puts the ring back on the loud channel. Never throws.
     */
    fun launch(ringScreen: Intent, windowUp: () -> Boolean, onFailed: () -> Unit, onPath: ((RingPath) -> Unit)? = null) {
        main.post { launchOnMain(ringScreen, Watch(windowUp, onFailed, onPath)) }
    }

    /** Journals [line] with the launcher's own log and journal (observation only). */
    fun journalLine(line: String) = note(line)

    private class Watch(val windowUp: () -> Boolean, private val callback: () -> Unit, private val onPath: ((RingPath) -> Unit)? = null) {
        private var fired = false

        /** DEV-807: how the ring ended up, for `wall ring-front` and the heartbeat (never throws). */
        fun path(p: RingPath) {
            try { onPath?.invoke(p) } catch (_: Throwable) { }
        }

        fun fail() {
            if (fired) return
            fired = true
            try {
                callback()
            } catch (_: Throwable) {
                // the caller's fallback never throws into the main loop
            }
        }
    }

    private fun canDrawOverlays(): Boolean = try {
        Settings.canDrawOverlays(app)
    } catch (_: Throwable) {
        false
    }

    private fun launchOnMain(ringScreen: Intent, watch: Watch?) {
        try {
            removeOverlay() // never two
            val wm = app.getSystemService(WindowManager::class.java) ?: throw IllegalStateException("no WindowManager")
            val view = View(app)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            wm.addView(view, lp)
            overlay = view
            main.postDelayed(::removeOverlay, HARD_REMOVE_MS) // the leak guard, whatever else happens
            main.postDelayed({ startRingScreen(ringScreen, watch) }, OVERLAY_SETTLE_MS)
        } catch (e: Throwable) {
            removeOverlay()
            failed(e)
            watch?.path(RingPath.FAILED)
            watch?.fail()
        }
    }

    private fun startRingScreen(ringScreen: Intent, watch: Watch?) {
        try {
            app.startActivity(Intent(ringScreen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            note(RingScreenPolicy.DIRECT_LINE)
            if (watch != null) main.postDelayed({ if (!watch.windowUp()) { watch.path(RingPath.BANNER); watch.fail() } }, WINDOW_WATCHDOG_MS)
        } catch (e: Throwable) {
            failed(e) // SecurityException or a background-start block that throws; a door ring goes back on the loud channel
            watch?.path(RingPath.FAILED)
            watch?.fail()
        } finally {
            main.postDelayed(::removeOverlay, REMOVE_AFTER_START_MS)
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        try {
            app.getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
        } catch (e: Throwable) {
            log.log(HaLog.Level.WARN, "portico ring: overlay window removal failed (${e.javaClass.simpleName})")
        }
    }

    private fun failed(e: Throwable) = note(RingScreenPolicy.failedLine(e.javaClass.simpleName))

    private fun note(line: String) {
        log.log(HaLog.Level.INFO, "portico $line")
        try {
            journal("${PushJournalFormat.time(clock(), TimeZone.getDefault())} $line")
        } catch (_: Throwable) {
            // observation only
        }
    }

    companion object {
        const val OVERLAY_SETTLE_MS = 150L
        const val REMOVE_AFTER_START_MS = 1_500L
        const val HARD_REMOVE_MS = 5_000L

        /** After the start was issued: no window by now = the start was blocked silently; the ring goes back on the loud channel. */
        const val WINDOW_WATCHDOG_MS = 3_000L
    }
}
