package com.meda.portico.core.call

import com.meda.portico.core.ha.LinkPath
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class AwayPathPolicyTest {
    private fun d(c: LinkPath?, l: LinkPath?) = AwayPathPolicy.decide(c, l)

    @Test fun `tailnet and direct decide on the open socket`() {
        assertEquals(AwayPathPolicy.Decision(true, "tailnet"), d(LinkPath.TAILNET, LinkPath.DIRECT))
        assertEquals(AwayPathPolicy.Decision(false, "direct"), d(LinkPath.DIRECT, LinkPath.TAILNET))
    }

    @Test fun `a closed socket (null path) after the tailnet is still frames, never WebRTC`() {
        assertEquals(true, d(null, LinkPath.TAILNET).frames)
    }

    @Test fun `a closed socket after direct keeps WebRTC`() {
        assertEquals(false, d(null, LinkPath.DIRECT).frames)
    }

    @Test fun `unknown with no history is not direct, so frames`() {
        assertEquals(AwayPathPolicy.Decision(true, "path-unknown"), d(null, null))
    }
}
