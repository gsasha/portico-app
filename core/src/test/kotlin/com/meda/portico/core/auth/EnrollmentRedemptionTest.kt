package com.meda.portico.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnrollmentRedemptionTest {
    private val clock = FakeClock(10_000_000L)
    private val wall = FakeClock(1_800_000_000_000L)
    private val v = EnrollFixture.values
    private val statuses = ArrayList<EnrollStatus>()
    private val sleeps = ArrayList<Long>()

    private fun pending(nonce: String = v.getValue("nonce"), since: Long = wall.ms) =
        PendingEnrollment(v.getValue("code"), nonce, v.getValue("enroll_id"), "https://ha.example.com", since)

    private fun build(
        http: ScriptedHttp,
        store: MemPendingStore,
        login: MemLoginStore = MemLoginStore(refresh = null, client = null),
        install: String? = v.getValue("installation_id"),
        sleep: (Long) -> Unit = { ms -> sleeps += ms; wall.advance(ms) },
    ): Pair<EnrollmentRedemption, AccessTokens> {
        val tokens = AccessTokens(login, RefreshClient(http), { "https://ha.example.com" }, clock)
        val r = EnrollmentRedemption(store, RedeemClient(http), tokens, { install }, wall, sleep, { statuses += it })
        return r to tokens
    }

    private fun success(refresh: String = "REFRESH-1", access: String = "ACCESS-1") = reply(200, EnrollFixture.successBody(refresh, access))

    @Test
    fun `the happy path saves the login in one commit, drops the code and seeds the access token`() {
        val store = MemPendingStore(pending()).also { it.legacyToken = "OLD-LONG-LIVED" }
        val http = ScriptedHttp(success())
        val (r, tokens) = build(http, store)
        assertEquals(EnrollStatus.Done, r.run())
        assertEquals("REFRESH-1", store.refreshToken)
        assertEquals(EnrollFixture.clientId, store.clientId)
        assertNull(store.legacyToken, "a re-enrolment replaces the long-lived token")
        assertNull(store.pending, "the code is dropped")
        assertEquals("ACCESS-1", tokens.cached(), "the first access token needs no refresh")
        assertEquals(1, http.seen.size)
        assertEquals(listOf(EnrollStatus.Redeeming, EnrollStatus.Done), statuses)
    }

    @Test
    fun `the request is built from the pending record and the installation id`() {
        val http = ScriptedHttp(success())
        build(http, MemPendingStore(pending())).first.run()
        val sent = Json.parseToJsonElement(http.seen.single().body).jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(mapOf("code" to v["code"], "enroll_id" to v["enroll_id"], "installation_id" to v["installation_id"], "nonce" to v["nonce"]), sent)
        assertEquals("https://ha.example.com/api/portico/enroll/redeem", http.seen.single().url)
    }

    @Test
    fun `nothing pending does nothing`() {
        val http = ScriptedHttp()
        assertEquals(EnrollStatus.Idle, build(http, MemPendingStore(null)).first.run())
        assertTrue(http.seen.isEmpty())
    }

    @Test
    fun `a 400 is final - the code is dropped, the old login is untouched, one request only`() {
        val store = MemPendingStore(pending()).also { it.legacyToken = "OLD-LONG-LIVED"; it.refreshToken = "OLD-REFRESH" }
        val http = ScriptedHttp(reply(400, """{"error":"invalid_or_expired"}"""))
        val out = build(http, store).first.run()
        assertEquals(EnrollStatus.Failed(EnrollStatus.Kind.INVALID_OR_EXPIRED), out)
        assertEquals(EnrollContract.MSG_INVALID_OR_EXPIRED, (out as EnrollStatus.Failed).message)
        assertNull(store.pending)
        assertEquals("OLD-LONG-LIVED", store.legacyToken, "a failed enrolment must not touch the phone's working login")
        assertEquals("OLD-REFRESH", store.refreshToken)
        assertEquals(1, http.seen.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `an unreachable HA is retried with the SAME request until it answers`() {
        val store = MemPendingStore(pending())
        val http = ScriptedHttp(unreachable, unreachable, success())
        val (r, _) = build(http, store)
        assertEquals(EnrollStatus.Done, r.run())
        assertEquals(3, http.seen.size)
        assertEquals(1, http.seen.toSet().size, "the three requests are byte for byte the same (same nonce)")
        assertTrue(statuses.any { it is EnrollStatus.Retrying && it.why.kind == EnrollStatus.Kind.UNREACHABLE })
        assertEquals(2_000L + 5_000L, sleeps.sum(), "the pauses before the retries: 2 s, then 5 s")
    }

    @Test
    fun `a lost response - the request got through, the answer did not - the same request returns the same token`() {
        val store = MemPendingStore(pending())
        // the server answers the 2nd (identical) request with the SAME refresh token the first one minted
        val http = ScriptedHttp(unreachable, success("SAME-REFRESH"))
        build(http, store).first.run()
        assertEquals("SAME-REFRESH", store.refreshToken)
        assertEquals(http.seen[0], http.seen[1])
    }

    @Test
    fun `it gives up when the code can no longer be good, 12 minutes, and says it could not reach HA`() {
        val store = MemPendingStore(pending())
        val http = ScriptedHttp()
        http.responder = { unreachable }
        val out = build(http, store).first.run()
        assertEquals(EnrollStatus.Failed(EnrollStatus.Kind.UNREACHABLE), out)
        assertEquals(EnrollContract.MSG_UNREACHABLE, (out as EnrollStatus.Failed).message)
        assertNull(store.pending, "the stale record is removed")
        assertTrue(wall.ms - 1_800_000_000_000L <= EnrollContract.PENDING_MAX_AGE_MS + 30_000L, "stopped about when the window closed")
        assertTrue(wall.ms - 1_800_000_000_000L > EnrollContract.PENDING_MAX_AGE_MS, "and not before")
        assertTrue(http.seen.size in 10..60, "growing pauses, not a storm: ${http.seen.size} requests in 12 minutes")
        assertTrue(sleeps.all { it <= 30_000L + 500L })
    }

    @Test
    fun `a pending record older than 12 minutes is dropped without a request`() {
        val store = MemPendingStore(pending(since = wall.ms - EnrollContract.PENDING_MAX_AGE_MS - 1))
        val http = ScriptedHttp()
        assertEquals(EnrollStatus.Failed(EnrollStatus.Kind.INVALID_OR_EXPIRED), build(http, store).first.run())
        assertNull(store.pending)
        assertTrue(http.seen.isEmpty())
    }

    @Test
    fun `exactly 12 minutes old is still tried`() {
        val store = MemPendingStore(pending(since = wall.ms - EnrollContract.PENDING_MAX_AGE_MS))
        val http = ScriptedHttp(success())
        assertEquals(EnrollStatus.Done, build(http, store).first.run())
    }

    @Test
    fun `a record stamped in the future (the clock went backwards) is dropped, not trusted`() {
        val store = MemPendingStore(pending(since = wall.ms + 60_000L))
        val http = ScriptedHttp()
        assertTrue(build(http, store).first.run() is EnrollStatus.Failed)
        assertNull(store.pending)
        assertTrue(http.seen.isEmpty())
    }

    @Test
    fun `a 429 that outlasts the bounded retries stops, keeps the code for a later try and does not retry on its own`() {
        val store = MemPendingStore(pending())
        val http = ScriptedHttp(reply(429, "", "2"), reply(429, "", "2"), reply(429, "", "2"), reply(429, "", "2"))
        val out = build(http, store).first.run()
        assertEquals(EnrollStatus.Failed(EnrollStatus.Kind.RATE_LIMITED), out)
        assertEquals(EnrollContract.MSG_RATE_LIMITED, (out as EnrollStatus.Failed).message)
        assertNotNull(store.pending, "the server did not look at the code: it may still be good")
        assertEquals(4, http.seen.size)
        // a later run (the next start of the app) picks it up again
        val again = ScriptedHttp(success())
        assertEquals(EnrollStatus.Done, build(again, store).first.run())
    }

    @Test
    fun `a 429 then success is one retry after Retry-After`() {
        val http = ScriptedHttp(reply(429, "", "4"), success())
        val store = MemPendingStore(pending())
        assertEquals(EnrollStatus.Done, build(http, store).first.run())
        assertEquals(4_000L, sleeps.sum())
        assertEquals(http.seen[0], http.seen[1])
    }

    @Test
    fun `a 415 ends it at once, nothing is retried`() {
        val store = MemPendingStore(pending())
        val http = ScriptedHttp(reply(415, """{"error":"unsupported_media_type"}"""))
        assertEquals(EnrollStatus.Failed(EnrollStatus.Kind.UNEXPECTED, 415), build(http, store).first.run())
        assertEquals(1, http.seen.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `a 500 or an odd 200 is retried like an unreachable HA`() {
        val store = MemPendingStore(pending())
        val http = ScriptedHttp(reply(502), reply(200, "not a grant"), success())
        assertEquals(EnrollStatus.Done, build(http, store).first.run())
        assertTrue(statuses.any { it is EnrollStatus.Retrying && it.why == EnrollStatus.Failed(EnrollStatus.Kind.UNEXPECTED, 502) })
        assertEquals("Home Assistant answered unexpectedly (HTTP 502). Ask for a new QR in Home Assistant and scan it again.", EnrollStatus.Failed(EnrollStatus.Kind.UNEXPECTED, 502).message)
    }

    @Test
    fun `the nonce survives a process death - a new run from the stored record sends the same nonce`() {
        val store = MemPendingStore(pending("n".repeat(40)))
        val first = ScriptedHttp(unreachable)
        var calls = 0
        // the process dies during the first attempt's pause
        val (r1, _) = build(first, store, sleep = { calls++; if (calls == 1) throw ProcessDied() })
        runCatching { r1.run() }
        assertNotNull(store.pending, "the record is still there")
        val second = ScriptedHttp(success())
        build(second, store).first.run()
        val n1 = Json.parseToJsonElement(first.seen.single().body).jsonObject["nonce"]!!.jsonPrimitive.content
        val n2 = Json.parseToJsonElement(second.seen.single().body).jsonObject["nonce"]!!.jsonPrimitive.content
        assertEquals("n".repeat(40), n1)
        assertEquals(n1, n2, "the SAME nonce after a restart, so a lost answer is returned again")
    }

    private class ProcessDied : RuntimeException()

    @Test
    fun `a newer scan replaces the record - the older run stops at once and saves nothing`() {
        val store = MemPendingStore(pending("a".repeat(30)))
        val http = ScriptedHttp(unreachable)
        var swapped = false
        val (r, _) = build(http, store, sleep = { ms ->
            if (!swapped) { swapped = true; store.pending = pending("b".repeat(30)) }
            wall.advance(ms)
        })
        assertEquals(EnrollStatus.Idle, r.run())
        assertEquals("b".repeat(30), store.pending!!.nonce, "the newer record is untouched")
        assertEquals(0, store.completes)
        assertTrue(store.cleared.isEmpty())
        assertEquals(1, http.seen.size)
    }

    @Test
    fun `a grant that arrives after a newer scan replaced the record is not saved over it`() {
        val store = MemPendingStore(pending("a".repeat(30)))
        val http = ScriptedHttp()
        http.responder = {
            store.pending = pending("b".repeat(30)) // the newer scan lands while the request is out
            success()
        }
        assertEquals(EnrollStatus.Idle, build(http, store).first.run())
        assertNull(store.refreshToken)
        assertEquals("b".repeat(30), store.pending!!.nonce)
    }

    @Test
    fun `a commit that fails is retried with the same request, the code is not lost`() {
        val store = MemPendingStore(pending()).also { it.commitFails = true }
        val http = ScriptedHttp(success(), success())
        var healed = false
        val (r, _) = build(http, store, sleep = { ms -> if (!healed) { healed = true; store.commitFails = false }; wall.advance(ms) })
        assertEquals(EnrollStatus.Done, r.run())
        assertEquals(2, http.seen.size)
        assertEquals(http.seen[0], http.seen[1])
        assertTrue(statuses.any { it is EnrollStatus.Retrying && it.why.kind == EnrollStatus.Kind.CANNOT_SAVE })
    }

    @Test
    fun `an unreadable installation id sends nothing and ends as could-not-save`() {
        val store = MemPendingStore(pending())
        val http = ScriptedHttp()
        val out = build(http, store, install = null).first.run()
        assertEquals(EnrollStatus.Failed(EnrollStatus.Kind.CANNOT_SAVE), out)
        assertTrue(http.seen.isEmpty())
    }

    @Test
    fun `no status text holds the code, the nonce or a token`() {
        val store = MemPendingStore(pending())
        val http = ScriptedHttp(unreachable, success("REFRESH-SECRET", "ACCESS-SECRET"))
        build(http, store).first.run()
        val text = statuses.joinToString("\n") { it.toString() + (it as? EnrollStatus.Failed)?.message }
        for (secret in listOf("REFRESH-SECRET", "ACCESS-SECRET", v.getValue("code"), v.getValue("nonce"))) assertFalse(text.contains(secret))
        assertFalse(pending().toString().contains(v.getValue("code")))
        assertFalse(pending().toString().contains(v.getValue("nonce")))
    }
}
