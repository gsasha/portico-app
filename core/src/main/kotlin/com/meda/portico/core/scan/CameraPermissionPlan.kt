package com.meda.portico.core.scan

/** What Setup does when "Scan QR" is tapped. */
enum class ScanStart {
    /** The camera may be used: open the scanner. */
    OPEN_SCANNER,

    /** Say why the camera is needed (and that pasting works too), then show Android's prompt. */
    EXPLAIN_THEN_ASK,

    /** Explained before and refused: ask again without a second explanation. Android answers
     *  at once, with no dialog, when the person chose "don't ask again"; [CameraPermissionPlan.afterAsk] handles that. */
    ASK,

    /** The device has no camera: say so; the paste field is the way. */
    NO_CAMERA,
}

/** What Setup does with Android's answer to the camera prompt. */
enum class AfterAsk {
    OPEN_SCANNER,

    /** Refused this time; tapping "Scan QR" asks again. */
    DENIED,

    /** Refused and Android will not ask again ("don't ask again", or a policy): offer the app's settings screen. */
    DENIED_PERMANENTLY,
}

/**
 * The CAMERA runtime permission, asked at the moment of use (P2 plan §6 PR-C2). Pure, so every
 * branch is JVM-tested; the Android calls stay in `SetupActivity`.
 *
 * Android's `shouldShowRequestPermissionRationale` is false both before the first prompt and after
 * "don't ask again", so [askedBefore] (a flag Setup stores when it first shows the prompt) tells
 * them apart. Without it "never asked" would read as "blocked" and the prompt would never appear.
 * Rather than trusting the flag to mean "blocked", a refused-before tap asks again ([ScanStart.ASK]):
 * if Android shows no dialog the answer comes back refused with no rationale, which [afterAsk]
 * turns into [AfterAsk.DENIED_PERMANENTLY]. So a dismissed dialog (Android 11+ does not count that
 * as a refusal) is never mistaken for a permanent block.
 */
object CameraPermissionPlan {
    fun onScanTapped(hasCamera: Boolean, granted: Boolean, askedBefore: Boolean, showRationale: Boolean): ScanStart = when {
        !hasCamera -> ScanStart.NO_CAMERA
        granted -> ScanStart.OPEN_SCANNER
        !askedBefore || showRationale -> ScanStart.EXPLAIN_THEN_ASK
        else -> ScanStart.ASK
    }

    fun afterAsk(granted: Boolean, showRationale: Boolean): AfterAsk = when {
        granted -> AfterAsk.OPEN_SCANNER
        showRationale -> AfterAsk.DENIED
        else -> AfterAsk.DENIED_PERMANENTLY
    }
}
