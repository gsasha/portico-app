package com.meda.portico.core.netpolicy

import com.meda.portico.core.ha.CameraSize
import com.meda.portico.core.roaming.RoamingFacts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkPolicyTest {
    private fun facts(lan: Boolean = false, have: Boolean = true, wifi: Boolean = false, cell: Boolean = false) = RoamingFacts(
        lanVerified = lan, haveDefaultNet = have, wifiOrEthernet = wifi, cellular = cell,
        notRoamingCap = true, telephonyRoaming = false,
    )

    @Test
    fun `Wi-Fi, a cable, or provably at home is Wi-Fi even when cellular is the default`() {
        assertEquals(NetClass.WIFI, NetworkPolicy.classify(facts(wifi = true), away = false))
        assertEquals(NetClass.WIFI, NetworkPolicy.classify(facts(lan = true, cell = true), away = false))
    }

    @Test
    fun `mobile is Mobile, away is Roaming, a VPN-only or unknown network is Mobile, no network is no answer`() {
        assertEquals(NetClass.MOBILE, NetworkPolicy.classify(facts(cell = true), away = false))
        assertEquals(NetClass.ROAMING, NetworkPolicy.classify(facts(cell = true), away = true))
        assertEquals(NetClass.MOBILE, NetworkPolicy.classify(facts(), away = false))
        assertNull(NetworkPolicy.classify(facts(have = false), away = false))
    }

    @Test
    fun `defaults reproduce 0_6_57 for the old switch values`() {
        val on = NetworkPolicy.defaults(oldStayConnected = true, oldLiveVideo = true)
        for (c in NetClass.entries) assertEquals(ConnectionMode.STAY, on.connection(c))
        assertEquals(VideoMode.LIVE_BIG, on.video(NetClass.WIFI))
        assertEquals(VideoMode.LIVE_BIG, on.video(NetClass.MOBILE))
        assertEquals(VideoMode.OFF, on.video(NetClass.ROAMING)) // the roaming block
        assertTrue(on.anyStay)
        val off = NetworkPolicy.defaults(oldStayConnected = false, oldLiveVideo = false)
        for (c in NetClass.entries) assertEquals(ConnectionMode.PUSH, off.connection(c))
        assertEquals(VideoMode.STILLS, off.video(NetClass.WIFI))
        assertEquals(VideoMode.STILLS, off.video(NetClass.MOBILE))
        assertFalse(off.anyStay)
    }

    @Test
    fun `the default roaming setting is the old block, a tap lifts it to the Mobile setting`() {
        val on = NetworkPolicy.defaults(true, true)
        assertEquals(VideoVerdict(VideoMode.OFF, roamingHold = true), NetworkPolicy.video(NetClass.ROAMING, on, tripAllowed = false))
        assertEquals(VideoVerdict(VideoMode.LIVE_BIG), NetworkPolicy.video(NetClass.ROAMING, on, tripAllowed = true))
        val stills = NetworkPolicy.defaults(true, false)
        assertEquals(VideoVerdict(VideoMode.STILLS), NetworkPolicy.video(NetClass.ROAMING, stills, tripAllowed = true))
    }

    @Test
    fun `a roaming choice other than Off is the viewer's own and is never held back`() {
        val c = NetworkPolicy.defaults(true, true).let { it.copy(video = it.video + (NetClass.ROAMING to VideoMode.STILLS)) }
        assertEquals(VideoVerdict(VideoMode.STILLS), NetworkPolicy.video(NetClass.ROAMING, c, tripAllowed = false))
    }

    @Test
    fun `Wi-Fi and Mobile Off are plain Off, not the roaming hold`() {
        val c = NetworkPolicy.defaults(true, true).let { it.copy(video = it.video + (NetClass.MOBILE to VideoMode.OFF)) }
        val v = NetworkPolicy.video(NetClass.MOBILE, c, tripAllowed = false)
        assertEquals(VideoVerdict(VideoMode.OFF), v)
        assertTrue(v.picturesOff)
        assertFalse(NetworkPolicy.video(NetClass.ROAMING, c, false).picturesOff) // the hold draws its own notice
    }

    private fun cap(v: VideoMode, off: Boolean = false, live: Boolean = true, stream: CameraSize = CameraSize.BIG, hasSmall: Boolean = true) =
        NetworkPolicy.cap(VideoVerdict(v), off, live, stream, hasSmall)

    @Test
    fun `the cap works on top of the per-camera choices`() {
        // Live big: anything allowed
        assertEquals(CappedCamera(false, true, CameraSize.BIG), cap(VideoMode.LIVE_BIG))
        assertEquals(CappedCamera(false, false, CameraSize.BIG), cap(VideoMode.LIVE_BIG, live = false)) // the camera's own stills choice stays
        assertEquals(CappedCamera(true, true, CameraSize.BIG), cap(VideoMode.LIVE_BIG, off = true)) // the camera's own off stays
        // Live small: the sub stream wherever there is one
        assertEquals(CappedCamera(false, true, CameraSize.SMALL), cap(VideoMode.LIVE_SMALL))
        assertEquals(CappedCamera(false, true, CameraSize.BIG), cap(VideoMode.LIVE_SMALL, hasSmall = false))
        assertEquals(CappedCamera(false, false, CameraSize.SMALL), cap(VideoMode.LIVE_SMALL, live = false))
        // Stills: slideshow only
        assertEquals(CappedCamera(false, false, CameraSize.BIG), cap(VideoMode.STILLS))
        // Off: nothing at all, whatever the camera chose
        assertTrue(cap(VideoMode.OFF).off)
        assertFalse(cap(VideoMode.OFF).wantsLive)
    }

    @Test
    fun `the roaming hold does not turn tiles off, it keeps its own notice`() {
        val c = NetworkPolicy.cap(VideoVerdict(VideoMode.OFF, roamingHold = true), off = false, wantsLive = true, stream = CameraSize.BIG, hasSmall = true)
        assertFalse(c.off)
        assertFalse(c.wantsLive)
    }

    @Test
    fun `the ring pre-warm asks for the same stream the capped screen asks for`() {
        val cam = com.meda.portico.core.live.PrewarmCam("c", CameraSize.BIG, hasSmall = true, available = true, wantsLive = true)
        assertEquals(CameraSize.BIG, com.meda.portico.core.live.RingPrewarm.keyFor(cam).size)
        assertEquals(CameraSize.SMALL, com.meda.portico.core.live.RingPrewarm.keyFor(cam.copy(smallOnly = true)).size)
        assertEquals(CameraSize.BIG, com.meda.portico.core.live.RingPrewarm.keyFor(cam.copy(smallOnly = true, hasSmall = false)).size)
    }

    @Test
    fun `keys round-trip and unknown keys are null`() {
        for (c in NetClass.entries) assertEquals(c, NetClass.of(c.key))
        for (m in ConnectionMode.entries) assertEquals(m, ConnectionMode.of(m.key))
        for (m in VideoMode.entries) assertEquals(m, VideoMode.of(m.key))
        assertNull(VideoMode.of("hd"))
        assertNull(ConnectionMode.of(null))
    }
}
