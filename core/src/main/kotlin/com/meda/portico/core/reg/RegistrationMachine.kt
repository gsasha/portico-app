package com.meda.portico.core.reg

import com.meda.portico.core.ha.Clock
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.PushCommands
import com.meda.portico.core.ha.PushCommands.ErrorKind
import com.meda.portico.core.ha.PushCommands.Outcome

/**
 * The push ring notification registration state machine (P2 plan §3.2). Pure: events in, [RegEffect]s out,
 * [status] for the UI. No sockets, no Firebase, no real time ([clock] is injected). Single
 * threaded: the caller serialises calls.
 *
 * Rules:
 * - register on EVERY connect (spec §3.3, the DEV-484 lesson), and on rotation of the value;
 * - never a blank or placeholder value (refused before the wire);
 * - FID first; if none arrives [TOKEN_FALLBACK_MS] after `RequestFid`, or the request fails,
 *   fall back to `RequestToken` (kind `token`);
 * - at most one attempt per connect for a retryable failure (no tight loop), but a NEW value
 *   is always worth one send;
 * - terminal codes stop until their fix: `unauthorized` (re-enrol), `project_mismatch` (a new
 *   `fcm` block), `package_mismatch` (an app bug), a second `invalid_registration`;
 * - unregister on push-off (queued while offline) and on sign-out (sent only if connected:
 *   HA's `user_removed` cleanup covers the rest); `not_found` is success.
 */
class RegistrationMachine(
    config: RegistrationConfig,
    private val store: RegistrationStore,
    private val clock: Clock,
    private val log: HaLog = HaLog.NONE,
    pushEnabled: Boolean = true,
) {
    private var config: RegistrationConfig = config
    private var pushEnabled = pushEnabled
    private var signedOut = false
    private var connected = false

    /** The register on the wire, awaiting its result (one at a time). */
    private var inFlight: RegValue? = null

    /** A new value arrived while [inFlight]: send it after the result. */
    private var dirty = false

    /** The value HA accepted on THIS connection (a repeat of it is a no-op). */
    private var acceptedThisConnection: RegValue? = null
    private var serverWithoutPushThisConnection = false

    private var terminal: RegStatus.Refused? = null
    private var refusedProject: String? = null
    private var lastOutcome: RegStatus? = null
    private var invalidRetryUsed = false

    private var fidRequestedAt: Long? = null
    private var tokenRequested = false

    /** PR-E: both the FID request and the token fallback failed; retried once on the next connect. */
    private var sourceExhausted = false

    var status: RegStatus = RegStatus.WaitingForRegistration
        private set

    init {
        refreshStatus()
    }

    fun start(): List<RegEffect> = done(
        if (config.fcmProjectId == null || signedOut || fidRequestedAt != null) emptyList() else requestFid(),
    )

    fun onConnected(): List<RegEffect> {
        connected = true
        inFlight = null
        dirty = false
        acceptedThisConnection = null
        serverWithoutPushThisConnection = false
        if (signedOut || config.fcmProjectId == null) return done(emptyList())
        if (!pushEnabled) {
            return done(if (store.loadPendingUnregister()) listOf(RegEffect.SendUnregister(config.installationId)) else emptyList())
        }
        if (sourceExhausted && store.loadValue() == null) {
            // PR-E: register() and the fallback both failed (e.g. offline at start-up). Without
            // this the machine never asked Firebase again for the life of the process.
            sourceExhausted = false
            tokenRequested = false
        }
        return done(tryRegister())
    }

    fun onDisconnected(): List<RegEffect> {
        connected = false
        if (inFlight != null) lastOutcome = RegStatus.RetryNextConnect("connection lost")
        inFlight = null
        dirty = false
        acceptedThisConnection = null
        return done(emptyList())
    }

    fun onRegistrationValue(value: RegValue): List<RegEffect> {
        if (!PushCommands.isUsableRegistration(value.value)) {
            log.log(HaLog.Level.WARN, "refused a blank or placeholder ${value.kind.wire}")
            return done(emptyList())
        }
        if (signedOut || config.fcmProjectId == null) return done(emptyList())
        val current = store.loadValue()
        if (value.kind == RegKind.TOKEN && current?.kind == RegKind.FID && PushCommands.isUsableRegistration(current.value)) {
            log.log(HaLog.Level.DEBUG, "token ignored: an FID is in use")
            return done(emptyList())
        }
        fidRequestedAt = null
        sourceExhausted = false
        if (value == current) return done(emptyList())
        store.saveValue(value)
        if (terminal?.code == "invalid_registration") terminal = null // a fresh value lifts it
        if (lastOutcome !is RegStatus.Registered) lastOutcome = null
        return done(tryRegister())
    }

    fun onRegistrationSourceFailed(): List<RegEffect> {
        if (tokenRequested) {
            sourceExhausted = true
            return done(emptyList())
        }
        if (signedOut || config.fcmProjectId == null) return done(emptyList())
        return done(requestToken())
    }

    fun tick(): List<RegEffect> {
        val at = fidRequestedAt ?: return done(emptyList())
        if (tokenRequested || clock.nowMs() - at < TOKEN_FALLBACK_MS) return done(emptyList())
        log.log(HaLog.Level.INFO, "no FID ${TOKEN_FALLBACK_MS / 1000} s after register(): token fallback")
        return done(requestToken())
    }

    fun onRegisterResult(outcome: Outcome): List<RegEffect> {
        val sent = inFlight
        inFlight = null
        when (outcome) {
            is Outcome.Registered -> {
                lastOutcome = RegStatus.Registered(outcome.regId, outcome.pushEnabled)
                acceptedThisConnection = sent
                invalidRetryUsed = false
            }
            is Outcome.Failed -> when (outcome.kind) {
                ErrorKind.UNAUTHORIZED -> terminal = RegStatus.Refused(outcome.code, outcome.message, TEXT_NOT_ENROLLED)
                // Contract 3 refuses an app below 0.5.0. It cannot happen to a 0.5.0 build; if a server ever says it, the
                // only fix is an update, so it is terminal and says so (cleared with the process, i.e. by the update).
                ErrorKind.APP_TOO_OLD -> terminal = RegStatus.Refused(outcome.code, outcome.message, TEXT_APP_TOO_OLD)
                ErrorKind.PROJECT_MISMATCH -> {
                    terminal = RegStatus.Refused(outcome.code, outcome.message, outcome.message.ifBlank { TEXT_PROJECT })
                    refusedProject = config.fcmProjectId
                }
                ErrorKind.PACKAGE_MISMATCH -> {
                    log.log(HaLog.Level.ERROR, "package_mismatch: HA refuses package ${config.packageName} (an app bug)")
                    terminal = RegStatus.Refused(outcome.code, outcome.message, TEXT_PACKAGE)
                }
                ErrorKind.INVALID_REGISTRATION -> {
                    val current = store.loadValue()
                    if (current == null || current == sent) {
                        store.saveValue(null)
                        dirty = false
                        if (!invalidRetryUsed) {
                            invalidRetryUsed = true
                            lastOutcome = null
                            tokenRequested = false
                            return done(requestFid())
                        }
                        terminal = RegStatus.Refused(outcome.code, outcome.message, TEXT_BAD_REGISTRATION)
                    }
                }
                ErrorKind.PUSH_NOT_CONFIGURED, ErrorKind.UNKNOWN_COMMAND -> {
                    lastOutcome = RegStatus.ServerWithoutPush
                    serverWithoutPushThisConnection = true
                }
                else -> lastOutcome = RegStatus.RetryNextConnect(outcome.code)
            }
            Outcome.Lost -> lastOutcome = RegStatus.RetryNextConnect("connection lost")
            else -> lastOutcome = RegStatus.RetryNextConnect("unexpected result")
        }
        if (dirty) {
            dirty = false
            return done(tryRegister())
        }
        return done(emptyList())
    }

    fun onUnregisterResult(outcome: Outcome): List<RegEffect> {
        val finished = when (outcome) {
            is Outcome.Unregistered -> true // removed, or not_found: already gone
            is Outcome.Failed -> outcome.kind in NOTHING_TO_REMOVE
            else -> false
        }
        if (finished || signedOut) store.savePendingUnregister(false)
        return done(if (pushEnabled) tryRegister() else emptyList())
    }

    fun setPushEnabled(enabled: Boolean): List<RegEffect> {
        if (enabled == pushEnabled) return done(emptyList())
        pushEnabled = enabled
        acceptedThisConnection = null
        lastOutcome = null
        dirty = false
        if (enabled) {
            store.savePendingUnregister(false)
            return done(tryRegister())
        }
        if (signedOut) return done(emptyList())
        store.savePendingUnregister(true)
        return done(if (connected) listOf(RegEffect.SendUnregister(config.installationId)) else emptyList())
    }

    fun onConfigChanged(config: RegistrationConfig): List<RegEffect> {
        this.config = config
        val t = terminal
        if (t != null && (t.code != "project_mismatch" || config.fcmProjectId != refusedProject)) {
            terminal = null
            lastOutcome = null
            invalidRetryUsed = false
        }
        acceptedThisConnection = null
        if (config.fcmProjectId == null) return done(emptyList())
        return done(tryRegister())
    }

    fun signOut(): List<RegEffect> {
        if (signedOut) return done(emptyList())
        signedOut = true
        store.saveValue(null)
        store.savePendingUnregister(false)
        dirty = false
        inFlight = null
        return done(if (connected) listOf(RegEffect.SendUnregister(config.installationId)) else emptyList())
    }

    // ---- internals -------------------------------------------------------------------------

    private fun tryRegister(): List<RegEffect> {
        if (signedOut || config.fcmProjectId == null || !pushEnabled || terminal != null) return emptyList()
        if (!connected || serverWithoutPushThisConnection || store.loadPendingUnregister()) return emptyList()
        if (inFlight != null) {
            dirty = true
            return emptyList()
        }
        val value = store.loadValue()
        if (value == null) {
            return if (fidRequestedAt == null && !tokenRequested) requestFid() else emptyList()
        }
        if (!PushCommands.isUsableRegistration(value.value)) {
            log.log(HaLog.Level.WARN, "dropped a cached placeholder ${value.kind.wire}")
            store.saveValue(null)
            return requestFid()
        }
        if (value == acceptedThisConnection) return emptyList()
        inFlight = value
        return listOf(RegEffect.SendRegister(value, config))
    }

    private fun requestFid(): List<RegEffect> {
        fidRequestedAt = clock.nowMs()
        return listOf(RegEffect.RequestFid)
    }

    private fun requestToken(): List<RegEffect> {
        tokenRequested = true
        fidRequestedAt = null
        return listOf(RegEffect.RequestToken)
    }

    private fun done(effects: List<RegEffect>): List<RegEffect> {
        refreshStatus()
        return effects
    }

    private fun refreshStatus() {
        status = when {
            signedOut -> RegStatus.SignedOut
            config.fcmProjectId == null -> RegStatus.NoFirebaseConfig
            !pushEnabled -> RegStatus.PushOff
            terminal != null -> terminal!!
            inFlight != null -> RegStatus.Sent
            lastOutcome != null -> lastOutcome!!
            store.loadValue() == null -> RegStatus.WaitingForRegistration
            else -> RegStatus.Ready
        }
    }

    override fun toString(): String = "RegistrationMachine(status=$status, connected=$connected)"

    companion object {
        /** [I] plan §3.2's proposal: no `onRegistered` within 30 s of `register()` -> token. */
        const val TOKEN_FALLBACK_MS = 30_000L

        private val NOTHING_TO_REMOVE = setOf(ErrorKind.PUSH_NOT_CONFIGURED, ErrorKind.UNAUTHORIZED, ErrorKind.UNKNOWN_COMMAND)

        const val TEXT_NOT_ENROLLED = "This phone is not enrolled in Home Assistant (or its HA user was deleted). Re-enrol it in Settings."
        const val TEXT_APP_TOO_OLD = "This Home Assistant needs a newer Portico app. Update the app."
        const val TEXT_PROJECT = "This phone uses a different Firebase project than Home Assistant. Re-scan the enrollment QR."
        const val TEXT_PACKAGE = "Home Assistant does not accept this app build (package mismatch). This is an app bug."
        const val TEXT_BAD_REGISTRATION = "Home Assistant refused this phone's push registration twice. It is retried when Firebase gives a new one."
    }
}
