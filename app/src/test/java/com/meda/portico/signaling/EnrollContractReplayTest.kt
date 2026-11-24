package com.meda.portico.signaling

import com.meda.portico.core.auth.AccessTokens
import com.meda.portico.core.auth.EnrollContract
import com.meda.portico.core.auth.EnrollStatus
import com.meda.portico.core.auth.EnrollmentRedemption
import com.meda.portico.core.auth.RedeemClient
import com.meda.portico.core.auth.RedeemOutcome
import com.meda.portico.core.auth.RedeemRequest
import com.meda.portico.core.auth.RefreshClient
import com.meda.portico.core.enroll.SetupPlan
import com.meda.portico.core.enroll.SetupResult
import com.meda.portico.store.FakeSharedPreferences
import com.meda.portico.store.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.TimeUnit

/**
 * DEV-544: the app's client against the server's recorded exchange. MockWebServer plays Home Assistant; the real [OkHttpPost]
 * and [RedeemClient] / [RefreshClient] / [EnrollmentRedemption] talk to it. Every entry of `enroll_v1.json`'s `cases` is
 * replayed: the server's answer the entry records is what the fake sends, and the app must turn it into the outcome the
 * contract names. The request the app builds is compared with the entry's `send`.
 */
class EnrollContractReplayTest {
    private lateinit var server: MockWebServer
    private val v = EnrollContractFile.values
    private val request = RedeemRequest(v.getValue("code"), v.getValue("enroll_id"), v.getValue("installation_id"), v.getValue("nonce"))

    @BeforeEach
    fun up() {
        server = MockWebServer()
        server.start(java.net.InetAddress.getLoopbackAddress(), 0)
    }

    @AfterEach
    fun down() {
        server.shutdown()
    }

    private fun haUrl() = server.url("/").toString().trimEnd('/')
    private fun client() = RedeemClient(OkHttpPost())

    companion object {
        @JvmStatic
        fun caseNames(): List<String> = EnrollContractFile.cases.map { EnrollContractFile.name(it) }
    }

    @Test
    fun `the replay runs against the server's file - the pin`() {
        assertEquals(EnrollContractFile.PINNED_SHA256, EnrollContractFile.sha256())
        assertEquals(12, EnrollContractFile.cases.size)
    }

    /** Serve one response from a case's `expect`: its status, body and Retry-After. */
    private fun serve(expect: JsonObject, refreshToken: String = "R".repeat(128)) {
        val status = expect["status"]!!.jsonPrimitive.content.toInt()
        val body = when (status) {
            200 -> {
                val b = EnrollContractFile.successBody(refreshToken)
                assertEquals(expect["body_keys"]!!.jsonArray.map { it.jsonPrimitive.content }.sorted(), Json.parseToJsonElement(b).jsonObject.keys.sorted(), "the 200 body has the keys the case names")
                b
            }
            else -> expect["body"]!!.toString()
        }
        val r = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
        if (status == 200) r.setHeader("Cache-Control", "no-store")
        expect["retry_after"]?.let { r.setHeader("Retry-After", it.jsonPrimitive.content) }
        server.enqueue(r)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("caseNames")
    fun `every recorded case - the app sends the file's request and reads the file's answer`(name: String) {
        val case = EnrollContractFile.case(name)
        val expect = case["expect"]!!.jsonObject
        val status = expect["status"]!!.jsonPrimitive.content.toInt()
        val lost = case["given"]!!.jsonPrimitive.content == "redeemed_by_this_phone"
        val waits = ArrayList<Long>()

        if (lost) {
            // the response to the first request was lost on the way; the app's SAME request comes again and gets the same token
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        }
        serve(expect, "SAME-REFRESH-TOKEN")
        if (status == 429) repeat(RedeemClient.MAX_RETRIES) { serve(expect) } // the throttle outlasts every retry

        var out = client().redeem(haUrl(), request) { waits += it; true }
        if (lost) {
            assertEquals(RedeemOutcome.Unreachable, out, "a lost response is not a refusal")
            out = client().redeem(haUrl(), request) { true }
        }

        when (status) {
            200 -> {
                val g = (out as RedeemOutcome.Granted).grant
                if (expect["same_refresh_token"]?.jsonPrimitive?.content == "true") assertEquals("SAME-REFRESH-TOKEN", g.refreshToken)
                assertEquals(EnrollContractFile.clientId, g.clientId)
                assertEquals(86400L, g.expiresInS)
            }
            400 -> assertEquals(RedeemOutcome.InvalidOrExpired, out)
            429 -> {
                assertInstanceOf(RedeemOutcome.RateLimited::class.java, out)
                assertEquals(RedeemClient.MAX_RETRIES, waits.size, "bounded retries")
                assertTrue(waits.all { it == 2_000L }, "each retry waits the header's 2 s: $waits")
                assertEquals(RedeemClient.MAX_RETRIES + 1, server.requestCount)
            }
            415 -> assertEquals(RedeemOutcome.Unexpected(415), out)
            else -> throw AssertionError("a case with status $status")
        }

        // the request(s) the app sent
        val sent = (0 until server.requestCount).map { server.takeRequest(1, TimeUnit.SECONDS)!! }
        val bodies = sent.map { it.body.readByteArray().toList() }
        if (lost || status == 429) assertEquals(1, bodies.toSet().size, "every retry is the same bytes (same nonce)")
        for ((i, r) in sent.withIndex()) {
            assertEquals("POST", r.method)
            assertEquals(EnrollContract.REDEEM_PATH, r.path)
            assertNull(r.getHeader("Authorization"), "the phone holds no token yet: no Authorization header")
            assertTrue(r.getHeader("Content-Type")!!.startsWith("application/json"), "the case 'not application/json' (text/plain) is one the app never sends")
            for (h in listOf("X-Forwarded-For", "Forwarded", "X-Real-IP")) assertNull(r.getHeader(h), "the file: an app should send none of these")
            val bytes = bodies[i].toByteArray()
            assertTrue(bytes.size <= 640, "the server's body cap")
            val body = Json.parseToJsonElement(String(bytes)).jsonObject
            assertEquals(listOf("code", "enroll_id", "installation_id", "nonce"), body.keys.toList())
            val fixture = EnrollContractFile.resolvedSend(case)
            val standard = fixture.keys == body.keys && fixture["nonce"] == v["nonce"] && fixture["code"] == v["code"] && fixture["enroll_id"] == v["enroll_id"]
            if (standard) assertEquals(fixture, body.mapValues { it.value.jsonPrimitive.content }, "the request the file records")
        }
    }

    @Test
    fun `the two cases the app cannot produce - an extra key, a short nonce - are ones its builder never makes`() {
        assertEquals("text/plain", EnrollContractFile.case("not application/json")["content_type"]!!.jsonPrimitive.content)
        for (n in listOf("an extra key", "a nonce that is too short")) {
            val bad = EnrollContractFile.resolvedSend(EnrollContractFile.case(n))
            assertTrue(bad.size > 4 || !EnrollContract.isValidNonce(bad["nonce"]), n)
        }
        assertEquals(4, Json.parseToJsonElement(request.toJson()).jsonObject.size)
        repeat(20) { assertTrue(EnrollContract.isValidNonce(EnrollContract.newNonce(java.security.SecureRandom()::nextBytes))) }
    }

    @Test
    fun `a lost response - the SAME bytes go out again and the app ends up with the same token (through the redemption)`() {
        val prefs = FakeSharedPreferences()
        val settings = Settings(prefs)
        val qr = """{"portico":2,"ha_url":"${haUrl()}","code":"${v["code"]}","phone_name":"Pixel","enroll_id":"${v["enroll_id"]}"}"""
        assertTrue(settings.apply(SetupPlan.evaluate(qr, "").let { it as SetupResult.Ok }, nowMs = 1_800_000_000_000L, nonce = v["nonce"]!!))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setResponseCode(200).setBody(EnrollContractFile.successBody("THE-REFRESH-TOKEN", "THE-ACCESS-TOKEN")))
        val wall = java.util.concurrent.atomic.AtomicLong(1_800_000_000_000L)
        val tokens = AccessTokens(settings.loginStore, RefreshClient(OkHttpPost()), { settings.haUrl }, { 0L })
        val statuses = ArrayList<EnrollStatus>()
        val redemption = EnrollmentRedemption(
            settings.pendingStore, RedeemClient(OkHttpPost()), tokens, { v["installation_id"] }, { wall.get() },
            sleep = { wall.addAndGet(it) }, onStatus = { statuses += it },
        )
        assertEquals(EnrollStatus.Done, redemption.run())
        val a = server.takeRequest(1, TimeUnit.SECONDS)!!
        val b = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(a.body.readUtf8(), b.body.readUtf8(), "the same bytes - same nonce")
        assertEquals("THE-REFRESH-TOKEN", settings.refreshToken)
        assertEquals(EnrollContractFile.clientId, settings.clientId)
        assertNull(settings.pendingStore.load())
        assertEquals("THE-ACCESS-TOKEN", tokens.cached(), "the redemption's access token is used for the first connect")
        assertTrue(statuses.any { it is EnrollStatus.Retrying })
    }

    @Test
    fun `the refresh grant - form body, content type, client id, and the answers`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"access_token":"ACC-1","token_type":"Bearer","expires_in":86400}"""))
        val out = RefreshClient(OkHttpPost()).refresh(haUrl(), "the-refresh-token", EnrollContractFile.clientId)
        assertEquals(com.meda.portico.core.auth.RefreshOutcome.Ok("ACC-1", 86400), out)
        val r = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", r.method)
        assertEquals("/auth/token", r.path)
        assertTrue(r.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertNull(r.getHeader("Authorization"))
        assertEquals(
            "grant_type=refresh_token&refresh_token=the-refresh-token&client_id=https%3A%2F%2Fportico.invalid%2Fphone",
            r.body.readUtf8(),
        )
        val refresh = EnrollContractFile.root["refresh"]!!.jsonObject
        assertEquals(refresh["form"]!!.jsonObject["client_id"]!!.jsonPrimitive.content, EnrollContractFile.clientId)
    }

    @Test
    fun `the refresh grant - invalid_grant is signed out, invalid_request and a 5xx are not`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_request"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        val c = RefreshClient(OkHttpPost())
        assertEquals(com.meda.portico.core.auth.RefreshOutcome.SignedOut, c.refresh(haUrl(), "r", "c"))
        assertEquals(com.meda.portico.core.auth.RefreshOutcome.Failed(400), c.refresh(haUrl(), "r", "c"))
        assertEquals(com.meda.portico.core.auth.RefreshOutcome.Failed(503), c.refresh(haUrl(), "r", "c"))
        server.shutdown()
        assertEquals(com.meda.portico.core.auth.RefreshOutcome.Unreachable, c.refresh(haUrl(), "r", "c"), "a dead server is unreachable, not signed out")
    }
}
