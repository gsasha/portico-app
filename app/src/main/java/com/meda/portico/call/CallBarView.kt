package com.meda.portico.call

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.meda.portico.AppGraph
import com.meda.portico.R
import android.content.Intent
import com.meda.portico.core.call.CallUi
import com.meda.portico.core.callbar.CallBarState
import com.meda.portico.core.callbar.CallBars
import com.meda.portico.core.callbar.CallPhase
import com.meda.portico.home.HomeActivity

/**
 * APP-02: the call bar, the bottom row of the app while a call lasts: [route chooser] over [red End button | "Calling <name>"
 * and the running duration]. Hosted by [CallBarHost] under every screen that is not itself a call screen. It draws
 * `:core`'s [CallBarState] and does nothing else (APP-03: while a station call RINGS the red button is Decline and a green Answer
 * sits beside it, the words say who is calling, and there is no duration or route chooser yet; once answered it is this very bar): the state is the three coordinators' own, so it cannot disagree with the
 * call. Gone (not merely invisible) when no call is dialling or connected, so it takes no room then.
 *
 * Between [attach] and [detach] (the host's onStart / onStop) it listens to the coordinators; between [resume] and [pause]
 * a once-a-second tick moves the duration. Nothing runs while the screen is off or the app is in the background.
 */
class CallBarView(context: Context, private val graph: AppGraph, private val showRoute: Boolean) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val chooser = RouteChooserView(context)
    private val endButton = ImageButton(context)
    private val answerButton = ImageButton(context)
    private val title = TextView(context)
    private val duration = TextView(context)

    /** DEV-807 (Panel mode): the large "Microphone is open" banner with its own Hang up, above the row, for the whole call. Gone off Panel mode. */
    private val micBanner = LinearLayout(context)
    private val micText = TextView(context)
    private val micHangup = android.widget.Button(context)
    private val control = CallAudioControl.get(context)
    private var removers = emptyList<() -> Unit>()
    private var state = CallBarState.IDLE
    private var ticking = false

    /** The host screen is resumed (between [resume] and [pause]): the tick may run. Kept apart from [ticking] so a re-parenting cannot lose it. */
    private var resumed = false

    private val tick = object : Runnable {
        override fun run() {
            renderDuration()
            if (ticking) handler.postDelayed(this, 1_000)
        }
    }

    init {
        orientation = VERTICAL
        visibility = GONE
        val pad = dp(12)
        setPadding(pad, dp(8), pad, dp(8))
        // The bar is its own surface: a theme-neutral dark slate that reads over a light and a dark page, with white text.
        background = GradientDrawable().apply { setColor(0xFF1F2937.toInt()) }
        contentDescription = context.getString(R.string.callbar_description)
        if (showRoute) {
            chooser.bind(control)
            addView(chooser, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        micBanner.apply {
            orientation = VERTICAL
            visibility = GONE
            setBackgroundColor(context.getColor(R.color.panel_mic_banner_bg))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        micText.apply { setTextColor(context.getColor(R.color.panel_mic_banner_text)); textSize = 28f; setTypeface(typeface, android.graphics.Typeface.BOLD); setText(R.string.panel_mic_open) }
        micHangup.apply { setText(R.string.call_hangup); minHeight = dp(72); setOnClickListener { CallActions.hangUpAll(graph) } }
        micBanner.addView(micText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        micBanner.addView(micHangup, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(micBanner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        endButton.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = dp(14)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(END_RED) }
            ResourcesCompat.getDrawable(resources, R.drawable.ic_mdi_phone_hangup, context.theme)?.mutate()?.let {
                it.setTint(Color.WHITE)
                setImageDrawable(it)
            }
            contentDescription = context.getString(R.string.callbar_end)
            setOnClickListener { endOrDecline() }
        }
        row.addView(endButton, LayoutParams(dp(56), dp(56)))
        answerButton.apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = dp(14)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ANSWER_GREEN) }
            ResourcesCompat.getDrawable(resources, R.drawable.ic_mdi_phone, context.theme)?.mutate()?.let {
                it.setTint(Color.WHITE)
                setImageDrawable(it)
            }
            contentDescription = context.getString(R.string.callbar_answer)
            visibility = GONE
            setOnClickListener { answer() }
        }
        row.addView(answerButton, LayoutParams(dp(56), dp(56)).apply { marginStart = dp(12) })
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        title.apply { setTextColor(Color.WHITE); textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        duration.apply { setTextColor(0xFFD1D5DB.toInt()); textSize = 16f }
        texts.addView(title)
        texts.addView(duration)
        row.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + 0.5f).toInt()

    fun attach() {
        removers = listOf(
            graph.talk.addListener { post { refresh() } },
            graph.place.addListener { post { refresh() } },
            graph.answer.addListener { post { refresh() } },
            control.onChange { post { chooser.refresh() } },
            graph.ring.addChangeListener { post { refresh() } },
        )
        refresh()
    }

    fun detach() {
        removers.forEach { it() }
        removers = emptyList()
        pause()
    }

    fun resume() {
        resumed = true
        refresh()
        startTick()
    }

    fun pause() {
        resumed = false
        stopTick()
    }

    private fun stopTick() {
        ticking = false
        handler.removeCallbacks(tick)
    }

    private fun startTick() {
        if (ticking || !state.isActive) return
        ticking = true
        handler.post(tick)
    }

    /** Re-reads the three coordinators (any thread is posted to the main one first) and redraws. */
    fun refresh() {
        val next = CallBars.pick(
            CallBars.fromAnswer(graph.answer.state(), CallUi.doorName(graph.answer.state(), graph.doors)),
            CallBars.fromPlace(graph.place.state()),
            CallBars.fromTalk(graph.talk.state()),
            CallBars.fromRing(graph.stationCall()),
        )
        state = next
        visibility = if (next.isShown) VISIBLE else GONE
        val ringing = next.phase == CallPhase.RINGING
        renderMicBanner(next.isActive && !ringing)
        // APP-03: a ringing call has Decline (the red button) and Answer (green, only where it can be answered), no route chooser yet.
        answerButton.visibility = if (ringing && next.ring?.canAnswer == true) VISIBLE else GONE
        endButton.contentDescription = context.getString(if (ringing) R.string.callbar_decline else R.string.callbar_end)
        if (ringing) chooser.visibility = GONE // an active call's refresh() below decides it again
        if (ringing) {
            title.text = next.name?.let { context.getString(R.string.callbar_incoming, it) } ?: context.getString(R.string.callbar_incoming_nameless)
            duration.text = context.getString(if (next.ring?.canAnswer == true) R.string.callbar_incoming_hint else R.string.callbar_incoming_ring_only)
            stopTick()
        } else if (next.isActive) {
            title.text = next.name?.let { context.getString(R.string.callbar_calling, it) } ?: context.getString(R.string.callbar_calling_nameless)
            chooser.refresh()
            renderDuration()
            if (isAttachedToWindow) startTick()
        } else {
            stopTick()
        }
    }

    /** DEV-807: the banner shows for the whole call in Panel mode (core [com.meda.portico.core.panel.MicBanner]), saying when the microphone is muted. */
    private fun renderMicBanner(callLive: Boolean) {
        val show = try { com.meda.portico.core.panel.MicBanner.visible(graph.panelMode(), callLive) } catch (_: Exception) { false }
        micBanner.visibility = if (show) VISIBLE else GONE
        if (!show) return
        val muted = try { graph.answer.state().snapshot.muted || graph.place.state().snapshot.muted } catch (_: Exception) { false }
        micText.setText(if (muted) R.string.panel_mic_muted else R.string.panel_mic_open)
    }

    /** The red button: ends a live call; while a station call rings it is Decline (stops the ring on this phone only, like the notification's). */
    private fun endOrDecline() {
        val ring = state.ring
        if (state.phase == CallPhase.RINGING && ring != null) graph.ring.stopRing(ring.doorRef, ring.callId, "declined from the call bar") else CallActions.hangUpAll(graph)
    }

    /**
     * The green button. The answer is started by the main screen (it asks for the microphone, which only an activity can), so this
     * brings that screen forward with the notification's own Answer action: from Home it is the same press, from Settings it also
     * goes back to Home.
     */
    private fun answer() {
        val ring = state.ring ?: return
        context.startActivity(
            Intent(context, HomeActivity::class.java)
                .setAction(HomeActivity.ACTION_ANSWER)
                .putExtra(HomeActivity.EXTRA_DOOR, ring.doorRef)
                .putExtra(HomeActivity.EXTRA_CALL, ring.callId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun renderDuration() {
        duration.text = state.duration(System.currentTimeMillis())
    }

    /**
     * 0.6.49 (the clock stuck at 00:00): Home moves this very view (placeCallBar removes it from the bottom box and adds it to the page's
     * button flow; a page rebuild detaches it too). Detaching used to call pause(), which killed the tick, and nothing started it again
     * on re-attach, so the duration drew once ("00:00") and never moved. Now detaching only stops the tick, and attaching restarts it
     * while the screen is resumed and a call is active.
     */
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (resumed) {
            renderDuration()
            startTick()
        }
    }

    override fun onDetachedFromWindow() {
        stopTick()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val END_RED = 0xFFDC2626.toInt()
        const val ANSWER_GREEN = 0xFF16A34A.toInt()
    }
}

/** What a button of the bar or the notification does to a call. One place, so they cannot differ. */
object CallActions {
    /** Ends whichever call is live; the other two coordinators are idle and ignore it. */
    fun hangUpAll(graph: AppGraph) {
        graph.answer.hangUp()
        graph.place.hangUp()
        graph.talk.hangUp()
    }
}
