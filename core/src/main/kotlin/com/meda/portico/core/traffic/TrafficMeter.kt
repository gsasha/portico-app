package com.meda.portico.core.traffic

import java.util.concurrent.atomic.AtomicLong

/**
 * Payload bytes the app itself classified, counted where each stream is recognisable (A-10): video (live pictures over RTSP, camera
 * stills) and audio (call frames over the link). Process-wide and only ever going up; the [MobileSampler] charges the growth of an
 * interval to the mobile meter only when the phone was on mobile data. Cheap and non-blocking, safe from a frame loop.
 */
object TrafficTally {
    private val video = AtomicLong()
    private val audio = AtomicLong()

    fun addVideo(bytes: Long) {
        if (bytes > 0) video.addAndGet(bytes)
    }

    fun addAudio(bytes: Long) {
        if (bytes > 0) audio.addAndGet(bytes)
    }

    fun video(): Long = video.get()
    fun audio(): Long = audio.get()
}

/** Which network the bytes of an interval were moved over (0.6.70: Wi-Fi is metered too). Roaming counts as [MOBILE]; Ethernet counts as [WIFI]. */
enum class Medium { MOBILE, WIFI }

/** What was moved over one medium, in bytes (mobile data historically, hence the name). [os] is Android's own count for this app (everything); [video] and [audio] are what the app classified. */
data class MobileUse(val os: Long = 0, val video: Long = 0, val audio: Long = 0) {
    /** Everything else: the link to Home Assistant, the tailnet's control traffic, tunnel overhead; the part of [os] nothing classified. */
    val other: Long get() = (os - video - audio).coerceAtLeast(0)

    /** The classified parts can briefly exceed the OS count (payload counted before the radio sent it): the total never shows less than they add up to. */
    val total: Long get() = maxOf(os, video + audio)

    operator fun plus(o: MobileUse) = MobileUse(os + o.os, video + o.video, audio + o.audio)
}

/** Durable text for the meter (the app: one SharedPreferences string). */
interface TrafficStore {
    fun read(): String
    fun write(text: String)
}

/**
 * The traffic meter's ledger (A-10): bytes per local calendar day and per [Medium], with a month and a since-reset view, resettable.
 * 0.6.70: Wi-Fi is kept beside mobile. **Storage is backward compatible:** the mobile lines are exactly the old ledger
 * (`since,`, `total,`, `yyyy-MM-dd,`), so an old ledger reads back as mobile; Wi-Fi uses its own `wtotal,` and `w,yyyy-MM-dd,`
 * lines, which an older build skips as unknown. Every method without a medium means [Medium.MOBILE].
 *
 * **Bounded:** at most [maxDays] day rows are kept (default 62: this month and last), the oldest dropped on every write, so the
 * stored text is at most ~62 short lines. The "since reset" total is a separate running sum that is NOT cut by the rollover, so
 * dropping an old day never changes it. **Rollover** is by date: a row belongs to the day it was recorded on, the month view is the
 * rows of the current `yyyy-MM`. [dayOf] maps a clock time to `yyyy-MM-dd` in the phone's zone (injected, so the tests need no zone).
 * Thread-safe. Any store error is fenced: a meter must never break a screen.
 */
class TrafficMeter(
    private val store: TrafficStore,
    private val dayOf: (Long) -> String,
    private val maxDays: Int = 62,
) {
    private val days = Medium.entries.associateWith { java.util.TreeMap<String, MobileUse>() }
    private val sinceResets = Medium.entries.associateWith { MobileUse() }.toMutableMap()
    private var sinceMs = 0L
    private var loaded = false

    @Synchronized
    fun add(nowMs: Long, use: MobileUse, medium: Medium = Medium.MOBILE) {
        if (use.os <= 0 && use.video <= 0 && use.audio <= 0) return
        load(nowMs)
        val day = dayOf(nowMs)
        val rows = days.getValue(medium)
        rows[day] = (rows[day] ?: MobileUse()) + use
        sinceResets[medium] = sinceResets.getValue(medium) + use
        while (rows.size > maxDays) rows.pollFirstEntry()
        save()
    }

    @Synchronized
    fun today(nowMs: Long, medium: Medium = Medium.MOBILE): MobileUse {
        load(nowMs)
        return days.getValue(medium)[dayOf(nowMs)] ?: MobileUse()
    }

    @Synchronized
    fun month(nowMs: Long, medium: Medium = Medium.MOBILE): MobileUse {
        load(nowMs)
        val prefix = dayOf(nowMs).substring(0, 7)
        var sum = MobileUse()
        for ((d, u) in days.getValue(medium)) if (d.startsWith(prefix)) sum += u
        return sum
    }

    @Synchronized
    fun sinceReset(nowMs: Long, medium: Medium = Medium.MOBILE): MobileUse {
        load(nowMs)
        return sinceResets.getValue(medium)
    }

    /** When counting began (the last reset, or the first byte), epoch ms. */
    @Synchronized
    fun sinceMs(nowMs: Long): Long {
        load(nowMs)
        return sinceMs
    }

    @Synchronized
    fun reset(nowMs: Long) {
        load(nowMs)
        days.values.forEach { it.clear() }
        Medium.entries.forEach { sinceResets[it] = MobileUse() }
        sinceMs = nowMs
        save()
    }

    /** Number of day rows held (a test reads it to prove the bound). */
    @Synchronized
    fun rows(medium: Medium = Medium.MOBILE): Int = days.getValue(medium).size

    private fun load(nowMs: Long) {
        if (loaded) return
        loaded = true
        sinceMs = nowMs
        val text = try {
            store.read()
        } catch (_: Exception) {
            ""
        }
        for (line in text.lineSequence()) {
            val p = line.split(',')
            try {
                when {
                    p.size == 2 && p[0] == "since" -> sinceMs = p[1].toLong()
                    p.size == 4 && p[0] == "total" -> sinceResets[Medium.MOBILE] = use(p, 1)
                    p.size == 4 && p[0] == "wtotal" -> sinceResets[Medium.WIFI] = use(p, 1)
                    p.size == 4 && p[0].length == 10 -> days.getValue(Medium.MOBILE)[p[0]] = use(p, 1)
                    p.size == 5 && p[0] == "w" && p[1].length == 10 -> days.getValue(Medium.WIFI)[p[1]] = use(p, 2)
                }
            } catch (_: NumberFormatException) {
                // a damaged line is skipped, never fatal
            }
        }
        days.values.forEach { while (it.size > maxDays) it.pollFirstEntry() }
    }

    private fun use(p: List<String>, at: Int) = MobileUse(p[at].toLong(), p[at + 1].toLong(), p[at + 2].toLong())

    private fun save() {
        val sb = StringBuilder()
        sb.append("since,").append(sinceMs).append('\n')
        fun line(prefix: String, u: MobileUse) = sb.append(prefix).append(u.os).append(',').append(u.video).append(',').append(u.audio).append('\n')
        line("total,", sinceResets.getValue(Medium.MOBILE))
        for ((d, u) in days.getValue(Medium.MOBILE)) line("$d,", u)
        val wifi = sinceResets.getValue(Medium.WIFI)
        if (wifi != MobileUse()) line("wtotal,", wifi)
        for ((d, u) in days.getValue(Medium.WIFI)) line("w,$d,", u)
        try {
            store.write(sb.toString())
        } catch (_: Exception) {
            // kept in memory; the next write tries again
        }
    }
}

/**
 * Turns the running counters into meter entries (A-10). Call [sample] on a timer and BEFORE a network change is applied: the growth
 * since the last call is charged to the medium that was current for that interval (0.6.70: mobile or Wi-Fi; any other network charges nothing).
 * The first call only records a baseline. A counter that went DOWN (a reboot resets Android's) is a new baseline, never negative
 * traffic. Bytes moved while the process was dead are not counted (at most the last interval is lost).
 */
class MobileSampler(private val meter: TrafficMeter) {
    private var lastOs = -1L
    private var lastVideo = -1L
    private var lastAudio = -1L

    /** [os] is the app's OS-counted rx+tx so far, or null when the platform cannot say (nothing is charged). [medium] null: not charged. */
    @Synchronized
    fun sample(nowMs: Long, medium: Medium?, os: Long?, video: Long, audio: Long) {
        val dOs = delta(os ?: -1L, lastOs)
        val dVideo = delta(video, lastVideo)
        val dAudio = delta(audio, lastAudio)
        if (os != null) lastOs = os
        lastVideo = video
        lastAudio = audio
        if (medium == null) return
        meter.add(nowMs, MobileUse(dOs, dVideo, dAudio), medium)
    }

    private fun delta(now: Long, last: Long): Long = if (now < 0 || last < 0 || now < last) 0 else now - last
}
