package com.meda.portico.core.ring

/**
 * 0.6.74 (experimental): which path puts the ring screen in front of the user. Pure, so a JVM test pins it.
 *
 * Android shows a notification's full-screen intent only when the device is locked or the screen is off; while the phone is in use it
 * shows a heads-up banner instead. An app holding "Display over other apps" (SYSTEM_ALERT_WINDOW) may start an activity from the
 * background, so on an UNLOCKED, interactive phone the ring screen can be opened directly, in addition to the notification.
 *
 * Locked or screen off is never [Decision.DIRECT_OVERLAY]: a background start while locked is DEFERRED to the unlock and would also
 * pre-empt the full-screen intent that is allowed to take the screen (memory android-lock-screen-call-checklist, 1).
 */
object RingScreenPolicy {
    enum class Decision { DIRECT_OVERLAY, FULL_SCREEN_INTENT_ONLY }

    /** TYPE_APPLICATION_OVERLAY exists from Android 8 (API 26). */
    const val MIN_SDK = 26

    fun decide(canDrawOverlays: Boolean, interactive: Boolean, keyguardLocked: Boolean, sdk: Int): Decision =
        if (canDrawOverlays && interactive && !keyguardLocked && sdk >= MIN_SDK) Decision.DIRECT_OVERLAY else Decision.FULL_SCREEN_INTENT_ONLY

    /** The journal line of a decision that was NOT direct: says why, so the owner can read the outcome. */
    fun notificationLine(canDrawOverlays: Boolean, interactive: Boolean, keyguardLocked: Boolean, sdk: Int): String {
        val why = when {
            sdk < MIN_SDK -> "old Android"
            !canDrawOverlays -> "no overlay permission"
            keyguardLocked -> "locked"
            !interactive -> "screen off"
            else -> "not direct"
        }
        return "ring screen: notification ($why)"
    }

    /** 0.6.76: the ring notification went to the quiet channel because the call window rings (no heads-up banner over the window). */
    const val QUIET_LINE = "ring screen: banner suppressed (quiet channel)"

    /** 0.6.76: the direct launch failed or the window never came up, so the ring is back on the normal (loud) channel. */
    const val BANNER_RESTORED_LINE = "ring screen: banner restored (window did not open)"

    const val DIRECT_LINE = "ring screen: direct (overlay)"

    /** [reason] is an exception class name only, never a message. */
    fun failedLine(reason: String): String = "ring screen: direct failed ${reason.ifBlank { "Exception" }}"
}
