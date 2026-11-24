package com.meda.portico.core.ha

import com.meda.portico.core.call.WsMediaContract
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The vendored contract-3 fixtures are the server's files, byte for byte, and have the shape the replays
 * ([WsContractReplayTest], [WsMediaContractTest]) read; and the commands the app's code sends are exactly the vendored
 * list. See docs/reference/websocket-contract.md to re-vendor them.
 */
class WsContractTest {

    @Test
    fun vendoredFileMatchesPinnedSha256() {
        assertEquals(
            WsContract.PINNED_SHA256, WsContract.sha256(),
            "ws_v3.json drifted from the pinned copy: re-vendor it byte for byte from portico-ha " +
                "contract/ws_v3.json and bump WsContract.PINNED_SHA256 (docs/reference/websocket-contract.md)",
        )
    }

    @Test
    fun theMediaFileAndTheCommandListMatchTheirPins() {
        assertEquals(WsContract.MEDIA_PINNED_SHA256, WsContract.sha256Of(WsContract.resourceBytes(WsContract.MEDIA_RESOURCE)), "ws_media_v2.json drifted from the pinned copy")
        assertEquals(WsContract.COMMANDS_PINNED_SHA256, WsContract.sha256Of(WsContract.resourceBytes(WsContract.COMMANDS_RESOURCE)), "app_commands_v3.txt drifted from the pinned copy")
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun fileShapeIsFullyMapped() {
        val root = WsContract.root()
        assertEquals(setOf("_meta", "cases"), root.keys, "top-level keys")
        val meta = root["_meta"] as Map<String, Any?>
        assertEquals("portico-ws", meta["contract"])
        assertEquals(3L, meta["version"])
        val cases = WsContract.cases
        assertEquals(WsContract.EXPECTED_CASES, cases.size, "case count")
        assertEquals(cases.size, cases.map { it.name }.toSet().size, "case names unique")
        for (raw in root["cases"] as List<Map<String, Any?>>) {
            assertEquals(setOf("name", "as", "send", "receive", "auth"), raw.keys, "keys of ${raw["name"]}")
        }
        for (c in cases) {
            assertTrue(c.caller in setOf("phone", "screen", "user", "admin"), "as of $c")
            assertTrue(c.send["id"] is Long, "send id of $c")
            assertTrue(c.type.startsWith("portico/"), "type of $c")
            assertTrue(c.receive.isNotEmpty(), "receive of $c")
            for (f in c.receive) assertTrue(f["type"] in setOf("result", "event"), "frame type in $c")
            assertEquals(1, c.receive.count { it["type"] == "result" }, "one result ($c)")
            val firstResult = c.receive.indexOfFirst { it["type"] == "result" }
            assertEquals(c.send["id"], c.receive[firstResult]["id"], "the result carries its request's id ($c)")
            if (firstResult == 0) {
                // a reply, and the events of the subscription it opens, all carry the request's id
                for (f in c.receive) assertEquals(c.send["id"], f["id"], "a reply carries its request's id ($c)")
            } else {
                // M7: events pushed to an EARLIER subscription of the same connection (`talk_state`, `house_call`) can come
                // before the result, and carry that subscription's id, never this request's
                for (f in c.receive.take(firstResult)) {
                    assertEquals("event", f["type"], "before the result of $c")
                    assertTrue(f["id"] != c.send["id"], "an event before the result belongs to an earlier subscription ($c)")
                }
            }
        }
    }

    @Test
    fun theEncoderRoundTripsEveryFrame() {
        for (c in WsContract.cases) for ((i, f) in c.receive.withIndex()) {
            assertEquals(f + ("id" to 7L), com.meda.portico.core.push.MiniJson.parse(c.receiveFor(7)[i]), "$c frame $i")
        }
    }

    @Test
    fun everyRecordedFrameParsesAsAResultOrAnEvent() {
        for (c in WsContract.cases) for ((i, wire) in c.receiveFor(9).withIndex()) {
            val frame = HaProtocol.parse(wire).single()
            assertTrue(frame is HaInbound.Result || frame is HaInbound.Event, "$c frame $i parsed as $frame")
            assertEquals(c.receive[i]["type"] == "event", frame is HaInbound.Event, "$c frame $i")
        }
    }

    // ---- the commands the app sends ---------------------------------------------------------

    @Test
    fun theCommandListIsSortedAndWithoutRepeatsOrContract2Commands() {
        val commands = WsContract.appCommands
        assertEquals(commands.sorted().distinct(), commands, "one command a line, sorted, no repeats")
        assertTrue(commands.all { Regex("portico/[a-z_/]+").matches(it) }, "commands only, no comments")
        for (gone in listOf("portico/devices", "portico/subscribe", "portico/content/list", "portico/doors/secrets", "portico/house/subscribe", "portico/house/call", "portico/house/roster")) {
            assertTrue(gone !in commands, gone)
        }
    }

    @Test
    fun everyCommandInTheListHasACaseInTheFixtures() {
        val recorded = WsContract.cases.map { it.type }.toSet() + WsMediaContract.cases.flatMap { c -> c.steps.mapNotNull { it.sendType } }
        for (command in WsContract.appCommands) assertTrue(command in recorded, "$command has no recorded case")
    }

    /**
     * The commands the app's own code sends equal the vendored list: every `"portico/..."` string literal in the
     * `:core` and `:app` sources (the constants the builders and the link use) is in `app_commands_v3.txt`, and every
     * command in the file has such a literal. A command added to the app without the server (or dropped from one side)
     * turns this red.
     */
    @Test
    fun commandsSentEqualTheVendoredList() {
        val root = WsContract.repoRoot()
        val literal = Regex("\"(portico/[a-z_/]+)\"")
        val sources = listOf("core/src/main", "app/src/main").map { File(root, it) }.also { dirs ->
            for (d in dirs) assertTrue(d.isDirectory, "$d not found")
        }
        val sent = sources.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { f -> literal.findAll(f.readText()).map { it.groupValues[1] }.toList() }
            .toSortedSet()
        assertTrue(sent.size >= 10, "the scan found only $sent")
        assertEquals(emptySet<String>(), WsContract.NOT_YET_SENT, "DEV-713 S1 built the live commands: no vendored command is left unbuilt")
        assertEquals(
            (WsContract.appCommands - WsContract.NOT_YET_SENT).toSortedSet(), sent,
            "commands in the app's sources vs app_commands_v3.txt",
        )
    }
}
