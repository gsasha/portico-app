package com.meda.portico.core.auth

import com.meda.portico.core.ha.Connector
import com.meda.portico.core.ha.WsListener
import com.meda.portico.core.ha.WsTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executor
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * DEV-798 (SEC-02): Home Assistant proves itself before this phone sends it a token over a CLEARTEXT address.
 *
 * Why: a phone enrolled with a private `http://` address tries it first on any network. On a hotel or cafe Wi-Fi a host at
 * the same private address or `.local` name would be sent the refresh token (`POST /auth/token`) or the access token (the
 * websocket `auth` frame) in clear text. Recognising the network is no proof (anyone can name an access point). What only the
 * real Home Assistant has is this phone's refresh token, so the portico plugin answers `POST /api/portico/whoami` with
 * `HMAC-SHA256(refresh token, nonce)` and the phone sends nothing until the answer checks out. `contract/whoami_v1.json`
 * (vendored at `core/src/test/resources/whoami_v1.json`, sha256 pinned in the test) is the server's fixture and carries a vector.
 *
 * What it covers and does not: it stops IMPERSONATION. It does not hide the token sent afterwards over `http://` from someone who
 * can read the real LAN; only `https://` or the tailnet does. An `https://` / `wss://` address needs no proof (TLS names the
 * server), the tailnet is authenticated by WireGuard, and a phone with no refresh token (enrolled the old format-1 way) has
 * nothing to prove with: it is left as it was, until it is enrolled again.
 */
object HomeProof {
    const val PATH = "/api/portico/whoami"
    const val AUTH_TOKEN_PATH = "/auth/token"
    private const val DOMAIN_TAG = "portico-whoami-v1"
    private const val NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
    private const val NONCE_LENGTH = 32
    private const val PROOF_HEX = 64

    /** Which token this is, without being the token: 8 bytes of its SHA-256, as 16 lowercase hex characters. */
    fun hint(refreshToken: String): String =
        MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray(Charsets.UTF_8)).copyOf(8).hex()

    /** The proof the server computes for [nonce] and [hint], 64 lowercase hex characters. */
    fun proof(refreshToken: String, nonce: String, hint: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(refreshToken.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(DOMAIN_TAG.toByteArray(Charsets.US_ASCII))
        mac.update(0)
        mac.update(nonce.toByteArray(Charsets.US_ASCII))
        mac.update(0)
        mac.update(hint.toByteArray(Charsets.US_ASCII))
        return mac.doFinal().hex()
    }

    /** 32 random characters of `[A-Za-z0-9_-]`, fresh for every probe. */
    fun newNonce(random: SecureRandom = SecureRandom()): String =
        buildString { repeat(NONCE_LENGTH) { append(NONCE_CHARS[random.nextInt(NONCE_CHARS.length)]) } }

    /** True for `ws://` and `http://`: the address where a token would travel in clear text. */
    fun isCleartext(url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        return scheme == "ws" || scheme == "http"
    }

    /** `http://host:port/api/portico/whoami` for a `ws://`/`http://` address (scheme and authority kept, path replaced); null for anything else. */
    fun endpoint(url: String): String? {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "ws" && scheme != "http") return null
        val rest = url.substringAfter("://", "")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || authority.contains('@') || authority.any { it.isWhitespace() }) return null
        return "http://$authority$PATH"
    }

    fun requestBody(nonce: String, hint: String): String =
        buildJsonObject { put("nonce", nonce); put("hint", hint) }.toString()

    /** Is [reply] a `200` carrying exactly `{"proof": <the proof for this token, nonce and hint>}`? Constant-time compare. */
    fun verify(reply: HttpReply, refreshToken: String, nonce: String, hint: String): Boolean {
        if (reply.status != 200) return false
        val obj = try {
            Json.parseToJsonElement(reply.body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return false
        if (obj.keys != setOf("proof")) return false
        val got = (obj["proof"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return false
        if (got.length != PROOF_HEX) return false
        val want = proof(refreshToken, nonce, hint)
        return MessageDigest.isEqual(got.toByteArray(Charsets.US_ASCII), want.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}

/**
 * Asks the server for its proof. [http] is the DIRECT post (never the gated one, which would ask itself) and should have a short
 * timeout. [refreshToken] is the phone's stored token, or null.
 */
class HomeVerifier(
    private val http: HttpPost,
    private val refreshToken: () -> String?,
    private val newNonce: () -> String = { HomeProof.newNonce() },
    /** Every decision, for the connection log: a fixed word each, never a URL, address, nonce or token. Never throws into the caller. */
    private val onOutcome: (ProofOutcome) -> Unit = {},
) {
    private fun report(o: ProofOutcome) {
        runCatching { onOutcome(o) }
    }

    /** No proof was needed for [url] (TLS, or no refresh token to prove with): said to the log, so a connect never leaves the question open. */
    fun noteSkipped(url: String) {
        report(if (!HomeProof.isCleartext(url)) ProofOutcome.SKIPPED_TLS else ProofOutcome.SKIPPED_NO_TOKEN)
    }

    private fun token(): String? = try {
        refreshToken()?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    /** Does sending a token to [url] over the direct path need the server's proof first? (A cleartext address, and a token to prove with.) */
    fun needsProof(url: String): Boolean = HomeProof.isCleartext(url) && token() != null

    /** May a token go to [url] over the direct path? True when no proof is needed, or the server gave a valid one. Never throws. */
    fun allows(url: String): Boolean {
        if (!HomeProof.isCleartext(url)) return true.also { report(ProofOutcome.SKIPPED_TLS) }
        val t = token() ?: return true.also { report(ProofOutcome.SKIPPED_NO_TOKEN) }
        val endpoint = HomeProof.endpoint(url) ?: return false.also { report(ProofOutcome.REFUSED) }
        val nonce = newNonce()
        val hint = HomeProof.hint(t)
        val reply = try {
            http.post(endpoint, "application/json", HomeProof.requestBody(nonce, hint))
        } catch (_: Exception) {
            return false.also { report(ProofOutcome.NO_ANSWER) }
        }
        val ok = HomeProof.verify(reply, t, nonce, hint)
        report(if (ok) ProofOutcome.PASSED else ProofOutcome.REFUSED)
        return ok
    }
}

/**
 * What the proof check decided, for the connection log (DEV-798). [word] is the fixed class the log carries; [stage] the timeline
 * stage it is marked as. Nothing but these words ever reaches the log: no address, nonce, hint or token.
 */
enum class ProofOutcome(val word: String, val stage: com.meda.portico.core.ha.ConnectTimeline.Stage) {
    /** The server answered with the right proof: a token may go. */
    PASSED("passed", com.meda.portico.core.ha.ConnectTimeline.Stage.PROOF_OK),

    /** The server answered, but not with a proof for this token (an old plugin, a wrong host, a revoked token), or the address could not be probed. */
    REFUSED("refused", com.meda.portico.core.ha.ConnectTimeline.Stage.PROOF_FAILED),

    /** No answer at all (no route, a timeout, a reset). */
    NO_ANSWER("no_answer", com.meda.portico.core.ha.ConnectTimeline.Stage.PROOF_FAILED),

    /** `https://` or `wss://`: TLS names the server, nothing to prove. */
    SKIPPED_TLS("tls", com.meda.portico.core.ha.ConnectTimeline.Stage.PROOF_SKIPPED),

    /** A cleartext address, but no refresh token to prove with (enrolled the old format-1 way). */
    SKIPPED_NO_TOKEN("no_token", com.meda.portico.core.ha.ConnectTimeline.Stage.PROOF_SKIPPED),
}

/**
 * The refresh call (`/auth/token`) goes to a cleartext address only when that address has proved itself. The enrollment redemption
 * is NOT gated: it sends a one-time code, not a token, possibly to a new Home Assistant that cannot prove an old token. A refusal
 * is an [IOException], so [PathedHttp] goes on to the tailnet exactly as for an unreachable address.
 */
class VerifiedHttpPost(private val inner: HttpPost, private val verifier: HomeVerifier) : HttpPost {
    override fun post(url: String, contentType: String, body: String): HttpReply {
        if (url.substringBefore('?').endsWith(HomeProof.AUTH_TOKEN_PATH) && !verifier.allows(url)) {
            throw IOException("the server at this address did not prove it is the enrolled Home Assistant")
        }
        return inner.post(url, contentType, body)
    }
}

/**
 * The direct websocket opens to a cleartext address only when that address has proved itself (the `auth` frame carries the access
 * token). While the proof is asked for, the caller holds a transport that opens the real socket on [worker] once it is checked; a
 * refusal arrives as a close, which [com.meda.portico.core.ha.FallbackConnector] treats as a failed direct attempt (the tailnet
 * next). An address that needs no proof (TLS, or no token to prove with) opens at once, as before.
 */
class VerifiedConnector(
    private val inner: Connector,
    private val verifier: HomeVerifier,
    private val worker: Executor,
) : Connector {
    override fun open(url: String, listener: WsListener): WsTransport {
        if (!verifier.needsProof(url)) {
            verifier.noteSkipped(url)
            return inner.open(url, listener)
        }
        return Gated(url, listener)
    }

    private inner class Gated(private val url: String, private val listener: WsListener) : WsTransport {
        private val lock = Any()
        private var real: WsTransport? = null
        private var closed = false

        init {
            worker.execute { run() }
        }

        private fun run() {
            val ok = verifier.allows(url)
            if (synchronized(lock) { closed }) return
            if (!ok) {
                listener.onClosed("the server at this address did not prove it is the enrolled Home Assistant")
                return
            }
            val t = try {
                inner.open(url, listener)
            } catch (e: Exception) {
                listener.onClosed(e.message ?: e.javaClass.simpleName)
                return
            }
            val closeNow = synchronized(lock) { real = t; closed }
            if (closeNow) t.close(1000, "closed while connecting")
        }

        override fun send(text: String): Boolean = synchronized(lock) { real }?.send(text) ?: false

        override fun queueSize(): Long = synchronized(lock) { real }?.queueSize() ?: 0L

        override fun close(code: Int, reason: String) {
            val t = synchronized(lock) { closed = true; real }
            t?.close(code, reason)
        }
    }
}
