package com.meda.portico.core.auth

import com.meda.portico.core.ha.LinkPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class PathedHttpTest {
    private val url = "https://ha.example.com/auth/token"
    private fun post(h: PathedHttp) = h.post(url, "application/x-www-form-urlencoded", "a=b")

    @Test
    fun `the direct path answers - the tailnet is never asked`() {
        val direct = ScriptedHttp(reply(200, "ok"))
        val tailnet = ScriptedHttp()
        assertEquals(200, post(PathedHttp(direct, tailnet, { true })).status)
        assertTrue(tailnet.seen.isEmpty())
    }

    @Test
    fun `any HTTP answer on the direct path ends it, a 400 or a 429 is HA talking`() {
        for (st in listOf(400, 403, 429, 500, 503)) {
            val direct = ScriptedHttp(reply(st))
            val tailnet = ScriptedHttp(reply(200))
            assertEquals(st, post(PathedHttp(direct, tailnet, { true })).status)
            assertTrue(tailnet.seen.isEmpty(), "status $st")
        }
    }

    @Test
    fun `direct cannot complete and the tailnet can help - the SAME request goes over the tailnet`() {
        val direct = ScriptedHttp(unreachable)
        val tailnet = ScriptedHttp(reply(200, "via tailnet"))
        val failed = ArrayList<LinkPath>()
        val out = post(PathedHttp(direct, tailnet, { true }, onPathFailed = { failed += it }))
        assertEquals("via tailnet", out.body)
        assertEquals(direct.seen.single(), tailnet.seen.single())
        assertEquals(listOf(LinkPath.DIRECT), failed)
    }

    @Test
    fun `direct cannot complete and the tailnet cannot help - one error naming the paths, no URL`() {
        val direct = ScriptedHttp(unreachable)
        val tailnet = ScriptedHttp()
        val e = assertThrows(PathsFailedException::class.java) { post(PathedHttp(direct, tailnet, { false })) }
        assertEquals(listOf(LinkPath.DIRECT), e.tried)
        assertTrue(tailnet.seen.isEmpty())
        assertFalse(e.message!!.contains("ha.example.com"))
        val e2 = assertThrows(PathsFailedException::class.java) { post(PathedHttp(ScriptedHttp(unreachable), null, { true })) }
        assertEquals(listOf(LinkPath.DIRECT), e2.tried)
    }

    @Test
    fun `both paths fail`() {
        val e = assertThrows(PathsFailedException::class.java) {
            post(PathedHttp(ScriptedHttp(unreachable), ScriptedHttp(IOException("tailnet not up")), { true }))
        }
        assertEquals(listOf(LinkPath.DIRECT, LinkPath.TAILNET), e.tried)
    }

    @Test
    fun `a usable-check that throws is no tailnet`() {
        assertThrows(PathsFailedException::class.java) {
            post(PathedHttp(ScriptedHttp(unreachable), ScriptedHttp(reply(200)), { throw IllegalStateException("engine") }))
        }
    }

    @Test
    fun `a paused phone sends nothing on any path`() {
        val direct = ScriptedHttp(reply(200))
        val tailnet = ScriptedHttp(reply(200))
        assertThrows(IOException::class.java) { post(PathedHttp(direct, tailnet, { true }, paused = { true })) }
        assertTrue(direct.seen.isEmpty() && tailnet.seen.isEmpty())
    }

    @Test
    fun `pausing between the paths stops the second`() {
        var paused = false
        val direct = ScriptedHttp()
        direct.responder = { paused = true; unreachable }
        val tailnet = ScriptedHttp(reply(200))
        assertThrows(PathsFailedException::class.java) { post(PathedHttp(direct, tailnet, { true }, paused = { paused })) }
        assertTrue(tailnet.seen.isEmpty())
    }

    @Test
    fun `a pause flag that cannot be read is not paused (fail open)`() {
        assertEquals(200, post(PathedHttp(ScriptedHttp(reply(200)), null, { false }, paused = { throw IllegalStateException() })).status)
    }

    @Test
    fun `the answer type shows no body`() {
        assertEquals("HttpReply(200)", reply(200, "SECRET").toString())
    }
}
