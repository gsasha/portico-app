package com.meda.portico.panel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.meda.portico.AppGraph
import com.meda.portico.R
import com.meda.portico.core.panel.FrontAction
import com.meda.portico.core.panel.FrontReason
import com.meda.portico.core.panel.PanelLayout
import com.meda.portico.core.panel.TouchGuard
import com.meda.portico.home.HomeActivity
import com.meda.portico.ring.HouseRingActivity
import com.meda.portico.ring.RingCallActivity
import com.meda.portico.setup.SetupActivity

/**
 * DEV-807 slice 2 (design section 7): everything that makes Panel mode leave the app again, for every screen of ours, without a base activity
 * (the same pattern as [com.meda.portico.call.CallBarHost]). Nothing here acts outside Panel mode: [AppGraph.panelMode] gates every step and the
 * overlay is not even built while it is off.
 *
 * - Counts the screens that are started: "in front" is "at least one of our screens is started" (a hand-off between two of our screens starts the
 *   new one before it stops the old).
 * - Every [TICK_MS] feeds [AppGraph.panelFrontTick] (core [com.meda.portico.core.panel.PanelFrontMachine]) and does what it says: draws the
 *   progress bar, or returns to Fully.
 * - Puts one transparent overlay over each screen's content: a touch spy (a DOWN anywhere restarts the count and is passed on untouched), the
 *   bar, and on Home and Settings a large fixed Close control (not Android's back). A ring window's spy swallows the first 700 ms of touches
 *   ([TouchGuard]): a wall dashboard is touched all day.
 * - The return: starts Fully by its package's launch intent, then `moveTaskToBack(true)` (a ring window, which has a task of its own, is finished
 *   instead). The machine judges it by our own lifecycle: still in front 3 s later = `wall return-failed`, retried once.
 *
 * Whether Fully keeps its page when started by its launch intent, and whether Android lets the start through at all, are Bench B questions.
 */
class PanelFrontHost(private val graph: AppGraph) : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val started = LinkedHashSet<Activity>()
    private val chromes = HashMap<Activity, Chrome>()
    private var ticking = false
    private var issued: FrontReason? = null

    init {
        // D-20261121-73ff: the server stopped calling this device a panel while a screen is open: it lets go of keep-screen-on now, not at its next resume
        graph.onPanelModeOff = { main.post { started.toList().forEach { PanelScreen.keepOn(it); PanelScreen.systemBars(it) } } }
    }

    private val tick = object : Runnable {
        override fun run() {
            ticking = false
            try {
                onTick()
            } catch (_: Throwable) {
                // the return rule must never take a screen down
            }
            if (started.isNotEmpty()) schedule()
        }
    }

    private fun schedule() {
        if (ticking) return
        ticking = true
        main.postDelayed(tick, TICK_MS)
    }

    override fun onActivityStarted(activity: Activity) {
        started.add(activity)
        graph.panelSetFrontCount(started.size)
        try {
            if (graph.panelMode()) install(activity)
        } catch (_: Throwable) {
        }
        schedule()
    }

    override fun onActivityStopped(activity: Activity) {
        started.remove(activity)
        graph.panelSetFrontCount(started.size)
        chromes.remove(activity)?.detach()
        if (started.isEmpty()) {
            main.removeCallbacks(tick)
            ticking = false
            try {
                graph.panelFrontTick() // the machine learns it is no longer in front
            } catch (_: Throwable) {
            }
            issued?.let { graph.panelReturnLine(it, "ok") } // out of front after a return was issued: it took
            issued = null
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        started.remove(activity)
        chromes.remove(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {
        PanelScreen.systemBars(activity) // 0.6.99: Panel mode is immersive; a dialog or another window may have brought the bars back
    }
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    private fun onTick() {
        if (!graph.panelMode()) {
            chromes.values.forEach { it.hideBar() }
            graph.panelFrontTick()
            return
        }
        for (a in started.toList()) install(a) // Panel mode may have been switched on while a screen was open
        when (val action = graph.panelFrontTick()) {
            FrontAction.None -> Unit
            FrontAction.HideBar -> chromes.values.forEach { it.hideBar() }
            is FrontAction.ShowBar -> chromes.values.forEach { it.showBar(action.remainingMs, action.totalMs) }
            is FrontAction.ReturnNow -> returnToFully(action.attempt, action.reason)
            is FrontAction.ReturnFailed -> {
                graph.panelReturnLine(issued ?: graph.panelFront.reason, "failed")
                if (action.willRetry) returnToFully(2, issued ?: graph.panelFront.reason)
            }
        }
    }

    private fun install(activity: Activity) {
        if (chromes.containsKey(activity)) return
        PanelScreen.keepOn(activity) // the server made this a panel while the screen was open (otherwise set at its resume)
        PanelScreen.systemBars(activity)
        val frame = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val guard = activity is RingCallActivity || activity is HouseRingActivity
        val withClose = activity is HomeActivity || activity is SetupActivity
        val chrome = Chrome(activity, withClose = withClose, guarded = guard, graph = graph)
        if (withClose) chrome.reserveBottom(frame.getChildAt(0))
        frame.addView(chrome.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        chromes[activity] = chrome
    }

    /** Starts Fully, then takes our screens out of the way. Main thread. */
    private fun returnToFully(attempt: Int, reason: FrontReason) {
        val front = started.lastOrNull() ?: return
        issued = reason
        graph.panelReturnLine(reason, if (attempt == 1) "issued" else "retry")
        try {
            graph.homeLiveGrace.endNow() // the live tiles go at once; the 30 s grace would hold the streams while nobody looks
        } catch (_: Throwable) {
        }
        val launch = try { front.packageManager.getLaunchIntentForPackage(FULLY_PACKAGE) } catch (_: Throwable) { null }
        if (launch != null) {
            try {
                front.startActivity(launch)
            } catch (_: Throwable) {
                // Android refused the start: the screens below still go back, and the machine reports the failure if the app stays in front
            }
        }
        for (a in started.toList()) {
            try {
                if (a is RingCallActivity || a is HouseRingActivity) a.finishAndRemoveTask() else a.moveTaskToBack(true)
            } catch (_: Throwable) {
            }
        }
    }

    /** The overlay of one screen. */
    private class Chrome(
        private val activity: Activity,
        withClose: Boolean,
        guarded: Boolean,
        private val graph: AppGraph,
    ) {
        val root = FrameLayout(activity).apply {
            // 0.6.99: above the viewport's fullscreen overlay (elevation 100), so the countdown and Close are never hidden by a camera view
            elevation = CHROME_ELEVATION
        }
        private val withStrip = withClose
        private var padded: View? = null
        private var paddedFrom = 0
        private val barBox = LinearLayout(activity)
        private val barText = TextView(activity)
        private val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        private val shownAt = SystemClock.elapsedRealtime()
        private val guard = if (guarded) TouchGuard(shownAt) else null

        init {
            val d = activity.resources.displayMetrics.density
            fun dp(n: Int) = (n * d + 0.5f).toInt()
            root.isClickable = false
            barBox.apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(16), dp(10), dp(16), dp(14))
                background = GradientDrawable().apply { setColor(0xE6111827.toInt()) }
            }
            barText.apply { setTextColor(Color.WHITE); textSize = 20f }
            bar.apply { max = BAR_MAX }
            barBox.addView(barText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            barBox.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14)).apply { topMargin = dp(8) })
            if (!withClose) root.addView(barBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            if (withClose) {
                // 0.6.99 (D-20261122-b45c): one bottom strip, never over the header: the countdown on the left, Close on the right. The screen's
                // content and the fullscreen overlay stop above it, so nothing (menu, camera buttons) is covered.
                val strip = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    setBackgroundColor(0xFF111827.toInt())
                    setPadding(dp(16), 0, dp(16), 0)
                    isClickable = true // the strip is solid: it takes the touch, nothing under it
                }
                barBox.background = null
                barBox.setPadding(0, dp(4), dp(16), dp(4))
                barText.textSize = 16f
                strip.addView(barBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val close = Button(activity).apply {
                    setText(R.string.panel_close)
                    textSize = 22f
                    minHeight = dp(56)
                    minimumHeight = dp(56)
                    minWidth = dp(140)
                    setOnClickListener { graph.panelClosePressed() }
                    contentDescription = activity.getString(R.string.panel_close_description)
                }
                strip.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                root.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PanelLayout.STRIP_DP), Gravity.BOTTOM))
            }
            // The spy sits on top and is added last. A DOWN restarts the count and is passed on (returns false) unless the guard swallows it.
            val spy = View(activity)
            spy.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    graph.panelTouched()
                    guard != null && !guard.accepts(SystemClock.elapsedRealtime())
                } else {
                    guard != null && !guard.accepts(SystemClock.elapsedRealtime())
                }
            }
            root.addView(spy, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        /** Gives the screen's own content (its first child of the content frame) room for the strip, so the strip covers nothing. Undone at [detach]. */
        fun reserveBottom(content: View?) {
            if (!withStrip || content == null) return
            padded = content
            paddedFrom = content.paddingBottom
            val d = activity.resources.displayMetrics.density
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, paddedFrom + (PanelLayout.STRIP_DP * d + 0.5f).toInt())
        }

        fun showBar(remainingMs: Long, totalMs: Long) {
            val secs = ((remainingMs + 999) / 1000).toInt()
            barText.text = activity.getString(R.string.panel_return_bar, secs)
            bar.progress = (remainingMs * BAR_MAX / totalMs.coerceAtLeast(1)).toInt().coerceIn(0, BAR_MAX)
            barBox.visibility = View.VISIBLE
        }

        fun hideBar() {
            if (barBox.visibility != View.GONE) barBox.visibility = View.GONE
        }

        fun detach() {
            try {
                padded?.let { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, paddedFrom) }
                padded = null
            } catch (_: Throwable) {
            }
            try {
                (root.parent as? ViewGroup)?.removeView(root)
            } catch (_: Throwable) {
            }
        }

        private companion object {
            const val BAR_MAX = 1000
            const val CHROME_ELEVATION = 1000f
        }
    }

    companion object {
        /** Fully's package: a build constant (design section 7; read from the live foreground sensor). The manifest's `<queries>` names it. */
        const val FULLY_PACKAGE = "de.ozerov.fully"
        const val TICK_MS = 250L
    }
}
