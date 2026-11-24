package com.meda.portico.call

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * DEV-698: the audio facts reach the Journal. What only the app module can do (libwebrtc's `getStats`, the wiring of the three
 * call paths) cannot run on the JVM, so it is pinned by reading the sources, as the resource tests do. The formatting, the parser
 * and the once-per-call guards are real tests in `:core`.
 */
class AudioJournalWiringTest {
    private fun src(path: String) = File("src/main/java/com/meda/portico/$path").readText()
    private val graph by lazy { src("AppGraph.kt") }
    private val rtc by lazy { src("call/WebRtcMedia.kt") }

    @Test
    fun `the answer, Talk and house WebRTC media all open through the audio journal`() {
        assertTrue(graph.contains("""audioJournaled("answer", callId, SerialMedia(WebRtcMedia(app, log, pushJournal), ::onAnswerThread))"""))
        assertTrue(graph.contains("""audioJournaled("talk", callId, SerialMedia(WebRtcMedia(app, log, pushJournal), ::onAnswerThread))"""))
        assertTrue(graph.contains("""audioFor = { callId, port -> audioJournaled("house", callId, port) }"""))
        // and no WebRtcMedia is opened outside those three
        assertEquals(3, Regex("WebRtcMedia\\(app, log, pushJournal\\)").findAll(graph).count())
    }

    @Test
    fun `the audio journal writes to the push and ring journal and reads its timer from the answer thread`() {
        val helper = graph.substringAfter("private fun audioJournaled(").substringBefore("private fun onAnswerThread")
        assertTrue(helper.contains("real, pushJournal, path, callId"))
        assertTrue(helper.contains("answerExec.schedule("))
    }

    @Test
    fun `libwebrtc is asked for its stats three ways only - the 5 s window sampler, the start read, the close read - never per frame`() {
        val calls = Regex("\\.getStats\\b").findAll(rtc).count()
        assertEquals(3, calls, "the sampler's read, the start read, the close read")
        assertFalse(rtc.contains("scheduleAtFixedRate") || rtc.contains("scheduleWithFixedDelay"), "no executor polling: the sampler re-arms itself")
        assertTrue(rtc.substringAfter("override fun audioStats").substringBefore("override fun close()").contains("p.getStats"))
        assertTrue(rtc.substringAfter("override fun closeReporting").contains("p.getStats"))
        val sampler = rtc.substringAfter("private val sampler = RtcWindowSampler(").substringBefore("private val observer")
        assertTrue(sampler.contains("guard.run { p.getStats"), "the window read is a guarded native touch")
        assertTrue(sampler.contains("rawStats(report)"))
        assertTrue(sampler.contains("journal?.diag(\"ui\", event"))
    }

    @Test
    fun `the window sampler starts when the audio connects and stops before the native objects are guarded off`() {
        val observer = rtc.substringAfter("override fun onConnectionChange").substringBefore("override fun onSignalingChange")
        assertTrue(observer.contains("sampler.start()"))
        assertTrue(observer.indexOf("sampler.start()") < observer.indexOf("listener.onConnected()"))
        val close = rtc.substringAfter("override fun closeReporting").substringBefore("teardownExecutor.execute")
        assertTrue(close.contains("sampler.stop()"))
        assertTrue(close.indexOf("sampler.stop()") < close.indexOf("guard.close()"))
    }

    @Test
    fun `the device module's start, stop and error callbacks and its settings are journalled, and the builder keeps its defaults`() {
        for (cb in listOf("setAudioTrackStateCallback", "setAudioRecordStateCallback", "setAudioTrackErrorCallback", "setAudioRecordErrorCallback")) assertTrue(rtc.contains(cb), cb)
        for (ev in listOf("trkstart", "trkstop", "recstart", "recstop", "trkerr", "recerr")) assertTrue(rtc.contains("\"$ev\""), ev)
        assertTrue(rtc.contains("jr(\"rtc-adm-cfg\""))
        // no behaviour change: none of the audio-affecting builder setters, no gain, no volume
        for (bad in listOf("setUseHardware", "setUseLowLatency", "setAudioAttributes", "setAudioSource", "setSampleRate", "setVolume", "createAudioSource(MediaConstraints().")) assertFalse(rtc.contains(bad), bad)
        // the error text is free text: only its kind reaches the line
        assertTrue(rtc.lines().filter { it.contains("jr(\"rtc-adm\"") }.none { it.contains("to msg") })
    }

    @Test
    fun `both paths journal the Android audio environment at start and end, and the frames path its window lines`() {
        assertTrue(graph.contains("onEnv = ::audioEnv,"), "the WebRTC decorator")
        assertEquals(3, Regex("onReport = ::frameAudioReport, onDiag = ::audioDiag, onEnv = ::audioEnv").findAll(graph).count(), "the three frame-path builds")
        assertTrue(graph.contains("pushJournal.diag(\"ui\", \"audio-env\""))
        assertTrue(graph.contains("pushJournal.diag(\"ui\", event, fields)"))
        assertTrue(src("call/TailnetAwayMedia.kt").contains("onDiag = onDiag, onEnv = onEnv"))
        assertTrue(src("call/AndroidPcmDevice.kt").contains("override fun facts()"))
    }

    @Test
    fun `closing switches the microphone track off before it waits for the last stats, and the wait is bounded`() {
        val close = rtc.substringAfter("override fun closeReporting").substringBefore("private fun parse")
        assertTrue(close.indexOf("track?.setEnabled(false)") in 0 until close.indexOf("teardownExecutor.execute"), "the mic is off first")
        assertTrue(close.contains("done.await(STATS_WAIT_MS"))
        assertTrue(close.indexOf("onStats(stats)") < close.indexOf("teardown()"), "stats are read before the teardown")
        assertTrue(close.contains("if (!closed.compareAndSet(false, true)) return"), "a repeat close does nothing")
        assertTrue(Regex("STATS_WAIT_MS = (\\d+)L").find(rtc)!!.groupValues[1].toInt() <= 1000)
    }

    @Test
    fun `the stats are reduced to RawStat entries for the pure parser and nothing else is taken from the report`() {
        val parse = rtc.substringAfter("private fun parse").substringBefore("/** [SdpObserver]")
        assertTrue(parse.contains("AudioStatsParser.parse(report.statsMap.values.map { RawStat(it.type, it.id, it.members) })"))
        // the journal's source is the stats report, never the SDP the call negotiated
        assertFalse(parse.contains("localDescription") || parse.contains("remoteDescription") || parse.contains(".description"))
        assertFalse(parse.contains("log.log"), "the stats are not written to logcat from here")
    }
}
