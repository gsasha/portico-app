package com.meda.portico.core.ring

import com.meda.portico.core.enroll.SettingsSnapshot

/** The plain "label: value" facts of the Status screen (0.6.70: what the old "This phone" section listed, minus the ring configuration). */
enum class FactKey(val group: StatusGroup) {
    ADDRESS(StatusGroup.HOME_ASSISTANT),
    TAILNET_DIAL(StatusGroup.HOME_ASSISTANT),
    SIGN_IN(StatusGroup.HOME_ASSISTANT),
    PHONE_NAME(StatusGroup.HOME_ASSISTANT),
    ENROLMENT(StatusGroup.HOME_ASSISTANT),
    VERSION(StatusGroup.HOME_ASSISTANT),
    FIREBASE(StatusGroup.PUSH),
}

/** How this phone proves who it is to Home Assistant (the [FactKey.SIGN_IN] value). */
enum class SignIn { TOKEN, RENEWS_ITSELF }

/**
 * One fact. [value] is data (an address, a name, a version) except for [FactKey.SIGN_IN] (a [SignIn] name) and
 * [FactKey.ENROLMENT] (always "finishing"), which the app turns into words.
 */
data class StatusFact(val key: FactKey, val value: String)

object StatusFacts {
    /**
     * The facts of [snap] (null while the Settings are still being read: only the version shows) and the app's [version] text.
     * Only what is set is listed: a missing fact is the check lines' business (not enrolled, signed out), not a row of "none".
     * The installation id and the local ring switch are not facts here (an id is plumbing; the switch is ring configuration).
     */
    fun of(snap: SettingsSnapshot?, version: String): List<StatusFact> = buildList {
        if (snap != null) {
            snap.haHost?.let { add(StatusFact(FactKey.ADDRESS, it)) }
            snap.haDial?.let { add(StatusFact(FactKey.TAILNET_DIAL, it)) }
            when {
                snap.hasToken -> add(StatusFact(FactKey.SIGN_IN, SignIn.TOKEN.name))
                snap.hasRefreshToken -> add(StatusFact(FactKey.SIGN_IN, SignIn.RENEWS_ITSELF.name))
            }
            snap.phoneName?.ifBlank { null }?.let { add(StatusFact(FactKey.PHONE_NAME, it)) }
            if (snap.pendingRedemption) add(StatusFact(FactKey.ENROLMENT, "finishing"))
            snap.firebase?.let { add(StatusFact(FactKey.FIREBASE, it.projectId)) }
        }
        add(StatusFact(FactKey.VERSION, version))
    }
}
