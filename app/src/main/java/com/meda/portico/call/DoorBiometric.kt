package com.meda.portico.call

import android.os.Build
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.meda.portico.AppGraph
import com.meda.portico.R
import com.meda.portico.core.ring.InCallLock
import com.meda.portico.core.ring.InCallLock.BioResult

/**
 * the owner 2026-11-21: the confirmation a door, gate or lock button on the in-call screen needs while the phone is locked. An app-level
 * androidx.biometric prompt (a small sheet over the call screen, so the camera stays behind it); it does NOT unlock the phone. Only a success
 * lets the caller run the button's usual path; a cancel or an error does nothing. A phone with no secure lock or biometric cannot show it
 * ([BioResult.UNAVAILABLE]): the caller falls back to the system keyguard request, the old rule.
 *
 * Journal: `bio-prompt` (auth, avail code), `bio-result` (r, ms, code).
 */
object DoorBiometric {
    fun prompt(a: AppCompatActivity, graph: AppGraph, label: String, onResult: (BioResult) -> Unit) {
        val auth = InCallLock.bioAuth(Build.VERSION.SDK_INT)
        val flags = when (auth) {
            InCallLock.BioAuth.STRONG_OR_DEVICE_CREDENTIAL -> BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            InCallLock.BioAuth.STRONG_ONLY -> BiometricManager.Authenticators.BIOMETRIC_STRONG
        }
        val avail = try { BiometricManager.from(a).canAuthenticate(flags) } catch (_: Throwable) { BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE }
        val authWord = if (auth == InCallLock.BioAuth.STRONG_ONLY) "strong" else "strong-or-cred"
        InCallUnlock.journal(graph, "bio-prompt", "auth" to authWord, "avail" to avail)
        if (avail != BiometricManager.BIOMETRIC_SUCCESS) {
            InCallUnlock.journal(graph, "bio-result", "r" to BioResult.UNAVAILABLE.word, "ms" to 0, "code" to avail)
            return onResult(BioResult.UNAVAILABLE)
        }
        val t0 = SystemClock.elapsedRealtime()
        val report = { r: BioResult, code: Int ->
            InCallUnlock.journal(graph, "bio-result", "r" to r.word, "ms" to (SystemClock.elapsedRealtime() - t0), "code" to code)
            onResult(r)
        }
        val bp = BiometricPrompt(
            a, ContextCompat.getMainExecutor(a),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = report(BioResult.OK, 0)

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                    report(if (cancelled) BioResult.CANCEL else BioResult.ERROR, errorCode)
                }
                // onAuthenticationFailed (a finger or face that did not match): the sheet stays up and asks again; nothing to do.
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(a.getString(R.string.bio_title))
            .setSubtitle(a.getString(R.string.bio_subtitle, label))
            .setAllowedAuthenticators(flags)
            .apply { if (auth == InCallLock.BioAuth.STRONG_ONLY) setNegativeButtonText(a.getString(R.string.bio_cancel)) }
            .build()
        try {
            bp.authenticate(info)
        } catch (e: Exception) {
            report(BioResult.ERROR, -1)
        }
    }
}
