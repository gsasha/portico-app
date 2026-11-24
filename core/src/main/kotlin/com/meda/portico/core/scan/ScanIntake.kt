package com.meda.portico.core.scan

import com.meda.portico.core.enroll.SetupPlan
import com.meda.portico.core.enroll.SetupResult
import java.util.concurrent.atomic.AtomicReference

/** How a scan ended, as Setup sees it. */
sealed interface ScanOutcome {
    /** The person left the scanner (Back, Cancel) without a code. */
    data object Cancelled : ScanOutcome

    /** The device has no camera at all. */
    data object NoCamera : ScanOutcome

    /** The camera could not be opened or failed while scanning. */
    data object CameraFailed : ScanOutcome

    /** The scanner reported a code but the hand-off was empty (the process was restarted in between). */
    data object Lost : ScanOutcome

    /** A code was read. [toString] never shows [text]: it is the enrollment's secrets. */
    class Text(val text: String) : ScanOutcome {
        override fun toString(): String = "ScanOutcome.Text(${text.length} chars)"
    }
}

/** Scan endings a paste cannot produce. The app maps each to a string resource. */
enum class ScanNotice { CANCELLED, NO_CAMERA, CAMERA_FAILED, LOST, EMPTY_CODE }

sealed interface ScanVerdict {
    /** Save exactly as the Save button would: [SetupResult.Ok] from [SetupPlan.evaluate]. */
    data class Save(val plan: SetupResult.Ok) : ScanVerdict

    /** The paste field's own messages, unchanged. None quotes the input. */
    data class Refused(val problems: List<String>) : ScanVerdict

    data class Notice(val notice: ScanNotice) : ScanVerdict
}

/**
 * The scanned QR's way into Setup (P2 plan §6 PR-C2, §7 Q4). A scan is treated as "the
 * enrollment field holds this text, and Save was pressed": the same [SetupPlan.evaluate] with the
 * google-services field as it stands, the same messages, the same save plan. The raw text is
 * never put in the enrollment field (it would sit on screen and in the IME), never stored and
 * never logged; it is dropped once [evaluate] returns.
 *
 * One difference from a paste: an empty code is a notice, never "Save with an empty enrollment
 * field", which [SetupPlan] would accept as a google-services-only save.
 */
object ScanIntake {
    fun evaluate(outcome: ScanOutcome, googleServicesText: String): ScanVerdict = when (outcome) {
        ScanOutcome.Cancelled -> ScanVerdict.Notice(ScanNotice.CANCELLED)
        ScanOutcome.NoCamera -> ScanVerdict.Notice(ScanNotice.NO_CAMERA)
        ScanOutcome.CameraFailed -> ScanVerdict.Notice(ScanNotice.CAMERA_FAILED)
        ScanOutcome.Lost -> ScanVerdict.Notice(ScanNotice.LOST)
        is ScanOutcome.Text ->
            if (outcome.text.isBlank()) {
                ScanVerdict.Notice(ScanNotice.EMPTY_CODE)
            } else {
                when (val r = SetupPlan.evaluate(outcome.text, googleServicesText)) {
                    is SetupResult.Ok -> ScanVerdict.Save(r)
                    is SetupResult.Refused -> ScanVerdict.Refused(r.problems)
                }
            }
    }

    /**
     * The scanner activity's result. A code travels through [ScanHandoff] (in process), never an
     * Intent extra, so it never passes through the system's activity records. Always empties the
     * hand-off, so a stale code cannot be picked up by a later result.
     */
    fun fromActivityResult(resultOk: Boolean, failure: ScanOutcome? = null): ScanOutcome {
        val text = ScanHandoff.take()
        if (failure != null) return failure
        if (!resultOk) return ScanOutcome.Cancelled
        return if (text == null) ScanOutcome.Lost else ScanOutcome.Text(text)
    }
}

/**
 * One scanned code on its way from the scanner screen to Setup, in this process only. [take]
 * returns it once and empties the slot. [toString] says only whether something is pending.
 */
object ScanHandoff {
    private val slot = AtomicReference<String?>(null)

    fun put(text: String) = slot.set(text)

    fun take(): String? = slot.getAndSet(null)

    fun clear() = slot.set(null)

    override fun toString(): String = "ScanHandoff(pending=${slot.get() != null})"
}
