package com.meda.portico.core.ha

import com.meda.portico.core.content.CameraState
import com.meda.portico.core.content.CameraViewport
import com.meda.portico.core.content.ContentCodec
import com.meda.portico.core.content.HomeModel
import com.meda.portico.core.content.StreamHeights
import com.meda.portico.core.content.StreamPick
import com.meda.portico.core.push.Redact
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 2026-11-12 (app 0.6.56): the gear menu said "Low" for the Gate's small stream although the server's live document carried
 * `streams.small.height = 288`. The document in `content_get_live_2026-11-12.json` is the REAL `portico/content/get` frame (endpoint redacted).
 * The wire parser ([ContentCommands.parseGet]) never read `streams`; only the cache decoder did, so the heights existed in the decoder's
 * tests and nowhere on a phone.
 */
class StreamLabelsLiveDocumentTest {
    private val frame = String(javaClass.getResourceAsStream("/content_get_live_2026-11-12.json")!!.readBytes(), Charsets.UTF_8)

    private fun live() = (ContentCommands.parseGet(HaProtocol.parse(frame).single() as HaInbound.Result) as ContentCommands.Outcome.Profile).profile

    /** The menu rows exactly as the app builds them: played height, else the tile's server height. */
    private fun rows(c: ContentCamera): List<Pair<StreamPick, String?>> {
        val tile = HomeModel.cameraTile(c, true, emptySet())
        val heights = StreamHeights()
        return CameraViewport.streamMenu(CameraState(), CameraSize.BIG, tile.hasSmall) { heights.height(c.id, it, tile.serverHeight(it)) }.items.map { it.pick to it.quality }
    }

    private fun cameras(p: ContentProfile) = p.pageList.single().cameras.associateBy { it.id }

    @Test
    fun `the live gate and doorbell name their streams by the server heights`() {
        val cams = cameras(live())
        assertEquals(
            listOf(StreamPick.AUTO to "720p", StreamPick.BIG to "720p", StreamPick.SMALL to "288p", StreamPick.STILLS to null, StreamPick.OFF to null),
            rows(cams.getValue("gate-intercom")),
        )
        assertEquals(
            listOf(StreamPick.AUTO to "1200p", StreamPick.BIG to "1200p", StreamPick.SMALL to "720p", StreamPick.STILLS to null, StreamPick.OFF to null),
            rows(cams.getValue("doorbell-2")),
        )
    }

    @Test
    fun `the same document through the cache keeps the heights`() {
        val cached = ContentCodec.decode(ContentCodec.encode(live()))!!
        val gate = cameras(cached).getValue("gate-intercom")
        assertEquals(720 to 288, gate.bigHeight to gate.smallHeight)
    }

    @Test
    fun `the menu journals where each label came from`() {
        val gate = HomeModel.cameraTile(cameras(live()).getValue("gate-intercom"), true, emptySet())
        val heights = StreamHeights()
        heights.record(gate.id, CameraSize.BIG, 720)
        assertEquals(
            "ui menu-labels cam=${Redact.prefix(gate.id)} big=720(played) small=288(server)",
            CameraViewport.menuLabelsLine(gate.id, { heights.height(gate.id, it) }, { gate.serverHeight(it) }),
        )
        assertEquals("ui menu-labels cam=${Redact.prefix("x")} big=none small=none", CameraViewport.menuLabelsLine("x", { null }, { null }))
    }
}
