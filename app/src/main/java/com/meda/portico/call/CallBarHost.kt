package com.meda.portico.call

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.meda.portico.AppGraph
import com.meda.portico.home.HomeActivity
import com.meda.portico.ring.HouseRingActivity
import com.meda.portico.setup.SetupActivity

/**
 * APP-02: puts the [CallBarView] under every screen of the app that is not a call screen of its own, without touching those
 * screens. There is no base activity, so this is one `ActivityLifecycleCallbacks` registered by the Application: when such an
 * activity starts, its content (whatever `setContentView` made) is moved into a vertical box above the bar. The content keeps
 * every size it had (it gets the box's remaining height, weight 1, so a ScrollView still scrolls) and the bar takes its own
 * height from the bottom only while a call lasts, so a ring screen's Answer / Hang up stay where they were and stay reachable.
 *
 * Home is the exception to "bottom": while a call lasts its bar is drawn in the page, directly below the row of the call's own Talk
 * button ([HomeActivity.placeCallBar], `:core`'s CallBarPlacement), and the screen scrolls it fully into view; with no such button
 * on the shown page it stays here, at the bottom ([dock]).
 *
 * Hosts: Home, Settings, and the house-call ring screen (it carries its own route chooser, so its bar has none). APP-03: a call
 * from the station has no screen of its own; Home is where it is drawn, with this bar.
 * Not hosts: [InCallActivity] and [OutgoingCallActivity] (the call screens: they have Hang up, the duration and the chooser
 * themselves), the QR scanner and the launcher trampoline. The About box is a dialog, above the bar.
 */
class CallBarHost(private val graph: AppGraph) : Application.ActivityLifecycleCallbacks {
    private val bars = HashMap<Activity, CallBarView>()

    override fun onActivityStarted(activity: Activity) {
        val withRoute = when (activity) {
            is HomeActivity, is SetupActivity -> true
            is HouseRingActivity -> false
            else -> return
        }
        try {
            val bar = bars[activity]?.takeIf { it.parent != null && isStillHosted(activity, it) } ?: install(activity, withRoute)
            bars[activity] = bar
            bar.attach()
            (activity as? HomeActivity)?.placeCallBar() // Home puts the bar under the call's own Talk button, not at the bottom
        } catch (_: Exception) {
            // A bar that cannot be drawn must never take a screen down.
        }
    }

    override fun onActivityResumed(activity: Activity) {
        bars[activity]?.resume()
    }

    override fun onActivityPaused(activity: Activity) {
        bars[activity]?.pause()
    }

    override fun onActivityStopped(activity: Activity) {
        bars[activity]?.detach()
    }

    override fun onActivityDestroyed(activity: Activity) {
        bars.remove(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    /** The bar of [activity], if it has one (Home asks, to put it under the call's own button). */
    private fun barFor(activity: Activity): CallBarView? = bars[activity]

    /** Puts [activity]'s bar back where it lives by default: the bottom of the screen, under the page. */
    private fun dockBar(activity: Activity) {
        val bar = bars[activity] ?: return
        val box = content(activity).getChildAt(0) as? LinearLayout ?: return
        if (box.tag != TAG || bar.parent === box) return
        (bar.parent as? ViewGroup)?.removeView(bar)
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun content(activity: Activity): ViewGroup = activity.findViewById(android.R.id.content)

    /** A later `setContentView` replaces the whole content, our box included: then the bar must be installed again. */
    private fun isStillHosted(activity: Activity, bar: CallBarView): Boolean {
        val box = content(activity).getChildAt(0)
        // The bar is in the box (bottom) or, on Home, inside the page under the call's own button: either way below our box.
        return box is LinearLayout && box.tag == TAG && generateSequence(bar as View?) { it.parent as? View }.any { it === box }
    }

    private fun install(activity: Activity, withRoute: Boolean): CallBarView {
        val frame = content(activity)
        val child = frame.getChildAt(0)
        val bar = CallBarView(activity, graph, withRoute)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; tag = TAG }
        if (child != null) {
            val lp = child.layoutParams
            frame.removeView(child)
            box.addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).also {
                if (lp is ViewGroup.MarginLayoutParams) it.setMargins(lp.leftMargin, lp.topMargin, lp.rightMargin, lp.bottomMargin)
            })
        }
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return bar
    }

    init {
        current = this
    }

    companion object {
        private const val TAG = "portico_call_bar_host"
        private var current: CallBarHost? = null

        /** The bar of [activity], if it has one (Home asks, to put it under the call's own button). */
        fun barOf(activity: Activity): CallBarView? = current?.barFor(activity)

        /** Puts [activity]'s bar back where it lives by default: the bottom of the screen, under the page. */
        fun dock(activity: Activity) {
            current?.dockBar(activity)
        }
    }
}
