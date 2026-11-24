package com.meda.portico.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** DEV-544 checked without a device (working directory = app/): the strings, the layout and the wiring of the new states and screens. */
class EnrollResourcesTest {
    private val strings: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private fun core(path: String) = File("../core/src/main/kotlin/com/meda/portico/core/$path").readText()
    private fun args(s: String) = Regex("%(\\d)\\\$s").findAll(s).map { it.groupValues[1].toInt() }.toSortedSet().toList()

    @Test
    fun `the new strings exist, with the arguments the code passes`() {
        for (k in listOf("setup_code_saved", "enroll_redeeming", "enroll_done", "st_link_signed_out", "home_status_signed_out", "home_banner_signed_out")) {
            assertTrue(strings.getValue(k).isNotBlank(), k)
            assertEquals(emptyList<Int>(), args(strings.getValue(k)), k)
        }
        assertEquals(listOf(1), args(strings.getValue("enroll_retrying")))
    }

    @Test
    fun `the signed-out words tell the person what to do`() {
        for (k in listOf("st_link_signed_out", "home_banner_signed_out")) {
            val t = strings.getValue(k)
            assertTrue("signed out" in t && "Enroll it again" in t && "QR" in t, t)
        }
        assertEquals("signed out", strings["home_status_signed_out"])
    }

    @Test
    fun `every state has its mapping in the screens that switch on it`() {
        assertTrue("StatusLabel.SIGNED_OUT -> R.string.home_status_signed_out" in src("home/HomeActivity.kt"))
        assertTrue("Banner.SIGNED_OUT -> getString(R.string.home_banner_signed_out)" in src("home/HomeActivity.kt"))
        assertTrue("StatusKey.LINK_SIGNED_OUT -> R.string.st_link_signed_out" in src("setup/SetupChecks.kt"))
        assertTrue("SIGNED_OUT" in src("signaling/SignalingClient.kt").substringAfter("enum class State").substringBefore("\n"))
        assertTrue("SIGNED_OUT" in core("ring/SetupStatus.kt").substringAfter("enum class LinkState").substringBefore("\n"))
    }

    @Test
    fun `Settings has a place for the redemption's progress, hidden until there is some`() {
        val layout = File("src/main/res/layout/activity_setup.xml").readText()
        val tv = layout.substringAfter("@+id/setup_enroll_status").substringBefore("/>")
        assertTrue("android:visibility=\"gone\"" in tv)
        val setup = src("setup/SetupActivity.kt")
        assertTrue("renderEnroll(graph.enrollStatus)" in setup)
        assertTrue("graph.redeemPendingCode()" in setup.substringAfter("private fun applySave").substringBefore("// ---- PR-C2"))
        assertTrue("r.redeemsCode" in setup)
        assertTrue("graph.redeemPendingCode()" in setup.substringAfter("override fun onResume()").substringBefore("override fun onPause()"), "opening Settings continues a pending redemption")
        assertTrue("onEnrollChanged = null" in setup.substringAfter("override fun onPause()"), "no screen callback is left behind")
    }

    @Test
    fun `a process start continues a redemption the last process did not finish`() {
        val g = src("AppGraph.kt")
        assertTrue("redeemPendingCode()" in g.substringAfter("fun start()").substringBefore("// ---- Pause"))
    }

    @Test
    fun `the launcher, Stay connected and the offline-registration inputs count a refresh-token phone as enrolled`() {
        assertTrue("s?.enrolled == true" in src("MainActivity.kt"))
        val g = src("AppGraph.kt")
        assertEquals(2, Regex("s\\?\\.enrolled == true").findAll(g).count())
        assertFalse("haToken.isNullOrBlank" in g, "no place still tests only the long-lived token")
        assertFalse("haToken.isNullOrBlank" in src("MainActivity.kt"))
    }

    @Test
    fun `the connection config hands the websocket the login only while a refresh token is stored`() {
        val s = src("store/PrefsStateStores.kt")
        assertTrue("override val login: AccessTokens?" in s)
        assertTrue("it.refreshToken != null && it.clientId != null" in s)
        assertTrue("override val accessToken: String? get() = settings()?.haToken" in s, "the old token is still served as it was")
        assertTrue("SettingsConnectionConfig(tokens, ::settings)" in src("AppGraph.kt"))
    }

    @Test
    fun `nothing that handles a code or a token logs or prints`() {
        val files = File("../core/src/main/kotlin/com/meda/portico/core/auth").listFiles()!!.map { it.readText() } +
            src("signaling/OkHttpPost.kt")
        for (t in files) {
            assertFalse(Regex("""\blog\.log\(|\bLog\.[dviwe]\(|println\(|HttpLoggingInterceptor|printStackTrace""").containsMatchIn(t))
        }
        // the AppGraph lines around the redemption log outcome words only
        val g = src("AppGraph.kt").substringAfter("// ---- DEV-544").substringBefore("val signaling: SignalingClient")
        for (m in Regex("""log\.log\([^\n]*""").findAll(g)) assertFalse('$' in m.value.replace("\${e.javaClass.simpleName}", ""), "a log line here interpolates only an exception's class name: ${m.value}")
    }

    @Test
    fun `the release is recorded in the build file only`() {
        val gradle = File("build.gradle.kts").readText()
        assertEquals(1, Regex("val porticoVersionName = ").findAll(gradle).count())
        assertTrue(Regex("""val porticoVersionName = "\d+\.\d+\.\d+-alpha"""").containsMatchIn(gradle)) // not pinned to one version: every bump used to break this
    }
}
