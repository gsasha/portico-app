package com.meda.portico.call

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.meda.portico.PorticoApp
import com.meda.portico.core.call.AudioRoutePlan
import com.meda.portico.core.call.RouteChoice
import com.meda.portico.core.call.RouteDevice
import com.meda.portico.core.callbar.CallRoute
import com.meda.portico.core.callbar.CallRoutes

/**
 * A-06: the thin Android side of [AudioRoutePlan]. One per process. [AndroidAudioRoute] calls [callStarted] / [callEnded]; the
 * ring and in-call screens and the call bar call [choose] and [volumeStep], and read [routes] / [lit] / [volume].
 *
 * The choice is kept in SharedPreferences, as the old app did ("audio_route"), and is effective mid-call. The proximity sensor
 * is listened to only while a call is live. Every platform call is wrapped: a routing failure must leave audio where the
 * platform puts it, never take a call down.
 */
class CallAudioControl private constructor(private val app: Context) {
    private val am: AudioManager? = app.getSystemService(AudioManager::class.java)
    private val prefs = app.getSharedPreferences("audio", Context.MODE_PRIVATE)
    private val sensors = app.getSystemService(SensorManager::class.java)
    private val hasEarpiece = app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
        (am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } == true)

    @Volatile private var live = 0
    @Volatile private var near: Boolean? = null
    private var listener: SensorEventListener? = null
    private var deviceCallback: android.media.AudioDeviceCallback? = null
    private var listeners = mutableListOf<() -> Unit>()

    fun choice(): RouteChoice = try {
        RouteChoice.valueOf(prefs.getString("route", null) ?: RouteChoice.AUTO.name)
    } catch (_: Exception) {
        RouteChoice.AUTO
    }

    /** What the key says now: the chosen word, and for AUTO where the sound is going. */
    fun device(): RouteDevice = AudioRoutePlan.decide(choice(), headsetConnected(), hasEarpiece, near, panelMode = panel())

    /** DEV-807: Panel mode forces the speaker and ignores proximity (design section 10). */
    private fun panel(): Boolean = try { PorticoApp.graph(app).panelMode() } catch (_: Exception) { false }

    /** APP-02: the chooser's tap. Stored, and applied at once when a call is live (the choice applies mid-call). */
    fun choose(choice: RouteChoice) {
        prefs.edit().putString("route", choice.name).apply()
        apply()
        try { PorticoApp.graph(app).journalConfig("route") } catch (_: Exception) { }
    }

    /** The words the chooser offers now; read from the platform's device list each time (a headset can come and go mid-call). */
    fun routes(): List<CallRoute> = if (panel()) CallRoutes.available(false, false) else CallRoutes.available(hasEarpiece, bluetoothConnected())

    /** Which word is lit: where the sound goes now. */
    fun lit(): CallRoute? = CallRoutes.lit(device(), bluetoothConnected())

    fun volume(): Int = try { am?.getStreamVolume(AudioManager.STREAM_VOICE_CALL) ?: 0 } catch (_: Exception) { 0 }
    fun maxVolume(): Int = try { am?.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) ?: 0 } catch (_: Exception) { 0 }
    private fun minVolume(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am?.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL) ?: 1 else 1
    } catch (_: Exception) { 1 }

    /** One press of the - / + key (or a hardware rocker). Returns the new level. */
    fun volumeStep(delta: Int): Int {
        val m = am ?: return 0
        return try {
            val to = AudioRoutePlan.stepVolume(volume(), delta, minVolume(), maxVolume())
            m.setStreamVolume(AudioManager.STREAM_VOICE_CALL, to, 0)
            to
        } catch (_: Exception) { // a do-not-disturb policy can refuse a volume change
            volume()
        }
    }

    fun onChange(l: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners.add(l) }
        return { synchronized(listeners) { listeners.remove(l) } }
    }

    @Synchronized
    fun callStarted() {
        live++
        if (live == 1) {
            if (!panel()) watchProximity() // a wall panel is never held to an ear
            watchDevices()
            try { PorticoApp.graph(app).panelCallStarted() } catch (_: Exception) { }
        }
        apply()
    }

    @Synchronized
    fun callEnded() {
        if (live == 0) return
        live--
        if (live == 0) {
            try { PorticoApp.graph(app).panelCallEnded() } catch (_: Exception) { }
            listener?.let { try { sensors?.unregisterListener(it) } catch (_: Exception) {} }
            listener = null
            near = null
            deviceCallback?.let { try { am?.unregisterAudioDeviceCallback(it) } catch (_: Exception) {} }
            deviceCallback = null
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am?.clearCommunicationDevice()
                @Suppress("DEPRECATION") am?.stopBluetoothSco()
            } catch (_: Exception) {}
        }
    }

    /** A headset coming or going mid-call re-plans the route and redraws the chooser. Registered only while a call is live. */
    private fun watchDevices() {
        val m = am ?: return
        val cb = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = apply()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = apply()
        }
        try {
            m.registerAudioDeviceCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
            deviceCallback = cb
        } catch (_: Exception) {}
    }

    private fun watchProximity() {
        val s = try { sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY) } catch (_: Exception) { null } ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val n = AudioRoutePlan.isNear(e.values[0], s.maximumRange)
                if (n != near) {
                    near = n
                    apply()
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        try {
            if (sensors?.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL) == true) listener = l
        } catch (_: Exception) {}
    }

    /** A Bluetooth audio device that can carry a call (SCO or LE audio) is connected. */
    fun bluetoothConnected(): Boolean = try {
        am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        } == true
    } catch (_: Exception) { false }

    private fun headsetConnected(): Boolean = try {
        am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        } == true
    } catch (_: Exception) { false }

    /** Puts the live call on the planned device. Unverified on hardware (no emulator): see the PR. */
    @Synchronized
    fun apply() {
        val m = am
        if (m != null && live > 0) {
            try {
                val want = device()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val types = when (want) {
                        RouteDevice.SPEAKER -> listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                        RouteDevice.EARPIECE -> listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
                        RouteDevice.HEADSET -> listOf(
                            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
                        )
                    }
                    val dev = m.availableCommunicationDevices.firstOrNull { it.type in types }
                    if (dev != null) m.setCommunicationDevice(dev) else m.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    m.isSpeakerphoneOn = want == RouteDevice.SPEAKER
                    @Suppress("DEPRECATION")
                    if (want == RouteDevice.HEADSET) m.startBluetoothSco() else m.stopBluetoothSco()
                }
            } catch (_: Exception) {
                // audio stays where the platform put it
            }
        }
        val snapshot = synchronized(listeners) { listeners.toList() }
        for (l in snapshot) try { l() } catch (_: Exception) {}
    }

    companion object {
        @Volatile private var inst: CallAudioControl? = null
        fun get(context: Context): CallAudioControl =
            inst ?: synchronized(this) { inst ?: CallAudioControl(context.applicationContext).also { inst = it } }
    }
}
