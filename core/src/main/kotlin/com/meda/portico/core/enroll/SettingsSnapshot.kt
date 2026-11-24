package com.meda.portico.core.enroll

import com.meda.portico.core.auth.PendingCodec
import com.meda.portico.core.net.UrlCheck
import com.meda.portico.core.net.UrlPolicy
import com.meda.portico.core.tailnet.TailnetTarget

/**
 * What Setup shows about the stored settings: the HA host (never the URL's path or the token),
 * whether a token is stored, the phone name, the Firebase project, the local ring switch, and
 * whether the installation id exists. Holds no secret, so its [toString] is safe.
 */
class SettingsSnapshot private constructor(
    val haHost: String?,
    /** PR-J: [UrlPolicy]'s verdict on the stored `ha_url`, null when none is stored. */
    val haUrlCheck: UrlCheck?,
    val hasToken: Boolean,
    val phoneName: String?,
    val firebase: FirebaseConfig?,
    /** Some Firebase keys are stored but they do not make a valid config. */
    val firebaseIncomplete: Boolean,
    val ringOnThisPhone: Boolean,
    val hasInstallationId: Boolean,
    /** DEV-557: the stored `ha_dial` (an IP and port, not secret), null when the QR had none. */
    val haDial: String? = null,
    /** DEV-544: a refresh token (one-time-code enrolment) is stored. */
    val hasRefreshToken: Boolean = false,
    /** DEV-544: a scanned one-time code is waiting to be redeemed. */
    val pendingRedemption: Boolean = false,
    /** DEV-544: HA refused the refresh token and the phone has no login left. */
    val signedOut: Boolean = false,
) {
    val enrolled: Boolean get() = haHost != null && (hasToken || hasRefreshToken)

    fun summaryLines(): List<String> = listOf(
        "Home Assistant: ${haHost ?: "not enrolled"}",
        // Where the tailnet path dials: without ha_dial it dials ha_url's host NAME, which only
        // resolves away from home when it is an IP or a MagicDNS name (docs/tailnet.md).
        "Tailnet dial target: ${haDial ?: "none: dials the address's host name"}",
        "Access token: ${if (hasToken) "present" else if (hasRefreshToken) "refresh token (renews itself)" else "absent"}",
        "Phone name: ${phoneName?.ifBlank { null } ?: "(none)"}",
        "Push ring notification (Firebase): " + when {
            firebase != null -> "project ${firebase.projectId}"
            firebaseIncomplete -> "stored values are incomplete; paste a google-services.json"
            else -> "not configured"
        },
        "Ring for door calls on this phone: ${if (ringOnThisPhone) "on" else "off"}",
        "Installation id: ${if (hasInstallationId) "set" else "not set"}",
    ) + listOfNotNull(
        if (pendingRedemption) "Enrolment: finishing, redeeming the one-time code" else null,
        if (signedOut) "Sign-in: signed out of Home Assistant. Enroll this phone again." else null,
    )

    override fun toString(): String = "SettingsSnapshot(${summaryLines().joinToString("; ")})"

    companion object {
        /** [get] reads one stored string by [SettingsKeys] key. */
        fun from(get: (String) -> String?, ringOnThisPhone: Boolean, installationId: String?): SettingsSnapshot {
            val url = get(SettingsKeys.HA_URL)
            val fcmValues = listOf(
                SettingsKeys.FCM_PROJECT_ID, SettingsKeys.FCM_SENDER_ID, SettingsKeys.FCM_APP_ID, SettingsKeys.FCM_API_KEY,
            ).map(get)
            val firebase = if (fcmValues.all { it != null }) {
                (FirebaseConfig.create(fcmValues[0]!!, fcmValues[1]!!, fcmValues[2]!!, fcmValues[3]!!) as? FirebaseConfigResult.Ok)?.config
            } else {
                null
            }
            return SettingsSnapshot(
                haHost = url?.let { EnrollmentPayload.httpHost(it) },
                haUrlCheck = url?.let { UrlPolicy.check(it) },
                hasToken = !get(SettingsKeys.HA_TOKEN).isNullOrBlank(),
                phoneName = get(SettingsKeys.PHONE_NAME),
                firebase = firebase,
                firebaseIncomplete = firebase == null && fcmValues.any { it != null },
                ringOnThisPhone = ringOnThisPhone,
                hasInstallationId = !installationId.isNullOrBlank(),
                haDial = get(SettingsKeys.HA_DIAL)?.let { TailnetTarget.parseHaDial(it) },
                hasRefreshToken = !get(SettingsKeys.REFRESH_TOKEN).isNullOrBlank() && !get(SettingsKeys.CLIENT_ID).isNullOrBlank(),
                pendingRedemption = PendingCodec.read(get) != null,
                signedOut = get(SettingsKeys.SIGNED_OUT) == "1" &&
                    get(SettingsKeys.HA_TOKEN).isNullOrBlank() && get(SettingsKeys.REFRESH_TOKEN).isNullOrBlank(),
            )
        }
    }
}
