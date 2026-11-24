package com.meda.portico.core.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class PushMessageTest {
    private val ring = mapOf(
        "pv" to "2", "kind" to "ring", "door_ref" to "01JABCDEFGHJKMNPQRSTVWXYZ0",
        "call_id" to "gso-1759700000000000000-1-a1b2c3", "seq" to "1", "t" to "1759700000000",
        "exp" to "1759700045000",
    )

    private fun ignored(data: Map<String, String?>) = PushMessage.parse(data) as PushMessage.Ignored

    @Test
    fun parsesTheThreeKinds() {
        assertEquals(
            PushMessage.Ring("01JABCDEFGHJKMNPQRSTVWXYZ0", "gso-1759700000000000000-1-a1b2c3", 1, 1759700000000, 1759700045000),
            PushMessage.parse(ring),
        )
        val answered = ring - "exp" + mapOf("kind" to "answered", "seq" to "2", "by" to "other")
        assertEquals("other", (PushMessage.parse(answered) as PushMessage.Answered).by)
        val ended = ring - "exp" + mapOf("kind" to "ended", "seq" to "3")
        assertEquals(3, (PushMessage.parse(ended) as PushMessage.Ended).seq)
    }

    @Test
    fun answeredWithoutByIsAccepted() {
        val answered = ring - "exp" + mapOf("kind" to "answered", "seq" to "2")
        assertEquals(null, (PushMessage.parse(answered) as PushMessage.Answered).by)
    }

    @Test
    fun unknownOrMissingVersion() {
        assertEquals(IgnoreReason.UNKNOWN_VERSION, ignored(ring + ("pv" to "3")).reason)
        assertEquals(IgnoreReason.UNKNOWN_VERSION, ignored(ring - "pv").reason)
        assertEquals(IgnoreReason.UNKNOWN_VERSION, ignored(ring + ("pv" to "1.0")).reason)
        // push/v2 does not read the v1 shape: the sender no longer sends it, and a stale one must not ring
        assertEquals(IgnoreReason.UNKNOWN_VERSION, ignored(ring + ("pv" to "1")).reason)
    }

    // ---- push v2's call type: checked BEFORE door_ref is required (R7-14) ----

    private val houseRing = ring - "door_ref" + mapOf("ct" to "house", "from_ref" to "c-3171d89ad005")

    @Test
    fun aHouseCallWithNoDoorRefIsIgnoredAsAnUnknownKindNeverAsMalformed() {
        // The M7 sender gives a house call a caller reference and no door: reading door_ref first would call it malformed.
        val ig = ignored(houseRing)
        assertEquals(IgnoreReason.UNKNOWN_KIND, ig.reason)
        assertEquals("ct not recognised", ig.detail)
    }

    @Test
    fun aHouseCallWithADoorRefIsIgnoredToo() {
        assertEquals(IgnoreReason.UNKNOWN_KIND, ignored(ring + ("ct" to "house")).reason)
        for (kind in listOf("answered", "ended")) {
            assertEquals(IgnoreReason.UNKNOWN_KIND, ignored(houseRing + ("kind" to kind)).reason, kind)
        }
    }

    @Test
    fun ctDoorSpelledOutOrAbsentIsADoorCallAndFromRefIsReserved() {
        val plain = PushMessage.parse(ring)
        assertEquals(plain, PushMessage.parse(ring + ("ct" to "door")))
        assertEquals(plain, PushMessage.parse(ring + ("from_ref" to "c-3171d89ad005")), "from_ref is reserved and unread")
    }

    @Test
    fun anyOtherCallTypeIsIgnored() {
        for (ct in listOf("", "DOOR", "phone", "house ")) {
            assertEquals(IgnoreReason.UNKNOWN_KIND, ignored(ring + ("ct" to ct)).reason, "ct='$ct'")
        }
    }

    @Test
    fun aDoorRefMayBeTheDerivedFormAndNeverAUrl() {
        val derived = "d-6e5163e1070819ff1e4336aaf9d93c38"
        assertEquals(derived, (PushMessage.parse(ring + ("door_ref" to derived)) as PushMessage.Ring).doorRef)
        assertEquals(IgnoreReason.MALFORMED, ignored(ring + ("door_ref" to "http://192.168.1.7:8091")).reason)
    }

    @Test
    fun unknownKind() {
        assertEquals(IgnoreReason.UNKNOWN_KIND, ignored(ring + ("kind" to "RING")).reason)
        assertEquals(IgnoreReason.MALFORMED, ignored(ring - "kind").reason)
    }

    @Test
    fun everyRequiredKeyMissingOrGarbledIsMalformed() {
        for (key in listOf("door_ref", "call_id", "seq", "t", "exp")) {
            assertEquals(IgnoreReason.MALFORMED, ignored(ring - key).reason, "$key missing")
            assertEquals("$key missing", ignored(ring - key).detail)
        }
        val garbled = mapOf(
            "door_ref" to "https://10.0.0.1/door", "call_id" to "", "seq" to "0", "t" to "-5",
            "exp" to "1.7e12",
        )
        for ((key, value) in garbled) {
            val ig = ignored(ring + (key to value))
            assertEquals(IgnoreReason.MALFORMED, ig.reason, "$key=$value")
            assertEquals("$key unparseable", ig.detail)
        }
        assertEquals(IgnoreReason.MALFORMED, ignored(ring + ("call_id" to "x".repeat(129))).reason)
        assertEquals(IgnoreReason.MALFORMED, ignored(ring + ("t" to "99999999999999999999")).reason)
        assertEquals(IgnoreReason.MALFORMED, ignored(ring + ("seq" to " 1")).reason)
    }

    @Test
    fun nullValuesAndAnEmptyMapNeverThrow() {
        assertEquals(IgnoreReason.UNKNOWN_VERSION, ignored(emptyMap()).reason)
        assertEquals(IgnoreReason.MALFORMED, ignored(ring + ("call_id" to null)).reason)
    }

    @Test
    fun ignoredDetailNeverCarriesTheValue() {
        val secret = "AAAA-this-looks-like-a-token-ZZZZ"
        for (key in listOf("door_ref", "call_id", "seq", "t", "exp", "kind", "pv")) {
            val p = PushMessage.parse(ring + (key to "$secret!"))
            if (p is PushMessage.Ignored) assertFalse(secret in p.detail, "$key leaked")
        }
    }

    @Test
    fun extraKeysAreIgnored() {
        assertEquals(PushMessage.parse(ring), PushMessage.parse(ring + ("future" to "x")))
    }

    @Test
    fun testKindNeedsOnlyVersionAndKind() {
        assertEquals(PushMessage.Test(null), PushMessage.parse(mapOf("pv" to "2", "kind" to "test")))
        assertEquals(PushMessage.Test(5), PushMessage.parse(mapOf("pv" to "2", "kind" to "test", "t" to "5")))
        assertEquals(IgnoreReason.UNKNOWN_VERSION, ignored(mapOf("pv" to "3", "kind" to "test")).reason)
    }
}
