package com.meda.portico.core.auth

import com.meda.portico.core.enroll.SettingsKeys

/**
 * A scanned format-2 QR waiting to be redeemed, kept in the ENCRYPTED settings store (the code is a credential for ten
 * minutes; the nonce must survive a process death, because a redemption whose answer was lost is retried with the SAME
 * nonce). [sinceMs] is wall-clock time of the scan. [toString] shows nothing.
 */
class PendingEnrollment(val code: String, val nonce: String, val enrollId: String, val haUrl: String, val sinceMs: Long) {
    override fun toString(): String = "PendingEnrollment(code=present, nonce=present, sinceMs=$sinceMs)"
}

/** How the pending record is spelled in the settings store (the keys are [SettingsKeys]). */
object PendingCodec {
    /** The record, or null when any part of it is missing or malformed (a half-written record is no record). */
    fun read(get: (String) -> String?): PendingEnrollment? {
        val code = get(SettingsKeys.PENDING_CODE)
        val nonce = get(SettingsKeys.PENDING_NONCE)
        val enrollId = get(SettingsKeys.PENDING_ENROLL_ID)
        val url = get(SettingsKeys.PENDING_HA_URL)
        val since = get(SettingsKeys.PENDING_SINCE)?.toLongOrNull()
        if (!EnrollContract.isValidCode(code) || !EnrollContract.isValidNonce(nonce)) return null
        if (enrollId.isNullOrBlank() || url.isNullOrBlank() || since == null) return null
        return PendingEnrollment(code!!, nonce!!, enrollId, url, since)
    }

    fun writes(p: PendingEnrollment): Map<String, String> = linkedMapOf(
        SettingsKeys.PENDING_CODE to p.code,
        SettingsKeys.PENDING_NONCE to p.nonce,
        SettingsKeys.PENDING_ENROLL_ID to p.enrollId,
        SettingsKeys.PENDING_HA_URL to p.haUrl,
        SettingsKeys.PENDING_SINCE to p.sinceMs.toString(),
    )

    val keys: Set<String> = setOf(
        SettingsKeys.PENDING_CODE, SettingsKeys.PENDING_NONCE, SettingsKeys.PENDING_ENROLL_ID,
        SettingsKeys.PENDING_HA_URL, SettingsKeys.PENDING_SINCE,
    )

    /**
     * What a successful redemption writes and removes in ONE commit: the refresh token and client id in; the pending record,
     * the old long-lived `ha_token` (a re-enrolment replaces it) and the signed-out marker out.
     */
    fun completionWrites(refreshToken: String, clientId: String): Map<String, String> = linkedMapOf(
        SettingsKeys.REFRESH_TOKEN to refreshToken,
        SettingsKeys.CLIENT_ID to clientId,
    )

    val completionRemovals: Set<String> = keys + SettingsKeys.HA_TOKEN + SettingsKeys.SIGNED_OUT

    /** HA said `invalid_grant`: the login is forgotten and the phone is marked signed out, in one commit. */
    val signOutWrites: Map<String, String> = mapOf(SettingsKeys.SIGNED_OUT to "1")
    val signOutRemovals: Set<String> = setOf(SettingsKeys.REFRESH_TOKEN, SettingsKeys.CLIENT_ID)
}

enum class CompleteResult { SAVED, SUPERSEDED, FAILED }

/** The pending record's home. The app implements it over the encrypted settings. */
interface PendingStore {
    fun load(): PendingEnrollment?

    /** Saves the grant in one commit, but only while the stored record still has [nonce] (a newer scan replaces it). */
    fun complete(nonce: String, refreshToken: String, clientId: String): CompleteResult

    /** Removes the record, but only while it still has [nonce]. */
    fun clear(nonce: String)
}

sealed interface EnrollStatus {
    /** Nothing waiting, nothing running. */
    data object Idle : EnrollStatus

    data object Redeeming : EnrollStatus

    /** The last attempt did not get an answer; the same request is sent again after a pause. */
    data class Retrying(val why: Failed) : EnrollStatus

    data object Done : EnrollStatus

    /** [Kind.INVALID_OR_EXPIRED], [Kind.RATE_LIMITED] and [Kind.CANNOT_SAVE] end the attempt for good; [Kind.UNREACHABLE]/[Kind.UNEXPECTED] only when the code has run out. */
    data class Failed(val kind: Kind, val httpStatus: Int? = null) : EnrollStatus {
        val message: String
            get() = when (kind) {
                Kind.INVALID_OR_EXPIRED -> EnrollContract.MSG_INVALID_OR_EXPIRED
                Kind.RATE_LIMITED -> EnrollContract.MSG_RATE_LIMITED
                Kind.UNREACHABLE -> EnrollContract.MSG_UNREACHABLE
                Kind.UNEXPECTED -> "Home Assistant answered unexpectedly (HTTP ${httpStatus ?: 0}). Ask for a new QR in Home Assistant and scan it again."
                Kind.CANNOT_SAVE -> "This phone could not save the sign-in. Scan the QR again."
            }
    }

    enum class Kind { INVALID_OR_EXPIRED, RATE_LIMITED, UNREACHABLE, UNEXPECTED, CANNOT_SAVE }
}

/**
 * Redeems the pending QR (steps 1 and 2 of the contract) on the caller's worker thread; [run] blocks until the
 * redemption is finished, refused for good, superseded by a newer scan, or too old to succeed.
 *
 * - The SAME request (same code, installation id and nonce) is sent on every retry, so a response lost on the way is answered
 *   with the same refresh token (within [EnrollContract.REPLAY_WINDOW_MS] of the redemption).
 * - It retries an unreachable HA or an odd answer with growing pauses until the record is [EnrollContract.PENDING_MAX_AGE_MS]
 *   old, then gives up and removes it (a code that old cannot succeed). 400 ends it at once; a 429 that outlasts the bounded retries ends it but keeps
 *   the record, so the next start of the app (or Setup opening) tries again while the code lives.
 * - Success commits the refresh token and client id and drops the record and the old long-lived token in one commit
 *   ([PendingStore.complete]), then seeds [tokens] with the access token the answer carried.
 * - The phone's old credentials are not touched by anything that fails.
 *
 * Logs nothing. Pass [sleep] a real sleep in the app and a fake clock in tests.
 */
class EnrollmentRedemption(
    private val store: PendingStore,
    private val client: RedeemClient,
    private val tokens: AccessTokens,
    private val installationId: () -> String?,
    private val wallClockMs: () -> Long,
    private val sleep: (Long) -> Unit,
    private val onStatus: (EnrollStatus) -> Unit = {},
    private val retryDelaysMs: List<Long> = listOf(2_000, 5_000, 10_000, 20_000, 30_000),
) {
    fun run(): EnrollStatus {
        val first = store.load() ?: return finish(EnrollStatus.Idle)
        val mine = first.nonce
        var attempt = 0
        var last: EnrollStatus.Failed? = null
        while (true) {
            val p = store.load()
            if (p == null || p.nonce != mine) return finish(EnrollStatus.Idle)
            val age = wallClockMs() - p.sinceMs
            if (age < 0 || age > EnrollContract.PENDING_MAX_AGE_MS) {
                store.clear(mine)
                return finish(last ?: EnrollStatus.Failed(EnrollStatus.Kind.INVALID_OR_EXPIRED))
            }
            val install = installationId()?.takeIf { EnrollContract.isValidInstallationId(it) }
            if (install == null) {
                last = EnrollStatus.Failed(EnrollStatus.Kind.CANNOT_SAVE)
            } else {
                onStatus(if (last == null) EnrollStatus.Redeeming else EnrollStatus.Retrying(last))
                val outcome = client.redeem(p.haUrl, RedeemRequest(p.code, p.enrollId, install, p.nonce)) { waitMs(it, mine) }
                when (outcome) {
                    is RedeemOutcome.Granted -> {
                        val g = outcome.grant
                        when (store.complete(mine, g.refreshToken, g.clientId)) {
                            CompleteResult.SAVED -> {
                                tokens.invalidate()
                                tokens.seed(g.accessToken, g.expiresInS)
                                return finish(EnrollStatus.Done)
                            }
                            CompleteResult.SUPERSEDED -> return finish(EnrollStatus.Idle)
                            CompleteResult.FAILED -> last = EnrollStatus.Failed(EnrollStatus.Kind.CANNOT_SAVE)
                        }
                    }
                    RedeemOutcome.InvalidOrExpired -> {
                        store.clear(mine)
                        return finish(EnrollStatus.Failed(EnrollStatus.Kind.INVALID_OR_EXPIRED))
                    }
                    is RedeemOutcome.RateLimited -> return finish(EnrollStatus.Failed(EnrollStatus.Kind.RATE_LIMITED))
                    RedeemOutcome.Cancelled -> return finish(EnrollStatus.Idle)
                    RedeemOutcome.Unreachable -> last = EnrollStatus.Failed(EnrollStatus.Kind.UNREACHABLE)
                    is RedeemOutcome.Unexpected -> {
                        last = EnrollStatus.Failed(EnrollStatus.Kind.UNEXPECTED, outcome.status)
                        // 415: the request was not application/json. That is this app's bug, not a moment to wait out.
                        if (outcome.status == 415) return finish(last)
                    }
                }
            }
            onStatus(EnrollStatus.Retrying(last!!))
            val delay = retryDelaysMs[attempt.coerceAtMost(retryDelaysMs.size - 1)]
            attempt++
            if (!waitMs(delay, mine)) return finish(EnrollStatus.Idle)
        }
    }

    private fun finish(s: EnrollStatus): EnrollStatus {
        onStatus(s)
        return s
    }

    /** Sleeps [ms] in short slices; false as soon as the record is no longer [nonce]'s (a newer scan, or it is gone). */
    private fun waitMs(ms: Long, nonce: String): Boolean {
        var left = ms
        while (left > 0) {
            val step = minOf(left, SLICE_MS)
            sleep(step)
            left -= step
            if (store.load()?.nonce != nonce) return false
        }
        return true
    }

    private companion object {
        const val SLICE_MS = 500L
    }
}
