package com.meda.portico.call

import android.app.Activity
import android.media.AudioManager
import android.widget.Button
import android.widget.TextView
import com.meda.portico.R

/** Wires `view_audio_controls` on one screen to [CallAudioControl]. Call [bind] in onCreate, [attach]/[detach] in onStart/onStop. */
class AudioControlsView(private val activity: Activity) {
    private val control = CallAudioControl.get(activity)
    private var off: (() -> Unit)? = null

    /** [rocker]: the in-call screen sends the hardware rocker to the voice-call stream; a ring screen must not (the ring plays on its own stream). */
    fun bind(rocker: Boolean) {
        if (rocker) activity.volumeControlStream = AudioManager.STREAM_VOICE_CALL
        activity.findViewById<RouteChooserView>(R.id.audio_route).bind(control)
        activity.findViewById<Button>(R.id.audio_vol_down).setOnClickListener { control.volumeStep(-1); render() }
        activity.findViewById<Button>(R.id.audio_vol_up).setOnClickListener { control.volumeStep(1); render() }
        render()
    }

    fun attach() {
        off = control.onChange { activity.runOnUiThread { render() } }
        render()
    }

    fun detach() {
        off?.invoke()
        off = null
    }

    fun render() {
        activity.findViewById<RouteChooserView>(R.id.audio_route).refresh()
        activity.findViewById<TextView>(R.id.audio_vol_label).text =
            activity.getString(R.string.audio_volume_label, control.volume(), control.maxVolume())
    }
}
