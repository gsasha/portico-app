package com.meda.portico.core.auth

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** One request a [ScriptedHttp] saw. */
data class SeenPost(val url: String, val contentType: String, val body: String)

/**
 * A fake HTTP path: each [post] is answered by the next scripted step (a reply, or an [IOException]); an empty script is a
 * test bug and throws [AssertionError]. [responder] replaces the script for tests that compute the answer.
 */
class ScriptedHttp(vararg steps: Any) : HttpPost {
    private val script = ArrayDeque<Any>(steps.toList())
    val seen = CopyOnWriteArrayList<SeenPost>()
    var responder: ((SeenPost) -> Any)? = null

    fun then(step: Any): ScriptedHttp = apply { script.addLast(step) }

    override fun post(url: String, contentType: String, body: String): HttpReply {
        val p = SeenPost(url, contentType, body)
        seen += p
        val step = responder?.invoke(p) ?: script.removeFirstOrNull() ?: throw AssertionError("unscripted request #${seen.size}")
        return when (step) {
            is HttpReply -> step
            is IOException -> throw step
            else -> throw AssertionError("bad step $step")
        }
    }
}

fun reply(status: Int, body: String = "", retryAfter: String? = null) = HttpReply(status, body, retryAfter)

val unreachable = IOException("fake: no route")

/** An in-memory [PendingStore] with the same compare-the-nonce rules as the app's. */
class MemPendingStore(var pending: PendingEnrollment? = null) : PendingStore {
    var refreshToken: String? = null
    var clientId: String? = null
    var legacyToken: String? = null
    var commitFails = false
    var completes = 0
    val cleared = CopyOnWriteArrayList<String>()
    var onLoad: () -> Unit = {}

    override fun load(): PendingEnrollment? {
        onLoad()
        return pending
    }

    override fun complete(nonce: String, refreshToken: String, clientId: String): CompleteResult {
        if (pending?.nonce != nonce) return CompleteResult.SUPERSEDED
        if (commitFails) return CompleteResult.FAILED
        this.refreshToken = refreshToken
        this.clientId = clientId
        legacyToken = null
        pending = null
        completes++
        return CompleteResult.SAVED
    }

    override fun clear(nonce: String) {
        if (pending?.nonce == nonce) {
            pending = null
            cleared += nonce
        }
    }
}

/** An in-memory [LoginStore]. */
class MemLoginStore(var refresh: String? = "refresh-AAAA", var client: String? = EnrollFixture.clientId) : LoginStore {
    var signedOut = 0
    override fun refreshToken() = refresh
    override fun clientId() = client
    override fun signOut() {
        signedOut++
        refresh = null
        client = null
    }
}

/** A monotonic clock a test moves by hand. */
class FakeClock(var ms: Long = 1_000_000L) : () -> Long {
    override fun invoke(): Long = ms
    fun advance(deltaMs: Long) {
        ms += deltaMs
    }
}
