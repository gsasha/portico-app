package com.meda.portico.core.ring

/** What a person pressed on the ring screen. */
enum class RingPress {
    /** Answer (screen button or notification action), Stop / Decline: joining or ending a call. */
    CALL_HANDLING,

    /** Any of the caller's custom `ring_buttons` (open a door, a lock, the gate, run a script...). */
    DOOR_ACTION,
}

/**
 * A-02 (old app FR-19, the owner 2026-09-06): the ring screen shows over the lock screen, and taking the call needs no
 * unlock, but any other activity, opening a door first of all, does. Pure so a JVM test pins it.
 */
object DoorUnlockRule {
    fun needsUnlock(keyguardLocked: Boolean, press: RingPress): Boolean =
        keyguardLocked && press == RingPress.DOOR_ACTION
}
