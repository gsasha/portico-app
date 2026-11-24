package com.meda.portico.setup

import com.meda.portico.core.ring.Severity
import com.meda.portico.core.ring.StatusKey
import com.meda.portico.core.ring.StatusLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** APP-04: which rows belong to which screen, and the badge on the Status button. Working directory = app/. */
class SettingsStatusSplitTest {
    private fun idsIn(group: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/layout/activity_setup.xml"))
        val all = doc.getElementsByTagName("*")
        fun id(e: Element) = e.getAttribute("android:id").removePrefix("@+id/")
        val root = (0 until all.length).map { all.item(it) as Element }.first { id(it) == group }
        val inner = root.getElementsByTagName("*")
        return (0 until inner.length).map { id(inner.item(it) as Element) }.filter { it.isNotEmpty() }.toSet()
    }

    private val settings by lazy { idsIn("setup_settings_screen") }
    private val status by lazy { idsIn("setup_status_screen") }

    @Test
    fun `settings holds what the owner changes`() {
        for (id in listOf(
            "setup_pause_resume", "setup_pause_choices", "setup_conn_wifi", "setup_video_wifi", "setup_conn_mobile", "setup_video_mobile",
            "setup_conn_roaming", "setup_video_roaming", "setup_ring_control",
            "setup_android_settings", "setup_android_app", "setup_android_notifications", "setup_android_calls", "setup_android_status_channel",
            "setup_house_group", "setup_scan", "setup_enrollment",
            "setup_save", "setup_connlog_switch",
        )) assertTrue(id in settings, id)
    }

    @Test
    fun `status holds the read-only health, with the fix buttons' container`() {
        for (id in listOf(
            "setup_ha_checks", "setup_push_checks", "setup_offline", "setup_status",
            "setup_traffic_rows", "setup_traffic_reset", "setup_share_diagnostics",
        )) assertTrue(id in status, id)
        for (gone in listOf("setup_ring_checks", "setup_version", "setup_journal_text", "setup_journal_clear", "setup_connlog_status")) assertTrue(gone !in status, gone)
    }

    @Test
    fun `no row is on both screens and none was dropped`() {
        assertEquals(emptySet<String>(), settings intersect status)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/layout/activity_setup.xml"))
        val all = doc.getElementsByTagName("*")
        val ids = (0 until all.length).map { (all.item(it) as Element).getAttribute("android:id").removePrefix("@+id/") }
            .filter { it.isNotEmpty() && !it.endsWith("_screen") && it != "setup_scroll" }.toSet()
        assertEquals(ids, settings + status)
    }

    @Test
    fun `the screen comes from the extra and defaults to settings`() {
        assertEquals(SettingsStatusSplit.Screen.SETTINGS, SettingsStatusSplit.Screen.of(null))
        assertEquals(SettingsStatusSplit.Screen.SETTINGS, SettingsStatusSplit.Screen.of("junk"))
        assertEquals(SettingsStatusSplit.Screen.STATUS, SettingsStatusSplit.Screen.of("status"))
    }

    @Test
    fun `the badge counts only failing checks`() {
        val lines = listOf(
            StatusLine(StatusKey.NOTIFICATIONS_OK, Severity.OK),
            StatusLine(StatusKey.DND_ON, Severity.INFO),
            StatusLine(StatusKey.NOTIFICATIONS_OFF, Severity.PROBLEM),
            StatusLine(StatusKey.RING_CHANNEL_BLOCKED, Severity.PROBLEM),
        )
        assertEquals(2, SettingsStatusSplit.badgeCount(lines))
        assertEquals(0, SettingsStatusSplit.badgeCount(lines.take(2)))
        assertEquals(0, SettingsStatusSplit.badgeCount(emptyList()))
    }

    @Test
    fun `home enters Status from the status line and has a menu item for each screen`() {
        assertEquals(SettingsStatusSplit.Screen.STATUS, SettingsStatusSplit.HOME_STATUS_LINE_TARGET)
        assertEquals(listOf(SettingsStatusSplit.Screen.SETTINGS, SettingsStatusSplit.Screen.STATUS), SettingsStatusSplit.HOME_MENU_TARGETS)
        val home = File("src/main/java/com/meda/portico/home/HomeActivity.kt").readText()
        assertTrue(home.contains("MENU_SETTINGS -> openSetup(SettingsStatusSplit.Screen.SETTINGS)"))
        assertTrue(home.contains("MENU_STATUS -> openSetup(SettingsStatusSplit.Screen.STATUS)"))
        assertTrue(home.contains("menu.menu.add(0, MENU_STATUS,"))
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"home_menu_status\">Status</string>"))
        assertTrue(strings.contains("Tap for details in Status."))
    }

    @Test
    fun `neither screen links to the other`() {
        val layout = File("src/main/res/layout/activity_setup.xml").readText()
        val code = File("src/main/java/com/meda/portico/setup/SetupActivity.kt").readText()
        for (gone in listOf("setup_nav_to_status", "setup_nav_to_settings", "setup_status_badge")) {
            assertTrue(!layout.contains(gone) && !code.contains(gone), gone)
        }
    }

    @Test
    fun `in 0_6_64 the ring channel is reached by ONE Settings row and Status keeps only lines for it`() {
        val checks = File("src/main/java/com/meda/portico/setup/SetupChecks.kt").readText()
        val code = File("src/main/java/com/meda/portico/setup/SetupActivity.kt").readText()
        val channels = File("src/main/java/com/meda/portico/ring/Channels.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        // the row: one button in Settings, opening the ring channel's page; no state text
        assertTrue("setup_ring_control" in settings)
        assertTrue("Channels.settingsIntent(this, Channel.RING)" in code && "DndOverride" !in code)
        assertTrue("ACTION_CHANNEL_NOTIFICATION_SETTINGS" in channels && "EXTRA_CHANNEL_ID, idOf(context, channel)" in channels)
        assertTrue("<string name=\"setup_ring_control\">Ring control (Android)</string>" in strings)
        // 0.6.70: Status shows no ring-channel line at all (ring configuration is not status)
        assertTrue("OPEN_RING_CHANNEL_SETTINGS" !in checks && "fix_open_ring_channel" !in strings)
        assertTrue("RINGING_SETTINGS_KEYS" !in checks && "openRingingSettings" !in checks && "showRingingSection" !in code)
    }

    @Test
    fun `in 0_6_69 every Android system page is a button in Settings and none is on Status`() {
        val checks = File("src/main/java/com/meda/portico/setup/SetupChecks.kt").readText()
        val code = File("src/main/java/com/meda/portico/setup/SetupActivity.kt").readText()
        val pages = File("src/main/java/com/meda/portico/setup/AndroidPages.kt").readText()
        val core = File("../core/src/main/kotlin/com/meda/portico/core/ring/SetupStatus.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        // Status: no Android intent, no OPEN_* fix, no button label for one
        for (gone in listOf("Intent(", "startActivity", "import android.provider.Settings", "OPEN_NOTIFICATION_SETTINGS", "OPEN_STATUS_CHANNEL_SETTINGS", "OPEN_FULL_SCREEN_SETTINGS", "OPEN_APP_DETAILS")) {
            assertTrue(gone !in checks, gone)
        }
        assertTrue("OPEN_" !in core.substringAfter("enum class Fix").substringBefore("}"))
        for (gone in listOf("fix_open_notification_settings", "fix_open_status_channel", "fix_open_full_screen")) assertTrue(gone !in strings, gone)
        // 0.6.70: the in-app permission prompt left Status for the Notifications button of Settings > Android settings
        assertTrue("setup_android_notifications" in code && "notifPermission.promptsFirst" in code)
        assertTrue("requestNotifications()" in code.substringAfter("R.id.setup_android_notifications").substringBefore("AndroidPages.notifications(this)"))
        // Settings: one compact section, one button per page, intents built in ONE place
        for (id in listOf("setup_android_app", "setup_android_notifications", "setup_android_calls", "setup_android_status_channel")) {
            assertTrue(id in settings, id)
        }
        assertEquals("Android settings", Regex("<string name=\"setup_android_settings\">(.*?)</string>").find(strings)!!.groupValues[1])
        assertEquals("App settings", Regex("<string name=\"setup_android_app\">(.*?)</string>").find(strings)!!.groupValues[1])
        assertEquals("Notifications", Regex("<string name=\"setup_android_notifications\">(.*?)</string>").find(strings)!!.groupValues[1])
        assertEquals("Full-screen calls", Regex("<string name=\"setup_android_calls\">(.*?)</string>").find(strings)!!.groupValues[1])
        assertEquals("Status notification", Regex("<string name=\"setup_android_status_channel\">(.*?)</string>").find(strings)!!.groupValues[1])
        assertTrue("ACTION_APPLICATION_DETAILS_SETTINGS" in pages && "ACTION_APP_NOTIFICATION_SETTINGS" in pages && "ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT" in pages)
        assertTrue("Channels.settingsIntent(context, Channel.STATUS)" in pages)
        assertTrue("AndroidPages.appDetails(this)" in code && "AndroidPages.notifications(this)" in code && "AndroidPages.fullScreen(this)" in code && "AndroidPages.statusChannel(this)" in code)
        // the strings of the retired lines still exist in strings.xml (their producers are kept) but no Status row shows them
        assertTrue("in ANDROID_SETTINGS_KEYS" !in checks && "openAndroidSettings" !in checks && "showAndroidPagesSection" !in code)
    }

    @Test
    fun `in 0_6_70 Status has two groups, Home Assistant with the merged phone facts and push, and no phone or ring group`() {
        val checks = File("src/main/java/com/meda/portico/setup/SetupChecks.kt").readText()
        val code = File("src/main/java/com/meda/portico/setup/SetupActivity.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val layout = File("src/main/res/layout/activity_setup.xml").readText()
        val ha = layout.indexOf("id/setup_ha_checks"); val push = layout.indexOf("id/setup_push_checks")
        assertTrue(ha in 0 until push, "order: Home Assistant, push")
        assertTrue("<string name=\"setup_ha_heading\">Home Assistant</string>" in strings)
        assertTrue("<string name=\"setup_push_heading\">Push ring notification</string>" in strings)
        // gone: the Ringing group and the This phone group
        for (gone in listOf("setup_ring_heading", "setup_status_heading")) assertTrue(gone !in strings && gone !in layout, gone)
        assertTrue("setup_ring_checks" !in layout && "setup_ring_checks" !in code && "StatusGroup.THIS_PHONE" !in code)
        assertTrue("SetupStatus.permissionLines" !in code && "SetupStatus.ringStyleLines" !in code && "SetupStatus.coexistenceLines" !in code)
        assertTrue("summaryLines" !in code, "the old This phone text is not rendered on Status")
        // the facts are rows of the Home Assistant section
        assertTrue("StatusBoard.items(pushLines, StatusFacts.of(reading.snapshot, versionText))" in code)
        for (k in listOf("fact_address", "fact_sign_in", "fact_phone_name", "fact_version")) assertTrue("name=\"$k\"" in strings, k)
        assertTrue("0.38f" in checks && "0.62f" in checks, "two aligned columns for label and value")
        assertTrue("setup_mark_" !in strings && "setup_mark_" !in checks && "setup_url_warning" !in layout)
        assertTrue("R.drawable.status_dot" in checks && "contentDescription = activity.getString(dotDescription(row.dot))" in checks)
        for ((k, v) in mapOf("dot_ok" to "OK", "dot_attention" to "Needs attention", "dot_problem" to "Problem", "dot_off" to "Off")) {
            assertTrue("<string name=\"$k\">$v</string>" in strings, k)
        }
        val dot = File("src/main/res/drawable/status_dot.xml").readText()
        assertTrue("<vector" in dot && "pathData" in dot)
        for (c in listOf("dot_green", "dot_amber", "dot_red", "dot_grey")) {
            assertTrue(File("src/main/res/values/colors.xml").readText().contains("name=\"$c\"") && File("src/main/res/values-night/colors.xml").readText().contains("name=\"$c\""), c)
        }
    }

    @Test
    fun `in 0_6_70 data use is a table with a Mobile and a Wi-Fi column and a small Reset`() {
        val code = File("src/main/java/com/meda/portico/setup/SetupActivity.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()
        val layout = File("src/main/res/layout/activity_setup.xml").readText()
        assertTrue(Regex("<LinearLayout[^>]*android:id=\"@\\+id/setup_traffic_rows\"", RegexOption.DOT_MATCHES_ALL).containsMatchIn(layout))
        assertTrue("fun row(vararg cells: TextView)" in code && "TrafficTable.blocks(t.meter, now)" in code)
        assertTrue("R.string.setup_traffic_mobile" in code && "R.string.setup_traffic_wifi" in code)
        assertEquals("Data used by this app", Regex("<string name=\"setup_traffic_heading\">(.*?)</string>").find(strings)!!.groupValues[1])
        assertEquals("Mobile", Regex("<string name=\"setup_traffic_mobile\">(.*?)</string>").find(strings)!!.groupValues[1])
        assertEquals("Wi-Fi", Regex("<string name=\"setup_traffic_wifi\">(.*?)</string>").find(strings)!!.groupValues[1])
        assertEquals("Reset", Regex("<string name=\"setup_traffic_reset\">(.*?)</string>").find(strings)!!.groupValues[1])
        assertTrue("setup_traffic_hint" !in strings && "setup_traffic_hint" !in layout && "Counted only while" !in strings)
        assertTrue("setup_traffic_row\"" !in strings)
        assertTrue("borderlessButtonStyle" in layout.substringAfter("id/setup_traffic_reset").take(150))
        val meter = File("src/main/java/com/meda/portico/roaming/AndroidTraffic.kt").readText()
        assertTrue("Medium.WIFI" in meter && "Medium.MOBILE" in meter && "TRANSPORT_WIFI" in meter)
    }
}
