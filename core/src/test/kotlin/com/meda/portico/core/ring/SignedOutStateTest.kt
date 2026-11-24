package com.meda.portico.core.ring

import com.meda.portico.core.content.Banner
import com.meda.portico.core.ha.ContentProfile
import com.meda.portico.core.content.ContentSupport
import com.meda.portico.core.content.HomeModel
import com.meda.portico.core.fcm.BootResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** DEV-544: `invalid_grant` is its own state - red, with its own words, never a retry and never "token refused". */
class SignedOutStateTest {
    @Test
    fun `the status dot is red with its own word`() {
        val s = ConnectionModel.of(LinkState.SIGNED_OUT, null)
        assertEquals(ConnectionStatus(Health.BAD, StatusLabel.SIGNED_OUT), s)
        assertEquals("○", s.glyph)
        assertFalse(s.label == StatusLabel.TOKEN_REFUSED)
    }

    @Test
    fun `Setup says it as a problem`() {
        val lines = SetupStatus.pushLines(BootResult.NotConfigured, LinkState.SIGNED_OUT, null, null)
        assertEquals(StatusLine(StatusKey.LINK_SIGNED_OUT, Severity.PROBLEM), lines[0])
    }

    @Test
    fun `Home shows the signed-out banner in every support state`() {
        for (support in ContentSupport.entries) {
            val v = HomeModel.build(ContentProfile.EMPTY, LinkState.SIGNED_OUT, support, true, emptySet(), emptySet())
            assertEquals(Banner.SIGNED_OUT, v.banner, support.toString())
        }
        assertFalse(HomeModel.usable(LinkState.SIGNED_OUT, ContentSupport.YES), "no button works while signed out")
    }

    @Test
    fun `every other state keeps its banner`() {
        assertEquals(Banner.AUTH_REJECTED, HomeModel.build(ContentProfile.EMPTY, LinkState.AUTH_REJECTED, ContentSupport.YES, true, emptySet(), emptySet()).banner)
        assertEquals(Banner.NOT_CONFIGURED, HomeModel.build(ContentProfile.EMPTY, LinkState.NOT_CONFIGURED, ContentSupport.YES, true, emptySet(), emptySet()).banner)
    }
}
