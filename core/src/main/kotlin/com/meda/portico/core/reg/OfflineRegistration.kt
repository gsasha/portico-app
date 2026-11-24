package com.meda.portico.core.reg

import com.meda.portico.core.enroll.RegistrationString
import com.meda.portico.core.ha.Mask
import com.meda.portico.core.ring.LinkState

/** What Setup shows about offline registration (owner decision 2026-10-07). */
sealed interface OfflineOffer {
    /** The normal path: nothing extra. */
    data object Hidden : OfflineOffer

    /** Cannot reach HA, a value is in hand, but the enrollment QR had no `enroll_id` (an older HA). */
    data object ScanAgain : OfflineOffer

    /** Cannot reach HA: offer [text] (a [RegistrationString]) to copy. [toString] never prints it. */
    class Copy(val text: String) : OfflineOffer {
        override fun equals(other: Any?): Boolean = other is Copy && other.text == text
        override fun hashCode(): Int = text.hashCode()
        override fun toString(): String = "Copy(${Mask.short(text)})"
    }
}

/**
 * The state the decision reads, gathered by Setup each refresh.
 *
 * @param configured Settings hold an HA URL and a token.
 * @param link the socket's state ([LinkState] mirrors `SignalingClient.State`).
 * @param notConnectedForMs how long the socket has not been CONNECTED (authenticated), from [NotConnectedTimer].
 * @param regStatus the registration machine's last status.
 * @param value the stored FID/token for the RUNNING Firebase project (`RegistrationStore.loadValue()`).
 * @param enrollId the stored `enroll_id`, null for an older QR.
 */
class OfflineInputs(
    val configured: Boolean,
    val link: LinkState,
    val notConnectedForMs: Long,
    val regStatus: RegStatus?,
    val value: RegValue?,
    val enrollId: String?,
    val config: RegistrationConfig,
) {
    override fun toString(): String =
        "OfflineInputs(configured=$configured, link=$link, notConnectedForMs=$notConnectedForMs, reg=$regStatus, " +
            "value=$value, enroll_id=${if (enrollId.isNullOrEmpty()) "absent" else "present"})"
}

/**
 * Pure: should Setup's "this phone cannot reach Home Assistant" block show, and with what.
 *
 * Shown only when ALL hold: Settings are configured; the socket is CONNECTING or WAITING_TO_RETRY
 * (trying and failing to reach HA) and has not been CONNECTED for [AFTER_MS]; a registration value
 * is available for the running Firebase project. Then: an `enroll_id` is stored -> [OfflineOffer.Copy],
 * else -> [OfflineOffer.ScanAgain].
 *
 * Hidden also when: AUTH_REJECTED (HA answered and refused the token), URL_REFUSED (the app's own
 * URL policy, its PROBLEM line already says so), STOPPED; the machine already got `Registered`
 * this run (HA has the value); push is off locally, signed out, or no Firebase project; a string
 * that cannot be built (never a crash).
 */
object OfflineRegistration {
    /** "Not connected for ~15 s": long enough for a normal connect + auth to finish on a slow network. */
    const val AFTER_MS = 15_000L

    private val TRYING = setOf(LinkState.CONNECTING, LinkState.WAITING_TO_RETRY)

    fun decide(i: OfflineInputs): OfflineOffer {
        if (!i.configured || i.link !in TRYING || i.notConnectedForMs < AFTER_MS) return OfflineOffer.Hidden
        when (i.regStatus) {
            is RegStatus.Registered, RegStatus.PushOff, RegStatus.SignedOut, RegStatus.NoFirebaseConfig -> return OfflineOffer.Hidden
            else -> Unit
        }
        val value = i.value ?: return OfflineOffer.Hidden
        val project = i.config.fcmProjectId ?: return OfflineOffer.Hidden
        val enrollId = i.enrollId?.takeIf { it.isNotEmpty() } ?: return OfflineOffer.ScanAgain
        return try {
            OfflineOffer.Copy(
                RegistrationString.build(enrollId, i.config.installationId, value.kind, value.value, i.config.packageName, project, i.config.appVersion),
            )
        } catch (_: IllegalArgumentException) {
            OfflineOffer.Hidden
        }
    }
}

/**
 * How long the socket has gone without being CONNECTED. Counts from creation (process start)
 * until the first connect, and from each drop; CONNECTING <-> WAITING_TO_RETRY changes inside one
 * outage do not restart it. Times are monotonic milliseconds from the caller's clock.
 */
class NotConnectedTimer(nowMs: Long) {
    @Volatile
    private var since: Long? = nowMs

    fun update(connected: Boolean, nowMs: Long) {
        if (connected) {
            since = null
        } else if (since == null) {
            since = nowMs
        }
    }

    fun notConnectedForMs(nowMs: Long): Long {
        val s = since ?: return 0
        return (nowMs - s).coerceAtLeast(0)
    }
}
