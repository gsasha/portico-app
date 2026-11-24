package com.meda.portico.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException

/** The four fields of `POST /api/portico/enroll/redeem`, exactly these and nothing else. [toString] shows nothing secret. */
class RedeemRequest(val code: String, val enrollId: String, val installationId: String, val nonce: String) {
    /** Compact JSON, the four keys in the contract's order. */
    fun toJson(): String = buildJsonObject {
        put("code", code)
        put("enroll_id", enrollId)
        put("installation_id", installationId)
        put("nonce", nonce)
    }.toString()

    override fun toString(): String = "RedeemRequest(code=present, nonce=present)"
}

/** A redemption's 200 answer. Every field but [expiresInS] is a credential: [toString] shows none. */
class RedeemGrant(val refreshToken: String, val accessToken: String, val expiresInS: Long, val clientId: String) {
    override fun toString(): String = "RedeemGrant(expires_in=$expiresInS)"
}

sealed interface RedeemOutcome {
    data class Granted(val grant: RedeemGrant) : RedeemOutcome

    /** HTTP 400 `invalid_or_expired`: unknown, wrong, expired, used, cancelled or malformed. Final: never retry this QR. */
    data object InvalidOrExpired : RedeemOutcome

    /** HTTP 429 on every one of [RedeemClient.MAX_RETRIES] + 1 tries (each retry after waiting `Retry-After`): the server is busy. The code was not looked at; it may still be good. */
    data class RateLimited(val retryAfterS: Int) : RedeemOutcome

    /** No path completed the request. Retryable with the SAME request. */
    data object Unreachable : RedeemOutcome

    /** Any other answer (a 5xx, a 404 from a server without the view, a 200 that is not a grant). Retryable with the SAME request. [status] is the HTTP status. */
    data class Unexpected(val status: Int) : RedeemOutcome

    /** The wait for a `Retry-After` was cancelled (a newer scan replaced this redemption). Nothing more to do. */
    data object Cancelled : RedeemOutcome
}

/**
 * Step 2 of the contract: `POST {code, enroll_id, installation_id, nonce}` to the redeem path of the enrolled URL.
 *
 * - 200 -> [RedeemOutcome.Granted]; 400 -> [RedeemOutcome.InvalidOrExpired] (whatever the body says: the server never tells
 *   which refusal it was).
 * - 429 -> the server THROTTLES a hot source (one request per 2 s gets through, `Retry-After: 2`): wait `Retry-After`
 *   (via [wait]) and send the SAME request again, at most [MAX_RETRIES] times; then [RedeemOutcome.RateLimited] ("busy, try
 *   again in a minute", never "expired"). Never a tight loop: every retry waits at least a second.
 * - A request that cannot complete is [RedeemOutcome.Unreachable]. The caller sends the SAME request (same nonce) again
 *   within [EnrollContract.REPLAY_WINDOW_MS] and the server answers with the same refresh token if the first one got through.
 *
 * No Authorization header is sent (the phone holds no token yet). Logs nothing.
 */
class RedeemClient(private val http: HttpPost) {

    /** [wait] sleeps for the given milliseconds and returns false if the redemption was cancelled meanwhile. */
    fun redeem(haUrl: String?, request: RedeemRequest, wait: (Long) -> Boolean = { true }): RedeemOutcome {
        val url = EnrollContract.urlFor(haUrl, EnrollContract.REDEEM_PATH) ?: return RedeemOutcome.Unexpected(0)
        val body = request.toJson()
        var retries = 0
        while (true) {
            val reply = try {
                http.post(url, "application/json", body)
            } catch (_: IOException) {
                return RedeemOutcome.Unreachable
            }
            when (reply.status) {
                200 -> return parseGrant(reply.body)
                400 -> return RedeemOutcome.InvalidOrExpired
                429 -> {
                    val s = EnrollContract.retryAfterSeconds(reply.retryAfter)
                    if (retries >= MAX_RETRIES) return RedeemOutcome.RateLimited(s)
                    retries++
                    if (!wait(s * 1000L)) return RedeemOutcome.Cancelled
                }
                else -> return RedeemOutcome.Unexpected(reply.status)
            }
        }
    }

    companion object {
        /** After the first 429 the same request is sent at most this many more times. */
        const val MAX_RETRIES = 3
    }

    private fun parseGrant(body: String): RedeemOutcome {
        val o = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return RedeemOutcome.Unexpected(200)
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val refresh = str("refresh_token") ?: return RedeemOutcome.Unexpected(200)
        val access = str("access_token") ?: return RedeemOutcome.Unexpected(200)
        val clientId = str("client_id") ?: return RedeemOutcome.Unexpected(200)
        val expires = (o["expires_in"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it > 0 }
            ?: return RedeemOutcome.Unexpected(200)
        return RedeemOutcome.Granted(RedeemGrant(refresh, access, expires, clientId))
    }
}
