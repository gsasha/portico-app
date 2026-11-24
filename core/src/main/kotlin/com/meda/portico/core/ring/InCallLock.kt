package com.meda.portico.core.ring

/**
 * the owner 2026-11-21 (D-20261121-b207 and the follow-up decision): the in-call screen shown over the lock screen after an answer from a
 * locked phone. Pure decisions, so a JVM test pins them; the Android half is `InCallUnlock` and `DoorBiometric` in the app.
 *
 * - An always-visible "Unlock" control while the keyguard is locked ([unlockControlVisible]); it asks the system for the unlock
 *   (face, fingerprint or PIN), and on success the full app takes over the same call ([afterUnlock]).
 * - "Unlock when answering" (Setup, default on): the same request made once, by itself, when the in-call screen is up on a locked
 *   answer ([autoRequest]). Never repeated for the same call, never blocking: the call does not wait for it.
 * - A door, gate or lock button on this screen, pressed while the phone is locked, needs a real confirmation: an app-level biometric
 *   (or device credential) prompt that does NOT unlock the phone ([doorPress]). Unlocked, the press is what it always was.
 */
object InCallLock {
    /** Default of the Setup control "Unlock when answering": on (face recognised unlocks; a fingerprint prompt unlocks on touch). */
    const val DEFAULT_UNLOCK_WHEN_ANSWERING = true

    /** How long an unlock request may stay unanswered before the Journal says `timeout` (the request itself is left alone, and the call never waited). */
    const val UNLOCK_TIMEOUT_MS = 30_000L

    /** Where an unlock request came from, as a Journal word. */
    enum class Source(val word: String) { AUTO("auto"), BUTTON("button") }

    /** How a request ended, as a Journal word. */
    enum class UnlockResult(val word: String) { OK("ok"), CANCEL("cancel"), ERROR("error"), TIMEOUT("timeout") }

    fun unlockControlVisible(keyguardLocked: Boolean): Boolean = keyguardLocked

    /**
     * Whether to make the one automatic request now: the phone is locked, the setting is on, there is a call, and this call has not had one
     * (screen recreation, a second resume, a new intent never repeat it; a new call may).
     */
    fun autoRequest(keyguardLocked: Boolean, setting: Boolean, callId: String?, lastAutoCallId: String?): Boolean =
        keyguardLocked && setting && callId != null && callId != lastAutoCallId

    /** What the screen does when the system answered the request. Only a success changes screen; anything else leaves the in-call screen as it was. */
    enum class AfterUnlock { OPEN_FULL_APP, STAY }

    fun afterUnlock(r: UnlockResult): AfterUnlock = if (r == UnlockResult.OK) AfterUnlock.OPEN_FULL_APP else AfterUnlock.STAY

    /** What a press of a door, gate or lock button does on this screen. */
    enum class DoorPress(val word: String) { RUN("run"), NEEDS_BIOMETRIC("bio") }

    fun doorPress(keyguardLocked: Boolean): DoorPress =
        if (DoorUnlockRule.needsUnlock(keyguardLocked, RingPress.DOOR_ACTION)) DoorPress.NEEDS_BIOMETRIC else DoorPress.RUN

    /** What the prompt may accept, in the app's terms (the Android half maps these to `BiometricManager.Authenticators`). */
    enum class BioAuth { STRONG_OR_DEVICE_CREDENTIAL, STRONG_ONLY }

    /** Strong biometric or the device PIN/pattern together is allowed from API 30; before it only a strong biometric (the library's own rule). */
    fun bioAuth(sdkInt: Int): BioAuth = if (sdkInt >= 30) BioAuth.STRONG_OR_DEVICE_CREDENTIAL else BioAuth.STRONG_ONLY

    /** How a prompt ended, as a Journal word. */
    enum class BioResult(val word: String) { OK("ok"), CANCEL("cancel"), ERROR("error"), UNAVAILABLE("unavailable") }

    /**
     * What happens after the prompt: only a success runs the door action. [BioResult.UNAVAILABLE] (no secure lock or biometric on this phone, so the
     * prompt cannot even be shown) falls back to the system's keyguard request, the old rule; a cancel or an error does nothing.
     */
    enum class AfterBio { RUN_ACTION, ASK_KEYGUARD, NOTHING }

    fun afterBio(r: BioResult): AfterBio = when (r) {
        BioResult.OK -> AfterBio.RUN_ACTION
        BioResult.UNAVAILABLE -> AfterBio.ASK_KEYGUARD
        BioResult.CANCEL, BioResult.ERROR -> AfterBio.NOTHING
    }
}

/**
 * The door name the in-call screen shows. The doors list (`portico/doors`) names a door by its integration entry ("Portico (gate_sip_server)"),
 * which is the driver, not the door. The name a person knows is the title of the document's block that holds the door ("Gate"), so that comes
 * first; then the doors-list name; then nothing (the screen shows its generic word).
 */
object CallDoorTitle {
    enum class Source(val word: String) { BLOCK("block"), DOOR("door"), NONE("none") }

    data class Title(val text: String?, val source: Source)

    fun choose(blockTitle: String?, doorsListName: String?): Title {
        val b = com.meda.portico.core.ha.Label.clean(blockTitle, com.meda.portico.core.ha.Label.MAX)
        if (b != null) return Title(b, Source.BLOCK)
        val d = com.meda.portico.core.ha.Label.clean(doorsListName, com.meda.portico.core.ha.Label.MAX)
        if (d != null) return Title(d, Source.DOOR)
        return Title(null, Source.NONE)
    }

    /** The title of the first block, on any page, whose `door` is [doorId]; null when none says one. */
    fun blockTitleFor(doorId: String?, blocks: List<com.meda.portico.core.ha.ContentBlock>): String? =
        if (doorId == null) null else blocks.firstOrNull { it.door == doorId && !it.title.isNullOrBlank() }?.title
}
