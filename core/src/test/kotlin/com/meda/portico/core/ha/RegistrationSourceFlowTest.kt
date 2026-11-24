package com.meda.portico.core.ha

import com.meda.portico.core.fcm.BootResult
import com.meda.portico.core.ha.WsContract.HA_TOKEN
import com.meda.portico.core.reg.InMemoryRegistrationStore
import com.meda.portico.core.reg.RegKind
import com.meda.portico.core.reg.RegValue
import com.meda.portico.core.reg.RegValues
import com.meda.portico.core.reg.RegistrationMachine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PR-E: a fake [RegistrationSource] (what the app's FirebaseRegistrationSource does with
 * `FirebaseMessaging`) driving D's machine through [PorticoLink], the way the messaging service's
 * `onRegistered` / `onNewToken` feed it (through [RegValues]).
 */
class RegistrationSourceFlowTest {

    /** Records what the machine asked Firebase for; the test answers like the service would. */
    class FakeSource : RegistrationSource {
        var fidRequests = 0
        var tokenRequests = 0
        override fun requestFid() { fidRequests++ }
        override fun requestToken() { tokenRequests++ }
    }

    private val fid = "cPYZbX0nQ0mJ9mVdK3x_-A"
    private val fid2 = "dQWEbX0nQ0mJ9mVdK3x_-B"
    private val token = "$fid:APA91bH" + "t".repeat(140)
    private var now = 0L
    private val source = FakeSource()
    private val store = InMemoryRegistrationStore()
    private val logs = mutableListOf<String>()

    private fun link(boot: BootResult = BootResult.Ready(WsContract.PROJECT)): PorticoLink {
        val cfg = boot.registrationConfig(WsContract.INSTALLATION, "0.1.0")
        val log = HaLog { level, msg -> logs += "$level $msg" }
        return PorticoLink(RegistrationMachine(cfg, store, { now }, log), DoorsCache(InMemoryDoorsCacheStore()), {}, source, log = log)
    }

    private class Wire : WsTransport {
        val sent = mutableListOf<JsonObject>()
        override fun send(text: String): Boolean = sent.add(Json.parseToJsonElement(text).jsonObject)
        override fun close(code: Int, reason: String) {}
        fun registers() = sent.filter { it["type"]?.jsonPrimitive?.content == "portico/push/register" }
        fun lastId() = sent.last()["id"]!!.jsonPrimitive.int
    }

    private fun connect(l: PorticoLink): Wire {
        val w = Wire()
        l.onSocketOpen(w, HA_TOKEN)
        l.onSocketText(WsContract.AUTH_REQUIRED)
        l.onSocketText(WsContract.AUTH_OK)
        WsContract.frames("version: phone", w.lastId()).forEach(l::onSocketText)
        WsContract.frames("doors: enrolled phone", w.lastId()).forEach(l::onSocketText)
        return w
    }

    private fun Wire.answerRegister(l: PorticoLink) {
        val id = registers().last()["id"]!!.jsonPrimitive.int
        l.onSocketText(WsContract.reply("push register: ok", id))
    }

    private fun JsonObject.kindAndValue() = this["kind"]!!.jsonPrimitive.content to this["registration"]!!.jsonPrimitive.content

    @Test
    fun `FID - start asks for it, onRegistered registers it as kind fid`() {
        val l = link()
        l.start()
        assertEquals(1, source.fidRequests)
        val w = connect(l)
        assertEquals(emptyList<JsonObject>(), w.registers(), "nothing to send before Firebase answers")
        l.onRegistrationValue(RegValues.fromOnRegistered(fid))
        assertEquals("fid" to fid, w.registers().single().kindAndValue())
    }

    @Test
    fun `token fallback - no onRegistered within the wait, then the token registers as kind token`() {
        val l = link()
        l.start()
        val w = connect(l)
        now += RegistrationMachine.TOKEN_FALLBACK_MS - 1
        l.tick()
        assertEquals(0, source.tokenRequests, "not before the machine's wait")
        now += 1
        l.tick()
        assertEquals(1, source.tokenRequests)
        l.onRegistrationValue(RegValues.fromOnNewToken(token))
        assertEquals("token" to token, w.registers().single().kindAndValue())
    }

    @Test
    fun `register() failing outright falls back to the token at once`() {
        val l = link()
        l.start()
        l.onRegistrationSourceFailed()
        assertEquals(1, source.tokenRequests)
    }

    @Test
    fun `rotation - a new FID after a registered one re-registers, a token after an FID does not`() {
        val l = link()
        l.start()
        val w = connect(l)
        l.onRegistrationValue(RegValues.fromOnRegistered(fid))
        w.answerRegister(l)
        // In FID mode onNewToken never fires (25.1.3); if a token did arrive, the FID stays in use.
        l.onRegistrationValue(RegValues.fromOnNewToken(token))
        assertEquals(1, w.registers().size)
        l.onRegistrationValue(RegValues.fromOnRegistered(fid2))
        assertEquals(listOf("fid" to fid, "fid" to fid2), w.registers().map { it.kindAndValue() })
    }

    @Test
    fun `repeated onRegistered with the same FID never registers twice`() {
        val l = link()
        l.start()
        val w = connect(l)
        repeat(4) { l.onRegistrationValue(RegValues.fromOnRegistered(fid)) }
        w.answerRegister(l)
        repeat(4) { l.onRegistrationValue(RegValues.fromOnRegistered(fid)) }
        assertEquals(1, w.registers().size)
    }

    @Test
    fun `every connect registers the cached value again (no Firebase round trip needed)`() {
        store.value = RegValue(RegKind.FID, fid)
        val l = link()
        repeat(3) {
            val w = connect(l)
            assertEquals("fid" to fid, w.registers().single().kindAndValue())
            l.onSocketClosed("test")
        }
    }

    @Test
    fun `NotConfigured boot - no request to Firebase, values ignored, nothing on the wire`() {
        val l = link(BootResult.NotConfigured)
        l.start()
        val w = connect(l)
        l.onRegistrationValue(RegValues.fromOnRegistered(fid))
        assertEquals(0, source.fidRequests)
        assertEquals(emptyList<JsonObject>(), w.registers())
    }

    @Test
    fun `Failed boot behaves like NotConfigured`() {
        val l = link(BootResult.Failed("initializeApp threw IllegalStateException"))
        l.start()
        assertEquals(0, source.fidRequests)
    }

    @Test
    fun `restartToApply - registers under the RUNNING project, not the newly saved one`() {
        val l = link(BootResult.Ready(WsContract.PROJECT, restartToApply = true))
        store.value = RegValue(RegKind.FID, fid)
        val w = connect(l)
        assertEquals(WsContract.PROJECT, w.registers().single()["fcm_project_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a failed fallback is retried on the next connect, not stuck for the process lifetime`() {
        val l = link()
        l.start()                       // RequestFid
        l.onRegistrationSourceFailed()  // register() failed (offline) -> RequestToken
        l.onRegistrationSourceFailed()  // getToken()/register() failed too
        assertEquals(1 to 1, source.fidRequests to source.tokenRequests)
        connect(l)
        assertEquals(2, source.fidRequests, "a connect with no value in hand asks Firebase again")
        l.onSocketClosed("x")
        connect(l)
        assertEquals(2, source.fidRequests, "at most once per process while that request is pending")
        assertTrue(logs.none { fid in it })
    }
}
