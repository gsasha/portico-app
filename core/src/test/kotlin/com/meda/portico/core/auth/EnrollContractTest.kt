package com.meda.portico.core.auth

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The vendored `enroll_v1.json` is the server's file, and the app's constants and wording are the file's. */
class EnrollContractTest {

    @Test
    fun `the vendored file is the server's file, byte for byte`() {
        assertEquals(
            EnrollFixture.PINNED_SHA256, EnrollFixture.sha256(),
            "enroll_v1.json drifted from the pinned copy: re-vendor it byte for byte from portico-ha contract/enroll_v1.json " +
                "and change EnrollFixture.PINNED_SHA256 with it",
        )
    }

    @Test
    fun `the file has the shape the replays read`() {
        assertEquals("enroll/v1", EnrollFixture.root["contract"]!!.jsonPrimitive.content)
        assertEquals(EnrollFixture.EXPECTED_CASES, EnrollFixture.cases.size)
        assertEquals(EnrollFixture.cases.size, EnrollFixture.cases.map { it["name"]!!.jsonPrimitive.content }.toSet().size, "case names unique")
        for (c in EnrollFixture.cases) assertEquals(setOf("name", "given", "send", "expect"), c.keys - "content_type", c["name"].toString())
    }

    @Test
    fun `the QR format number is 2 and the file says ha_token is gone`() {
        val qr = EnrollFixture.root["qr"]!!.jsonObject
        assertEquals(2, qr["format"]!!.jsonPrimitive.int)
        assertEquals(listOf("ha_token"), EnrollFixture.strings(qr["removed"]!!.jsonArray))
        assertEquals(EnrollContract.CODE_TTL_MS / 1000, qr["ttl_s"]!!.jsonPrimitive.long)
    }

    @Test
    fun `the redeem path, the body cap, the retry window and the limits are the file's`() {
        val redeem = EnrollFixture.root["redeem"]!!.jsonObject
        assertEquals(redeem["path"]!!.jsonPrimitive.content, EnrollContract.REDEEM_PATH)
        assertEquals("POST", redeem["method"]!!.jsonPrimitive.content)
        assertEquals("application/json", redeem["request_content_type"]!!.jsonPrimitive.content)
        assertEquals(EnrollContract.REPLAY_WINDOW_MS / 1000, redeem["retry"]!!.jsonObject["window_s"]!!.jsonPrimitive.long)
        val rl = redeem["errors"]!!.jsonObject["rate_limited"]!!.jsonObject
        assertEquals("2", rl["headers"]!!.jsonObject["Retry-After"]!!.jsonPrimitive.content, "a throttle: one request in 2 s")
        assertEquals(2, redeem["limits"]!!.jsonObject["throttle_s"]!!.jsonPrimitive.int)
        assertEquals(415, redeem["errors"]!!.jsonObject["unsupported_media_type"]!!.jsonObject["status"]!!.jsonPrimitive.int)
        assertTrue(redeem["request_rules"]!!.jsonPrimitive.content.contains("Content-Type must be application/json"))
        assertTrue(RedeemClient.MAX_RETRIES in 1..5, "the file says up to 5; the coordinator's rule is 3")
        assertEquals(EnrollContract.PENDING_MAX_AGE_MS, EnrollContract.CODE_TTL_MS + EnrollContract.REPLAY_WINDOW_MS)
        // the longest request the app can build is under the server's cap
        val longest = RedeemRequest("c".repeat(128), "e".repeat(128), "i".repeat(128), "n".repeat(64)).toJson()
        assertTrue(longest.toByteArray().size <= redeem["max_body_bytes"]!!.jsonPrimitive.int, "longest body ${longest.length}")
    }

    @Test
    fun `the refresh grant is the file's`() {
        val refresh = EnrollFixture.root["refresh"]!!.jsonObject
        assertEquals(refresh["path"]!!.jsonPrimitive.content, EnrollContract.TOKEN_PATH)
        assertEquals("application/x-www-form-urlencoded", refresh["request_content_type"]!!.jsonPrimitive.content)
        assertEquals(setOf("grant_type", "refresh_token", "client_id"), refresh["form"]!!.jsonObject.keys)
        assertEquals("refresh_token", refresh["form"]!!.jsonObject["grant_type"]!!.jsonPrimitive.content)
        assertEquals(EnrollFixture.clientId, refresh["form"]!!.jsonObject["client_id"]!!.jsonPrimitive.content, "the form carries the redemption's client id")
        assertEquals(5 * 60_000L, EnrollContract.REFRESH_MARGIN_MS, "the file: refresh when under 5 minutes are left")
        assertTrue(refresh["when"]!!.jsonPrimitive.content.contains("5 minutes"))
        assertEquals(90, refresh["refresh_token_idle_expiry_days"]!!.jsonPrimitive.int)
    }

    @Test
    fun `every wording the app shows is the file's wording`() {
        assertEquals(EnrollFixture.message("redeem_invalid_or_expired"), EnrollContract.MSG_INVALID_OR_EXPIRED)
        assertEquals(EnrollFixture.message("redeem_rate_limited"), EnrollContract.MSG_RATE_LIMITED)
        assertEquals(EnrollFixture.message("refresh_signed_out"), EnrollContract.MSG_SIGNED_OUT)
        assertEquals(EnrollFixture.message("unreachable"), EnrollContract.MSG_UNREACHABLE)
        assertEquals(EnrollFixture.message("tailnet_node_gone"), EnrollContract.MSG_TAILNET_NODE_GONE)
        assertEquals(EnrollFixture.message("old_qr_format"), EnrollContract.MSG_OLD_QR_FORMAT)
        assertEquals(EnrollContract.MSG_INVALID_OR_EXPIRED, EnrollStatus.Failed(EnrollStatus.Kind.INVALID_OR_EXPIRED).message)
        assertEquals(EnrollContract.MSG_RATE_LIMITED, EnrollStatus.Failed(EnrollStatus.Kind.RATE_LIMITED).message)
        assertEquals(EnrollContract.MSG_UNREACHABLE, EnrollStatus.Failed(EnrollStatus.Kind.UNREACHABLE).message)
    }

    @Test
    fun `the field shapes accept the fixture values and refuse what the file refuses`() {
        val v = EnrollFixture.values
        assertTrue(EnrollContract.isValidCode(v["code"]))
        assertTrue(EnrollContract.isValidNonce(v["nonce"]))
        assertTrue(EnrollContract.isValidInstallationId(v["installation_id"]))
        assertFalse(EnrollContract.isValidNonce("short"), "the file's too-short nonce")
        assertFalse(EnrollContract.isValidCode("a".repeat(21)))
        assertTrue(EnrollContract.isValidCode("a".repeat(22)))
        assertTrue(EnrollContract.isValidCode("a".repeat(128)))
        assertFalse(EnrollContract.isValidCode("a".repeat(129)))
        assertFalse(EnrollContract.isValidCode("a".repeat(21) + "!"))
        assertFalse(EnrollContract.isValidNonce("n".repeat(65)))
        assertTrue(EnrollContract.isValidInstallationId("0b6f3a52-0000-4000-8000-000000000001"))
        assertFalse(EnrollContract.isValidInstallationId(""))
        assertFalse(EnrollContract.isValidCode(null))
    }

    @Test
    fun `a new nonce has the file's shape and is not repeated`() {
        val seen = HashSet<String>()
        var n = 0
        repeat(50) {
            val nonce = EnrollContract.newNonce { b ->
                for (i in b.indices) b[i] = (n * 7 + i).toByte()
                n++
            }
            assertTrue(EnrollContract.isValidNonce(nonce), nonce)
            seen += nonce
        }
        assertEquals(50, seen.size)
        // 24 random bytes: 48 hex characters, inside 16..64
        assertEquals(48, EnrollContract.newNonce { }.length)
    }

    @Test
    fun `Retry-After is whole seconds clamped to 1 to 60, anything else is the long end`() {
        assertEquals(1, EnrollContract.retryAfterSeconds("1"))
        assertEquals(60, EnrollContract.retryAfterSeconds("60"))
        assertEquals(7, EnrollContract.retryAfterSeconds(" 7 "))
        assertEquals(60, EnrollContract.retryAfterSeconds("3600"), "never waits longer than a minute")
        assertEquals(1, EnrollContract.retryAfterSeconds("0"))
        assertEquals(1, EnrollContract.retryAfterSeconds("-5"))
        assertEquals(60, EnrollContract.retryAfterSeconds(null))
        assertEquals(60, EnrollContract.retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun `the URLs follow the enrolled address, path prefix included, and refuse what the URL policy refuses`() {
        assertEquals("https://ha.example.com/api/portico/enroll/redeem", EnrollContract.urlFor("https://ha.example.com", EnrollContract.REDEEM_PATH))
        assertEquals("https://ha.example.com/ha/auth/token", EnrollContract.urlFor(" https://ha.example.com/ha/ ", EnrollContract.TOKEN_PATH))
        assertEquals("http://192.168.1.5:8123/auth/token", EnrollContract.urlFor("http://192.168.1.5:8123", EnrollContract.TOKEN_PATH), "plain http on a private address")
        assertNull(EnrollContract.urlFor("http://ha.example.com", EnrollContract.TOKEN_PATH), "plain http to a public name")
        assertNull(EnrollContract.urlFor(null, EnrollContract.TOKEN_PATH))
        assertNull(EnrollContract.urlFor("", EnrollContract.TOKEN_PATH))
        assertNull(EnrollContract.urlFor("ftp://ha.example.com", EnrollContract.TOKEN_PATH))
        assertNotEquals(EnrollContract.REDEEM_PATH, EnrollContract.TOKEN_PATH)
    }
}
