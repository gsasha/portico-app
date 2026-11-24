package com.meda.portico.core.enroll

import com.meda.portico.core.push.Redact
import com.meda.portico.core.reg.RegKind
import java.util.Base64
import java.util.zip.CRC32

/** Why a pasted registration string was refused. The message never quotes the input. */
enum class RegStringRefusal(val message: String) {
    EMPTY("Nothing was pasted."),
    TOO_LONG("That text is too long to be a Portico registration string."),
    NOT_A_REGISTRATION_STRING("That is not a Portico registration string."),
    UNSUPPORTED_VERSION("That registration string uses a format this version does not read."),
    CRC_MISMATCH("That registration string is incomplete or was changed. Copy it again from the phone."),
    NOT_JSON("That registration string is damaged."),
    MISSING_FIELD("That registration string is missing a field."),
    EXTRA_FIELD("That registration string has an unexpected field."),
    BAD_FIELD("That registration string has an invalid field."),
}

/**
 * One phone's registration, as carried by a [RegistrationString]. [toString] never prints the
 * registration value or the enroll id (only their `sha256[:8]`, [Redact.reg]).
 */
class RegistrationData(
    val enrollId: String,
    val installationId: String,
    val kind: RegKind,
    val registration: String,
    val packageName: String,
    val fcmProjectId: String,
    val appVersion: String,
) {
    override fun toString(): String =
        "RegistrationData(enroll_id=${Redact.reg(enrollId)}, installation_id=${Redact.prefix(installationId)}, " +
            "kind=${kind.wire}, registration=${Redact.reg(registration)}, package=$packageName, " +
            "project=$fcmProjectId, app=$appVersion)"
}

/**
 * The offline registration string (contract v1, `reg_string_spec.md`, revised 2026-10-07): what
 * the phone offers to copy when it cannot reach Home Assistant, and what the HA admin pastes into
 * the Portico integration. ONE opaque base64url token, no padding, no prefix:
 *
 * ```
 * base64url( [0x01] [CRC-32 big-endian of JSON] [JSON] )
 * JSON = {"v":1,"e":enroll_id,"i":installation_id,"k":"fid"|"token","r":value,"p":package,"f":fcm_project_id,"a":app_version}
 * ```
 *
 * compact, UTF-8, non-ASCII raw (Python `ensure_ascii=False`), keys in exactly that order, so the
 * golden vector round-trips byte for byte with the HA side. The CRC catches a truncated or
 * mistyped paste; it is not a security measure. No HA token, no Tailscale key, no URL.
 *
 * Reader rules: strip all whitespace; tolerate trailing `=` and standard-base64 `+` `/`;
 * at most [MAX_CHARS]; reject a wrong version byte, a CRC mismatch, `v` disagreeing with the
 * version byte, and any missing, extra or wrong-typed field. [parse] never throws and no result
 * quotes the input. Never log the string or its fields.
 */
object RegistrationString {
    const val VERSION = 1
    const val MAX_CHARS = 2048
    private const val HEADER_BYTES = 5

    /** Raw paste guard before whitespace stripping (a messenger may add some; not megabytes). */
    private const val MAX_RAW_CHARS = 16 * MAX_CHARS

    val KEYS = listOf("v", "e", "i", "k", "r", "p", "f", "a")
    private val ENROLL_ID = Regex("[A-Za-z0-9_-]{16,128}")
    private val BASE64URL = Regex("[A-Za-z0-9_-]+")

    sealed interface ParseResult {
        class Ok(val data: RegistrationData) : ParseResult {
            override fun toString(): String = "Ok($data)"
        }

        data class Refused(val reason: RegStringRefusal) : ParseResult
    }

    fun isValidEnrollId(id: String?): Boolean = id != null && ENROLL_ID.matches(id)

    /**
     * Builds the string. Throws [IllegalArgumentException] (with a message that quotes nothing) if
     * a field is blank, the enroll id is malformed, or the result would exceed [MAX_CHARS].
     */
    fun build(
        enrollId: String,
        installationId: String,
        kind: RegKind,
        registration: String,
        packageName: String,
        fcmProjectId: String,
        appVersion: String,
    ): String {
        require(isValidEnrollId(enrollId)) { "enroll_id is not [A-Za-z0-9_-]{16,128}" }
        require(listOf(installationId, registration, packageName, fcmProjectId, appVersion).none { it.isEmpty() }) { "a field is empty" }
        val json = buildString {
            append("{\"v\":").append(VERSION)
            for ((k, v) in listOf("e" to enrollId, "i" to installationId, "k" to kind.wire, "r" to registration, "p" to packageName, "f" to fcmProjectId, "a" to appVersion)) {
                append(",\"").append(k).append("\":")
                appendJsonString(v)
            }
            append('}')
        }.toByteArray(Charsets.UTF_8)
        val crc = crc32(json)
        val bytes = ByteArray(HEADER_BYTES + json.size)
        bytes[0] = VERSION.toByte()
        for (n in 0 until 4) bytes[1 + n] = (crc ushr (24 - 8 * n)).toByte()
        json.copyInto(bytes, HEADER_BYTES)
        val out = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        require(out.length <= MAX_CHARS) { "registration string longer than $MAX_CHARS" }
        return out
    }

    fun parse(text: String): ParseResult = try {
        parseOrRefuse(text)
    } catch (_: RuntimeException) {
        // Defensive: never let a paste crash anything, never attach the exception (it may quote input).
        ParseResult.Refused(RegStringRefusal.NOT_A_REGISTRATION_STRING)
    }

    private fun refuse(r: RegStringRefusal) = ParseResult.Refused(r)

    private fun parseOrRefuse(text: String): ParseResult {
        if (text.length > MAX_RAW_CHARS) return refuse(RegStringRefusal.TOO_LONG)
        val t = text.filterNot { it.isWhitespace() || it == '﻿' }.trimEnd('=').replace('+', '-').replace('/', '_')
        if (t.isEmpty()) return refuse(RegStringRefusal.EMPTY)
        if (t.length > MAX_CHARS) return refuse(RegStringRefusal.TOO_LONG)
        if (!BASE64URL.matches(t) || t.length % 4 == 1) return refuse(RegStringRefusal.NOT_A_REGISTRATION_STRING)
        val bytes = try {
            Base64.getUrlDecoder().decode(t)
        } catch (_: IllegalArgumentException) {
            return refuse(RegStringRefusal.NOT_A_REGISTRATION_STRING)
        }
        if (bytes.size <= HEADER_BYTES) return refuse(RegStringRefusal.NOT_A_REGISTRATION_STRING)
        if (bytes[0].toInt() != VERSION) return refuse(RegStringRefusal.UNSUPPORTED_VERSION)
        var crc = 0L
        for (n in 1..4) crc = (crc shl 8) or (bytes[n].toLong() and 0xff)
        val json = bytes.copyOfRange(HEADER_BYTES, bytes.size)
        if (crc32(json) != crc) return refuse(RegStringRefusal.CRC_MISMATCH)

        val decoder = Charsets.UTF_8.newDecoder()
        val jsonText = try {
            decoder.decode(java.nio.ByteBuffer.wrap(json)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            return refuse(RegStringRefusal.NOT_JSON)
        }
        val obj = MiniJson.parse(jsonText) as? Json.Obj ?: return refuse(RegStringRefusal.NOT_JSON)

        for (k in KEYS) if (!obj.has(k)) return refuse(RegStringRefusal.MISSING_FIELD)
        if (obj.members.keys.any { it !in KEYS }) return refuse(RegStringRefusal.EXTRA_FIELD)

        val v = obj["v"] as? Json.Num ?: return refuse(RegStringRefusal.BAD_FIELD)
        if (v.raw != VERSION.toString()) return refuse(RegStringRefusal.UNSUPPORTED_VERSION)

        fun str(k: String): String? = (obj[k] as? Json.Str)?.value?.takeIf { it.isNotEmpty() }
        val e = str("e") ?: return refuse(RegStringRefusal.BAD_FIELD)
        if (!isValidEnrollId(e)) return refuse(RegStringRefusal.BAD_FIELD)
        val kind = when (str("k")) {
            RegKind.FID.wire -> RegKind.FID
            RegKind.TOKEN.wire -> RegKind.TOKEN
            else -> return refuse(RegStringRefusal.BAD_FIELD)
        }
        return ParseResult.Ok(
            RegistrationData(
                enrollId = e,
                installationId = str("i") ?: return refuse(RegStringRefusal.BAD_FIELD),
                kind = kind,
                registration = str("r") ?: return refuse(RegStringRefusal.BAD_FIELD),
                packageName = str("p") ?: return refuse(RegStringRefusal.BAD_FIELD),
                fcmProjectId = str("f") ?: return refuse(RegStringRefusal.BAD_FIELD),
                appVersion = str("a") ?: return refuse(RegStringRefusal.BAD_FIELD),
            ),
        )
    }

    private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    /** Python `json.dumps(ensure_ascii=False)`'s string form: escape `"`, `\` and C0 controls only. */
    private fun StringBuilder.appendJsonString(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
