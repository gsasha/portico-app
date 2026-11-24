package com.meda.portico.core.enroll

import com.meda.portico.core.reg.RegKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.zip.CRC32

/**
 * The offline registration string, contract v1 as revised 2026-10-07 (one opaque base64url token:
 * `[0x01][CRC-32 BE of the JSON][JSON]`), golden vector shared with portico-ha.
 */
class RegistrationStringTest {

    private val enrollId = "E0123456789abcdefABCDEF-_xyz0123456789AB"
    private val registration = "cFAKEfid0000000000001A"
    private val goldenJson =
        """{"v":1,"e":"E0123456789abcdefABCDEF-_xyz0123456789AB","i":"inst-0f1e2d3c4b5a","k":"fid","r":"cFAKEfid0000000000001A","p":"com.meda.portico","f":"portico-test","a":"0.1.0"}"""
    private val golden =
        "AWDBl1t7InYiOjEsImUiOiJFMDEyMzQ1Njc4OWFiY2RlZkFCQ0RFRi1feHl6MDEyMzQ1Njc4OUFCIiwiaSI6Imluc3QtMGYxZTJkM2M0YjVhIiwiayI6ImZpZCIsInIiOiJjRkFLRWZpZDAwMDAwMDAwMDAwMDFBIiwicCI6ImNvbS5tZWRhLnBvcnRpY28iLCJmIjoicG9ydGljby10ZXN0IiwiYSI6IjAuMS4wIn0"

    private fun buildGolden() = RegistrationString.build(
        enrollId, "inst-0f1e2d3c4b5a", RegKind.FID, registration, "com.meda.portico", "portico-test", "0.1.0",
    )

    private fun ok(text: String): RegistrationData = when (val r = RegistrationString.parse(text)) {
        is RegistrationString.ParseResult.Ok -> r.data
        is RegistrationString.ParseResult.Refused -> fail("expected Ok, got ${r.reason}")
    }

    private fun refused(text: String): RegStringRefusal = when (val r = RegistrationString.parse(text)) {
        is RegistrationString.ParseResult.Ok -> fail("expected a refusal")
        is RegistrationString.ParseResult.Refused -> r.reason
    }

    /** Encodes arbitrary bytes the way the contract frames them, with a correct CRC unless [crcOverride]. */
    private fun frame(json: String, version: Int = 1, crcOverride: Long? = null): String {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val crc = crcOverride ?: CRC32().apply { update(bytes) }.value
        val head = byteArrayOf(version.toByte(), (crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(head + bytes)
    }

    @Test
    fun goldenBuildsByteForByte() {
        assertEquals(golden, buildGolden())
        assertEquals(235, golden.length)
        assertEquals(171, goldenJson.toByteArray(Charsets.UTF_8).size)
        assertEquals(golden, frame(goldenJson))
        assertEquals(176, Base64.getUrlDecoder().decode(golden).size)
    }

    @Test
    fun goldenParses() {
        val d = ok(golden)
        assertEquals(enrollId, d.enrollId)
        assertEquals("inst-0f1e2d3c4b5a", d.installationId)
        assertEquals(RegKind.FID, d.kind)
        assertEquals(registration, d.registration)
        assertEquals("com.meda.portico", d.packageName)
        assertEquals("portico-test", d.fcmProjectId)
        assertEquals("0.1.0", d.appVersion)
        assertEquals(golden, RegistrationString.build(d.enrollId, d.installationId, d.kind, d.registration, d.packageName, d.fcmProjectId, d.appVersion))
    }

    @Test
    fun whitespaceAnywhereIsStripped() {
        val wrapped = golden.chunked(40).joinToString("\n ") + "\r\n\t"
        assertEquals(registration, ok("  $wrapped  ").registration)
        assertEquals(registration, ok(golden.chunked(7).joinToString(" ")).registration)
    }

    @Test
    fun paddingAndStandardBase64AreTolerated() {
        assertEquals(registration, ok("$golden=").registration)
        assertEquals(registration, ok("$golden==").registration)
        val standard = Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(golden))
        assertTrue('+' in standard || '/' in standard || '=' in standard, standard) // the variant is really different
        assertEquals(registration, ok(standard).registration)
    }

    @Test
    fun truncationIsRefused() {
        assertEquals(RegStringRefusal.CRC_MISMATCH, refused(golden.dropLast(4)))
        assertEquals(RegStringRefusal.CRC_MISMATCH, refused(golden.take(120)))
        assertEquals(RegStringRefusal.NOT_A_REGISTRATION_STRING, refused(golden.take(6))) // shorter than the 5-byte header
        assertEquals(RegStringRefusal.NOT_A_REGISTRATION_STRING, refused(golden.dropLast(2))) // length % 4 == 1: not base64
    }

    @Test
    fun wrongCrcIsRefused() {
        assertEquals(RegStringRefusal.CRC_MISMATCH, refused(frame(goldenJson, crcOverride = 0x60c1975cL)))
        val body = golden.toCharArray()
        body[100] = if (body[100] == 'A') 'B' else 'A'
        assertEquals(RegStringRefusal.CRC_MISMATCH, refused(String(body)))
    }

    @Test
    fun wrongVersionByteIsRefused() {
        assertEquals(RegStringRefusal.UNSUPPORTED_VERSION, refused(frame(goldenJson, version = 2)))
        assertEquals(RegStringRefusal.UNSUPPORTED_VERSION, refused(frame(goldenJson, version = 0)))
    }

    @Test
    fun versionFieldMustAgreeWithTheLeadingByte() {
        assertEquals(RegStringRefusal.UNSUPPORTED_VERSION, refused(frame(goldenJson.replace("\"v\":1", "\"v\":2"))))
        assertEquals(RegStringRefusal.UNSUPPORTED_VERSION, refused(frame(goldenJson.replace("\"v\":1", "\"v\":1.0"))))
        assertEquals(RegStringRefusal.BAD_FIELD, refused(frame(goldenJson.replace("\"v\":1", "\"v\":\"1\""))))
    }

    @Test
    fun randomTextIsNotARegistrationString() {
        for (t in listOf(
            "hello world, here is my registration",
            """{"ha_url":"https://h","ha_token":"t"}""",
            "legacy-transfer/1",
            "PORTICO-REG1:abc.60c1975b",
            "AAAAAAAAAAAAAAAAAAAA",
            "ü",
        )) {
            val r = refused(t)
            assertTrue(r == RegStringRefusal.NOT_A_REGISTRATION_STRING || r == RegStringRefusal.UNSUPPORTED_VERSION, "$t -> $r")
        }
        assertEquals(RegStringRefusal.EMPTY, refused(" \n\t "))
        assertEquals(RegStringRefusal.EMPTY, refused("=="))
    }

    @Test
    fun missingFieldsAreRefused() {
        for (key in listOf("v", "e", "i", "k", "r", "p", "f", "a")) {
            val json = goldenJson.replace(Regex("\"$key\":(\"[^\"]*\"|1),?"), "").replace(",}", "}")
            assertEquals(RegStringRefusal.MISSING_FIELD, refused(frame(json)), key)
        }
    }

    @Test
    fun extraFieldsAreRefused() {
        assertEquals(RegStringRefusal.EXTRA_FIELD, refused(frame(goldenJson.replace("}", ",\"x\":1}"))))
        assertEquals(RegStringRefusal.EXTRA_FIELD, refused(frame(goldenJson.replace("}", ",\"ha_token\":\"t\"}"))))
    }

    @Test
    fun wrongTypesAreRefused() {
        assertEquals(RegStringRefusal.BAD_FIELD, refused(frame(goldenJson.replace("\"r\":\"cFAKEfid0000000000001A\"", "\"r\":12"))))
        assertEquals(RegStringRefusal.BAD_FIELD, refused(frame(goldenJson.replace("\"i\":\"inst-0f1e2d3c4b5a\"", "\"i\":null"))))
        assertEquals(RegStringRefusal.BAD_FIELD, refused(frame(goldenJson.replace("\"a\":\"0.1.0\"", "\"a\":[\"0.1.0\"]"))))
        assertEquals(RegStringRefusal.BAD_FIELD, refused(frame(goldenJson.replace("\"k\":\"fid\"", "\"k\":\"apns\""))))
        assertEquals(RegStringRefusal.BAD_FIELD, refused(frame(goldenJson.replace("\"r\":\"cFAKEfid0000000000001A\"", "\"r\":\"\""))))
        assertEquals(RegStringRefusal.BAD_FIELD, refused(frame(goldenJson.replace(enrollId, "short"))))
        assertEquals(RegStringRefusal.NOT_JSON, refused(frame("[1,2,3]")))
        assertEquals(RegStringRefusal.NOT_JSON, refused(frame("{\"v\":1,")))
    }

    @Test
    fun oversizeIsRefused() {
        val big = frame(goldenJson.replace("0.1.0", "x".repeat(1600)))
        assertTrue(big.length > RegistrationString.MAX_CHARS)
        assertEquals(RegStringRefusal.TOO_LONG, refused(big))
        assertEquals(RegStringRefusal.TOO_LONG, refused(" ".repeat(10) + "A".repeat(RegistrationString.MAX_CHARS + 1)))
        assertThrows(IllegalArgumentException::class.java) {
            RegistrationString.build(enrollId, "i", RegKind.TOKEN, "t".repeat(2000), "com.meda.portico", "p", "1")
        }
    }

    @Test
    fun buildRefusesInvalidInput() {
        assertThrows(IllegalArgumentException::class.java) {
            RegistrationString.build("short", "i", RegKind.FID, registration, "p", "f", "a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegistrationString.build(enrollId, "", RegKind.FID, registration, "p", "f", "a")
        }
    }

    @Test
    fun nonAsciiIsRawUtf8AndControlCharsAreEscaped() {
        val s = RegistrationString.build(enrollId, "inst", RegKind.TOKEN, "tok", "com.meda.portico", "proj", "1.0-ü\"\\\n")
        val json = String(Base64.getUrlDecoder().decode(s).copyOfRange(5, Base64.getUrlDecoder().decode(s).size), Charsets.UTF_8)
        assertTrue(json.endsWith(""""a":"1.0-ü\"\\\n"}"""), json)
        assertEquals("1.0-ü\"\\\n", ok(s).appVersion)
        assertEquals(RegKind.TOKEN, ok(s).kind)
        assertTrue(s.all { it.isLetterOrDigit() || it == '-' || it == '_' }, s)
    }

    @Test
    fun resultsNeverPrintTheRegistrationOrEnrollId() {
        val d = ok(golden)
        val texts = listOf(
            d.toString(),
            RegistrationString.parse(golden).toString(),
            RegistrationString.parse("garbage $registration").toString(),
            RegistrationString.parse(golden.dropLast(4)).toString(),
        )
        for (t in texts) {
            assertFalse(registration in t, t)
            assertFalse(enrollId in t, t)
            assertFalse(golden.take(20) in t, t)
        }
        for (r in RegStringRefusal.entries) assertFalse(registration in r.message)
    }
}
