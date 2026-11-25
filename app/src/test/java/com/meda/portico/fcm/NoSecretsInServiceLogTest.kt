package com.meda.portico.fcm

import com.meda.portico.core.enroll.FirebaseConfig
import com.meda.portico.core.enroll.FirebaseConfigResult
import com.meda.portico.core.fcm.FirebaseBootCore
import com.meda.portico.core.fcm.FirebaseInitializer
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorsCache
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.PorticoLink
import com.meda.portico.core.ha.RegistrationSource
import com.meda.portico.core.ha.WsTransport
import com.meda.portico.core.push.SocketCallSink
import com.meda.portico.core.reg.RegistrationMachine
import com.meda.portico.store.FakeSharedPreferences
import com.meda.portico.store.PrefsCallStore
import com.meda.portico.store.PrefsDoorsCacheStore
import com.meda.portico.store.PrefsRegistrationStore
import com.meda.portico.store.Settings
import com.meda.portico.store.SettingsConnectionConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P2 plan §3.7: rendered log lines from the service path never hold a registration (FID or
 * token), the HA token or the Firebase api key in full. Drives the same objects the app wires:
 * boot, onRegistered/onNewToken, the link over a fake socket (auth, version, doors, register,
 * subscribe events), the push receive path and the default (logging) outcome handler.
 */
class NoSecretsInServiceLogTest {
    private val fid = "cPYZbX0nQ0mJ9mVdK3x_-A"
    private val fid2 = "dQWEbX0nQ0mJ9mVdK3x_-B"
    private val token = "$fid:APA91bHsecretTokenBody" + "Z".repeat(120)
    private val haToken = "fake-jwt-header.ha-long-lived-access-token.SIGNATURE-0123456789"
    private val apiKey = "${"AIza"}SyFAKE-secrets-test-key-0123456789XY"
    private val secrets = listOf(fid, fid2, token, haToken, apiKey)

    private val lines = ArrayList<String>()
    private val log = HaLog { level, msg -> lines += "$level $msg" }

    @Test
    fun `no secret string appears in any rendered line`() {
        // Boot: the api key goes in, only a project id may come out.
        val config = (FirebaseConfig.create("portico-test", "123456789012", "1:123456789012:android:0a1b2c3d", apiKey) as FirebaseConfigResult.Ok).config
        val boot = FirebaseBootCore(object : FirebaseInitializer {
            var app: FirebaseConfig? = null
            override fun hasDefaultApp() = app != null
            override fun running() = app
            override fun initialize(config: FirebaseConfig) {
                app = config
                throw IllegalArgumentException("bad api key $apiKey") // a message that quotes a value
            }
        })
        log.log(HaLog.Level.INFO, "boot: ${boot.ensure(config)}")
        val ready = boot.ensure(config)
        log.log(HaLog.Level.INFO, "boot: $ready ${ready.statusLine()} $config")

        // The app's stores and link, as AppGraph wires them.
        val statePrefs = FakeSharedPreferences()
        val doors = DoorsCache(PrefsDoorsCacheStore(statePrefs))
        val calls = PrefsCallStore(FakeSharedPreferences())
        val dispatcher = OutcomeDispatcher(LoggingOutcomeHandler(log), { log.log(HaLog.Level.INFO, "wake") }, { true }, log)
        val receiver = PushReceiver(calls, doors::knownDoorRefs, dispatcher, log)
        val regStore = PrefsRegistrationStore(statePrefs) { "portico-test" }
        val source = object : RegistrationSource {
            override fun requestFid() = log.log(HaLog.Level.INFO, "requestFid")
            override fun requestToken() = log.log(HaLog.Level.INFO, "requestToken")
        }
        val sink = SocketCallSink(calls, doors, { 1_760_000_000_000 }, { _, o -> dispatcher.dispatch(o) }, { log.log(HaLog.Level.INFO, it) })
        val cfg = ready.registrationConfig("0b6f3a52-0000-4000-8000-000000000001", "0.0.1")
        val link = PorticoLink(RegistrationMachine(cfg, regStore, { 0L }, log), doors, sink, source, log = log)
        val logic = MessagingServiceLogic(receiver, { v -> link.onRegistrationValue(v) }, { cfg.installationId }, log)
        val haConfig = SettingsConnectionConfig { null as Settings? }
        log.log(HaLog.Level.INFO, "config $haConfig")

        link.start()
        logic.onRegistered(fid)
        logic.onNewToken(token)
        val sent = ArrayList<String>()
        val wire = object : WsTransport {
            override fun send(text: String): Boolean = sent.add(text)
            override fun close(code: Int, reason: String) {}
        }
        link.onSocketOpen(wire, haToken)
        link.onSocketText("""{"type":"auth_required","ha_version":"2026.10.0"}""")
        link.onSocketText("""{"type":"auth_ok","ha_version":"2026.10.0"}""")
        link.onSocketText("""{"id":1,"type":"result","success":true,"result":{"contract":3,"ui_version":1,"providers":[]}}""")
        link.onSocketText("""{"id":2,"type":"result","success":true,"result":{"doors":[{"door_id":"gate-sip.example","door_ref":"01J9ENTRYGATE","endpoint_id":"fake:gate","name":"Gate","driver":"fake"}]}}""")
        link.onSocketText("""{"id":3,"type":"result","success":true,"result":{"reg_id":"r1","push_enabled":true}}""")
        logic.onRegistered(fid2) // rotation: the register after the connect's six commands
        link.onSocketText("""{"id":7,"type":"result","success":false,"error":{"code":"invalid_registration","message":"bad"}}""")
        link.onSocketText("""{"id":4,"type":"event","event":{"type":"call_state_changed","door_id":"gate-sip.example","call_id":"gso-1-2-x","state":"ringing"}}""")
        link.onSocketText("""{"id":4,"type":"event","event":{"type":"call_state_changed","door_id":"gate-sip.example","call_id":"gso-1-2-x","state":"ended","prev_state":"ringing"}}""")
        val t = 1_760_000_000_000L
        for (kind in listOf("ring", "answered", "ended")) {
            logic.onMessage(mapOf("pv" to "2", "kind" to kind, "door_ref" to "01J9ENTRYGATE", "call_id" to "c1", "seq" to "1", "t" to "$t", "exp" to "${t + 45_000}"), t)
        }
        logic.onMessage(mapOf("pv" to "2", "kind" to "test", "t" to "$t", "registration" to token), t)
        log.log(HaLog.Level.INFO, "link $link store $regStore")

        assertTrue(sent.any { haToken in it } && sent.any { fid in it }, "the secrets did reach the wire (the test exercised them)")
        assertTrue(lines.size > 15, "too few lines to mean anything: $lines")
        for (line in lines) for (s in secrets) assertTrue(s !in line, "secret leaked into: $line")
    }
}
