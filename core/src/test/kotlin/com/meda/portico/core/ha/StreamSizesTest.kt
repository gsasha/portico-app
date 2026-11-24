package com.meda.portico.core.ha

import com.meda.portico.core.content.CameraState
import com.meda.portico.core.content.CameraViewport
import com.meda.portico.core.content.ContentCodec
import com.meda.portico.core.content.HomeModel
import com.meda.portico.core.content.StreamHeights
import com.meda.portico.core.content.StreamPick
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 0.6.54: the server's `streams` sizes feed the gear menu's labels before a stream has played. */
class StreamSizesTest {
    private fun text(extra: String) =
        """{"v":3,"rev":"r9","pages":[{"id":"p","label":"P","blocks":[{"id":"b","cameras":[{"id":"gate","label":"Gate","available":true,"small":true$extra}],"actions":[]}]}],"callers":{},"roster":[]}"""

    private fun cameras(extra: String): List<ContentCamera> = ContentCodec.decode(text(extra))!!.pageList.single().cameras

    @Test
    fun `both variants are read, one is enough, and old documents have none`() {
        val both = cameras(""","streams":{"big":{"width":2560,"height":1440},"small":{"width":640,"height":360}}""").single()
        assertEquals(1440 to 360, both.bigHeight to both.smallHeight)
        val one = cameras(""","streams":{"small":{"height":480}}""").single()
        assertEquals(null to 480, one.bigHeight to one.smallHeight)
        val none = cameras("").single()
        assertEquals(null to null, none.bigHeight to none.smallHeight)
    }

    @Test
    fun `hostile or odd streams read as absent and never throw, unknown fields are ignored`() {
        for (bad in listOf("0", "\"x\"", "null", "[]", """{"big":5}""", """{"big":{"height":"1440"}}""", """{"big":{"height":0}}""", """{"big":{"height":-3}}""", """{"big":{"height":14.5}}""", """{"big":{"height":9999999}}""")) {
            val c = cameras(""","streams":$bad""").single()
            assertNull(c.bigHeight, bad)
            assertEquals("Gate", c.label)
        }
        val extra = cameras(""","streams":{"big":{"height":1080,"codec":"h265","fps":25},"medium":{"height":1}}""").single()
        assertEquals(1080 to null, extra.bigHeight to extra.smallHeight)
    }

    @Test
    fun `the cache keeps the sizes through encode and decode and writes them only when present`() {
        val with = ContentCodec.decode(text(""","streams":{"big":{"height":1440},"small":{"height":360}}"""))!!
        val back = ContentCodec.decode(ContentCodec.encode(with))!!.pageList.single().cameras.single()
        assertEquals(1440 to 360, back.bigHeight to back.smallHeight)
        assertTrue(ContentCodec.encode(with).contains("streams"))
        assertFalse(ContentCodec.encode(ContentCodec.decode(text(""))!!).contains("streams"))
    }

    @Test
    fun `server sizes name the streams before they play, the played height wins, and no height is null`() {
        val tile = HomeModel.cameraTile(cameras(""","streams":{"big":{"height":1440},"small":{"height":360}}""").single(), true, emptySet())
        assertEquals(1440 to 360, tile.serverHeight(CameraSize.BIG) to tile.serverHeight(CameraSize.SMALL))
        val heights = StreamHeights()
        fun labels() = CameraViewport.streamMenu(CameraState(), CameraSize.BIG, true) { heights.height("gate", it, tile.serverHeight(it)) }.items.map { it.pick to it.quality }
        assertEquals(listOf(StreamPick.AUTO to "1440p", StreamPick.BIG to "1440p", StreamPick.SMALL to "360p", StreamPick.STILLS to null, StreamPick.OFF to null), labels())
        heights.record("gate", CameraSize.BIG, 1080) // what really played overrides the server's number
        assertEquals("1080p", labels()[1].second)
        val bare = HomeModel.cameraTile(cameras("").single(), true, emptySet())
        assertNull(StreamHeights().height("gate", CameraSize.BIG, bare.serverHeight(CameraSize.BIG)), "neither known: the app says High / Low")
    }
}
