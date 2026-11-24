package com.meda.portico.core.call

/**
 * What the person chose with the route chooser (APP-02). AUTO is the initial choice, when nothing was chosen (the old app's
 * default, DEV-422): the proximity sensor decides. BLUETOOTH is offered only while a Bluetooth audio device is connected.
 */
enum class RouteChoice { AUTO, EARPIECE, SPEAKER, BLUETOOTH }

/** Where the call's sound comes out. */
enum class RouteDevice { HEADSET, EARPIECE, SPEAKER }

/**
 * A02 / A-06 / APP-02: the route decision and the volume arithmetic, with no Android in it.
 *
 * Rules:
 *  - SPEAKER and EARPIECE are literal, even with a headset connected (the person asked; the choice applies mid-call);
 *    a device with no earpiece (a tablet) has only the speaker, so EARPIECE is the speaker there;
 *  - BLUETOOTH is the headset while one is connected; if it has gone, the choice falls back to AUTO;
 *  - Panel mode (DEV-807): always the SPEAKER; a board codec may report a headset that is not there, and no one holds a panel to an ear;
 *  - AUTO: a connected headset (wired or Bluetooth) wins, as in the old app (`Route.java`: nobody plugs one in to hear the
 *    loudspeaker); then against the ear is the earpiece, away from it is the speaker, and before the sensor has said anything
 *    (`near == null`, or no sensor) it is the SPEAKER: a caller who cannot hear reports a broken call, a moment of speaker is
 *    only startling.
 */
object AudioRoutePlan {
    fun decide(choice: RouteChoice, headsetConnected: Boolean, hasEarpiece: Boolean, near: Boolean?, panelMode: Boolean = false): RouteDevice = when {
        panelMode -> RouteDevice.SPEAKER // DEV-807 section 10: a wall panel's call is always on its loudspeaker, whatever the board reports
        choice == RouteChoice.SPEAKER -> RouteDevice.SPEAKER
        choice == RouteChoice.EARPIECE -> if (hasEarpiece) RouteDevice.EARPIECE else RouteDevice.SPEAKER
        headsetConnected -> RouteDevice.HEADSET // AUTO, or BLUETOOTH while one is connected
        !hasEarpiece -> RouteDevice.SPEAKER
        near == true -> RouteDevice.EARPIECE
        else -> RouteDevice.SPEAKER
    }

/** Proximity is "near" when the reading is below the sensor's own maximum range and below 5 cm (many sensors report only 0 and max). */
    fun isNear(distanceCm: Float, maxRangeCm: Float): Boolean = distanceCm < maxRangeCm && distanceCm < 5f

    /** Keeps a stream level inside `[min, max]`; a broken range (max < min) collapses to `min`. */
    fun clampVolume(level: Int, min: Int, max: Int): Int = if (max < min) min else level.coerceIn(min, max)

    /** One press of the - / + key. */
    fun stepVolume(level: Int, delta: Int, min: Int, max: Int): Int = clampVolume(level + delta, min, max)
}
