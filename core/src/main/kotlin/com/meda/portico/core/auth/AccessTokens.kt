package com.meda.portico.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URLEncoder

sealed interface RefreshOutcome {
    data class Ok(val accessToken: String, val expiresInS: Long) : RefreshOutcome {
        override fun toString(): String = "Ok(expires_in=$expiresInS)"
    }

    /** `400 invalid_grant` (the refresh token no longer exists) or `403 access_denied` (the user is disabled): the phone is signed out. */
    data object SignedOut : RefreshOutcome

    /** No path completed the request. Not a refusal: keep the stored tokens and try again later. */
    data object Unreachable : RefreshOutcome

    /** Any other answer (a 5xx, a 400 `invalid_request`, a malformed 200). Keep the stored tokens; [status] is the HTTP status. */
    data class Failed(val status: Int) : RefreshOutcome
}

/**
 * Step 3 of the contract: HA's own refresh grant, `POST /auth/token`, form-encoded `grant_type=refresh_token`,
 * `refresh_token` and `client_id` (HA 2026.10 answers a grant without the client id `400 invalid_request`). The refresh
 * token never rotates, so only an access token comes back. Logs nothing.
 */
class RefreshClient(private val http: HttpPost) {

    fun refresh(haUrl: String?, refreshToken: String, clientId: String): RefreshOutcome {
        val url = EnrollContract.urlFor(haUrl, EnrollContract.TOKEN_PATH) ?: return RefreshOutcome.Failed(0)
        val body = form("grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to clientId)
        val reply = try {
            http.post(url, "application/x-www-form-urlencoded", body)
        } catch (_: IOException) {
            return RefreshOutcome.Unreachable
        }
        return when (reply.status) {
            200 -> parse(reply.body)
            400 -> if (errorOf(reply.body) == "invalid_grant") RefreshOutcome.SignedOut else RefreshOutcome.Failed(400)
            403 -> if (errorOf(reply.body) == "access_denied") RefreshOutcome.SignedOut else RefreshOutcome.Failed(403)
            else -> RefreshOutcome.Failed(reply.status)
        }
    }

    private fun parse(body: String): RefreshOutcome {
        val o = obj(body) ?: return RefreshOutcome.Failed(200)
        val token = (o["access_token"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?: return RefreshOutcome.Failed(200)
        val expires = (o["expires_in"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.takeIf { it > 0 }
            ?: return RefreshOutcome.Failed(200)
        return RefreshOutcome.Ok(token, expires)
    }

    private fun errorOf(body: String): String? = (obj(body)?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun obj(body: String): JsonObject? = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
}

/** Where the stored login lives. The app implements it over the encrypted settings. Never log what it returns. */
interface LoginStore {
    /** The refresh token, null when none is stored. */
    fun refreshToken(): String?

    /** The client id the refresh token was issued with. */
    fun clientId(): String?

    /** HA said `invalid_grant`: forget the login and remember that the phone is signed out. */
    fun signOut()
}

/**
 * The refresh-token login (DEV-544): the access token the WebSocket's `auth` message carries.
 *
 * - [cached] is the in-memory access token, or null when there is none or it has less than
 *   [EnrollContract.REFRESH_MARGIN_MS] left (an exact 5 minutes left is still enough).
 * - [obtain] returns the cached token or trades the refresh token for a new one at `/auth/token`; one request at a time (a
 *   second caller waits and then finds the first one's token). It blocks on the network: never call it on the UI thread
 *   or the signaling thread ([cached] never blocks on it).
 * - The access token is kept in MEMORY ONLY. Persisting it would put a second 24-hour bearer secret at rest to save one
 *   request per process start; the refresh token (encrypted, in the settings store) is all a start needs.
 * - `invalid_grant` -> [LoginStore.signOut] and [Result.SignedOut]. Every other failure keeps the stored tokens.
 *
 * [clockMs] is monotonic milliseconds ([System.nanoTime] / 1e6 or `SystemClock.elapsedRealtime`): never wall time, so a
 * clock change cannot make a dead token look fresh.
 */
class AccessTokens(
    private val store: LoginStore,
    private val client: RefreshClient,
    private val haUrl: () -> String?,
    private val clockMs: () -> Long,
) {
    sealed interface Result {
        data class Token(val value: String) : Result {
            override fun toString(): String = "Token"
        }

        data object SignedOut : Result
        data object Unreachable : Result
        data class Failed(val status: Int) : Result
    }

    private val lock = Any()
    private var token: String? = null
    private var validUntilMs = 0L

    /** True when a refresh token is stored: the app then logs in with [obtain] instead of a long-lived token. */
    fun enrolled(): Boolean = !store.refreshToken().isNullOrBlank() && !store.clientId().isNullOrBlank()

    fun cached(): String? = synchronized(lock) {
        val t = token ?: return null
        if (validUntilMs - clockMs() >= EnrollContract.REFRESH_MARGIN_MS) t else null
    }

    /** The access token a redemption returned (so the first connect needs no refresh). */
    fun seed(accessToken: String, expiresInS: Long) = synchronized(lock) {
        token = accessToken
        validUntilMs = clockMs() + expiresInS * 1000L
    }

    /** Forget the cached token (HA refused it, or a different login replaced it): the next [obtain] asks `/auth/token`. */
    fun invalidate() = synchronized(lock) {
        token = null
        validUntilMs = 0L
    }

    /** One refresh at a time. Held across the network call, so it is a different lock from [lock], which [cached] needs at once. */
    private val refreshLock = Any()

    fun obtain(): Result = synchronized(refreshLock) {
        cached()?.let { return Result.Token(it) }
        val refresh = store.refreshToken()?.takeIf { it.isNotBlank() }
        val clientId = store.clientId()?.takeIf { it.isNotBlank() }
        if (refresh == null || clientId == null) return Result.SignedOut
        val r = client.refresh(haUrl(), refresh, clientId)
        // A re-enrolment may have replaced the login while this request was out: its answer belongs to the OLD login, so it
        // is neither cached nor allowed to sign the NEW one out. The caller simply asks again.
        if ((r is RefreshOutcome.Ok || r == RefreshOutcome.SignedOut) && store.refreshToken() != refresh) return Result.Unreachable
        when (r) {
            is RefreshOutcome.Ok -> {
                seed(r.accessToken, r.expiresInS)
                Result.Token(r.accessToken)
            }
            RefreshOutcome.SignedOut -> {
                invalidate()
                store.signOut()
                Result.SignedOut
            }
            RefreshOutcome.Unreachable -> Result.Unreachable
            is RefreshOutcome.Failed -> Result.Failed(r.status)
        }
    }

    override fun toString(): String = "AccessTokens(cached=${synchronized(lock) { token != null }})"
}
