package com.meda.portico.core.auth

import com.meda.portico.core.ha.ConnectTimeline
import com.meda.portico.core.ha.Connector
import com.meda.portico.core.ha.WsListener
import com.meda.portico.core.ha.WsTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.Executor

/** DEV-798: what the proof check tells the connection log, so a connect's report shows whether the proof ran and how it ended. */
class HomeProofOutcomeTest {
    private val token = "refresh-token-" + "w".repeat(60)
    private val ws = "ws://192.168.1.7:8123/api/websocket"
    private val seen = ArrayList<ProofOutcome>()

    private fun real() = ScriptedHttp().also { h ->
        h.responder = { p ->
            val o = Json.parseToJsonElement(p.body).jsonObject
            val nonce = o["nonce"]!!.jsonPrimitive.content
            val hint = o["hint"]!!.jsonPrimitive.content
            reply(200, """{"proof":"${HomeProof.proof(token, nonce, hint)}"}""")
        }
    }

    private fun verifier(http: HttpPost, t: String? = token, onOutcome: (ProofOutcome) -> Unit = { seen += it }) =
        HomeVerifier(http, { t }, onOutcome = onOutcome)

    @Test
    fun `a proof that checks out is reported as passed`() {
        assertTrue(verifier(real()).allows(ws))
        assertEquals(listOf(ProofOutcome.PASSED), seen)
    }

    @Test
    fun `an answer that is not the proof is refused, and no answer is its own word`() {
        assertFalse(verifier(ScriptedHttp(reply(404))).allows(ws))
        assertFalse(verifier(ScriptedHttp(reply(400, """{"error":"invalid_or_expired"}"""))).allows(ws))
        assertFalse(verifier(ScriptedHttp(IOException("timeout"))).allows(ws))
        assertFalse(verifier(ScriptedHttp()).allows("ws://u:p@h/x"))
        assertEquals(listOf(ProofOutcome.REFUSED, ProofOutcome.REFUSED, ProofOutcome.NO_ANSWER, ProofOutcome.REFUSED), seen)
    }

    @Test
    fun `an address that needs no proof says why it was skipped`() {
        assertTrue(verifier(ScriptedHttp()).allows("wss://ha.example.com/api/websocket"))
        assertTrue(verifier(ScriptedHttp(), t = null).allows(ws))
        assertEquals(listOf(ProofOutcome.SKIPPED_TLS, ProofOutcome.SKIPPED_NO_TOKEN), seen)
    }

    @Test
    fun `the websocket path reports a skip once, with no probe, and the refusal once`() {
        val opened = ArrayList<String>()
        val inner = Connector { url, _ -> opened += url; object : WsTransport { override fun send(text: String) = true; override fun close(code: Int, reason: String) {} } }
        val worker = Executor { it.run() }
        val l = object : WsListener { override fun onOpen() {}; override fun onText(text: String) {}; override fun onClosed(reason: String) {} }
        VerifiedConnector(inner, verifier(ScriptedHttp()), worker).open("wss://ha.example.com/api/websocket", l)
        VerifiedConnector(inner, verifier(ScriptedHttp(), t = null), worker).open(ws, l)
        assertEquals(listOf(ProofOutcome.SKIPPED_TLS, ProofOutcome.SKIPPED_NO_TOKEN), seen)
        assertEquals(2, opened.size)
        seen.clear()
        VerifiedConnector(inner, verifier(ScriptedHttp(reply(404))), worker).open(ws, l)
        assertEquals(listOf(ProofOutcome.REFUSED), seen)
        assertEquals(2, opened.size, "the refused address never got a socket")
    }

    @Test
    fun `a logger that throws costs the log, never the decision`() {
        assertTrue(verifier(real(), onOutcome = { throw IllegalStateException("log down") }).allows(ws))
        assertFalse(verifier(ScriptedHttp(reply(404)), onOutcome = { throw IllegalStateException("log down") }).allows(ws))
    }

    @Test
    fun `every outcome is a short fixed word on one of the three proof stages, with nothing that could carry a secret`() {
        val words = ProofOutcome.entries.map { it.word }
        assertEquals(setOf("passed", "refused", "no_answer", "tls", "no_token"), words.toSet())
        for (w in words) assertTrue(Regex("[a-z_]{1,12}").matches(w), w)
        assertEquals(
            setOf(ConnectTimeline.Stage.PROOF_OK, ConnectTimeline.Stage.PROOF_FAILED, ConnectTimeline.Stage.PROOF_SKIPPED),
            ProofOutcome.entries.map { it.stage }.toSet(),
        )
        assertEquals(listOf("proof_ok", "proof_failed", "proof_skipped"), listOf(ConnectTimeline.Stage.PROOF_OK, ConnectTimeline.Stage.PROOF_FAILED, ConnectTimeline.Stage.PROOF_SKIPPED).map { it.wire })
    }

    @Test
    fun `the timeline records a proof stage with its word for the connection log`() {
        var now = 1_000L
        val t = ConnectTimeline({ now })
        val got = ArrayList<Pair<ConnectTimeline.Stage, String?>>()
        t.observer = object : ConnectTimeline.Observer { override fun mark(stage: ConnectTimeline.Stage, cls: String?) { got += stage to cls } }
        t.begin()
        now += 40
        val v = verifier(real(), onOutcome = { t.mark(it.stage, it.word) })
        assertTrue(v.allows(ws))
        assertEquals(listOf(ConnectTimeline.Stage.PROOF_OK to "passed"), got)
        assertTrue(t.has(ConnectTimeline.Stage.PROOF_OK))
    }
}
