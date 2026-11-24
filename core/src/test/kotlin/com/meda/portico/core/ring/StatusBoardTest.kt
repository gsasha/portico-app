package com.meda.portico.core.ring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 0.6.69: the Status board's dots, groups and dedupe rules. */
class StatusBoardTest {
    private fun l(k: StatusKey, s: Severity = Severity.INFO) = StatusLine(k, s)
    private fun keys(lines: List<StatusLine>) = lines.map { it.key }

    @Test
    fun `dot colours follow severity with the named exceptions`() {
        assertEquals(Dot.GREEN, StatusBoard.dotOf(l(StatusKey.LINK_CONNECTED, Severity.OK)))
        assertEquals(Dot.RED, StatusBoard.dotOf(l(StatusKey.LINK_AUTH_REJECTED, Severity.PROBLEM)))
        assertEquals(Dot.AMBER, StatusBoard.dotOf(l(StatusKey.PUSH_RESTART_TO_APPLY, Severity.PROBLEM)))
        assertEquals(Dot.AMBER, StatusBoard.dotOf(l(StatusKey.LINK_CONNECTING)))
        assertEquals(Dot.GREY, StatusBoard.dotOf(l(StatusKey.PUSH_NO_FIREBASE)))
        assertEquals(Dot.GREY, StatusBoard.dotOf(l(StatusKey.LINK_PAUSED)))
        assertEquals(Dot.GREY, StatusBoard.dotOf(l(StatusKey.TAILNET_NOT_BUILT)))
    }

    @Test
    fun `there are two groups, Home Assistant then push, and no This phone or Ringing group`() {
        assertEquals(listOf(StatusGroup.HOME_ASSISTANT, StatusGroup.PUSH), StatusGroup.entries)
        for (k in StatusKey.entries) StatusBoard.groupOf(k)
        assertEquals(StatusGroup.HOME_ASSISTANT, StatusBoard.groupOf(StatusKey.LINK_PATH_DIRECT))
        assertEquals(StatusGroup.HOME_ASSISTANT, StatusBoard.groupOf(StatusKey.TAILNET_UP))
        assertEquals(StatusGroup.HOME_ASSISTANT, StatusBoard.groupOf(StatusKey.URL_PLAIN_HTTP))
        assertEquals(StatusGroup.PUSH, StatusBoard.groupOf(StatusKey.PUSH_ON))
    }

    @Test
    fun `phone and ring configuration lines never reach the board`() {
        val all = StatusBoard.RETIRED_KEYS.map { l(it, Severity.PROBLEM) } + l(StatusKey.LINK_CONNECTED, Severity.OK)
        assertEquals(listOf(StatusKey.LINK_CONNECTED), keys(StatusBoard.dedupe(all)))
        val b = StatusBoard.build(all)
        assertEquals(1, b.values.sumOf { it.size })
        for (k in listOf(StatusKey.NOTIFICATIONS_NOT_ASKED, StatusKey.RING_CHANNEL_BLOCKED, StatusKey.BATTERY_OPTIMISED, StatusKey.CALL_STYLE_REFUSED, StatusKey.RING_SWITCH_OFF)) {
            assertTrue(k in StatusBoard.RETIRED_KEYS, k.name)
        }
        // none of the checks a person acts on in the connection or push groups is retired
        for (k in StatusKey.entries.filter { it.name.startsWith("LINK_") || it.name.startsWith("PUSH_") || it.name.startsWith("TAILNET_") || it.name.startsWith("URL_") }) {
            assertTrue(k !in StatusBoard.RETIRED_KEYS, k.name)
        }
    }

    @Test
    fun `connected says it once`() {
        val all = listOf(
            l(StatusKey.LINK_CONNECTED, Severity.OK), l(StatusKey.LINK_PATH_DIRECT, Severity.OK), l(StatusKey.PATH_DIRECT_OPENED, Severity.OK),
        )
        assertEquals(listOf(StatusKey.LINK_PATH_DIRECT), keys(StatusBoard.dedupe(all)))
        val noPath = listOf(l(StatusKey.LINK_CONNECTED, Severity.OK), l(StatusKey.PATH_TAILNET_OPENED, Severity.OK))
        assertEquals(listOf(StatusKey.LINK_CONNECTED), keys(StatusBoard.dedupe(noPath)))
        // not connected: the last opened path is history, kept
        val retrying = listOf(l(StatusKey.LINK_RETRYING), l(StatusKey.PATH_DIRECT_OPENED, Severity.OK))
        assertEquals(keys(retrying), keys(StatusBoard.dedupe(retrying)))
    }

    @Test
    fun `paused, signed out, refused address and no tailnet client are each one row`() {
        assertEquals(
            listOf(StatusKey.LINK_PAUSED),
            keys(StatusBoard.dedupe(listOf(l(StatusKey.LINK_PAUSED), l(StatusKey.PUSH_PAUSED), l(StatusKey.TAILNET_PAUSED)))),
        )
        assertEquals(
            listOf(StatusKey.LINK_SIGNED_OUT),
            keys(StatusBoard.dedupe(listOf(l(StatusKey.LINK_SIGNED_OUT, Severity.PROBLEM), l(StatusKey.PUSH_SIGNED_OUT)))),
        )
        assertEquals(
            listOf(StatusKey.URL_REFUSED),
            keys(StatusBoard.dedupe(listOf(l(StatusKey.LINK_URL_REFUSED, Severity.PROBLEM), l(StatusKey.URL_REFUSED, Severity.PROBLEM)))),
        )
        assertEquals(
            listOf(StatusKey.TAILNET_NOT_BUILT),
            keys(StatusBoard.dedupe(listOf(l(StatusKey.TAILNET_NOT_BUILT), l(StatusKey.PATH_TAILNET_NOT_BUILT)))),
        )
    }

    @Test
    fun `no check is dropped when nothing duplicates it`() {
        val all = listOf(
            l(StatusKey.LINK_AUTH_REJECTED, Severity.PROBLEM), l(StatusKey.PUSH_OFF_IN_HA),
            l(StatusKey.TAILNET_READY, Severity.OK), l(StatusKey.CONNECT_TIMELINE),
        )
        assertEquals(keys(all), keys(StatusBoard.dedupe(all)))
    }

    @Test
    fun `build groups in order and puts the worst push line first`() {
        val b = StatusBoard.build(
            listOf(
                l(StatusKey.PUSH_ON, Severity.OK), l(StatusKey.PUSH_REFUSED, Severity.PROBLEM), l(StatusKey.PUSH_RETRY),
                l(StatusKey.LINK_PATH_DIRECT, Severity.OK), l(StatusKey.TAILNET_NOT_BUILT),
            ),
        )
        assertEquals(StatusGroup.entries, b.keys.toList())
        assertEquals(listOf(Dot.RED, Dot.AMBER, Dot.GREEN), b[StatusGroup.PUSH]!!.map { it.dot })
        // Home Assistant is not re-sorted by severity: the producers' order is the logical order
        assertEquals(listOf(StatusKey.LINK_PATH_DIRECT, StatusKey.TAILNET_NOT_BUILT), keys(b[StatusGroup.HOME_ASSISTANT]!!.map { it.line }))
    }

    private fun fact(k: FactKey, v: String = "x") = StatusFact(k, v)

    @Test
    fun `Home Assistant is one section ordered connection, address, security, dial, sign-in, name, version`() {
        val lines = listOf(
            l(StatusKey.URL_PLAIN_HTTP), l(StatusKey.TAILNET_UP, Severity.OK), l(StatusKey.LINK_PATH_TAILNET, Severity.OK),
            l(StatusKey.PUSH_ON, Severity.OK),
        )
        val facts = listOf(
            fact(FactKey.VERSION), fact(FactKey.PHONE_NAME), fact(FactKey.SIGN_IN), fact(FactKey.TAILNET_DIAL), fact(FactKey.ADDRESS),
            fact(FactKey.FIREBASE),
        )
        val items = StatusBoard.items(lines, facts)
        fun name(i: StatusItem) = when (i) { is StatusItem.Check -> i.row.line.key.name; is StatusItem.Fact -> i.fact.key.name }
        assertEquals(
            listOf("LINK_PATH_TAILNET", "TAILNET_UP", "ADDRESS", "URL_PLAIN_HTTP", "TAILNET_DIAL", "SIGN_IN", "PHONE_NAME", "VERSION"),
            items.getValue(StatusGroup.HOME_ASSISTANT).map(::name),
        )
        assertEquals(listOf("PUSH_ON", "FIREBASE"), items.getValue(StatusGroup.PUSH).map(::name))
    }

    @Test
    fun `facts that only repeat a check are dropped`() {
        val all = FactKey.entries.map { fact(it) }
        fun left(vararg k: StatusKey) = StatusBoard.dedupeFacts(all, k.map { l(it) }).map { it.key }.toSet()
        assertEquals(FactKey.entries.toSet(), left(StatusKey.LINK_CONNECTED))
        assertEquals(setOf(FactKey.ENROLMENT, FactKey.VERSION), left(StatusKey.LINK_NOT_ENROLLED))
        assertEquals(FactKey.entries.toSet() - FactKey.SIGN_IN, left(StatusKey.LINK_SIGNED_OUT))
        assertEquals(FactKey.entries.toSet() - FactKey.ADDRESS, left(StatusKey.URL_REFUSED))
        assertEquals(FactKey.entries.toSet() - FactKey.FIREBASE, left(StatusKey.PUSH_NO_FIREBASE))
    }
}
