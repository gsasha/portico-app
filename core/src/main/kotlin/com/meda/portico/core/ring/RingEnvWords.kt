package com.meda.portico.core.ring

/**
 * D-20261121-ee09 (logs report every relevant setting): the Journal words for what decides whether a ring is HEARD. Pure, so a JVM test pins them;
 * the Android half ([com.meda.portico.ring.RingEnvProbe]) reads the numbers and hands them here. Every word is a legal Journal value
 * (`a-z 0-9 _ -`, see `PushJournalFormat.diagLine`).
 */
object RingEnvWords {
    /** `AudioManager.getRingerMode()`: 0 silent, 1 vibrate, 2 normal. */
    fun ringer(mode: Int?): String = when (mode) { 0 -> "silent"; 1 -> "vibrate"; 2 -> "normal"; null -> "unknown"; else -> "other" }

    /** `NotificationManager.getCurrentInterruptionFilter()`: 1 all, 2 priority, 3 none, 4 alarms. */
    fun dnd(filter: Int?): String = when (filter) { 1 -> "off"; 2 -> "priority"; 3 -> "total"; 4 -> "alarms"; null -> "unknown"; else -> "other" }

    /** The channel's sound: no channel, "None" (null uri), the phone's default ringtone, or a ringtone the user chose. */
    fun sound(hasChannel: Boolean, soundIsNull: Boolean, isDefault: Boolean): String = when {
        !hasChannel -> "nochan"
        soundIsNull -> "none"
        isDefault -> "default"
        else -> "custom"
    }

    /** `AudioAttributes.getUsage()` of the channel's sound. */
    fun usage(u: Int?): String = when (u) {
        1 -> "media"; 2 -> "voicecomm"; 3 -> "signalling"; 4 -> "alarm"; 5 -> "notif"; 6 -> "notifring"; 7 -> "notifcomm"
        null -> "unknown"; else -> "other"
    }

    /** `ring-snd`'s `act`: what the call window's own alert did. */
    const val START = "start"
    const val STOP = "stop"

    /** A stop reason as a Journal word (a close reason such as "ring ended" becomes `ring-ended`). */
    fun word(s: String): String = s.lowercase().map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("").trim('-').ifEmpty { "none" }.take(32)
}
