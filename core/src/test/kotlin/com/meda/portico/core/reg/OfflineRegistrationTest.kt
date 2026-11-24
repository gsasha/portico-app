package com.meda.portico.core.reg

import com.meda.portico.core.enroll.RegistrationString
import com.meda.portico.core.ring.LinkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Setup's "this phone cannot reach Home Assistant" block: every branch of the pure decision. */
class OfflineRegistrationTest {

    private val enrollId = "E0123456789abcdefABCDEF-_xyz0123456789AB"
    private val value = RegValue(RegKind.FID, "cFAKEfid0000000000001A")
    private val config = RegistrationConfig("inst-0f1e2d3c4b5a", "portico-test", "0.1.0")
    private val long = OfflineRegistration.AFTER_MS

    private fun inputs(
        configured: Boolean = true,
        link: LinkState = LinkState.WAITING_TO_RETRY,
        notConnectedForMs: Long = long,
        reg: RegStatus? = RegStatus.Ready,
        value: RegValue? = this.value,
        enrollId: String? = this.enrollId,
        config: RegistrationConfig = this.config,
    ) = OfflineInputs(configured, link, notConnectedForMs, reg, value, enrollId, config)

    private fun decide(i: OfflineInputs) = OfflineRegistration.decide(i)

    @Test
    fun notConfiguredShowsNothing() {
        assertSame(OfflineOffer.Hidden, decide(inputs(configured = false)))
        assertSame(OfflineOffer.Hidden, decide(inputs(link = LinkState.NOT_CONFIGURED)))
    }

    @Test
    fun connectedShowsNothing() {
        assertSame(OfflineOffer.Hidden, decide(inputs(link = LinkState.CONNECTED, notConnectedForMs = 0)))
        // Even a stale timer cannot override a connected socket.
        assertSame(OfflineOffer.Hidden, decide(inputs(link = LinkState.CONNECTED, notConnectedForMs = long * 10)))
    }

    @Test
    fun disconnectedForLessThanFifteenSecondsShowsNothing() {
        assertSame(OfflineOffer.Hidden, decide(inputs(notConnectedForMs = long - 1)))
        assertSame(OfflineOffer.Hidden, decide(inputs(link = LinkState.CONNECTING, notConnectedForMs = 3_000)))
    }

    @Test
    fun disconnectedLongEnoughWithValueAndEnrollIdOffersTheString() {
        for (link in listOf(LinkState.CONNECTING, LinkState.WAITING_TO_RETRY)) {
            val offer = decide(inputs(link = link))
            assertTrue(offer is OfflineOffer.Copy, "$link -> $offer")
            val expected = RegistrationString.build(enrollId, config.installationId, RegKind.FID, value.value, config.packageName, "portico-test", "0.1.0")
            assertEquals(expected, (offer as OfflineOffer.Copy).text)
        }
        assertTrue(decide(inputs(notConnectedForMs = long * 100)) is OfflineOffer.Copy)
    }

    @Test
    fun withValueButNoEnrollIdShowsTheScanAgainHint() {
        assertSame(OfflineOffer.ScanAgain, decide(inputs(enrollId = null)))
        assertSame(OfflineOffer.ScanAgain, decide(inputs(enrollId = "")))
    }

    @Test
    fun withoutAValueShowsNothing() {
        assertSame(OfflineOffer.Hidden, decide(inputs(value = null)))
        assertSame(OfflineOffer.Hidden, decide(inputs(value = null, enrollId = null)))
        assertSame(OfflineOffer.Hidden, decide(inputs(value = null, reg = RegStatus.WaitingForRegistration)))
    }

    @Test
    fun noRunningFirebaseProjectShowsNothing() {
        assertSame(OfflineOffer.Hidden, decide(inputs(config = config.copy(fcmProjectId = null))))
    }

    @Test
    fun aRejectedTokenOrRefusedUrlIsNotUnreachable() {
        // HA answered (auth_invalid) or the app refused its own URL: the existing PROBLEM lines say so;
        // "this phone cannot reach Home Assistant" would be false.
        assertSame(OfflineOffer.Hidden, decide(inputs(link = LinkState.AUTH_REJECTED)))
        assertSame(OfflineOffer.Hidden, decide(inputs(link = LinkState.URL_REFUSED)))
        assertSame(OfflineOffer.Hidden, decide(inputs(link = LinkState.STOPPED)))
    }

    @Test
    fun alreadyRegisteredThisRunShowsNothing() {
        // HA accepted this phone's value on an earlier connection: nothing left to paste.
        assertSame(OfflineOffer.Hidden, decide(inputs(reg = RegStatus.Registered("r1", pushEnabled = true))))
        assertSame(OfflineOffer.Hidden, decide(inputs(reg = RegStatus.Registered("r1", pushEnabled = false), enrollId = null)))
    }

    @Test
    fun localStatesThatMustNotRegisterShowNothing() {
        assertSame(OfflineOffer.Hidden, decide(inputs(reg = RegStatus.PushOff)))
        assertSame(OfflineOffer.Hidden, decide(inputs(reg = RegStatus.SignedOut)))
        assertSame(OfflineOffer.Hidden, decide(inputs(reg = RegStatus.NoFirebaseConfig)))
    }

    @Test
    fun anUnbuildableStringShowsNothingRatherThanCrashing() {
        assertSame(OfflineOffer.Hidden, decide(inputs(enrollId = "bad id")))
        assertSame(OfflineOffer.Hidden, decide(inputs(config = config.copy(installationId = ""))))
    }

    @Test
    fun toStringsNeverCarryTheValueOrEnrollId() {
        val offer = decide(inputs())
        for (t in listOf(offer.toString(), inputs().toString())) {
            assertFalse(value.value in t, t)
            assertFalse(enrollId in t, t)
            assertFalse((offer as OfflineOffer.Copy).text.take(16) in t, t)
        }
    }

    // ---- the "not connected since" timer --------------------------------------------------------

    @Test
    fun timerCountsFromCreationUntilTheFirstConnect() {
        val t = NotConnectedTimer(nowMs = 1_000)
        assertEquals(0, t.notConnectedForMs(1_000))
        assertEquals(14_000, t.notConnectedForMs(15_000))
        t.update(connected = true, nowMs = 16_000)
        assertEquals(0, t.notConnectedForMs(20_000))
    }

    @Test
    fun timerRestartsWhenTheConnectionDropsAndIgnoresRetriesInBetween() {
        val t = NotConnectedTimer(nowMs = 0)
        t.update(connected = true, nowMs = 5_000)
        t.update(connected = false, nowMs = 10_000)
        t.update(connected = false, nowMs = 12_000) // CONNECTING -> WAITING_TO_RETRY: still the same outage
        t.update(connected = false, nowMs = 20_000)
        assertEquals(15_000, t.notConnectedForMs(25_000))
        t.update(connected = true, nowMs = 26_000)
        t.update(connected = true, nowMs = 27_000)
        assertEquals(0, t.notConnectedForMs(60_000))
    }

    @Test
    fun timerNeverGoesNegative() {
        val t = NotConnectedTimer(nowMs = 10_000)
        assertEquals(0, t.notConnectedForMs(5_000))
    }
}
