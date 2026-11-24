package com.meda.portico.core.enroll

import com.meda.portico.core.auth.PendingCodec
import com.meda.portico.core.auth.PendingEnrollment

/**
 * The keys the app's encrypted settings store uses (`app` `store/Settings.kt`). Defined here so
 * the JVM tests can prove what a save writes, and what it never writes.
 */
object SettingsKeys {
    const val HA_URL = "ha_url"
    const val HA_TOKEN = "ha_token"

    /**
     * DEV-544: the refresh token a one-time-code enrolment ends with, and the client id it was issued with (both encrypted
     * like [HA_TOKEN]; never logged). A phone has [HA_TOKEN] (format-1 enrolment) or these (format 2), never both after a
     * redemption: the redemption's commit removes [HA_TOKEN].
     */
    const val REFRESH_TOKEN = "refresh_token"
    const val CLIENT_ID = "client_id"

    /** DEV-544: a scanned format-2 QR waiting to be redeemed (`:core` `PendingCodec`). The code and nonce are credentials. */
    const val PENDING_CODE = "pending_code"
    const val PENDING_NONCE = "pending_nonce"
    const val PENDING_ENROLL_ID = "pending_enroll_id"
    const val PENDING_HA_URL = "pending_ha_url"
    const val PENDING_SINCE = "pending_since"

    /** DEV-544: "1" after HA refused the refresh token (`invalid_grant`); the phone must be enrolled again. Ignored while a login is stored. */
    const val SIGNED_OUT = "signed_out"
    const val PHONE_NAME = "phone_name"
    const val FCM_PROJECT_ID = "fcm_project_id"
    const val FCM_SENDER_ID = "fcm_sender_id"
    const val FCM_APP_ID = "fcm_app_id"
    const val FCM_API_KEY = "fcm_api_key"
    const val RING_ON_THIS_PHONE = "ring_on_this_phone"
    const val INSTALLATION_ID = "installation_id"

    /** Offline registration: the enrollment's `enroll_id` (not secret, but never logged). */
    const val ENROLL_ID = "enroll_id"

    /** DEV-557: the enrollment's validated `ha_dial` (`host:port`), the tailnet path's target. */
    const val HA_DIAL = "ha_dial"

    /**
     * DEV-557: the enrollment's single-use `tailscale_authkey` while it waits to be redeemed, and
     * its save time (epoch ms). Both are deleted once the node is up or after ~55 minutes.
     */
    const val TAILNET_KEY = "tailscale_authkey"
    const val TAILNET_KEY_SAVED_AT = "tailscale_authkey_saved_at"

    /** DEV-807: the old local "Panel mode" switch (0.6.58 to 0.6.97). Gone since 0.6.98 (D-20261121-73ff); the key is only removed from a store that still has it. */
    const val LEGACY_PANEL_MODE = "panel_mode"

    /** P2 plan §7 Q2 (decided 2026-10-06): the local "ring for door calls on this phone" switch defaults ON. */
    const val RING_ON_THIS_PHONE_DEFAULT = true

    /** M7 3b-B: "Receive house calls in Portico" (HC-2): default OFF, so installing Portico never doubles a ring while the old app is live. */
    const val HOUSE_RECEIVE = "house_receive"
    const val HOUSE_RECEIVE_DEFAULT = false

    /** M7 3b-B: let Portico read Android's Do Not Disturb and tell the house (HC-4); default ON, with a visible switch. */
    const val HOUSE_SHARE_DND = "house_share_dnd"
    const val HOUSE_SHARE_DND_DEFAULT = true

    /** "Stay connected" (decided 2026-10-15, NS-3): a foreground service that holds the socket on Wi-Fi/Ethernet. Defaults ON. */
    const val STAY_CONNECTED = "stay_connected"
    const val STAY_CONNECTED_DEFAULT = true
}

sealed interface SetupResult {
    /** What to save. A null part means "leave that part of the stored settings alone". */
    data class Ok(val enrollment: EnrollmentPayload?, val firebase: FirebaseConfig?) : SetupResult {
        /**
         * The exact key/value writes for one atomic commit. Never the `tailscale_authkey`: that is
         * [tailnetWrites]. A payload with no `fcm` block leaves the Firebase keys untouched. See [removals].
         */
        fun writes(): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            enrollment?.let {
                out[SettingsKeys.HA_URL] = it.haUrl
                // DEV-544: a format-2 payload has no token. The phone's existing login (if any) is left alone until the
                // code has been redeemed; see [pendingWrites].
                it.haToken?.let { token -> out[SettingsKeys.HA_TOKEN] = token }
                out[SettingsKeys.PHONE_NAME] = it.phoneName
                it.enrollId?.let { id -> out[SettingsKeys.ENROLL_ID] = id }
            }
            firebase?.let {
                out[SettingsKeys.FCM_PROJECT_ID] = it.projectId
                out[SettingsKeys.FCM_SENDER_ID] = it.senderId
                out[SettingsKeys.FCM_APP_ID] = it.appId
                out[SettingsKeys.FCM_API_KEY] = it.apiKey
            }
            return out
        }

        /**
         * DEV-544: a format-2 payload's one-time code waits in the encrypted store, with the [nonce] the redemption will use
         * on every retry, until it has been redeemed. Empty for a format-1 payload and for a Firebase-only save.
         */
        fun pendingWrites(nonce: String, nowMs: Long): Map<String, String> {
            val e = enrollment ?: return emptyMap()
            val code = e.code ?: return emptyMap()
            return PendingCodec.writes(PendingEnrollment(code, nonce, e.enrollId.orEmpty(), e.haUrl, nowMs))
        }

        /** The save carries a one-time code (Setup then starts the redemption). */
        val redeemsCode: Boolean get() = enrollment?.code != null

        /**
         * Keys to remove in the same commit. The one removal: an enrollment without `enroll_id`
         * (an older QR) drops the stored one, so a new Save always replaces it and a string is
         * never built with the previous enrollment's id.
         */
        fun removals(): Set<String> {
            val out = LinkedHashSet<String>()
            if (enrollment != null && enrollment.enrollId == null) out.add(SettingsKeys.ENROLL_ID)
            // SEC-03: a format-1 payload brings its own long-lived token, and the login check prefers a stored refresh token. So the
            // previous format-2 login (refresh token, client id, sign-out mark, an unredeemed pending record) goes in the same commit;
            // left behind, it would override the new token and be sent to the new ha_url. A format-2 payload leaves them alone until
            // its code is redeemed.
            if (enrollment?.haToken != null) {
                out.add(SettingsKeys.REFRESH_TOKEN)
                out.add(SettingsKeys.CLIENT_ID)
                out.add(SettingsKeys.SIGNED_OUT)
                out.addAll(PendingCodec.keys)
            }
            return out
        }

        /**
         * DEV-557, the tailnet part of the enrollment, applied in the same commit as [writes]:
         * `ha_dial` when present, and the pending key with its save time [nowMs] when present.
         * Kept apart from [writes] so that one stays secret-free apart from the HA token.
         */
        fun tailnetWrites(nowMs: Long): Map<String, String> {
            val e = enrollment ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            e.haDial?.let { out[SettingsKeys.HA_DIAL] = it }
            e.tailscaleAuthKey?.let {
                out[SettingsKeys.TAILNET_KEY] = it
                out[SettingsKeys.TAILNET_KEY_SAVED_AT] = nowMs.toString()
            }
            return out
        }

        /**
         * A new enrollment replaces the previous one's tailnet part: no `ha_dial` drops a stored one,
         * and no key drops a previous enrollment's unredeemed key (a joined node is not affected).
         */
        fun tailnetRemovals(): Set<String> {
            val e = enrollment ?: return emptySet()
            val out = LinkedHashSet<String>()
            if (e.haDial == null) out.add(SettingsKeys.HA_DIAL)
            if (e.tailscaleAuthKey == null) {
                out.add(SettingsKeys.TAILNET_KEY)
                out.add(SettingsKeys.TAILNET_KEY_SAVED_AT)
            }
            return out
        }

        /** The save carries a key to redeem (Setup then starts the join). */
        val joinsTailnet: Boolean get() = enrollment?.tailscaleAuthKey != null
    }

    /** Human-readable problems, one per input that failed. None quotes the input. */
    data class Refused(val problems: List<String>) : SetupResult
}

/**
 * The Setup screen's Save, as a pure function of the two paste fields (P2 plan §3.1, §7 Q4).
 *
 * - Either field may be blank, not both. A blank enrollment field keeps the stored enrollment.
 * - Firebase values come from the pasted google-services.json, or else from the enrollment's
 *   `fcm` block. When both are given they must agree.
 * - All or nothing: if any part is refused, nothing is saved.
 */
object SetupPlan {
    fun evaluate(enrollmentText: String, googleServicesText: String): SetupResult {
        val problems = ArrayList<String>()
        var enrollment: EnrollmentPayload? = null
        var fromGoogleServices: FirebaseConfig? = null

        if (enrollmentText.isBlank() && googleServicesText.isBlank()) {
            return SetupResult.Refused(listOf("Paste the enrollment text, a google-services.json, or both."))
        }
        if (enrollmentText.isNotBlank()) {
            when (val r = EnrollmentPayload.parse(enrollmentText)) {
                is EnrollmentParseResult.Ok -> enrollment = r.payload
                is EnrollmentParseResult.Refused -> problems.add("Enrollment: ${r.message}")
            }
        }
        if (googleServicesText.isNotBlank()) {
            when (val r = GoogleServicesParser.extract(googleServicesText)) {
                is GoogleServicesResult.Ok -> fromGoogleServices = r.config
                is GoogleServicesResult.Error -> problems.add("Firebase: ${r.message}")
            }
        }
        val fromEnrollment = (enrollment?.fcm as? FcmBlock.Present)?.config
        if (fromEnrollment != null && fromGoogleServices != null && fromEnrollment != fromGoogleServices) {
            problems.add(
                "The enrollment's Firebase block and the pasted google-services.json differ. " +
                    "Paste only one of them.",
            )
        }
        if (problems.isNotEmpty()) return SetupResult.Refused(problems)
        return SetupResult.Ok(enrollment, fromGoogleServices ?: fromEnrollment)
    }
}
