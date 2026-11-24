package com.meda.portico.core.ring

/**
 * the owner 2026-11-21 (D-20261121-2918): a call answered from the lock screen must connect, with audio, without the unlock; and that is a
 * Setup control ("Answer without unlocking"). Pure, so a JVM test pins it.
 *
 * What the setting changes is only whether Answer waits for the unlock. It never lets anything open a door: the caller's buttons keep
 * [DoorUnlockRule] (a door, a gate or a lock press asks for the unlock, locked or not, setting or not).
 *
 * - [Route.HOME]: the phone is unlocked, nothing special: the main screen takes the call (the call bar), as before.
 * - [Route.IN_CALL_OVER_LOCK]: locked and the setting is on: the answer is pressed from the ring window that is still on screen, and the
 *   in-call screen (declared show-when-locked in the manifest, so it needs no late window flag) takes the screen. The main screen is not opened.
 * - [Route.UNLOCK_FIRST]: locked and the setting is off: the answer waits for the person's unlock (the system's keyguard prompt), then is
 *   [Route.HOME]; a cancelled prompt leaves the ring as it was.
 */
object AnswerLockPolicy {
    enum class Route(val word: String) { HOME("home"), IN_CALL_OVER_LOCK("incall"), UNLOCK_FIRST("unlock") }

    /** Default of the Setup control: on (taking a door call from a locked phone is what the person expects of a phone). */
    const val DEFAULT_ANSWER_WITHOUT_UNLOCK = true

    fun route(keyguardLocked: Boolean, answerWithoutUnlock: Boolean): Route = when {
        !keyguardLocked -> Route.HOME
        answerWithoutUnlock -> Route.IN_CALL_OVER_LOCK
        else -> Route.UNLOCK_FIRST
    }

    /** What the answer waited for, as a Journal word: `none`, or `unlock` (it waited for the person's unlock). */
    fun waitedWord(route: Route): String = if (route == Route.UNLOCK_FIRST) "unlock" else "none"
}
