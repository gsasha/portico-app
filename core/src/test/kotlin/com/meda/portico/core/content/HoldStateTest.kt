package com.meda.portico.core.content

import com.meda.portico.core.content.HoldState.Outcome
import com.meda.portico.core.content.HoldState.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The hold-to-confirm machine, driven by a fake clock (every input carries its own time). */
class HoldStateTest {
    private val hold = HoldToConfirm.HOLD_MS

    @Test
    fun `the hold is one second`() {
        assertEquals(1000L, HoldToConfirm.HOLD_MS)
    }

    @Test
    fun `a tick before the hold is over does nothing and the progress climbs`() {
        val s = HoldState()
        assertEquals(Outcome.NONE, s.down(5_000))
        assertEquals(Outcome.NONE, s.tick(5_000 + 250))
        assertEquals(0.25f, s.progress(5_000 + 250), 0.0001f)
        assertEquals(0.999f, s.progress(5_000 + 999), 0.0001f)
        assertEquals(Outcome.NONE, s.tick(5_000 + 999))
        assertEquals(Phase.HOLDING, s.phase)
    }

    @Test
    fun `a tick at the hold fires and fires once`() {
        val s = HoldState()
        s.down(0)
        assertEquals(Outcome.FIRE, s.tick(hold))
        assertEquals(Outcome.NONE, s.tick(hold + 16))
        assertEquals(Outcome.NONE, s.tick(hold + 5_000))
        assertEquals(Phase.FIRED, s.phase)
    }

    @Test
    fun `the up that follows a fire does not fire again and gives no hint`() {
        val s = HoldState()
        s.down(0)
        assertEquals(Outcome.FIRE, s.tick(hold + 3))
        assertEquals(Outcome.NONE, s.up(hold + 400))
        assertEquals(Outcome.NONE, s.up(hold + 401))
        assertEquals(Phase.FIRED, s.phase)
    }

    @Test
    fun `an up before the hold resets the fill, fires nothing and says hold`() {
        val s = HoldState()
        s.down(100)
        assertEquals(Outcome.HINT, s.up(100 + hold - 1))
        assertEquals(Phase.CANCELLED, s.phase)
        assertEquals(0f, s.progress(100 + hold - 1))
        assertEquals(Outcome.NONE, s.tick(100 + hold + 50)) // a late frame after the lift fires nothing
    }

    @Test
    fun `an up exactly at the hold fires`() {
        val s = HoldState()
        s.down(100)
        assertEquals(Outcome.FIRE, s.up(100 + hold))
        assertEquals(Outcome.NONE, s.up(100 + hold + 1))
    }

    @Test
    fun `a short tap is a hint and nothing else`() {
        val s = HoldState()
        s.down(0)
        assertEquals(Outcome.HINT, s.up(120))
        assertEquals(0f, s.progress(120))
    }

    @Test
    fun `a cancel resets the fill and gives no hint and no fire`() {
        val s = HoldState()
        s.down(0)
        s.tick(500)
        s.cancel()
        assertEquals(Phase.CANCELLED, s.phase)
        assertEquals(0f, s.progress(500))
        assertEquals(Outcome.NONE, s.tick(hold + 10))
        assertEquals(Outcome.NONE, s.up(hold + 20))
    }

    @Test
    fun `a cancel after a fire leaves it fired`() {
        val s = HoldState()
        s.down(0)
        s.tick(hold)
        s.cancel()
        assertEquals(Phase.FIRED, s.phase)
        assertEquals(1f, s.progress(hold + 1))
    }

    @Test
    fun `an up or a tick with no down does nothing`() {
        val s = HoldState()
        assertEquals(Outcome.NONE, s.up(10))
        assertEquals(Outcome.NONE, s.tick(10_000))
        assertEquals(Phase.IDLE, s.phase)
        assertEquals(0f, s.progress(10_000))
    }

    @Test
    fun `a new down after a fire or a cancel starts a fresh hold that can fire once more`() {
        val s = HoldState()
        s.down(0)
        assertEquals(Outcome.FIRE, s.tick(hold))
        s.down(5_000)
        assertEquals(0f, s.progress(5_000))
        assertEquals(Outcome.NONE, s.tick(5_000 + hold - 1))
        assertEquals(Outcome.FIRE, s.tick(5_000 + hold))
        s.down(9_000)
        s.cancel()
        s.down(10_000)
        assertEquals(Outcome.FIRE, s.up(10_000 + hold))
    }

    @Test
    fun `progress never leaves 0 to 1`() {
        val s = HoldState()
        s.down(1_000)
        assertEquals(0f, s.progress(900)) // a clock that reads before the down
        assertEquals(1f, s.progress(1_000 + hold * 3)) // a frame long after, before any tick
    }
}
