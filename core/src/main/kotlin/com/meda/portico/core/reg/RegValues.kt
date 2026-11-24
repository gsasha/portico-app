package com.meda.portico.core.reg

/**
 * Turns what `FirebaseMessagingService` hands the app into a [RegValue] of the right kind. The
 * server sends with `message.fid` for kind `fid` and `message.token` for kind `token` (sender
 * spec §2.4), so the kind must match what the value really is.
 *
 * Why `onRegistered` is not always an FID [V: firebase-messaging 25.1.3 bytecode,
 * `GmsRegistrationClient.registerInternal`]: in FID mode the SDK registers over the new
 * "V1" path only when Google Play services is at least 26.12 (`261200000`); on an older Play
 * services it falls back to the legacy RPC and delivers that legacy registration TOKEN through
 * the same `onRegistered` callback. An FID has a fixed shape [V: `RandomFidGenerator`]:
 * 22 url-safe base64 characters whose first 4 bits are `0111`, so the first character is c-f.
 * Anything else is a token (FCM tokens are ~160 characters and contain `:`).
 */
object RegValues {
    private val FID_RE = Regex("^[c-f][A-Za-z0-9_-]{21}$")

    fun fromOnRegistered(value: String): RegValue =
        RegValue(if (FID_RE.matches(value)) RegKind.FID else RegKind.TOKEN, value)

    /** `onNewToken` (token mode only; it never fires in FID mode) always carries a token. */
    fun fromOnNewToken(value: String): RegValue = RegValue(RegKind.TOKEN, value)
}
