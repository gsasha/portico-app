package com.meda.portico.core.auth

import com.meda.portico.core.ha.Connector
import com.meda.portico.core.ha.WsListener
import com.meda.portico.core.ha.WsTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executor

/** DEV-798 (SEC-02): Home Assistant proves itself before a token goes to a cleartext address. The server's fixture is vendored. */
class HomeProofTest {
    private val fixture = File("src/test/resources/whoami_v1.json")
    private val contract = Json.parseToJsonElement(fixture.readText()).jsonObject
    private val vector = contract["vector"]!!.jsonObject

    private val token = "refresh-token-" + "q".repeat(60)
    private val ws = "ws://192.168.1.7:8123/api/websocket"
    private val authUrl = "http://192.168.1.7:8123/auth/token"

    /** The vendored file is the server's, byte for byte: a change is a contract bump on both sides. */
    @Test
    fun `the vendored fixture is the one the server pins`() {
        val sha = MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("880ecdb4e76fa95ac868d5c2b3a144bf88994e99d6bd60efdce3cd70e0bfa107", sha)
    }

    @Test
    fun `the server's test vector is what this code computes`() {
        val t = vector["refresh_token"]!!.jsonPrimitive.content
        val nonce = vector["nonce"]!!.jsonPrimitive.content
        val hint = vector["hint"]!!.jsonPrimitive.content
        assertEquals(hint, HomeProof.hint(t))
        assertEquals(vector["proof"]!!.jsonPrimitive.content, HomeProof.proof(t, nonce, hint))
    }

    @Test
    fun `a nonce has the shape the server accepts and is never repeated`() {
        val shape = Regex("[A-Za-z0-9_-]{16,64}")
        val seen = HashSet<String>()
        repeat(200) { val n = HomeProof.newNonce(); assertTrue(shape.matches(n), n); seen += n }
        assertEquals(200, seen.size)
    }

    @Test
    fun `the request body is exactly a nonce and a hint`() {
        val o = Json.parseToJsonElement(HomeProof.requestBody("n".repeat(20), "0123456789abcdef")).jsonObject
        assertEquals(setOf("nonce", "hint"), o.keys)
    }

    @Test
    fun `only ws and http are cleartext, and the endpoint keeps the authority`() {
        assertTrue(HomeProof.isCleartext(ws) && HomeProof.isCleartext(authUrl) && HomeProof.isCleartext("WS://h/x"))
        assertFalse(HomeProof.isCleartext("wss://ha.example.com/api/websocket") || HomeProof.isCleartext("https://ha.example.com/auth/token"))
        assertEquals("http://192.168.1.7:8123/api/portico/whoami", HomeProof.endpoint(ws))
        assertEquals("http://192.168.1.7:8123/api/portico/whoami", HomeProof.endpoint(authUrl))
        assertEquals("http://ha.local/api/portico/whoami", HomeProof.endpoint("ws://ha.local/api/websocket?x=1"))
        for (bad in listOf("wss://h/x", "https://h/x", "ftp://h", "ws:///x", "ws://u:p@h/x", "ws://a b/x", "nonsense", "")) assertNull(HomeProof.endpoint(bad), bad)
    }

    // ---- a faithful fake of the server ------------------------------------------------------------------------

    /** The real Home Assistant: knows [serverToken], answers the proof the plugin computes, a 400 for anything it cannot match. */
    private fun server(serverToken: String?) = ScriptedHttp().also { h ->
        h.responder = { p ->
            val o = Json.parseToJsonElement(p.body).jsonObject
            val nonce = o["nonce"]!!.jsonPrimitive.content
            val hint = o["hint"]!!.jsonPrimitive.content
            if (serverToken != null && HomeProof.hint(serverToken) == hint) {
                reply(200, """{"proof":"${HomeProof.proof(serverToken, nonce, hint)}"}""")
            } else {
                reply(400, """{"error":"invalid_or_expired"}""")
            }
        }
    }

    private fun verifier(http: HttpPost, t: String? = token) = HomeVerifier(http, { t })

    @Test
    fun `the real server proves itself and a token may go`() {
        val http = server(token)
        assertTrue(verifier(http).allows(ws))
        val seen = http.seen.single()
        assertEquals("http://192.168.1.7:8123/api/portico/whoami", seen.url)
        assertEquals("application/json", seen.contentType)
        assertFalse(token in seen.body, "the probe never carries the token")
    }

    @Test
    fun `every probe has a fresh nonce`() {
        val http = server(token)
        val v = verifier(http)
        repeat(3) { assertTrue(v.allows(ws)) }
        assertEquals(3, http.seen.map { Json.parseToJsonElement(it.body).jsonObject["nonce"]!!.jsonPrimitive.content }.toSet().size)
    }

    @Test
    fun `a host that does not know the token gets none`() {
        assertFalse(verifier(server("some-other-token")).allows(ws))
        assertFalse(verifier(server(null)).allows(ws))
    }

    @Test
    fun `a host that guesses fails in every way it can`() {
        val guesses = listOf(
            reply(200, """{"proof":"${"0".repeat(64)}"}"""), reply(200, """{"proof":""}"""), reply(200, "{}"), reply(200, "not json"),
            reply(200, """{"proof":"x","extra":1}"""), reply(200, """{"proof":12}"""), reply(200, ""), reply(404), reply(500), reply(401),
            reply(200, """["proof"]"""), reply(302),
        )
        for (g in guesses) assertFalse(verifier(ScriptedHttp(g)).allows(ws), g.toString() + g.body)
    }

    @Test
    fun `a recorded proof is useless for the next nonce`() {
        val real = server(token)
        val v1 = HomeVerifier(real, { token }, { "n".repeat(20) })
        assertTrue(v1.allows(ws))
        val recorded = real.responder!!.invoke(real.seen.single())
        // the attacker replays the proof it saw; the phone asks again with a new nonce
        assertFalse(HomeVerifier(ScriptedHttp(recorded), { token }, { "m".repeat(20) }).allows(ws))
    }

    @Test
    fun `no answer at all is not a proof`() {
        assertFalse(verifier(ScriptedHttp(IOException("timeout"))).allows(ws))
        assertFalse(verifier(ScriptedHttp(RuntimeException("boom").let { IOException(it) })).allows(ws))
    }

    @Test
    fun `an address that needs no proof is never probed`() {
        val http = ScriptedHttp()
        assertTrue(verifier(http).allows("wss://ha.example.com/api/websocket"))   // TLS names the server
        assertTrue(verifier(http, t = null).allows(ws))                           // no refresh token: nothing to prove with
        assertTrue(verifier(http, t = "  ").allows(ws))
        assertTrue(http.seen.isEmpty())
        assertFalse(verifier(http).needsProof("wss://h/x"))
        assertFalse(verifier(http, t = null).needsProof(ws))
        assertTrue(verifier(http).needsProof(ws))
    }

    @Test
    fun `a cleartext address that cannot be parsed is refused, not trusted`() {
        assertFalse(verifier(ScriptedHttp()).allows("ws://u:p@h/x"))
    }

    // ---- the refresh call ---------------------------------------------------------------------------------------

    @Test
    fun `the refresh goes only to a server that proved itself, and then it is the one POST that carries the token`() {
        val probe = server(token)
        val sent = ScriptedHttp(reply(200, "ok"))
        val post = VerifiedHttpPost(sent, verifier(probe))
        assertEquals(200, post.post(authUrl, "application/x-www-form-urlencoded", "refresh_token=$token").status)
        assertEquals(1, sent.seen.size)
        assertTrue(probe.seen.single().url.endsWith("/api/portico/whoami"))
    }

    @Test
    fun `a server that cannot prove itself never sees the refresh token and PathedHttp moves to the tailnet`() {
        val sent = ScriptedHttp()                                   // must stay empty
        val tailnet = ScriptedHttp(reply(200, "ok"))
        val direct = VerifiedHttpPost(sent, verifier(server(null)))
        val http = PathedHttp(direct, tailnet, { true })
        assertEquals(200, http.post(authUrl, "application/x-www-form-urlencoded", "refresh_token=$token").status)
        assertTrue(sent.seen.isEmpty(), "nothing reached the unproven server")
        assertEquals(1, tailnet.seen.size)
    }

    @Test
    fun `with no tailnet an unproven server is a failure, never a token`() {
        val sent = ScriptedHttp()
        val http = PathedHttp(VerifiedHttpPost(sent, verifier(server(null))), null, { false })
        assertThrows(PathsFailedException::class.java) { http.post(authUrl, "application/x-www-form-urlencoded", "refresh_token=$token") }
        assertTrue(sent.seen.isEmpty())
    }

    @Test
    fun `the enrollment redemption is not gated - a code is not a token and the server may be a new one`() {
        val sent = ScriptedHttp(reply(200, "{}"))
        val probe = ScriptedHttp()
        VerifiedHttpPost(sent, verifier(probe)).post("http://192.168.1.7:8123/api/portico/enroll/redeem", "application/json", "{}")
        assertEquals(1, sent.seen.size)
        assertTrue(probe.seen.isEmpty())
    }

    @Test
    fun `a TLS refresh is not probed`() {
        val sent = ScriptedHttp(reply(200, "ok"))
        val probe = ScriptedHttp()
        VerifiedHttpPost(sent, verifier(probe)).post("https://ha.example.com/auth/token", "application/x-www-form-urlencoded", "a=b")
        assertTrue(probe.seen.isEmpty())
        assertEquals(1, sent.seen.size)
    }

    // ---- the websocket ------------------------------------------------------------------------------------------

    private class Opened(val url: String, val listener: WsListener) : WsTransport {
        var closed = false
        override fun send(text: String) = true
        override fun close(code: Int, reason: String) { closed = true }
    }

    private class RecordingConnector : Connector {
        val opened = ArrayList<Opened>()
        override fun open(url: String, listener: WsListener): WsTransport = Opened(url, listener).also { opened += it }
    }

    private class Listener : WsListener {
        val closes = ArrayList<String>()
        var opens = 0
        override fun onOpen() { opens++ }
        override fun onText(text: String) {}
        override fun onClosed(reason: String) { closes += reason }
    }

    /** Runs the work when [run] is called, so a test can look at the state in between. */
    private class Manual : Executor {
        private val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun run() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    @Test
    fun `the websocket opens only once the server has proved itself`() {
        val inner = RecordingConnector()
        val worker = Manual()
        val listener = Listener()
        val t = VerifiedConnector(inner, verifier(server(token)), worker).open(ws, listener)
        assertTrue(inner.opened.isEmpty(), "nothing is opened before the proof")
        worker.run()
        assertEquals(listOf(ws), inner.opened.map { it.url })
        assertTrue(listener.closes.isEmpty())
        assertTrue(t.send("x"))
    }

    @Test
    fun `an unproven server never gets a socket and the caller hears a close`() {
        val inner = RecordingConnector()
        val worker = Manual()
        val listener = Listener()
        val t = VerifiedConnector(inner, verifier(server(null)), worker).open(ws, listener)
        worker.run()
        assertTrue(inner.opened.isEmpty())
        assertEquals(1, listener.closes.size)
        assertFalse(t.send("x"))
    }

    @Test
    fun `closing while the proof is pending opens nothing`() {
        val inner = RecordingConnector()
        val worker = Manual()
        val listener = Listener()
        val t = VerifiedConnector(inner, verifier(server(token)), worker).open(ws, listener)
        t.close(1000, "no longer wanted")
        worker.run()
        assertTrue(inner.opened.isEmpty())
        assertTrue(listener.closes.isEmpty(), "the caller closed it; there is nobody to tell")
    }

    @Test
    fun `an address that needs no proof opens at once with no worker involved`() {
        val inner = RecordingConnector()
        val worker = Manual()
        VerifiedConnector(inner, verifier(ScriptedHttp()), worker).open("wss://ha.example.com/api/websocket", Listener())
        VerifiedConnector(inner, verifier(ScriptedHttp(), t = null), worker).open(ws, Listener())
        assertEquals(2, inner.opened.size)
    }

    @Test
    fun `a connector that throws after the proof is a close, not a crash`() {
        val worker = Manual()
        val listener = Listener()
        val throwing = Connector { _, _ -> throw IllegalArgumentException("refused URL") }
        VerifiedConnector(throwing, verifier(server(token)), worker).open(ws, listener)
        worker.run()
        assertEquals(listOf("refused URL"), listener.closes)
    }
}
