package com.meda.portico.fcm

import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.RegistrationSource
import com.meda.portico.core.reg.RegKind
import com.meda.portico.core.reg.RegValue
import com.google.firebase.messaging.FirebaseMessaging

/**
 * D's [RegistrationSource] over `FirebaseMessaging` (firebase-messaging 25.1.3, FID mode: the
 * manifest sets `firebase_messaging_installation_id_enabled`).
 *
 * - [requestFid]: `register()`. Its Task carries no value; the value arrives through
 *   `PorticoMessagingService.onRegistered`, which feeds the machine. A failed Task reports
 *   [onFailed] (the machine then asks for the token fallback).
 * - [requestToken], the machine's fallback after its wait: `getToken()` first. **In FID mode
 *   25.1.3 refuses `getToken()`** ("API disabled. Please use register() instead", verified in its
 *   bytecode), so on failure this issues `register()` once more: the SDK itself falls back to a
 *   legacy token on Play services older than 26.12 and delivers it through `onRegistered` (kind
 *   decided by `RegValues`). If that also fails, [onFailed] marks the source exhausted and the
 *   machine retries on the next connect.
 *
 * Callbacks arrive on the main thread; [onValue] and [onFailed] must hop to the link's thread
 * (the app posts them through `SignalingClient.post`). Logs only exception class names.
 */
class FirebaseRegistrationSource(
    private val onValue: (RegValue) -> Unit,
    private val onFailed: () -> Unit,
    private val log: HaLog,
) : RegistrationSource {

    override fun requestFid() {
        register("register()")
    }

    override fun requestToken() {
        val messaging = messaging() ?: return
        try {
            messaging.token
                .addOnSuccessListener { t -> if (!t.isNullOrBlank()) onValue(RegValue(RegKind.TOKEN, t)) else onFailed() }
                .addOnFailureListener { e ->
                    log.log(HaLog.Level.INFO, "getToken() unavailable (${e.javaClass.simpleName}); FID mode: register() again")
                    register("register() (fallback)")
                }
        } catch (e: Exception) {
            log.log(HaLog.Level.WARN, "getToken() threw ${e.javaClass.simpleName}")
            register("register() (fallback)")
        }
    }

    private fun register(what: String) {
        val messaging = messaging() ?: return
        try {
            messaging.register()
                .addOnSuccessListener { log.log(HaLog.Level.INFO, "$what done; the value arrives via onRegistered") }
                .addOnFailureListener { e ->
                    log.log(HaLog.Level.WARN, "$what failed: ${e.javaClass.simpleName}")
                    onFailed()
                }
        } catch (e: Exception) {
            log.log(HaLog.Level.WARN, "$what threw ${e.javaClass.simpleName}")
            onFailed()
        }
    }

    /** Null (and [onFailed]) when no default FirebaseApp runs: `getInstance()` would throw. */
    private fun messaging(): FirebaseMessaging? = try {
        FirebaseMessaging.getInstance()
    } catch (e: Exception) {
        log.log(HaLog.Level.WARN, "FirebaseMessaging unavailable: ${e.javaClass.simpleName}")
        onFailed()
        null
    }
}
