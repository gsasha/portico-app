package com.meda.portico.core.auth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RedeemClientTest {
    private val url = "https://ha.example.com"
    private val req = EnrollFixture.appRequest()
    private fun granted(o: RedeemOutcome) = (o as RedeemOutcome.Granted).grant

    @Test
    fun `the request is the file's four fields, nothing else, to the redeem path`() {
        val http = ScriptedHttp(reply(200, EnrollFixture.successBody()))
        RedeemClient(http).redeem(url, req)
        val seen = http.seen.single()
        assertEquals("https://ha.example.com/api/portico/enroll/redeem", seen.url)
        assertEquals("application/json", seen.contentType)
        val sent = Json.parseToJsonElement(seen.body).jsonObject
        assertEquals(listOf("code", "enroll_id", "installation_id", "nonce"), sent.keys.toList(), "exactly these keys, in this order")
        val v = EnrollFixture.values
        assertEquals(
            mapOf("code" to v["code"], "enroll_id" to v["enroll_id"], "installation_id" to v["installation_id"], "nonce" to v["nonce"]),
            sent.mapValues { it.value.jsonPrimitive.content },
        )
        assertFalse(seen.body.contains(" "), "compact JSON")
    }

    @Test
    fun `a 200 is a grant with the refresh token, the first access token, its lifetime and the client id`() {
        val g = granted(RedeemClient(ScriptedHttp(reply(200, EnrollFixture.successBody("r".repeat(128), "acc.ess.tok", 86400)))).redeem(url, req))
        assertEquals("r".repeat(128), g.refreshToken)
        assertEquals("acc.ess.tok", g.accessToken)
        assertEquals(86400L, g.expiresInS)
        assertEquals(EnrollFixture.clientId, g.clientId)
    }

    @Test
    fun `a 400 is final whatever its body says`() {
        for (body in listOf("""{"error":"invalid_or_expired"}""", "", "<html>", """{"error":"something_else"}""")) {
            assertEquals(RedeemOutcome.InvalidOrExpired, RedeemClient(ScriptedHttp(reply(400, body))).redeem(url, req), body)
        }
    }

    @Test
    fun `a 429 waits Retry-After and sends the SAME request once more`() {
        val http = ScriptedHttp(reply(429, """{"error":"rate_limited"}""", "7"), reply(200, EnrollFixture.successBody()))
        val waits = ArrayList<Long>()
        val out = RedeemClient(http).redeem(url, req) { waits += it; true }
        assertInstanceOf(RedeemOutcome.Granted::class.java, out)
        assertEquals(listOf(7_000L), waits)
        assertEquals(2, http.seen.size)
        assertEquals(http.seen[0], http.seen[1], "the retry is byte for byte the first request (same nonce)")
    }

    @Test
    fun `a 429 that never ends is retried 3 times, each after Retry-After, then rate limited - never a tight loop`() {
        val http = ScriptedHttp(reply(429, "", "3600"), reply(429, "", "2"), reply(429, "", "2"), reply(429, "", "5"))
        val waits = ArrayList<Long>()
        val out = RedeemClient(http).redeem(url, req) { waits += it; true }
        assertEquals(RedeemOutcome.RateLimited(5), out)
        assertEquals(listOf(60_000L, 2_000L, 2_000L), waits, "3600 s is clamped to the 60 s end; every retry waits")
        assertEquals(4, http.seen.size, "the first try and 3 retries, no more")
        assertEquals(1, http.seen.toSet().size, "all the same bytes")
    }

    @Test
    fun `a throttle that lets the third retry through succeeds`() {
        val http = ScriptedHttp(reply(429, "", "2"), reply(429, "", "2"), reply(429, "", "2"), reply(200, EnrollFixture.successBody()))
        assertInstanceOf(RedeemOutcome.Granted::class.java, RedeemClient(http).redeem(url, req))
        assertEquals(4, http.seen.size)
    }

    @Test
    fun `every retry waits at least a second, whatever the header says`() {
        val http = ScriptedHttp(reply(429, "", "0"), reply(429, "", "-3"), reply(429, "", "x"), reply(429, "", "0"))
        val waits = ArrayList<Long>()
        RedeemClient(http).redeem(url, req) { waits += it; true }
        assertTrue(waits.all { it >= 1_000L }, waits.toString())
    }

    @Test
    fun `a 415 is an unexpected answer`() {
        assertEquals(RedeemOutcome.Unexpected(415), RedeemClient(ScriptedHttp(reply(415, """{"error":"unsupported_media_type"}"""))).redeem(url, req))
    }

    @Test
    fun `a 429 whose wait was cancelled sends nothing more`() {
        val http = ScriptedHttp(reply(429, "", "2"))
        assertEquals(RedeemOutcome.Cancelled, RedeemClient(http).redeem(url, req) { false })
        assertEquals(1, http.seen.size)
    }

    @Test
    fun `no path completing the request is unreachable, not a refusal`() {
        assertEquals(RedeemOutcome.Unreachable, RedeemClient(ScriptedHttp(unreachable)).redeem(url, req))
        assertEquals(RedeemOutcome.Unreachable, RedeemClient(ScriptedHttp(PathsFailedException(listOf(com.meda.portico.core.ha.LinkPath.DIRECT)))).redeem(url, req))
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403, 404, 405, 500, 502, 503])
    fun `any other status is an unexpected answer that keeps the code (retryable), never a refusal`(status: Int) {
        assertEquals(RedeemOutcome.Unexpected(status), RedeemClient(ScriptedHttp(reply(status, "x"))).redeem(url, req))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "not json", "[]", "{}",
            """{"refresh_token":"","access_token":"a","expires_in":1,"client_id":"c"}""",
            """{"refresh_token":"r","access_token":"","expires_in":1,"client_id":"c"}""",
            """{"refresh_token":"r","access_token":"a","expires_in":1,"client_id":""}""",
            """{"refresh_token":"r","access_token":"a","expires_in":0,"client_id":"c"}""",
            """{"refresh_token":"r","access_token":"a","expires_in":"86400","client_id":"c"}""",
            """{"refresh_token":1,"access_token":"a","expires_in":1,"client_id":"c"}""",
            """{"access_token":"a","expires_in":1,"client_id":"c"}""",
            """{"refresh_token":"r","access_token":"a","expires_in":1}""",
        ],
    )
    fun `a 200 that is not a grant is unexpected (the same request can be sent again), never a stored login`(body: String) {
        assertEquals(RedeemOutcome.Unexpected(200), RedeemClient(ScriptedHttp(reply(200, body))).redeem(url, req))
    }

    @Test
    fun `an address the URL policy refuses sends nothing`() {
        val http = ScriptedHttp()
        for (bad in listOf(null, "", "http://ha.example.com", "ftp://x")) {
            assertEquals(RedeemOutcome.Unexpected(0), RedeemClient(http).redeem(bad, req), bad.toString())
        }
        assertTrue(http.seen.isEmpty())
    }

    @Test
    fun `nothing the request or the grant prints holds a secret`() {
        val g = granted(RedeemClient(ScriptedHttp(reply(200, EnrollFixture.successBody("REFRESH-SECRET-1", "ACCESS-SECRET-2", 5)))).redeem(url, req))
        for (s in listOf(g.toString(), req.toString(), RedeemOutcome.Granted(g).toString())) {
            for (secret in listOf("REFRESH-SECRET-1", "ACCESS-SECRET-2", EnrollFixture.values.getValue("code"), EnrollFixture.values.getValue("nonce"), EnrollFixture.clientId)) {
                assertFalse(s.contains(secret), "$s leaks $secret")
            }
        }
        assertFalse(reply(200, "REFRESH-SECRET-1").toString().contains("REFRESH-SECRET-1"))
    }

    @Test
    fun `the request is a JsonObject the server's regexes accept`() {
        val o: JsonObject = Json.parseToJsonElement(req.toJson()).jsonObject
        assertTrue(EnrollContract.isValidCode(o["code"]!!.jsonPrimitive.content))
        assertTrue(EnrollContract.isValidNonce(o["nonce"]!!.jsonPrimitive.content))
        assertTrue(EnrollContract.isValidInstallationId(o["installation_id"]!!.jsonPrimitive.content))
    }
}
