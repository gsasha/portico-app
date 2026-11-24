package com.meda.portico.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Home's resources and wiring, checked without a device (working directory = app/): every string and id
 * the code names exists, the strings take the arguments the code passes, the screen scrolls, the
 * activity is registered and not exported, and the phone's side never builds anything but an id.
 */
class HomeResourcesTest {
    private fun parse(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))

    private val strings: Map<String, String> by lazy {
        val nodes = parse("src/main/res/values/strings.xml").getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent.replace("\\'", "'")
        }
    }

    private val activity: String by lazy { File("src/main/java/com/meda/portico/home/HomeActivity.kt").readText() }

    /** A2 moved the button and picture widgets out of the activity so the ring and in-call screens share them. */
    private val widgets: String by lazy {
        File("src/main/java/com/meda/portico/home/ActionButtons.kt").readText() + File("src/main/java/com/meda/portico/home/StillTiles.kt").readText()
    }

    private fun args(s: String) = Regex("%(\\d)\\\$s").findAll(s).map { it.groupValues[1].toInt() }.toSortedSet().toList()

    @Test
    fun `every string the activity names exists`() {
        val used = Regex("R\\.string\\.(home_\\w+)").findAll(activity + widgets).map { it.groupValues[1] }.toSet()
        assertTrue(used.size >= 25, "found ${used.size} home strings in the activity")
        for (name in used) assertTrue(name in strings, "R.string.$name is not in strings.xml")
    }

    @Test
    fun `strings that take the button's name take exactly that one argument, the rest none`() {
        val withLabel = setOf(
            "home_confirm_title", "home_confirm_message", "home_hold_description", "home_run_ok", "home_run_not_connected", "home_run_rate_limited",
            "home_run_unavailable", "home_run_not_allowed", "home_run_failed",
            // B6: a camera tile's spoken state takes the camera's label
            "home_tile_desc_big", "home_tile_desc_small", "home_tile_desc_zoomed", "home_tile_desc_fullscreen",
            // DEV-768 P3: one argument each (a layout or stream word)
            "home_tile_action_resize", "home_tile_action_stream", "home_stream_auto", "home_stream_desc",
        )
        val withTime = setOf("home_title_paused_until")
        for (k in strings.keys.filter { it.startsWith("home_") }) {
            assertEquals(if (k in withLabel || k in withTime) listOf(1) else emptyList<Int>(), args(strings.getValue(k)), k)
        }
        for (k in withLabel) assertTrue(k in strings, k)
    }

    @Test
    fun `every banner and every button kind has a string`() {
        for (b in listOf("not_configured", "paused", "auth_rejected", "url_refused", "offline", "connecting", "no_server_support", "not_enrolled", "no_content")) {
            assertTrue("home_banner_$b" in strings, b)
        }
        for (k in listOf("portico_open", "lock_open", "button_press", "script_run", "scene_activate", "call", "lock_lock", "lock_unlock", "cover_open", "cover_close", "talk")) assertTrue("home_kind_$k" in strings, k)
        for (k in listOf("not_connected", "rate_limited", "unavailable", "not_allowed", "failed", "ok", "paused")) assertTrue("home_run_$k" in strings, k)
    }

    @Test
    fun `every banner the model can give is worded by the activity`() {
        val enum = File("../core/src/main/kotlin/com/meda/portico/core/content/HomeModel.kt").readText()
        val banners = Regex("enum class Banner \\{(.*?)\\n\\}", RegexOption.DOT_MATCHES_ALL).find(enum)!!.groupValues[1]
            .lines().map { it.trim().trimEnd(',') }.filter { it.matches(Regex("[A-Z_]+")) }
        assertTrue(banners.size >= 10)
        for (b in banners) assertTrue(activity.contains("Banner.$b"), "the activity does not word Banner.$b")
    }

    @Test
    fun `every status label the model can give is worded by the activity and has a string`() {
        val enum = File("../core/src/main/kotlin/com/meda/portico/core/ring/ConnectionStatus.kt").readText()
        val labels = Regex("enum class StatusLabel \\{(.*?)\\n\\}", RegexOption.DOT_MATCHES_ALL).find(enum)!!.groupValues[1]
            .lines().map { it.trim().trimEnd(',') }.filter { it.matches(Regex("[A-Z_]+")) }
        assertTrue(labels.size >= 9, "found ${labels.size} labels")
        for (l in labels) {
            assertTrue(activity.contains("StatusLabel.$l ->"), "the activity does not word StatusLabel.$l")
            assertTrue("home_status_${l.lowercase()}" in strings, "no string home_status_${l.lowercase()}")
        }
    }

    @Test
    fun `the status is in the top bar, tappable, and the title can say PAUSED`() {
        val layout = File("src/main/res/layout/activity_home.xml").readText()
        // the status sits in the header row, above the scrolling blocks, not after them
        assertTrue(layout.indexOf("home_status_dot") in 1 until layout.indexOf("home_blocks"), "the status must come before the blocks")
        assertTrue(layout.contains("android:contentDescription=\"@string/home_status_description\""), "an accessible description")
        assertTrue(activity.contains("R.id.home_status).setOnClickListener { openSetup(SettingsStatusSplit.HOME_STATUS_LINE_TARGET) }"), "tapping the status opens Status")
        assertTrue(activity.contains("graph.isPaused()") && activity.contains("home_title_paused_until"), "paused changes the title")
        assertTrue(activity.contains("ConnectionModel.of(linkState(), graph.linkPath, graph.signaling.everConnected)"), "the model decides, from caches only")
    }

    @Test
    fun `one top line has the title, the status and the menu button at the end, with no fixed width`() {
        val doc = parse("src/main/res/layout/activity_home.xml")
        fun el(id: String) = (0 until doc.getElementsByTagName("*").length).map { doc.getElementsByTagName("*").item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("android:id") == "@+id/$id" }
        val title = el("home_title")
        val status = el("home_status")
        val menu = el("home_menu")
        assertTrue(title.parentNode === status.parentNode && status.parentNode === menu.parentNode, "title, status and menu share one row")
        val kids = generateSequence(title.parentNode.firstChild) { it.nextSibling }.filterIsInstance<org.w3c.dom.Element>().map { it.getAttribute("android:id") }.toList()
        assertEquals(listOf("@+id/home_title", "@+id/home_tabs", "@+id/home_status", "@+id/home_menu"), kids, "title and page selector share the slot; the menu button is last: the top-right corner")
        assertTrue(el("home_tabs").parentNode === title.parentNode)
        assertEquals("0dp", el("home_tabs").getAttribute("android:layout_width"))
        assertEquals("1", el("home_tabs").getAttribute("android:layout_weight"))
        assertEquals("0dp", title.getAttribute("android:layout_width"), "the title takes the spare width and wraps")
        assertEquals("1", title.getAttribute("android:layout_weight"))
        assertEquals("wrap_content", status.getAttribute("android:layout_width"))
        assertEquals("48dp", menu.getAttribute("android:layout_width"))
        assertEquals("48dp", menu.getAttribute("android:layout_height"))
        assertEquals("@string/home_menu_description", menu.getAttribute("android:contentDescription"))
        assertTrue("home_menu_description" in strings)
        assertFalse(doc.getElementsByTagName("Button").length > 0, "the Refresh/Setup button row is gone")
    }

    @Test
    fun `the menu is Settings, Status, About (no Refresh) and About has what it shows`() {
        assertTrue(Regex("R\\.string\\.home_menu_settings.*\\n.*R\\.string\\.home_menu_status.*\\n.*R\\.string\\.home_menu_about").containsMatchIn(activity))
        assertTrue(activity.contains("PopupMenu(this, anchor)"))
        assertFalse(activity.contains("MENU_REFRESH") || strings.containsKey("home_refresh"), "0.6.54: Refresh is gone from the menu")
        assertEquals("Settings", strings["home_menu_settings"])
        assertEquals("About", strings["home_menu_about"])
        assertEquals(listOf(1, 2), args(strings.getValue("about_version")))
        for (k in listOf("about_title", "about_build_debug", "about_build_release", "about_tailnet_yes", "about_tailnet_no", "about_icons", "about_ok")) {
            assertTrue(k in strings, k)
            assertEquals(emptyList<Int>(), args(strings.getValue(k)), k)
        }
        assertTrue(activity.contains("getLongVersionCode") && activity.contains("versionName"))
        assertFalse(activity.contains("signatures") || activity.contains("signingInfo"), "the signer is not shown")
    }

    @Test
    fun `no text a user reads says Setup any more`() {
        for ((k, v) in strings) assertFalse(Regex("\\bSetup\\b").containsMatchIn(v), "$k still says Setup: $v")
        assertEquals("Portico settings", strings["setup_title"])
        for (f in listOf("../core/src/main/kotlin/com/meda/portico/core/call/AnswerFailure.kt", "../core/src/main/kotlin/com/meda/portico/core/reg/RegistrationMachine.kt")) {
            assertFalse(Regex("\"[^\"]*\\bin Setup\\.\"").containsMatchIn(File(f).readText()), f)
        }
    }

    @Test
    fun `a button's icon is drawn from a bundled drawable and the text stays`() {
        assertTrue(widgets.contains("HomeIcons.resolve(tile.icon, tile.kind)"))
        assertTrue(widgets.contains("HomeIcons.drawableName(key)"))
        assertTrue(widgets.contains("setTintList(button.textColors)"), "tinted with the text colour")
        assertTrue(widgets.contains("text = if (tile.state == ButtonState.RUNNING)"), "the label is still the button's text")
        assertTrue(File("src/main/res/drawable/ic_mdi_cog.xml").isFile, "the cog stays: it is a curated button icon")
    }

    @Test
    fun `the menu button is the hamburger, a chrome icon outside the curated button list`() {
        val layout = File("src/main/res/layout/activity_home.xml").readText()
        assertTrue(layout.contains("android:src=\"@drawable/ic_menu_hamburger\""), "the menu button draws the hamburger")
        assertFalse(layout.contains("ic_mdi_cog"), "the gear is gone from the top line")
        assertTrue(layout.contains("android:tint=\"?android:attr/textColorPrimary\""), "still tinted with the text colour")
        val drawable = File("src/main/res/drawable/ic_menu_hamburger.xml").readText()
        assertTrue(drawable.contains("Material Design Icons \"menu\""), "it is MDI's menu")
        assertTrue(drawable.contains("android:pathData=\"M3,6H21V8H3V6M3,11H21V13H3V11M3,16H21V18H3V16Z\""), "three horizontal bars")
        // not a button icon: the list shared with the HA plugin's picker must not grow for UI chrome
        val contract = File("../core/src/test/resources/icons_v1.json").readText()
        assertFalse(Regex("\"menu\"").containsMatchIn(contract), "menu is not in icons_v1.json")
        assertFalse(File("../core/src/main/kotlin/com/meda/portico/core/content/HomeIcons.kt").readText().contains("\"menu\""))
        // and it comes from the same generator, in its separate chrome list
        assertTrue(File("../tools/gen_mdi_drawables.py").readText().contains("CHROME = {\"menu\": \"ic_menu_hamburger\"}"))
    }

    @Test
    fun `Home has no system action bar, so its own top line is the only title`() {
        val themes = parse("src/main/res/values/themes.xml")
        val styles = themes.getElementsByTagName("style")
        val byName = (0 until styles.length).map { styles.item(it) as org.w3c.dom.Element }.associateBy { it.getAttribute("name") }
        assertEquals("Theme.AppCompat.DayNight.NoActionBar", byName.getValue("Theme.Portico.NoActionBar").getAttribute("parent"))
        assertEquals("Theme.AppCompat.DayNight.DarkActionBar", byName.getValue("Theme.Portico").getAttribute("parent"), "the other screens keep the app theme")
        val nodes = parse("src/main/AndroidManifest.xml").getElementsByTagName("activity")
        val themeOf = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.associate { it.getAttribute("android:name") to it.getAttribute("android:theme") }
        assertEquals("@style/Theme.Portico.NoActionBar", themeOf.getValue(".home.HomeActivity"))
        assertEquals("", themeOf.getValue(".setup.SetupActivity"), "Settings keeps the app theme and its own bar")
        assertEquals("", themeOf.getValue(".MainActivity"))
    }

    @Test
    fun `the title is the selected page's label as plain text, else the home's name, else Portico, and PAUSED still takes over`() {
        assertEquals("Portico", strings["home_title"])
        assertTrue(activity.contains("paintStatus(view)"))
        assertTrue(activity.contains("TopLine.choose(graph.isPaused(), view.tabs.size, view.pageLabel, view.homeName)"))
        assertTrue(activity.contains("is TopLine.Title -> title.text = chosen.text"))
        assertTrue(activity.contains("TopLine.Fallback -> title.setText(R.string.home_title)"))
        assertTrue(activity.contains("tabsScroll.visibility = if (chosen == TopLine.Tabs) View.VISIBLE else View.GONE"), "the selector takes the title's slot only for two or more pages and never while paused")
        assertTrue(activity.contains("TopLine.highlighted(view.tabs, view.selectedId)"))
        assertFalse(activity.contains("Html.fromHtml") || activity.contains("HtmlCompat"), "the name is never HTML")
        val paused = activity.substringAfter("if (chosen == TopLine.Paused) {").substringBefore("} else {")
        assertTrue(paused.contains("home_title_paused_until") && paused.contains("COLOR_WARN"), "paused wins over the name")
        assertEquals(listOf(1), args(strings.getValue("about_home")))
        assertTrue(activity.contains("R.string.about_home"))
        val layout = File("src/main/res/layout/activity_home.xml").readText()
        assertFalse(Regex("android:id=\"@\\+id/home_title\"[^>]*android:(maxLines|singleLine|ellipsize)").containsMatchIn(layout), "the title wraps, never clips")
    }

    @Test
    fun `the layout scrolls and has every id the activity uses`() {
        val doc = parse("src/main/res/layout/activity_home.xml")
        assertEquals(1, doc.getElementsByTagName("ScrollView").length, "a growable screen must scroll (0.6.52: under the pinned top line)")
        val ids = (0 until doc.getElementsByTagName("*").length).mapNotNull {
            (doc.getElementsByTagName("*").item(it) as org.w3c.dom.Element).getAttribute("android:id").takeIf { s -> s.isNotEmpty() }
        }.map { it.substringAfter("/") }.toSet()
        val used = Regex("R\\.id\\.(home_\\w+)").findAll(activity).map { it.groupValues[1] }.toSet()
        assertTrue(used.isNotEmpty())
        for (id in used) assertTrue(id in ids, "R.id.$id is not in activity_home.xml")
    }

    @Test
    fun `the activity is registered, not exported and has no intent filter`() {
        val doc = parse("src/main/AndroidManifest.xml")
        val nodes = doc.getElementsByTagName("activity")
        val home = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }.single { it.getAttribute("android:name") == ".home.HomeActivity" }
        assertEquals("false", home.getAttribute("android:exported"))
        assertEquals(0, home.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun `the launcher opens Home once enrolled and Setup before that`() {
        val main = File("src/main/java/com/meda/portico/MainActivity.kt").readText()
        assertTrue(main.contains("HomeActivity::class.java") && main.contains("SetupActivity::class.java"))
        assertTrue(main.contains("isEnrolled("))
        // an unreadable Settings must read as "not set up", never "set up"
        assertTrue(Regex("catch \\(_: Exception\\) \\{\\s*false").containsMatchIn(main))
    }

    @Test
    fun `the phone never names a service or an entity, only the ids HA gave it`() {
        for (root in listOf("src/main", "../core/src/main")) {
            for (f in File(root).walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }) {
                val text = f.readText()
                assertFalse(text.contains("call_service"), "${f.name} builds a call_service")
                assertFalse(text.contains("services.async_call") || text.contains("\"service\""), "${f.name} names a service")
            }
        }
        val commands = File("../core/src/main/kotlin/com/meda/portico/core/ha/ContentCommands.kt").readText()
        assertFalse(commands.contains("entity_id\" to") || commands.contains("put(\"entity_id\""), "a content request must carry ids only")
        assertTrue(commands.contains("put(\"action_id\"") && commands.contains("put(\"camera_id\""))
    }

    @Test
    fun `Home is polite about the battery - nothing runs while it is not visible`() {
        assertTrue(activity.contains("override fun onStop()") && activity.contains("handler.removeCallbacks(tick)"))
        assertTrue(activity.contains("graph.content.unlisten(contentListener)"))
        assertTrue(activity.contains("override fun onStart()"))
    }

    @Test
    fun `a call button places the call on its press with no confirm step and no other way in`() {
        val onPress = activity.substringAfter("private fun onPress(").substringBefore("private fun pressCall(")
        // the call branch returns before any press (and so before the confirm step) can be reached
        assertTrue(onPress.indexOf("PressRoute.PLACE") in 0 until onPress.indexOf("ActionButtons.press("), "the call branch must come before the shared press")
        assertTrue(onPress.indexOf("PressRoute.PLACE") in 0 until onPress.indexOf("ActionButtons.pressHeld("), "and before the held press")
        // and the shared press (the one with the dialog) is never given a call: it is the ring screens' too
        val pressCall = activity.substringAfter("private fun pressCall(").substringBefore("private fun say(")
        assertFalse(pressCall.contains("AlertDialog") || pressCall.contains("confirm"), "no confirm step to call anyone")
        assertTrue(pressCall.contains("graph.place.press("))
        // the only caller of place.press in the whole app is that press
        val callers = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("place.press(") }.map { it.name }.toList()
        // D-20261121-c98a: one deliberate second caller, the panel's "Call phones" request (PanelCallCoordinator, reachable only after its
        // refusals and a visible call screen; pinned by PanelCallWiringTest). Anything else is a new way in and fails here.
        assertEquals(listOf("AppGraph.kt", "HomeActivity.kt"), callers.sorted())
        assertEquals(1, File("src/main/java/com/meda/portico/AppGraph.kt").readText().split("place.press(").size - 1)
        // and nothing but a user's tap (the click listener) or the screen's Try again reaches it
        assertTrue(widgets.contains("setOnClickListener { onPress(tile) }"))
    }

    @Test
    fun `every state a call button can be in has words`() {
        for (s in listOf("live", "offline", "busy", "dnd", "receive_off", "gone", "not_available")) assertTrue("home_call_$s" in strings, s)
        for (st in listOf("CALLING", "TARGET_OFFLINE", "TARGET_BUSY", "TARGET_DND", "TARGET_RECEIVE_OFF", "TARGET_GONE", "CALLS_NOT_AVAILABLE")) {
            assertTrue(widgets.contains("ButtonState.$st ->"), "Home does not word $st")
        }
    }
}
