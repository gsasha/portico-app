package com.meda.portico

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Assertions on the MERGED debug manifest (P2 plan §3.1, §3.4; PR-E), i.e. what the APK really
 * declares after every library manifest (firebase-messaging, androidx.startup from OkHttp, ...)
 * has been merged in. `app/build.gradle.kts` makes `testDebugUnitTest` depend on
 * `processDebugManifest` and passes the output path as `portico.mergedManifest`.
 */
class MergedManifestTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private val root: Element by lazy {
        val path = System.getProperty("portico.mergedManifest")
        assertNotNull(path, "portico.mergedManifest is not set: run through Gradle's testDebugUnitTest")
        val file = File(path!!)
        assertTrue(file.isFile, "merged manifest missing at $path")
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        f.newDocumentBuilder().parse(file).documentElement
    }

    private val application: Element get() = root.children("application").single()

    private fun Element.children(tag: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && n.tagName == tag) out += n
        }
        return out
    }

    private fun Element.a(name: String): String? = getAttributeNS(androidNs, name).ifEmpty { null }

    private fun component(tag: String, name: String): Element? = application.children(tag).firstOrNull { it.a("name") == name }

    private fun actions(e: Element): Set<String> =
        e.children("intent-filter").flatMap { f -> f.children("action").mapNotNull { it.a("name") } }.toSet()

    private fun metaData(name: String): String? = application.children("meta-data").firstOrNull { it.a("name") == name }?.a("value")

    @Test
    fun `FirebaseInitProvider is removed - no auto-init with half-configured options`() {
        val providers = application.children("provider").mapNotNull { it.a("name") }
        assertTrue("com.google.firebase.provider.FirebaseInitProvider" !in providers, "providers: $providers")
    }

    @Test
    fun `the messaging service is present, not exported, and wins MESSAGING_EVENT`() {
        val svc = component("service", "com.meda.portico.fcm.PorticoMessagingService")
        assertNotNull(svc, "PorticoMessagingService not in the merged manifest")
        assertEquals("false", svc!!.a("exported"))
        assertTrue("com.google.firebase.MESSAGING_EVENT" in actions(svc))
        // The library's generic FirebaseMessagingService entry stays merged in at priority -500
        // (as in every Gradle-built Firebase app); ours must resolve first.
        fun priority(e: Element) = e.children("intent-filter").firstOrNull { f ->
            f.children("action").any { it.a("name") == "com.google.firebase.MESSAGING_EVENT" }
        }?.a("priority")?.toInt() ?: 0
        val others = application.children("service").filter { it !== svc && "com.google.firebase.MESSAGING_EVENT" in actions(it) }
        for (o in others) {
            assertEquals("false", o.a("exported"), "${o.a("name")} must not be exported")
            assertTrue(priority(svc) > priority(o), "${o.a("name")} would outrank PorticoMessagingService")
        }
    }

    @Test
    fun `Firebase meta-data - auto-init off, FID registration on, no delivery-metrics export`() {
        assertEquals("false", metaData("firebase_messaging_auto_init_enabled"))
        // firebase-messaging 25.1.3: register() fails unless this is true (verified in its bytecode).
        assertEquals("true", metaData("firebase_messaging_installation_id_enabled"))
        assertEquals("false", metaData("delivery_metrics_exported_to_big_query_enabled"))
        // firebase-common's data-collection default: the only such flag the closure reads (only
        // messaging's AutoInit consults it, as a fallback to the explicit flag above).
        assertEquals("false", metaData("firebase_data_collection_default_enabled"))
    }

    @Test
    fun `boot and package-replaced receiver re-triggers registration`() {
        val r = component("receiver", "com.meda.portico.boot.BootReceiver")
        assertNotNull(r, "BootReceiver not in the merged manifest")
        assertEquals("false", r!!.a("exported"))
        assertEquals(
            setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"),
            actions(r),
        )
        val perms = root.children("uses-permission").mapNotNull { it.a("name") }
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in perms, "perms: $perms")
        assertTrue("android.permission.INTERNET" in perms, "perms: $perms")
    }

    @Test
    fun `queries name Fully's package and nothing else, and HomeActivity stays unexported (DEV-807 slice 2)`() {
        val q = root.children("queries").single()
        val pkgs = q.children("package").mapNotNull { it.a("name") }
        assertEquals(listOf("de.ozerov.fully"), pkgs.filter { it == "de.ozerov.fully" })
        assertEquals(com.meda.portico.panel.PanelFrontHost.FULLY_PACKAGE, "de.ozerov.fully")
        assertEquals("false", component("activity", "com.meda.portico.home.HomeActivity")!!.a("exported"))
    }

    @Test
    fun `the Application class is Portico's`() {
        assertEquals("com.meda.portico.PorticoApp", application.a("name"))
    }

    @Test
    fun `nothing is exported beyond the launcher and system-permission-guarded receivers`() {
        // name -> the permission the caller must hold (null: the launcher, open by design).
        val allowed = mapOf(
            "com.meda.portico.MainActivity" to null,
            // FCM delivery: only Google Play services holds c2dm SEND.
            "com.google.firebase.iid.FirebaseInstanceIdReceiver" to "com.google.android.c2dm.permission.SEND",
            // androidx.profileinstaller (via appcompat; present before PR-E): adb/system only.
            "androidx.profileinstaller.ProfileInstallReceiver" to "android.permission.DUMP",
        )
        val exported = listOf("activity", "activity-alias", "service", "receiver", "provider")
            .flatMap { application.children(it) }
            .filter { it.a("exported") == "true" }
        val names = exported.mapNotNull { it.a("name") }.toSet()
        assertTrue(names.all { it in allowed }, "unexpected exported components: ${names - allowed.keys}")
        for (e in exported) {
            assertEquals(allowed[e.a("name")], e.a("permission"), "${e.a("name")} must be guarded")
        }
        assertTrue("com.google.firebase.iid.FirebaseInstanceIdReceiver" in names, "FCM's delivery receiver is missing")
    }

    // ---- PR-F: ring UI ------------------------------------------------------------------------

    private val permissions: Set<String> get() = root.children("uses-permission").mapNotNull { it.a("name") }.toSet()

    @Test
    fun `PR-F - full-screen intent and notification permissions are declared`() {
        assertTrue("android.permission.USE_FULL_SCREEN_INTENT" in permissions, "perms: $permissions")
        assertTrue("android.permission.POST_NOTIFICATIONS" in permissions, "perms: $permissions")
    }

    @Test
    fun `PR-F - no exact-alarm permission (the backstop is setAndAllowWhileIdle) and the only foreground services are the answer path's microphone one and Stay connected's connectedDevice one`() {
        for (p in listOf("android.permission.SCHEDULE_EXACT_ALARM", "android.permission.USE_EXACT_ALARM")) {
            assertTrue(p !in permissions, "$p must not be declared")
        }
        // The ring needs no foreground service. Only the answer path's CallService declares one, and
        // only the microphone type: FOREGROUND_SERVICE_PHONE_CALL needs MANAGE_OWN_CALLS, which Portico lacks.
        // Stay connected (2026-10-15) adds the connectedDevice type (ConnectionService), pinned in its own test below.
        assertEquals(
            setOf(
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MICROPHONE",
                "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
            ),
            permissions.filter { it.startsWith("android.permission.FOREGROUND_SERVICE") }.toSet(),
        )
        val typed = application.children("service")
            .filter { it.a("name")?.startsWith("com.meda.portico.") == true && it.a("foregroundServiceType") != null }
            .associate { it.a("name") to it.a("foregroundServiceType") }
        assertEquals(
            mapOf("com.meda.portico.call.CallService" to "microphone", "com.meda.portico.stay.ConnectionService" to "connectedDevice"),
            typed,
        )
    }

    @Test
    fun `APP-03 - a station call has no ring screen - Home takes the ring, singleTop, not exported, over the lock screen only from code`() {
        assertNull(component("activity", "com.meda.portico.ring.RingActivity"), "the ring screen is gone")
        val a = component("activity", "com.meda.portico.home.HomeActivity")
        assertNotNull(a, "HomeActivity not in the merged manifest")
        assertEquals("false", a!!.a("exported"), "only Portico's own PendingIntents open it")
        assertEquals("singleTop", a.a("launchMode"), "a ring reaches an open Home via onNewIntent")
        assertNull(a.a("showWhenLocked"), "Home is over the lock screen only while a station call lasts, set in code")
        assertNull(a.a("turnScreenOn"))
        assertTrue(a.children("intent-filter").isEmpty(), "no intent filter: nothing outside the app can target it")
    }

    @Test
    fun `0_6_75 - the call window is the ring window - not exported, over the lock screen, singleTask in its own task, out of the recents`() {
        val a = component("activity", "com.meda.portico.ring.RingCallActivity")
        assertNotNull(a, "RingCallActivity not in the merged manifest")
        assertEquals("false", a!!.a("exported"), "only Portico's own PendingIntents and its direct launch open it")
        assertTrue(a.children("intent-filter").isEmpty(), "no intent filter: nothing outside the app can target it")
        assertEquals("true", a.a("showWhenLocked"))
        assertEquals("true", a.a("turnScreenOn"))
        assertEquals("true", a.a("excludeFromRecents"))
        assertEquals("singleTask", a.a("launchMode"), "one window per ring: a second ring or the Answer action arrives in onNewIntent")
        assertEquals("com.meda.portico.callwindow", a.a("taskAffinity"), "its own task: closing it never brings Home forward")
        assertEquals("@style/Theme.Portico.CallWindow", a.a("theme"))
    }

    @Test
    fun `PR-F - the ring receiver is not exported and has no intent filter`() {
        val r = component("receiver", "com.meda.portico.ring.RingReceiver")
        assertNotNull(r, "RingReceiver not in the merged manifest")
        assertEquals("false", r!!.a("exported"))
        assertTrue(r.children("intent-filter").isEmpty())
    }

    @Test
    fun `PR-F - no package visibility queries - Portico does not look for other apps (plan 3_6)`() {
        // DEV-807 slice 2: Panel mode must start Fully to go back to the dashboard, which needs exactly one package visible. Nothing else.
        val pkgs = root.children("queries").flatMap { q -> q.children("package").mapNotNull { it.a("name") } + q.children("intent").map { "intent" } + q.children("provider").map { "provider" } }
        assertEquals(listOf("de.ozerov.fully"), pkgs, "the only <queries> entry is Fully's package")
    }

    // ---- PR-G: permission cleanup ----------------------------------------------------------------

    @Test
    fun `PR-G - the microphone and the foreground service are declared only because the answer path requests and uses them`() {
        // Declared with the code that uses them (ship checklist 4: a declared permission with no request is a bug):
        // RECORD_AUDIO is requested by InCallActivity, the foreground service is CallService (type microphone).
        for (p in listOf("android.permission.RECORD_AUDIO", "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MICROPHONE")) {
            assertTrue(p in permissions, "$p is needed by the answer path")
        }
        assertTrue("android.permission.FOREGROUND_SERVICE_PHONE_CALL" !in permissions, "the phone-call type needs MANAGE_OWN_CALLS")
        // The microphone must never make the app uninstallable on a device without one.
        val mic = feature("android.hardware.microphone")
        assertNotNull(mic, "android.hardware.microphone must be declared (optional)")
        assertEquals("false", mic!!.a("required"))
        val service = component("service", "com.meda.portico.call.CallService")
        assertNotNull(service, "CallService not in the merged manifest")
        assertEquals("false", service!!.a("exported"))
        assertTrue(service.children("intent-filter").isEmpty())
        val call = component("activity", "com.meda.portico.call.InCallActivity")
        assertNotNull(call, "InCallActivity not in the merged manifest")
        assertEquals("false", call!!.a("exported"))
        assertTrue(call.children("intent-filter").isEmpty())
        val out = component("activity", "com.meda.portico.call.OutgoingCallActivity")
        assertNotNull(out, "OutgoingCallActivity not in the merged manifest")
        assertEquals("false", out!!.a("exported"))
        assertTrue(out.children("intent-filter").isEmpty())
        val receiver = component("receiver", "com.meda.portico.call.CallReceiver")
        assertNotNull(receiver, "CallReceiver not in the merged manifest")
        assertEquals("false", receiver!!.a("exported"))
        assertTrue(receiver.children("intent-filter").isEmpty())
    }

    @Test
    fun `PR-G - the permissions the app does need are all merged in`() {
        val needed = setOf(
            "android.permission.INTERNET", // the HA WebSocket
            "android.permission.ACCESS_NETWORK_STATE", // app + firebase-messaging, play-services-cloud-messaging
            "android.permission.POST_NOTIFICATIONS", // the ring and status notifications (requested from Setup)
            "android.permission.USE_FULL_SCREEN_INTENT", // the ring over the lock screen
            "android.permission.RECEIVE_BOOT_COMPLETED", // BootReceiver re-registers
            "android.permission.WAKE_LOCK", // merged from firebase-messaging: holds the CPU while a push is handled
            "com.google.android.c2dm.permission.RECEIVE", // merged from firebase-messaging: FCM delivery
        )
        assertTrue(permissions.containsAll(needed), "missing: ${needed - permissions}")
    }

    @Test
    fun `androidx startup provider from OkHttp is expected and not exported`() {
        val p = component("provider", "androidx.startup.InitializationProvider")
        assertNotNull(p, "OkHttp 5 brings androidx.startup; its provider should be merged in")
        assertEquals("false", p!!.a("exported"))
    }

    // ---- PR-C2: scan the enrollment QR --------------------------------------------------------

    private fun feature(name: String): Element? = root.children("uses-feature").firstOrNull { it.a("name") == name }

    @Test
    fun `PR-C2 - CAMERA is declared and every camera feature is optional, so the app installs without a camera`() {
        assertTrue("android.permission.CAMERA" in permissions, "perms: $permissions")
        // CAMERA alone makes Play/the installer imply android.hardware.camera(+autofocus) REQUIRED.
        for (f in listOf("android.hardware.camera", "android.hardware.camera.any", "android.hardware.camera.autofocus")) {
            val e = feature(f)
            assertNotNull(e, "$f must be declared (optional)")
            assertEquals("false", e!!.a("required"), "$f must be required=false")
        }
        val required = root.children("uses-feature").filter { it.a("required") != "false" }.mapNotNull { it.a("name") }
        assertTrue(required.none { it.startsWith("android.hardware.camera") }, "required camera features: $required")
    }

    @Test
    fun `PR-C2 - the scanner is not exported, has no intent filter and is portrait-locked`() {
        val a = component("activity", "com.meda.portico.setup.ScanActivity")
        assertNotNull(a, "ScanActivity not in the merged manifest")
        assertEquals("false", a!!.a("exported"), "only Setup opens it")
        assertTrue(a.children("intent-filter").isEmpty())
        assertEquals("portrait", a.a("screenOrientation"))
    }

    @Test
    fun `PR-C2 - no Play services scanner module or CameraX is merged in`() {
        val names = listOf("activity", "service", "receiver", "provider").flatMap { application.children(it) }.mapNotNull { it.a("name") }
        assertTrue(names.none { "mlkit" in it || "codescanner" in it.lowercase() || "androidx.camera" in it }, "$names")
        val meta = application.children("meta-data").mapNotNull { it.a("name") }
        assertTrue(meta.none { it.endsWith("vision.DEPENDENCIES") }, "a Play services vision module download is declared: $meta")
    }

    // ---- PR-J: network policy ---------------------------------------------------------------

    @Test
    fun `PR-J - the application names the network security config and does not set usesCleartextTraffic`() {
        assertEquals("@xml/network_security_config", application.a("networkSecurityConfig"))
        assertEquals(null, application.a("usesCleartextTraffic"), "the config supersedes usesCleartextTraffic; do not set both")
    }

    @Test
    fun `PR-J - the config permits cleartext only through base-config, with system trust anchors and no overrides`() {
        val path = System.getProperty("portico.networkSecurityConfig")
        assertNotNull(path, "portico.networkSecurityConfig is not set: run through Gradle's testDebugUnitTest")
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val nsc = f.newDocumentBuilder().parse(File(path!!)).documentElement
        assertEquals("network-security-config", nsc.tagName)
        val base = nsc.children("base-config").single()
        assertEquals("true", base.getAttribute("cleartextTrafficPermitted"))
        // No per-domain carve-outs and no debug overrides: the one rule lives in UrlPolicy.
        assertTrue(nsc.children("domain-config").isEmpty(), "domain-config present")
        assertTrue(nsc.children("debug-overrides").isEmpty(), "debug-overrides present")
        val anchors = base.children("trust-anchors").single().children("certificates").map { it.getAttribute("src") }
        assertEquals(listOf("system"), anchors, "only system CAs: no user-added CAs")
        // The comment must say where the real rule is, so nobody "fixes" this file alone.
        val text = File(path).readText()
        assertTrue("UrlPolicy" in text && "https" in text, "the explaining comment is missing")
    }

    @Test
    fun `the application id is com_meda_portico and every id-derived name follows it`() {
        // applicationId and the code namespace are both com.meda.portico: the package
        // and every name AGP derives from ${applicationId} carry that id.
        assertEquals("com.meda.portico", root.getAttribute("package"))
        val perm = "com.meda.portico.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        assertEquals(listOf(perm), root.children("permission").mapNotNull { it.a("name") })
        assertTrue(perm in root.children("uses-permission").mapNotNull { it.a("name") })
        val authorities = application.children("provider").mapNotNull { it.a("authorities") }
        assertTrue(authorities.isNotEmpty() && authorities.all { it.startsWith("com.meda.portico.") }, "authorities: $authorities")
    }

    // ---- Pause ("Pause this phone") ------------------------------------------------------------

    @Test
    fun `Pause - the permission set is exactly the one pinned here, no exact alarm, and a change is a deliberate decision`() {
        // Pinned on 2026-10-09 from main's APK (aapt2 dump badging). The pause's auto-resume is
        // setAndAllowWhileIdle (no SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM), its notification and
        // receiver need nothing new. A change here is a deliberate decision, not a side effect.
        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.ACCESS_WIFI_STATE",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.USE_FULL_SCREEN_INTENT",
                "android.permission.CAMERA",
                // Answering a door call: the microphone, its foreground service, and the call audio mode.
                "android.permission.RECORD_AUDIO",
                "android.permission.MODIFY_AUDIO_SETTINGS",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MICROPHONE",
                // Stay connected (2026-10-15): the connectedDevice service and the permission Android 14+ requires for that type.
                "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
                "android.permission.CHANGE_NETWORK_STATE",
                "android.permission.WAKE_LOCK",
                "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.VIBRATE",
                // 0.6.96: androidx.biometric's manifest (normal permissions, no prompt): the in-call screen's door-button confirmation while locked.
                "android.permission.USE_BIOMETRIC", "android.permission.USE_FINGERPRINT",
                "com.google.android.c2dm.permission.RECEIVE",
                "com.meda.portico.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ),
            permissions,
        )
    }

    @Test
    fun `Pause - its receiver (auto-resume alarm, the notification's Resume) is not exported and has no intent filter`() {
        val r = component("receiver", "com.meda.portico.pause.PauseReceiver")
        assertNotNull(r, "PauseReceiver not in the merged manifest")
        assertEquals("false", r!!.a("exported"), "only Portico's own PendingIntents reach it")
        assertTrue(r.children("intent-filter").isEmpty())
        assertEquals(null, r.a("permission"))
    }

    @Test
    fun `Stay connected - the connection service is connectedDevice, not exported, no intent filter, survives the task, and has its prerequisite permission`() {
        val s = component("service", "com.meda.portico.stay.ConnectionService")
        assertNotNull(s, "ConnectionService not in the merged manifest")
        assertEquals("false", s!!.a("exported"))
        assertEquals("connectedDevice", s.a("foregroundServiceType"))
        assertEquals("false", s.a("stopWithTask"))
        assertTrue(s.children("intent-filter").isEmpty())
        // Android 14+: startForeground(connectedDevice) throws without one of the type's prerequisite permissions.
        val prerequisites = setOf(
            "android.permission.CHANGE_NETWORK_STATE", "android.permission.CHANGE_WIFI_STATE",
            "android.permission.CHANGE_WIFI_MULTICAST_STATE", "android.permission.NFC", "android.permission.TRANSMIT_IR",
        )
        assertTrue(permissions.any { it in prerequisites }, "no connectedDevice prerequisite in $permissions")
        // Not a way around the background-start rule or a battery-policy override.
        for (p in listOf("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")) {
            assertTrue(p !in permissions, "$p must not be declared")
        }
    }

    @Test
    fun `the application declares the launcher icon and the round icon, both mipmaps`() {
        assertEquals("@mipmap/ic_launcher", application.a("icon"))
        assertEquals("@mipmap/ic_launcher_round", application.a("roundIcon"))
    }
}
