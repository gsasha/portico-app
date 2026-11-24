package com.meda.portico.core.auth

import com.meda.portico.core.Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * The vendored `contract/enroll_v1.json`: a byte copy of portico-ha's file (server PR #143, branch
 * `dev544/one-time-enrollment-code`, head `976c77c`). The server replays every entry of `cases` against its real view; the
 * app's tests (here and the MockWebServer replay in `:app`) drive the client from the same entries.
 */
object EnrollFixture {
    const val RESOURCE = "/enroll_v1.json"

    /** sha256 of the vendored file. Re-vendor it byte for byte from the server and change this pin together (docs/architecture/enrolment-and-login.md). */
    const val PINNED_SHA256 = "9cd7ed7ada9a3c7bae5ecd90ea5f603a0059c22b1b663fc717adb406729d4335"
    const val EXPECTED_CASES = 12

    fun bytes(): ByteArray =
        requireNotNull(EnrollFixture::class.java.getResourceAsStream(RESOURCE)) { "$RESOURCE not on the test classpath" }.use { it.readBytes() }

    fun sha256(): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes()))

    val root: JsonObject by lazy { Json.parseToJsonElement(String(bytes(), Charsets.UTF_8)).jsonObject }

    val values: Map<String, String> by lazy {
        root["fixture_values"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    val cases: List<JsonObject> by lazy { root["cases"]!!.jsonArray.map { it.jsonObject } }

    fun message(key: String): String = root["messages"]!!.jsonObject[key]!!.jsonPrimitive.content

    /** A case's `send` with `$code` style references replaced by the fixture values. */
    fun resolvedSend(case: JsonObject): Map<String, String> =
        case["send"]!!.jsonObject.mapValues { (_, v) ->
            val t = v.jsonPrimitive.content
            if (t.startsWith("$")) values.getValue(t.substring(1)) else t
        }

    /** The request the app builds from the fixture's phone values (`$code`, `$enroll_id`, `$installation_id`, `$nonce`). */
    fun appRequest(): RedeemRequest =
        RedeemRequest(values.getValue("code"), values.getValue("enroll_id"), values.getValue("installation_id"), values.getValue("nonce"))

    /** A 200 body of the shape the contract names; the values are fixtures. */
    fun successBody(refreshToken: String = "f".repeat(128), accessToken: String = "fake-jwt-header.fixture-access.SIG", expiresIn: Long = 86400): String {
        val s = root["redeem"]!!.jsonObject["success"]!!.jsonObject["body"]!!.jsonObject
        return JsonObject(
            mapOf(
                "refresh_token" to JsonPrimitive(refreshToken),
                "access_token" to JsonPrimitive(accessToken),
                "token_type" to s["token_type"]!!,
                "expires_in" to JsonPrimitive(expiresIn),
                "client_id" to s["client_id"]!!,
            ),
        ).toString()
    }

    val clientId: String get() = root["redeem"]!!.jsonObject["success"]!!.jsonObject["body"]!!.jsonObject["client_id"]!!.jsonPrimitive.content

    fun strings(a: JsonArray): List<String> = a.map { it.jsonPrimitive.content }
}
