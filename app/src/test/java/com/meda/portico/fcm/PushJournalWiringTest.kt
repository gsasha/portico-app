package com.meda.portico.fcm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The push and ring journal's Android wiring (0.5.12-alpha), checked from the sources without a device (working directory
 * = app/): every call is write-only and wrapped, comes AFTER the decision it describes and never changes a line of the
 * decision path; Settings has the row. What the phone shows is for a phone.
 */
class PushJournalWiringTest {
    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val receiver by lazy { src("fcm/PushReceiver.kt") }
    private val dispatcher by lazy { src("fcm/OutcomeHandler.kt") }
    private val handler by lazy { src("ring/AndroidOutcomeHandler.kt") }
    private val graph by lazy { src("AppGraph.kt") }
    private val setup by lazy { src("setup/SetupActivity.kt") }
    private val home by lazy { src("home/HomeActivity.kt") }
    private val layout by lazy { File("src/main/res/layout/activity_setup.xml").readText() }
    private val strings by lazy { File("src/main/res/values/strings.xml").readText() }
    private val onMessage by lazy { receiver.substringAfter("fun onMessage(").substringBefore("    /** Journal calls never throw") }

    @Test
    fun `PushReceiver journals after the decision and before the dispatch, and the decision lines are as they were`() {
        assertTrue(onMessage.contains("Decision.receive(store, msg, nowMs, knownDoorRefs(), resumed, doorTaken)"))
        assertTrue(onMessage.contains("log.log(HaLog.Level.INFO, \"\${PushLog.decision(msg, outcome)} \${priorityNote(priority, originalPriority)}\")"))
        val decided = onMessage.indexOf("it.decided(msg, outcome, nowMs")
        assertTrue(decided > onMessage.indexOf("PushLog.decision(msg, outcome)"))
        assertTrue(decided < onMessage.indexOf("dispatcher.dispatch(outcome)"))
        assertEquals(1, Regex("dispatcher\\.dispatch\\(outcome\\)").findAll(onMessage).count())
        assertTrue(onMessage.contains("observe { it.decideFailed(msg, nowMs, e) }\n            return null"))
        assertTrue(onMessage.trimEnd().endsWith("return outcome\n    }"))
    }

    @Test
    fun `every journal call in PushReceiver is wrapped, the journal is optional and nothing reads it`() {
        assertTrue(receiver.contains("private val journal: PushJournal? = null"))
        val observe = receiver.substringAfter("private inline fun observe(").substringBefore("companion object")
        assertTrue(observe.contains("catch (e: Throwable)"))
        assertFalse(receiver.contains("journal.lines()") || receiver.contains("journal.text()") || receiver.contains("journal?.lines"))
        val dropped = receiver.substringAfter("\"portico push: dropped (paused)").substringBefore("return\n")
        assertTrue(dropped.contains("try {") && dropped.contains("journal?.dropped(kindForLog(data), \"paused\")") && dropped.contains("catch (e: Throwable)"))
    }

    @Test
    fun `AndroidOutcomeHandler journals what it applied after applying it, and the plan and apply are untouched`() {
        assertTrue(handler.contains("val plan = NotificationPlanner.plan(outcome, clock(), ringOn, p, last, quiet)\n            apply(plan)\n            log.log(HaLog.Level.INFO, describe(outcome, plan))\n            observe { it.handled(outcome, plan, p) }"))
        assertTrue(handler.contains("observe { it.stopped(why, doorRef, callId, stopped) }"))
        assertTrue(handler.contains("observe { it.stoppedAll(why, n) }"))
        assertTrue(handler.contains("observe { it.failed(what, e) }"))
        val observe = handler.substringAfter("private inline fun observe(").substringBefore("}\n}")
        assertTrue(observe.contains("catch (e: Throwable)"))
        // apply() is exactly the pre-journal body
        val apply = handler.substringAfter("private fun apply(plan: NotificationPlan) {").substringBefore("private fun stopIfActive")
        assertFalse(apply.contains("journal") || apply.contains("observe"))
    }

    @Test
    fun `OutcomeDispatcher journals a held-back outcome and a thrown step, and decides exactly as before`() {
        assertTrue(dispatcher.contains("if (!RingGate.shouldHandle(outcome, ringOn)) {"))
        val gate = dispatcher.substringAfter("if (!RingGate.shouldHandle(outcome, ringOn)) {").substringBefore("if (outcome is Outcome.Ring && outcome.update.source")
        assertTrue(gate.contains("journal?.gated(outcome, \"ring switch off\")") && gate.trimEnd().endsWith("return\n        }"))
        assertTrue(dispatcher.contains("journal?.failed(what, e)"))
        assertTrue(dispatcher.contains("is Outcome.Ring -> handler.onRing(outcome)") && dispatcher.contains("is Outcome.Quiet -> handler.onQuiet(outcome)"))
    }

    @Test
    fun `AppGraph builds one journal on its own prefs file and hands it to every observation point`() {
        assertTrue(graph.contains("val pushJournal: PushJournal = PushJournal("))
        assertTrue(graph.contains("PrefsPushJournalStore(app.getSharedPreferences(PrefsPushJournalStore.FILE, Context.MODE_PRIVATE))"))
        assertTrue(graph.contains("env = { PushEnvProbe.read(app, uiShown.get() > 0) }"))
        assertEquals(12, Regex("journal = pushJournal").findAll(graph).count(), "ring handler, dispatcher, socket route, push receiver, messaging service, answer coordinator (M7), house ring coordinator (M7 3b-B), DEV-764: content, link, conn tracer, answer session, talk")
        assertTrue(graph.contains("sink = JournalingCallSink(CallStateSink { event ->"))
        assertTrue(graph.contains("}, pushJournal),"))
        assertTrue(graph.contains("{ m, o -> socketRoute.onOutcome(m, o) }"))
    }

    @Test
    fun `the socket route is the old inline pause check, moved`() {
        val route = src("fcm/SocketRoute.kt")
        assertTrue(route.contains("log.log(HaLog.Level.INFO, \"portico push: socket \${outcome.contractName} not alerted (paused)\")"))
        assertTrue(route.contains("if (paused()) {") && route.contains("dispatch(outcome)"))
        assertTrue(route.indexOf("it.decided(") < route.indexOf("if (paused())"))
        assertTrue(route.contains("delegate.onCallEvent(event)") && route.indexOf("journal?.socketEvent(event)") < route.indexOf("delegate.onCallEvent(event)"))
    }

    @Test
    fun `Status no longer lists the journal or the connection log, one Send diagnostics button shares them`() {
        // 0.6.72: diagnostics are collected and sent to Home Assistant, not listed for the owner
        for (id in listOf("setup_journal_text", "setup_journal_clear", "setup_connlog_status")) {
            assertFalse(layout.contains("@+id/$id"), id)
            assertFalse(setup.contains("R.id.$id"), id)
        }
        for (k in listOf("journal_heading", "journal_hint", "journal_clear", "journal_empty", "journal_cleared", "journal_clear_failed", "connlog_status")) {
            assertFalse(strings.contains("name=\"$k\""), k)
        }
        assertFalse(setup.contains("JOURNAL_SHOWN") || setup.contains("renderJournal") || setup.contains("renderConnLog"))
        assertTrue(layout.contains("android:id=\"@+id/setup_share_diagnostics\"") && strings.contains("name=\"share_diagnostics\">Send diagnostics<"))
        assertTrue(setup.contains("Intent.ACTION_SEND") && setup.contains("setType(\"text/plain\")") && setup.contains("graph.pushJournal.lines()"))
        // the button is the last thing on the Status screen, after the Data table; the connection-log switch stays in Settings
        assertTrue(layout.indexOf("setup_traffic_reset") < layout.indexOf("setup_share_diagnostics"))
        assertTrue(layout.indexOf("setup_connlog_switch") < layout.indexOf("setup_status_screen"))
        assertEquals(1, Regex("<ScrollView").findAll(layout).count())
        val onCreate = setup.substringAfter("override fun onCreate(").substringBefore("override fun onDestroy")
        assertTrue(onCreate.contains("wireJournal()") && onCreate.contains("wireConnLog()"))
    }

    @Test
    fun `the journal and connection-log producers and the Home Assistant reporting are still there`() {
        assertTrue(File("../core/src/main/kotlin/com/meda/portico/core/push/PushJournal.kt").exists())
        assertTrue(graph.contains("val pushJournal: PushJournal = PushJournal("))
        assertTrue(graph.contains("val connLogQueue = ConnLogQueue(") && graph.contains("connSender"))
        assertTrue(graph.contains("var connLogEnabled"))
        assertTrue(graph.contains("fun connLogStatus()"))
        assertTrue(receiver.contains("journal"))
    }

    @Test
    fun `Home has no Copy layout log item`() {
        assertFalse(home.contains("copyLayoutLog") || home.contains("MENU_LAYOUT_LOG") || home.contains("ClipData"))
        assertFalse(strings.contains("home_menu_layout_log") || strings.contains("home_layout_log_"))
    }

    @Test
    fun `the journal's own code has no logcat, no network and no Settings access`() {
        val core = "../core/src/main/kotlin/com/meda/portico/core/push/"
        val files = listOf(
            File(core + "PushJournal.kt"), File(core + "PushJournalFormat.kt"),
            File("src/main/java/com/meda/portico/store/PrefsPushJournalStore.kt"),
            File("src/main/java/com/meda/portico/ring/PushEnvProbe.kt"),
            File("src/main/java/com/meda/portico/fcm/SocketRoute.kt"),
        )
        for (f in files) {
            val text = f.readText()
            assertFalse(Regex("\\bLog\\.").containsMatchIn(text), "${f.name} calls Log")
            for (bad in listOf("android.util.Log", "okhttp", "HttpURLConnection", "URL(", "Settings.open", "installationId", "haUrl", "ha_token", "tailscale")) {
                assertFalse(text.contains(bad), "${f.name} mentions $bad")
            }
        }
    }

    @Test
    fun `every id in the formatter goes through Redact_prefix`() {
        val fmt = File("../core/src/main/kotlin/com/meda/portico/core/push/PushJournalFormat.kt").readText()
        val interpolations = Regex("\\$\\{[^}]*(callId|doorRef|doorId)[^}]*}").findAll(fmt).map { it.value }.toList()
        assertTrue(interpolations.isNotEmpty())
        assertTrue(Regex("Redact\\.prefix\\(").findAll(fmt).count() >= 5, "ids are shortened where they are written")
        for (i in interpolations) assertTrue(i.contains("Redact.prefix("), i)
        assertTrue(Regex("Redact\\.prefix\\(").findAll(fmt).count() >= 7, "the hold line shortens its two ids too")
    }

    // ---- M7: the hold, switch and resume lines ---------------------------------------------------------------------

    private val coreCall = "../core/src/main/kotlin/com/meda/portico/core/call/"
    private val coordinator by lazy { File(coreCall + "AnswerCoordinator.kt").readText() }

    @Test
    fun `the answer coordinator gets the journal in AppGraph and journals through one wrapped, write-only helper`() {
        val answer = graph.substringAfter("val answer: AnswerCoordinator = AnswerCoordinator(").substringBefore("val place: PlaceCoordinator")
        assertTrue(answer.contains("log = log,\n        journal = pushJournal,\n        linkFields = ::linkFields,\n    )"))
        assertTrue(coordinator.contains("private val journal: PushJournal? = null"))
        val jr = coordinator.substringAfter("private inline fun jr(").substringBefore("private fun publishSet")
        assertTrue(jr.contains("val j = journal ?: return") && jr.contains("catch (e: Throwable)"))
        // nothing reads the journal
        assertFalse(Regex("journal[?!]*\\.(lines|text|tail|isEmpty|clear)").containsMatchIn(coordinator))
        assertFalse(Regex("\\bj\\.(lines|text|tail|isEmpty|clear)").containsMatchIn(coordinator))
    }

    @Test
    fun `every journal call in the coordinator passes enum steps, ids for the formatter to shorten, and numbers - never a string`() {
        val calls = Regex("jr \\{ it\\.hold\\(([^\\n]*)\\) \\}").findAll(coordinator).map { it.groupValues[1] }.toList()
        assertTrue(calls.size >= 14, "the hold steps are all there: ${calls.size}")
        for (c in calls) {
            assertFalse(c.contains('"') || c.contains("$"), "a string reaches the journal: $c")
            assertTrue(c.startsWith("HoldStep.") || c.startsWith("if ("), c)
        }
        val api = File("../core/src/main/kotlin/com/meda/portico/core/push/PushJournal.kt").readText()
        assertTrue(api.contains("fun hold(step: HoldStep, doorRef: String?, callId: String?, result: Enum<*>? = null, ms: Long? = null, n: Int? = null)"))
        val fmt = File("../core/src/main/kotlin/com/meda/portico/core/push/PushJournalFormat.kt").readText()
        val holdLine = fmt.substringAfter("fun holdLine(").substringBefore("// ---- pieces")
        assertEquals(2, Regex("Redact\\.prefix\\(").findAll(holdLine).count(), "door and call, shortened where they are written")
        assertFalse(holdLine.contains("\${callId}") || holdLine.contains("\${doorRef}"), "an id is never written unshortened")
    }

    @Test
    fun `hold off means no hold journaling is reachable - the gate is false and every hold entry point returns before it journals`() {
        assertTrue(Regex("""const val HOLD_ENABLED = false\b""").containsMatchIn(File(coreCall + "AnswerPorts.kt").readText()))
        assertTrue(coordinator.contains("if (!holdEnabled) return SwitchResult.DISABLED"))
        assertTrue(coordinator.contains("if (!holdEnabled) return@post"))
        // the second-answer lines sit inside the HOLD_AND_BEGIN branch, which decide() returns only with the gate on
        assertTrue(coordinator.substringAfter("private fun attempt(").substringBefore("jr {").contains("== SessionSet.Decision.HOLD_AND_BEGIN"))
        // a held end is journaled only from a snapshot that is held, and only a hold ever sets one
        assertTrue(coordinator.contains("if (snap.phase == Phase.ENDED && snap.held) {"))
    }
}
