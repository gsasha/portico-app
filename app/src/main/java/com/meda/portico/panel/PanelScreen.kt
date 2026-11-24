package com.meda.portico.panel

import android.app.Activity
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.meda.portico.PorticoApp
import com.meda.portico.core.panel.KeepScreenOn
import com.meda.portico.core.panel.PanelLayout

/** DEV-807: the window of every screen of ours holds the screen on while it is in front in Panel mode (design section 7), and only then. */
object PanelScreen {
    fun keepOn(activity: Activity) {
        val on = try { KeepScreenOn.wanted(PorticoApp.graph(activity).panelMode(), inFront = true) } catch (_: Exception) { false }
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Whether this screen is in Panel mode (false on any failure: a phone's layout is the safe one). */
    fun on(activity: Activity): Boolean = try { PorticoApp.graph(activity).panelMode() } catch (_: Exception) { false }

    /**
     * 0.6.99 (D-20261122-b45c): in Panel mode the status and navigation bars are hidden and a swipe from the edge shows them for a moment
     * (immersive, transient). Outside Panel mode they are shown, unless [fullscreenVideo] (the viewport overlay) wants them gone.
     */
    fun systemBars(activity: Activity, fullscreenVideo: Boolean = false) {
        try {
            val c = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            if (PanelLayout.systemBarsHidden(on(activity), fullscreenVideo)) {
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                c.show(WindowInsetsCompat.Type.systemBars())
            }
        } catch (_: Exception) {
        }
    }

    /** The bottom strip (px) that Home's fullscreen overlay leaves free for the Close control and the countdown. */
    fun stripPx(activity: Activity): Int = (PanelLayout.stripDp(on(activity)) * activity.resources.displayMetrics.density + 0.5f).toInt()
}
