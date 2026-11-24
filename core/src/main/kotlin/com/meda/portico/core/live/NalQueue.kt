package com.meda.portico.core.live

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The bounded hand-off between the Go RTSP client's thread and a tile's decoder thread (DEV-713 S1b): the only place H.264 waits on
 * the phone, so the only place it could pile up. [offerFormat] and [offerNal] never block and never grow past [maxItems] items or
 * [maxBytes] bytes; [take] blocks the consumer.
 *
 * When a NAL unit does not fit the decoder is behind. Dropping one picture of a stream corrupts every picture that refers to it, so the
 * queue does not drop single pictures: it empties itself and refuses everything until the next IDR ([resyncs] counts these), which then
 * starts a clean run. A NAL unit larger than [maxNalBytes] counts as not fitting too. The latest parameter sets are always kept.
 */
class NalQueue(
    private val maxItems: Int = MAX_ITEMS,
    private val maxBytes: Int = MAX_BYTES,
    private val maxNalBytes: Int = MAX_NAL_BYTES,
) {
    sealed class Item {
        class Format(val sps: ByteArray, val pps: ByteArray, val width: Int, val height: Int) : Item()
        class Nal(val bytes: ByteArray, val ptsUs: Long, val key: Boolean) : Item()
    }

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val items = ArrayDeque<Item>()
    private var bytes = 0
    private var needKey = false
    private var closed = false

    /** NAL units refused or discarded since the start, and how many times the queue had to resync. */
    @Volatile var dropped = 0L
        private set
    @Volatile var resyncs = 0L
        private set

    fun offerFormat(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
        lock.withLock {
            if (closed) return
            items.addLast(Item.Format(sps, pps, width, height))
            cond.signalAll()
        }
    }

    /** True when the unit was queued. */
    fun offerNal(nal: ByteArray, ptsUs: Long, key: Boolean): Boolean {
        lock.withLock {
            if (closed) return false
            if (nal.size > maxNalBytes) {
                resync()
                dropped++
                return false
            }
            if (needKey) {
                if (!key) {
                    dropped++
                    return false
                }
                needKey = false
            }
            if (items.size >= maxItems || bytes + nal.size > maxBytes) {
                resync()
                if (!key) {
                    dropped++
                    return false
                }
                needKey = false // an IDR starts the clean run itself
            }
            items.addLast(Item.Nal(nal, ptsUs, key))
            bytes += nal.size
            cond.signalAll()
            return true
        }
    }

    /** Empties the queue of pictures (keeping the newest parameter sets) and refuses non-IDR units until an IDR comes. Call with the lock held. */
    private fun resync() {
        val lastFormat = items.lastOrNull { it is Item.Format }
        for (i in items) if (i is Item.Nal) dropped++
        items.clear()
        bytes = 0
        if (lastFormat != null) items.addLast(lastFormat)
        needKey = true
        resyncs++
    }

    /** The next item, waiting up to [timeoutMs] (0: do not wait); null when there is none or the queue is closed and empty. */
    fun take(timeoutMs: Long): Item? {
        lock.withLock {
            if (items.isEmpty() && !closed && timeoutMs > 0) cond.await(timeoutMs, TimeUnit.MILLISECONDS)
            val i = items.removeFirstOrNull() ?: return null
            if (i is Item.Nal) bytes -= i.bytes.size
            return i
        }
    }

    fun size(): Int = lock.withLock { items.size }

    fun queuedBytes(): Int = lock.withLock { bytes }

    /** Wakes the consumer and refuses everything from now on. */
    fun close() {
        lock.withLock {
            closed = true
            items.clear()
            bytes = 0
            cond.signalAll()
        }
    }

    companion object {
        /** About two seconds of 30 fps video. */
        const val MAX_ITEMS = 90
        const val MAX_BYTES = 4 * 1024 * 1024

        /** The largest NAL unit taken (a 1080p IDR is far smaller); the Go client has the same cap. */
        const val MAX_NAL_BYTES = 1024 * 1024
    }
}
