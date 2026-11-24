package com.meda.portico.core.reg

import com.meda.portico.core.ha.Mask
import com.meda.portico.core.ha.PushCommands

enum class RegKind(val wire: String) { FID("fid"), TOKEN("token") }

/** An FID or a token. [toString] shows only `sha256[:8]` (P2 plan §3.7). */
class RegValue(val kind: RegKind, val value: String) {
    override fun equals(other: Any?): Boolean = other is RegValue && other.kind == kind && other.value == value
    override fun hashCode(): Int = kind.hashCode() * 31 + value.hashCode()
    override fun toString(): String = "RegValue(${kind.wire}, ${Mask.short(value)})"
}

/**
 * What the registration needs from Setup (PR-C's Settings). [fcmProjectId] null = no `fcm` block
 * enrolled, so the app never sends `register` (state [RegStatus.NoFirebaseConfig]).
 */
data class RegistrationConfig(
    val installationId: String,
    val fcmProjectId: String?,
    val appVersion: String,
    val packageName: String = PushCommands.PACKAGE,
)

/**
 * The machine's persisted bits: the last FID/token (so a reconnect can register before Firebase
 * answers, plan §3.2) and a pending unregister (push switched off while the socket was down).
 * The Android layer implements it on prefs; PR-E wires it.
 */
interface RegistrationStore {
    fun loadValue(): RegValue?
    fun saveValue(value: RegValue?)
    fun loadPendingUnregister(): Boolean
    fun savePendingUnregister(pending: Boolean)
}

class InMemoryRegistrationStore(var value: RegValue? = null, var pendingUnregister: Boolean = false) : RegistrationStore {
    override fun loadValue(): RegValue? = value
    override fun saveValue(value: RegValue?) { this.value = value }
    override fun loadPendingUnregister(): Boolean = pendingUnregister
    override fun savePendingUnregister(pending: Boolean) { pendingUnregister = pending }
}

/** What the machine asks the outside world to do. */
sealed interface RegEffect {
    /** Send `portico/push/register` with this body. */
    class SendRegister(val value: RegValue, val config: RegistrationConfig) : RegEffect {
        override fun toString(): String = "SendRegister($value)"
    }

    data class SendUnregister(val installationId: String) : RegEffect

    /** Call `FirebaseMessaging.register()`; the FID comes back through `onRegistrationValue`. */
    data object RequestFid : RegEffect

    /** The fallback: `getToken()`, sent as kind `token`. */
    data object RequestToken : RegEffect
}

/** What Setup's status line shows (plan §3.2 table). */
sealed interface RegStatus {
    data object NoFirebaseConfig : RegStatus
    data object WaitingForRegistration : RegStatus

    /** A value is in hand; waiting for a connection. */
    data object Ready : RegStatus
    data object Sent : RegStatus
    data class Registered(val regId: String, val pushEnabled: Boolean) : RegStatus

    /** Terminal until the matching fix (re-enrol, a new fcm block, an app update). */
    data class Refused(val code: String, val message: String, val userText: String) : RegStatus

    /** `push_not_configured`/`unknown_command`: no banner, a one-line status; retried next connect. */
    data object ServerWithoutPush : RegStatus

    /** A transient failure (`invalid_format`, transport, unknown code): retried on the next connect. */
    data class RetryNextConnect(val reason: String) : RegStatus
    data object PushOff : RegStatus
    data object SignedOut : RegStatus
}
