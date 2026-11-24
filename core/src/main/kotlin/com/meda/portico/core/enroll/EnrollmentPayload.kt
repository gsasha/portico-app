package com.meda.portico.core.enroll

import com.meda.portico.core.net.UrlCheck
import com.meda.portico.core.net.UrlPolicy
import com.meda.portico.core.net.UrlRefusal
import com.meda.portico.core.tailnet.TailnetTarget
import java.net.URI

/** The optional `fcm` block of the enrollment payload (spec §5.1). */
sealed interface FcmBlock {
    /** Absent, null, `""` or `{}`: leave the stored Firebase values alone, never clear them (design §4.7 rule 1). */
    data object Unchanged : FcmBlock

    data class Present(val config: FirebaseConfig) : FcmBlock
}

/** Why a pasted enrollment text was refused. The message never contains any of the input. */
enum class EnrollmentRefusal(val message: String) {
    EMPTY("Nothing was pasted."),
    TOO_LARGE("That text is far too large to be a Portico enrollment."),
    GOOGLE_SERVICES_JSON("That is a google-services.json. Paste it into the Firebase field instead."),
    NOT_JSON("That is not a Portico enrollment (not valid JSON)."),
    NOT_AN_OBJECT("That is not a Portico enrollment (not a JSON object)."),
    UNSUPPORTED_FORMAT(
        "This enrollment uses a format this version of Portico does not read. Update the app, then paste it again.",
    ),
    MISSING_HA_URL("The enrollment has no ha_url."),
    HA_URL_NOT_SET(
        "The enrollment's ha_url is empty: Home Assistant has no URL configured. Set one under " +
            "Settings > System > Network, then enroll this phone again.",
    ),
    INVALID_HA_URL("The enrollment's ha_url is not an http(s) address with a host name."),

    /** PR-J: [UrlPolicy] refused it; [EnrollmentParseResult.Refused.url] says why. */
    HA_URL_NOT_ALLOWED("The enrollment's ha_url is not allowed."),
    MISSING_HA_TOKEN("The enrollment has no ha_token."),

    /** DEV-544: a format-2 QR carries a one-time `code` and never an `ha_token`. */
    MISSING_CODE("The enrollment has no one-time code."),
    INVALID_CODE("The enrollment's one-time code is not valid."),
    CODE_QR_WITH_TOKEN("That enrollment is not valid: a one-time-code QR must not carry a token."),
    MISSING_ENROLL_ID("The enrollment has no enroll_id."),
    INVALID_HA_TOKEN("The enrollment's ha_token is not a usable token."),
    MISSING_PHONE_NAME("The enrollment has no phone_name."),
    INVALID_TAILSCALE_KEY("The enrollment's tailscale_authkey is not text."),

    /** DEV-557: a present `ha_dial` must be a private/tailnet IP address with a port ([TailnetTarget.parseHaDial]). */
    INVALID_HA_DIAL(
        "The enrollment's ha_dial is not a private or tailnet IP address with a port (like 192.168.2.5:443).",
    ),
    FCM_UNSUPPORTED_VERSION(
        "The enrollment's Firebase block uses a format this version of Portico does not read. Update the app.",
    ),
    FCM_INVALID("The enrollment's Firebase block is incomplete or invalid."),

    /** Offline registration: a present `enroll_id` must be 16-128 chars of `[A-Za-z0-9_-]`. */
    INVALID_ENROLL_ID("The enrollment's enroll_id is not valid."),
}

sealed interface EnrollmentParseResult {
    data class Ok(val payload: EnrollmentPayload) : EnrollmentParseResult

    data class Refused(
        val reason: EnrollmentRefusal,
        val firebase: FirebaseConfigResult.Invalid? = null,
        /** PR-J: why [UrlPolicy] refused the ha_url (with [EnrollmentRefusal.HA_URL_NOT_ALLOWED]). */
        val url: UrlRefusal? = null,
    ) : EnrollmentParseResult {
        val message: String get() = listOfNotNull(reason.message, firebase?.message, url?.message).joinToString(" ")
    }
}

/**
 * The enrollment payload as `portico-ha` `custom_components/portico/enrollment_flow.py`
 * (`_async_finish_enrollment`) builds it and `_build_qr_data_uri` puts it in the QR, as compact
 * JSON:
 *
 * ```
 * {"ha_url": str, "ha_token": str, "phone_name": str, "tailscale_authkey": str (only in "auto" mode)}
 * ```
 *
 * plus two additions the app already reads:
 * - `"fcm": {"v": 1, "project_id", "app_id", "api_key", "sender_id"}` (spec §5.1, server P3).
 *   Optional; absent or blank means [FcmBlock.Unchanged]. A present block that is invalid refuses
 *   the whole payload, so a half-applied enrollment cannot happen.
 * - `"enroll_id": str` (offline registration, 2026-10-07): HA's random, non-secret id for this
 *   phone's enrollment, `[A-Za-z0-9_-]{16,128}` (`secrets.token_urlsafe(32)`). Optional: absent or
 *   null for a QR from an older HA. A present invalid one refuses the payload. The app stores it
 *   and puts it into the registration string ([RegistrationString]); never logged.
 * - `"portico": 1`, the format marker (server PR G4, not merged yet). Absent is accepted for now;
 *   present and anything but the number 1 or 2 is refused ("update the app").
 * - DEV-544, format 2 (`"portico": 2`, `contract/enroll_v1.json`): no `ha_token`; instead `"code"`, a ONE-TIME code
 *   (22..128 of `[A-Za-z0-9_-]`, good for 10 minutes and one redemption) that the app redeems for a Home Assistant
 *   refresh token. A format-2 QR needs `code` and `enroll_id`, and one that also carries `ha_token` is refused
 *   (nothing is saved). Format 1 (and the unmarked payload) keep working exactly as before: the app still reads
 *   them, so a server that has not been updated can enrol a phone; the updated server no longer issues them.
 *
 * Refused outright: a google-services.json pasted into the wrong field, and anything that is not one JSON object.
 *
 * DEV-557, the tailnet client:
 * - `tailscale_authkey` (only in the server's `auto` mode): the single-use key the phone redeems
 *   right after the save to join the tailnet. [SetupResult.Ok.tailnetWrites] stores it with its
 *   save time, apart from [SetupResult.Ok.writes]; it is deleted once the node is up or after
 *   ~55 minutes (`TailnetController`). Never logged, never shown.
 * - `"ha_dial": "192.168.1.5:443"` (optional, the server adds it in a follow-up): where the tailnet
 *   path dials when `ha_url`'s host does not resolve away from home. Strictly validated
 *   ([TailnetTarget.parseHaDial]: a private/tailnet IP literal with a port); a present invalid one
 *   refuses the payload; absent means "dial `ha_url`'s host".
 *
 * PR-J: `ha_url` must pass [UrlPolicy]: https always, plain http only to a private/LAN host
 * ([plainHttp] is then true and Setup shows a persistent warning). Anything else is refused with
 * [EnrollmentRefusal.HA_URL_NOT_ALLOWED] and the policy's reason, so nothing is saved.
 *
 * [parse] never throws. [toString] shows the host and whether each secret is present, never a
 * secret.
 */
class EnrollmentPayload private constructor(
    val haUrl: String,
    /** Host only, for display: what Setup shows instead of the URL. */
    val haHost: String,
    /** The long-lived token of a format-1 (or unmarked) payload; null for a format-2 payload, which carries [code] instead. */
    val haToken: String?,
    val phoneName: String,
    val tailscaleAuthKey: String?,
    val fcm: FcmBlock,
    /** The `portico` marker: null when absent (the server does not send it yet), else 1 or 2. */
    val formatVersion: Int?,
    /** PR-J: `ha_url` is plain http to a private/LAN host ([UrlCheck.HttpPrivate]). */
    val plainHttp: Boolean,
    /** Offline registration: HA's enroll id for this phone, null in an older QR. Never log it. */
    val enrollId: String? = null,
    /** DEV-557: the canonical `ha_dial` target (`host:port`), null when absent. Not secret. */
    val haDial: String? = null,
    /** DEV-544: the format-2 QR's one-time code, null for a format-1 payload. A credential for ten minutes: never log or show it. */
    val code: String? = null,
) {
    override fun toString(): String =
        "EnrollmentPayload(host=$haHost, token=${if (haToken != null) "present" else "absent"}, code=${if (code != null) "present" else "absent"}, " +
            "phone_name=$phoneName, " +
            "tailscale_authkey=${if (tailscaleAuthKey != null) "present" else "absent"}, " +
            "ha_dial=${haDial ?: "absent"}, " +
            "fcm=$fcm, format=${formatVersion ?: "unmarked"}, " +
            "enroll_id=${if (enrollId != null) "present" else "absent"})"

    companion object {
        const val FORMAT_VERSION = 1

        /** DEV-544: the one-time-code QR. */
        const val FORMAT_CODE = 2
        const val FCM_BLOCK_VERSION = 1

        /** The payload is ~300-750 bytes (spec §5.4); a QR cannot hold more than ~3 KB. */
        const val MAX_CHARS = 16 * 1024

        fun parse(text: String): EnrollmentParseResult = try {
            parseOrRefuse(text)
        } catch (_: RuntimeException) {
            // Defensive: no path above is expected to throw. Never let a paste crash the app,
            // and never attach the exception (its message could quote the input).
            refuse(EnrollmentRefusal.NOT_JSON)
        }

        private fun refuse(r: EnrollmentRefusal, f: FirebaseConfigResult.Invalid? = null) = EnrollmentParseResult.Refused(r, f)

        private fun parseOrRefuse(text: String): EnrollmentParseResult {
            val t = text.trim().removePrefix("﻿").trim()
            if (t.isEmpty()) return refuse(EnrollmentRefusal.EMPTY)
            if (t.length > MAX_CHARS) return refuse(EnrollmentRefusal.TOO_LARGE)
            val obj = when (val json = MiniJson.parse(t)) {
                null -> return refuse(EnrollmentRefusal.NOT_JSON)
                is Json.Obj -> json
                else -> return refuse(EnrollmentRefusal.NOT_AN_OBJECT)
            }

            var formatVersion: Int? = null
            if (obj.has("portico")) {
                val m = obj["portico"]
                formatVersion = when {
                    m is Json.Num && m.raw == FORMAT_VERSION.toString() -> FORMAT_VERSION
                    m is Json.Num && m.raw == FORMAT_CODE.toString() -> FORMAT_CODE
                    else -> return refuse(EnrollmentRefusal.UNSUPPORTED_FORMAT)
                }
            }

            if (!obj.has("ha_url") && (obj.has("project_info") || obj.has("client"))) {
                return refuse(EnrollmentRefusal.GOOGLE_SERVICES_JSON)
            }

            // ha_url: the server falls back to "" when HA has neither an external nor an internal URL.
            if (!obj.has("ha_url")) return refuse(EnrollmentRefusal.MISSING_HA_URL)
            val urlJson = obj["ha_url"] as? Json.Str ?: return refuse(EnrollmentRefusal.INVALID_HA_URL)
            if (urlJson.value.isBlank()) return refuse(EnrollmentRefusal.HA_URL_NOT_SET)
            val haUrl = urlJson.value.trim()
            // PR-J: the policy first, so a refusal names its reason; then java.net.URI's host for display.
            val urlCheck = UrlPolicy.check(haUrl)
            if (urlCheck is UrlCheck.Refused) {
                val r = urlCheck.reason
                // No "://" / not http(s) / empty: the existing, more basic refusals.
                if (r == UrlRefusal.NOT_HTTP || r == UrlRefusal.EMPTY || r == UrlRefusal.EMPTY_HOST) return refuse(EnrollmentRefusal.INVALID_HA_URL)
                return EnrollmentParseResult.Refused(EnrollmentRefusal.HA_URL_NOT_ALLOWED, url = r)
            }
            val host = httpHost(haUrl) ?: return refuse(EnrollmentRefusal.INVALID_HA_URL)

            var token: String? = null
            var code: String? = null
            if (formatVersion == FORMAT_CODE) {
                // DEV-544: a one-time-code QR. A token next to the code is a QR nobody should have made: refuse it whole.
                if (obj.has("ha_token")) return refuse(EnrollmentRefusal.CODE_QR_WITH_TOKEN)
                if (!obj.has("code")) return refuse(EnrollmentRefusal.MISSING_CODE)
                val codeJson = obj["code"] as? Json.Str ?: return refuse(EnrollmentRefusal.INVALID_CODE)
                if (codeJson.value.isEmpty()) return refuse(EnrollmentRefusal.MISSING_CODE)
                if (!com.meda.portico.core.auth.EnrollContract.isValidCode(codeJson.value)) return refuse(EnrollmentRefusal.INVALID_CODE)
                code = codeJson.value
                // The redemption names the phone by its enroll_id, so a code without one cannot be redeemed.
                if (!obj.has("enroll_id") || obj["enroll_id"] == Json.Null) return refuse(EnrollmentRefusal.MISSING_ENROLL_ID)
            } else {
                if (!obj.has("ha_token")) return refuse(EnrollmentRefusal.MISSING_HA_TOKEN)
                val tokenJson = obj["ha_token"] as? Json.Str ?: return refuse(EnrollmentRefusal.INVALID_HA_TOKEN)
                val t = tokenJson.value.trim()
                if (t.isEmpty()) return refuse(EnrollmentRefusal.MISSING_HA_TOKEN)
                if (t.lowercase() in FirebaseConfig.PLACEHOLDERS || t.any { it.isWhitespace() || it.isISOControl() }) {
                    return refuse(EnrollmentRefusal.INVALID_HA_TOKEN)
                }
                token = t
            }

            // The server always sends phone_name (a required form field), possibly empty.
            val nameJson = obj["phone_name"] as? Json.Str ?: return refuse(EnrollmentRefusal.MISSING_PHONE_NAME)

            val tsKey = when (val k = obj["tailscale_authkey"]) {
                null, Json.Null -> null
                is Json.Str -> k.value.trim().ifEmpty { null }
                else -> return refuse(EnrollmentRefusal.INVALID_TAILSCALE_KEY)
            }

            val enrollId = when (val e = obj["enroll_id"]) {
                null, Json.Null -> null
                is Json.Str -> e.value.takeIf { RegistrationString.isValidEnrollId(it) } ?: return refuse(EnrollmentRefusal.INVALID_ENROLL_ID)
                else -> return refuse(EnrollmentRefusal.INVALID_ENROLL_ID)
            }

            val haDial = when (val d = obj["ha_dial"]) {
                null, Json.Null -> null
                is Json.Str -> if (d.value.isBlank()) null else TailnetTarget.parseHaDial(d.value) ?: return refuse(EnrollmentRefusal.INVALID_HA_DIAL)
                else -> return refuse(EnrollmentRefusal.INVALID_HA_DIAL)
            }

            val fcm = when (val f = parseFcm(obj["fcm"])) {
                is FcmParse.Block -> f.block
                is FcmParse.Bad -> return f.refused
            }

            return EnrollmentParseResult.Ok(
                EnrollmentPayload(
                    haUrl, host, token, nameJson.value.trim(), tsKey, fcm, formatVersion,
                    plainHttp = urlCheck is UrlCheck.HttpPrivate,
                    enrollId = enrollId,
                    haDial = haDial,
                    code = code,
                ),
            )
        }

        private sealed interface FcmParse {
            data class Block(val block: FcmBlock) : FcmParse
            data class Bad(val refused: EnrollmentParseResult.Refused) : FcmParse
        }

        private fun parseFcm(f: Json?): FcmParse {
            val unchanged = FcmParse.Block(FcmBlock.Unchanged)
            val obj = when (f) {
                null, Json.Null -> return unchanged
                is Json.Str -> return if (f.value.isBlank()) unchanged else FcmParse.Bad(refuse(EnrollmentRefusal.FCM_INVALID))
                is Json.Obj -> f
                else -> return FcmParse.Bad(refuse(EnrollmentRefusal.FCM_INVALID))
            }
            if (obj.members.isEmpty()) return unchanged
            if (obj.has("v")) {
                val v = obj["v"]
                if (!(v is Json.Num && v.raw == FCM_BLOCK_VERSION.toString())) {
                    return FcmParse.Bad(refuse(EnrollmentRefusal.FCM_UNSUPPORTED_VERSION))
                }
            }
            val values = FirebaseField.entries.associateWith { (obj[it.key] as? Json.Str)?.value }
            for ((field, v) in values) {
                if (v == null) {
                    return FcmParse.Bad(refuse(EnrollmentRefusal.FCM_INVALID, FirebaseConfigResult.Invalid(field, FirebaseProblem.BLANK)))
                }
            }
            return when (val c = FirebaseConfig.create(
                values.getValue(FirebaseField.PROJECT_ID)!!,
                values.getValue(FirebaseField.SENDER_ID)!!,
                values.getValue(FirebaseField.APP_ID)!!,
                values.getValue(FirebaseField.API_KEY)!!,
            )) {
                is FirebaseConfigResult.Ok -> FcmParse.Block(FcmBlock.Present(c.config))
                is FirebaseConfigResult.Invalid -> FcmParse.Bad(refuse(EnrollmentRefusal.FCM_INVALID, c))
            }
        }

        /** The host of an http(s) URL with no user-info, or null. */
        internal fun httpHost(url: String): String? {
            val uri = try {
                URI(url)
            } catch (_: Exception) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.rawUserInfo != null) return null
            val host = uri.host
            if (host.isNullOrEmpty()) return null
            return host
        }
    }
}
