package com.meda.portico.signaling

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DEV-798 (SEC-02), checked in text (working directory = app/): every path that sends a Home Assistant token over the DIRECT path
 * goes through the proof gate. The gate's behaviour is `:core` `HomeProofTest`; this pins that the app still uses it, so a refactor of
 * [AppGraph] cannot quietly send a token to an unproven cleartext address again.
 */
class HomeProofWiringTest {
    private val graph = File("src/main/java/com/meda/portico/AppGraph.kt").readText()

    private fun code(text: String) = text.lines().filter { l -> l.trim().let { !(it.startsWith("*") || it.startsWith("/*") || it.startsWith("//")) } }.joinToString("\n")

    @Test
    fun `the direct websocket is the verified connector`() {
        val c = code(graph)
        assertTrue(c.contains("direct = VerifiedConnector(OkHttpConnector(), homeVerifier, proofWorker)"))
        assertFalse(c.contains("direct = OkHttpConnector()"), "no bare direct connector")
    }

    @Test
    fun `the refresh and redemption post goes through the verified post`() {
        val c = code(graph)
        assertTrue(c.contains("direct = VerifiedHttpPost(OkHttpPost(), homeVerifier)"))
        assertFalse(c.contains("direct = OkHttpPost()"), "no bare direct post")
    }

    @Test
    fun `the probe is a plain post with a short timeout, never the gated one, on its own threads`() {
        val c = code(graph)
        val verifier = c.substringAfter("private val homeVerifier = HomeVerifier(").substringBefore("private val connector")
        assertTrue(verifier.contains("OkHttpPost(") && !verifier.contains("VerifiedHttpPost") && !verifier.contains("authHttp"))
        assertTrue(verifier.contains("callTimeout(4, TimeUnit.SECONDS)"))
        assertTrue(verifier.contains("settings()?.refreshToken"))
        assertTrue(c.contains("private val proofWorker = Executors.newCachedThreadPool"))
        assertFalse(c.substringAfter("VerifiedConnector(OkHttpConnector(), homeVerifier,").substringBefore(")").contains("tailnetWorker"), "the probe must never wait behind a tailnet connect")
    }

    @Test
    fun `every proof decision reaches the connection log through the connect timeline`() {
        assertTrue(code(graph).contains("onOutcome = { connectTimeline.mark(it.stage, it.word) }"))
    }

    @Test
    fun `the app hands its connector to the signaling client, so the default one is never used`() {
        assertTrue(code(graph).contains("link, connector = connector"))
    }
}
