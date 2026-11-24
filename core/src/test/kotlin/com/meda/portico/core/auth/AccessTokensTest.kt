package com.meda.portico.core.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URLDecoder

class RefreshClientTest {
    private val url = "https://ha.example.com"
    private val cid = EnrollFixture.clientId
    private fun ok(token: String = "acc-1", expires: Long = 86400) = reply(200, """{"access_token":"$token","token_type":"Bearer","expires_in":$expires}""")

    @Test
    fun `the request is HA's refresh grant, form-encoded, with the client id`() {
        val http = ScriptedHttp(ok())
        assertEquals(RefreshOutcome.Ok("acc-1", 86400), RefreshClient(http).refresh(url, "r/e+f=resh&token", cid))
        val seen = http.seen.single()
        assertEquals("https://ha.example.com/auth/token", seen.url)
        assertEquals("application/x-www-form-urlencoded", seen.contentType)
        val pairs = seen.body.split("&").associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
        assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "r/e+f=resh&token", "client_id" to cid), pairs)
        assertEquals(3, seen.body.split("&").size, "a hostile token cannot add a parameter")
        assertTrue(seen.body.contains("client_id="), "HA 2026.10 answers a refresh grant without client_id 400 invalid_request")
    }

    @Test
    fun `invalid_grant (400) and access_denied (403) are signed out`() {
        assertEquals(RefreshOutcome.SignedOut, RefreshClient(ScriptedHttp(reply(400, """{"error":"invalid_grant"}"""))).refresh(url, "r", cid))
        assertEquals(RefreshOutcome.SignedOut, RefreshClient(ScriptedHttp(reply(403, """{"error":"access_denied"}"""))).refresh(url, "r", cid))
    }

    @Test
    fun `a 400 that is not invalid_grant never signs the phone out`() {
        assertEquals(RefreshOutcome.Failed(400), RefreshClient(ScriptedHttp(reply(400, """{"error":"invalid_request"}"""))).refresh(url, "r", cid))
        assertEquals(RefreshOutcome.Failed(400), RefreshClient(ScriptedHttp(reply(400, ""))).refresh(url, "r", cid))
        assertEquals(RefreshOutcome.Failed(400), RefreshClient(ScriptedHttp(reply(400, "<html>proxy</html>"))).refresh(url, "r", cid))
        assertEquals(RefreshOutcome.Failed(403), RefreshClient(ScriptedHttp(reply(403, "forbidden by the proxy"))).refresh(url, "r", cid))
    }

    @Test
    fun `a network failure is unreachable, other statuses keep the tokens`() {
        assertEquals(RefreshOutcome.Unreachable, RefreshClient(ScriptedHttp(unreachable)).refresh(url, "r", cid))
        for (st in listOf(404, 429, 500, 502, 503)) assertEquals(RefreshOutcome.Failed(st), RefreshClient(ScriptedHttp(reply(st, "{}"))).refresh(url, "r", cid), st.toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "{}", "nope", """{"access_token":"","expires_in":5}""", """{"access_token":"a"}""", """{"access_token":"a","expires_in":0}""", """{"access_token":"a","expires_in":"5"}"""])
    fun `a 200 that is not a token is a failure, never a cached token`(body: String) {
        assertEquals(RefreshOutcome.Failed(200), RefreshClient(ScriptedHttp(reply(200, body))).refresh(url, "r", cid))
    }

    @Test
    fun `an address the policy refuses sends nothing`() {
        val http = ScriptedHttp()
        assertEquals(RefreshOutcome.Failed(0), RefreshClient(http).refresh("http://ha.example.com", "r", cid))
        assertTrue(http.seen.isEmpty())
    }
}

class AccessTokensTest {
    private val url = "https://ha.example.com"
    private val clock = FakeClock()
    private val store = MemLoginStore()
    private fun ok(token: String, expires: Long = 86400) = reply(200, """{"access_token":"$token","token_type":"Bearer","expires_in":$expires}""")
    private fun tokens(http: ScriptedHttp, s: MemLoginStore = store) = AccessTokens(s, RefreshClient(http), { url }, clock)

    @Test
    fun `no token yet - obtain asks HA once, then the token is served from memory`() {
        val http = ScriptedHttp(ok("acc-1"))
        val t = tokens(http)
        assertNull(t.cached())
        assertEquals(AccessTokens.Result.Token("acc-1"), t.obtain())
        assertEquals("acc-1", t.cached())
        assertEquals(AccessTokens.Result.Token("acc-1"), t.obtain())
        assertEquals(1, http.seen.size, "a second obtain with a fresh token makes no request")
    }

    @Test
    fun `the 5 minute boundary - exactly 5 minutes left is still used, one millisecond less is refreshed`() {
        val http = ScriptedHttp(ok("acc-1", 3600), ok("acc-2", 3600))
        val t = tokens(http)
        t.obtain()
        clock.advance(3_600_000L - 5 * 60_000L) // exactly 5:00.000 left
        assertEquals("acc-1", t.cached(), "5:00.000 left is enough")
        assertEquals(AccessTokens.Result.Token("acc-1"), t.obtain())
        assertEquals(1, http.seen.size)
        clock.advance(1) // 4:59.999 left
        assertNull(t.cached(), "under 5 minutes left")
        assertEquals(AccessTokens.Result.Token("acc-2"), t.obtain())
        assertEquals(2, http.seen.size)
    }

    @Test
    fun `a token with a lifetime of under 5 minutes is never served from memory`() {
        val http = ScriptedHttp(ok("short", 299), ok("short-2", 299))
        val t = tokens(http)
        t.obtain()
        assertNull(t.cached())
        t.obtain()
        assertEquals(2, http.seen.size)
    }

    @Test
    fun `a seeded token (the one the redemption returned) saves the first refresh`() {
        val http = ScriptedHttp()
        val t = tokens(http)
        t.seed("first", 86400)
        assertEquals(AccessTokens.Result.Token("first"), t.obtain())
        assertTrue(http.seen.isEmpty())
        clock.advance(24 * 3_600_000L)
        assertNull(t.cached(), "after 24 hours the seeded token is dead")
    }

    @Test
    fun `invalidate forces the next obtain to ask HA`() {
        val http = ScriptedHttp(ok("acc-2"))
        val t = tokens(http)
        t.seed("first", 86400)
        t.invalidate()
        assertNull(t.cached())
        assertEquals(AccessTokens.Result.Token("acc-2"), t.obtain())
    }

    @Test
    fun `invalid_grant signs the phone out once and the cache is gone`() {
        val http = ScriptedHttp(reply(400, """{"error":"invalid_grant"}"""))
        val t = tokens(http)
        t.seed("old", 86400)
        t.invalidate()
        assertEquals(AccessTokens.Result.SignedOut, t.obtain())
        assertEquals(1, store.signedOut)
        assertNull(t.cached())
        // with nothing stored any more, obtain asks nobody
        assertEquals(AccessTokens.Result.SignedOut, t.obtain())
        assertEquals(1, http.seen.size, "no request without a refresh token: no loop")
        assertEquals(1, store.signedOut)
    }

    @Test
    fun `an unreachable HA or an odd answer keeps the stored login`() {
        val http = ScriptedHttp(unreachable, reply(503), reply(400, """{"error":"invalid_request"}"""), ok("acc-1"))
        val t = tokens(http)
        assertEquals(AccessTokens.Result.Unreachable, t.obtain())
        assertEquals(AccessTokens.Result.Failed(503), t.obtain())
        assertEquals(AccessTokens.Result.Failed(400), t.obtain())
        assertEquals(0, store.signedOut)
        assertEquals("refresh-AAAA", store.refresh)
        assertEquals(AccessTokens.Result.Token("acc-1"), t.obtain())
    }

    @Test
    fun `the request carries the stored refresh token and the stored client id`() {
        val http = ScriptedHttp(ok("a"))
        store.refresh = "the-refresh-token"
        store.client = "https://portico.invalid/phone"
        tokens(http).obtain()
        assertTrue(http.seen.single().body.contains("refresh_token=the-refresh-token"))
        assertTrue(http.seen.single().body.contains("client_id=https%3A%2F%2Fportico.invalid%2Fphone"))
    }

    @Test
    fun `a login replaced while the request was out is neither cached nor signed out`() {
        val s = MemLoginStore(refresh = "old-refresh")
        val http = ScriptedHttp()
        http.responder = {
            s.refresh = "new-refresh" // a re-enrolment lands while the request is in flight
            reply(400, """{"error":"invalid_grant"}""")
        }
        val t = tokens(http, s)
        assertEquals(AccessTokens.Result.Unreachable, t.obtain())
        assertEquals(0, s.signedOut, "the NEW login must not be signed out by the OLD one's refusal")
        assertEquals("new-refresh", s.refresh)

        http.responder = { s.refresh = "newer"; ok("stale") }
        assertEquals(AccessTokens.Result.Unreachable, t.obtain())
        assertNull(t.cached(), "an access token minted for the old login is not kept")
    }

    @Test
    fun `no refresh token or no client id is signed out without a request`() {
        val http = ScriptedHttp()
        assertEquals(AccessTokens.Result.SignedOut, tokens(http, MemLoginStore(refresh = null)).obtain())
        assertEquals(AccessTokens.Result.SignedOut, tokens(http, MemLoginStore(client = null)).obtain())
        assertTrue(http.seen.isEmpty())
        assertFalse(tokens(http, MemLoginStore(client = " ")).enrolled())
        assertTrue(tokens(http).enrolled())
    }

    @Test
    fun `the access token is in memory only and never printed`() {
        val t = tokens(ScriptedHttp(ok("SECRET-ACCESS")))
        val r = t.obtain()
        assertFalse(t.toString().contains("SECRET-ACCESS"))
        assertFalse(r.toString().contains("SECRET-ACCESS"))
        assertFalse(store.toString().contains("SECRET-ACCESS"))
        // a second AccessTokens over the same stored login (a new process) starts empty
        assertNull(tokens(ScriptedHttp()).cached())
    }

    @Test
    fun `two threads asking at once cause one request`() {
        val http = ScriptedHttp()
        val gate = java.util.concurrent.CountDownLatch(1)
        http.responder = {
            gate.await(2, java.util.concurrent.TimeUnit.SECONDS)
            ok("only-one")
        }
        val t = tokens(http)
        val results = java.util.concurrent.CopyOnWriteArrayList<AccessTokens.Result>()
        val threads = List(3) { Thread { results += t.obtain() }.also { it.start() } }
        Thread.sleep(100)
        gate.countDown()
        threads.forEach { it.join(3000) }
        assertEquals(3, results.size)
        assertTrue(results.all { it == AccessTokens.Result.Token("only-one") })
        assertEquals(1, http.seen.size)
    }

    @Test
    fun `cached never waits for a refresh that is in flight`() {
        val http = ScriptedHttp()
        val gate = java.util.concurrent.CountDownLatch(1)
        http.responder = {
            gate.await(3, java.util.concurrent.TimeUnit.SECONDS)
            ok("late")
        }
        val t = tokens(http)
        val th = Thread { t.obtain() }.also { it.start() }
        Thread.sleep(100)
        val start = System.nanoTime()
        assertNull(t.cached())
        assertTrue(System.nanoTime() - start < 500_000_000L, "cached() answered while the refresh was out")
        gate.countDown()
        th.join(3000)
    }
}
