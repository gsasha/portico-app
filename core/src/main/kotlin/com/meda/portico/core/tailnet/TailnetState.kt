package com.meda.portico.core.tailnet

/** Why the tailnet client is not up (DEV-557). Each maps to one Setup line. */
enum class FailReason {
    /** The control server refused the key: spent, expired, revoked or wrong. A new QR is needed. */
    KEY_REJECTED,

    /** The node wants an interactive login: the stored identity is gone (deleted, expired). A new QR is needed. */
    NEEDS_LOGIN,

    /** The node waited for an admin's approval (a user-owned node, DEV-556 `approve` mode) and timed out. */
    NEEDS_APPROVAL,

    /** The control plane did not answer in time: no internet, or something blocks it. Retried on the next connect. */
    TIMEOUT,

    /** The native library did not load (or the Go side was not prepared). */
    LIBRARY,

    /** Anything else from the Go side, including a contained panic. */
    INTERNAL,

    /** Up, but there is no address to dial (no usable ha_url / ha_dial). */
    NO_TARGET,
}

/**
 * The tailnet client as Setup and the path choice see it. Never holds the key.
 *
 * Transitions (TailnetController): [Idle] -join(key)-> [Joining] -> [Up] | [Failed];
 * a key that ages out unredeemed -> [KeyExpired]; [Up] -sleep (the direct URL works)-> [Idle].
 */
sealed interface TailnetState {
    /** The APK was built without `gocore.aar`: nothing to join with. */
    data object NotBuilt : TailnetState

    /** Not running. [hasIdentity]: this phone joined before and can come back without a key. */
    data class Idle(val hasIdentity: Boolean, val keyPending: Boolean = false) : TailnetState

    data class Joining(val needsApproval: Boolean = false) : TailnetState

    data class Up(val address: String) : TailnetState

    data class Failed(val reason: FailReason, val detail: String = "") : TailnetState

    /** The QR's single-use key aged out (~55 min) before a join succeeded. Scan a new QR. */
    data object KeyExpired : TailnetState
}

/** The Go side's raw status, as `gocore/tailnet.Status()` prints it. */
sealed interface GoStatus {
    data object Idle : GoStatus
    data class Joining(val needsApproval: Boolean) : GoStatus
    data class Up(val address: String) : GoStatus
    data class Failed(val reason: FailReason, val detail: String) : GoStatus

    companion object {
        /**
         * Parses `idle` | `joining[\tneeds_approval]` | `up\t<ip>` | `failed\t<kind>\t<msg>`.
         * Anything else (an older or newer library) is [Failed] with [FailReason.INTERNAL]; never throws.
         */
        fun parse(line: String?): GoStatus {
            val f = (line ?: "").trim().split('\t')
            return when (f[0]) {
                "idle" -> Idle
                "joining" -> Joining(f.getOrNull(1) == "needs_approval")
                "up" -> Up(f.getOrNull(1).orEmpty())
                "failed" -> Failed(reasonOf(f.getOrNull(1)), f.drop(2).joinToString(" ").take(300))
                else -> Failed(FailReason.INTERNAL, "unreadable status from the tailnet library")
            }
        }

        private fun reasonOf(kind: String?): FailReason = when (kind) {
            "key_rejected" -> FailReason.KEY_REJECTED
            "needs_login" -> FailReason.NEEDS_LOGIN
            "needs_approval" -> FailReason.NEEDS_APPROVAL
            "timeout" -> FailReason.TIMEOUT
            "not_prepared" -> FailReason.LIBRARY
            else -> FailReason.INTERNAL // "panic", "error", unknown
        }
    }
}
