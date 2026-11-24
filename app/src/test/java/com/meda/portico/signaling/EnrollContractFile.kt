package com.meda.portico.signaling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * The app module's reader for core's vendored `enroll_v1.json` (the server's `contract/enroll_v1.json`, DEV-544). The file
 * lives once, in `core/src/test/resources`; `app/build.gradle.kts` declares it as the unit-test task's input and passes its
 * path as `portico.enrollContract`. The sha256 is pinned here too (core's `EnrollContractTest` pins it as well), so a replay
 * can never run against a file that drifted from the server's.
 */
object EnrollContractFile {
    const val PINNED_SHA256 = "9cd7ed7ada9a3c7bae5ecd90ea5f603a0059c22b1b663fc717adb406729d4335"
    private const val RELATIVE = "core/src/test/resources/enroll_v1.json"

    val file: File by lazy {
        System.getProperty("portico.enrollContract")?.let { File(it) }?.takeIf { it.isFile }?.let { return@lazy it }
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, RELATIVE)
            if (f.isFile) return@lazy f
            dir = dir.parentFile
        }
        throw AssertionError("$RELATIVE not found above ${System.getProperty("user.dir")}")
    }

    fun sha256(): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    val root: JsonObject by lazy { Json.parseToJsonElement(file.readText()).jsonObject }

    val cases: List<JsonObject> by lazy { root["cases"]!!.jsonArray.map { it.jsonObject } }

    val values: Map<String, String> by lazy { root["fixture_values"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content } }

    fun case(name: String): JsonObject = cases.first { it["name"]!!.jsonPrimitive.content == name }

    fun name(c: JsonObject): String = c["name"]!!.jsonPrimitive.content

    /** A case's `send` with `$code` style references replaced by the fixture values. */
    fun resolvedSend(c: JsonObject): Map<String, String> = c["send"]!!.jsonObject.mapValues { (_, v) ->
        val t = v.jsonPrimitive.content
        if (t.startsWith("$")) values.getValue(t.substring(1)) else t
    }

    val clientId: String get() = root["redeem"]!!.jsonObject["success"]!!.jsonObject["body"]!!.jsonObject["client_id"]!!.jsonPrimitive.content

    /** A 200 body of the shape the contract names (its keys are checked against the case's `body_keys`). */
    fun successBody(refreshToken: String, accessToken: String = "fake-jwt-header.fixture-access.SIG", expiresIn: Long = 86400): String =
        JsonObject(
            mapOf(
                "refresh_token" to JsonPrimitive(refreshToken),
                "access_token" to JsonPrimitive(accessToken),
                "token_type" to JsonPrimitive("Bearer"),
                "expires_in" to JsonPrimitive(expiresIn),
                "client_id" to JsonPrimitive(clientId),
            ),
        ).toString()
}
