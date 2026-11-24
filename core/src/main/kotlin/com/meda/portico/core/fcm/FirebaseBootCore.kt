package com.meda.portico.core.fcm

import com.meda.portico.core.enroll.FirebaseConfig
import com.meda.portico.core.reg.RegistrationConfig

/**
 * What booting Firebase from the stored settings gave (P2 plan §3.1). Holds no value that could
 * be secret: a project id is not, and a [Failed.reason] names an exception class, never a message.
 */
sealed interface BootResult {
    /** No (valid) Firebase config is stored: the app never registers for push. */
    data object NotConfigured : BootResult

    /**
     * The default FirebaseApp runs with [projectId]. [restartToApply]: Settings now hold a
     * DIFFERENT config than the one running. A second `initializeApp` for the default app throws,
     * and `FirebaseApp.delete()` + re-init is not proven to reset messaging (plan §3.1), so the new
     * config is applied at the next process start; until then the app keeps registering under the
     * running project (its FID belongs to it).
     */
    data class Ready(val projectId: String, val restartToApply: Boolean = false) : BootResult

    /** `initializeApp` threw, or the default app is not one this code made. Retried on the next ensure. */
    data class Failed(val reason: String) : BootResult

    /** One status line for Setup. */
    fun statusLine(): String = when (this) {
        NotConfigured -> "Push ring notification (Firebase): not configured"
        is Ready -> if (restartToApply) {
            "Push ring notification (Firebase): project $projectId is running; restart Portico to apply the new Firebase config"
        } else {
            "Push ring notification (Firebase): project $projectId"
        }
        is Failed -> "Push ring notification (Firebase): start-up failed ($reason)"
    }

    /** The project the registration may claim: only a running app's ([Ready]). */
    val runningProjectId: String? get() = (this as? Ready)?.projectId

    /** D's registration config for this boot: `fcmProjectId` null (never register) unless [Ready]. */
    fun registrationConfig(installationId: String, appVersion: String): RegistrationConfig =
        RegistrationConfig(installationId = installationId, fcmProjectId = runningProjectId, appVersion = appVersion)
}

/**
 * The Firebase SDK calls [FirebaseBootCore] needs, so the decision is tested without Android.
 * The app's implementation is `fcm/FirebaseBoot.kt` over `FirebaseApp`.
 */
interface FirebaseInitializer {
    /** A `[DEFAULT]` FirebaseApp exists in this process. */
    fun hasDefaultApp(): Boolean

    /** The running default app's four values, or null if there is none or they do not validate. */
    fun running(): FirebaseConfig?

    /** `FirebaseApp.initializeApp(context, options)` for the default app. May throw. */
    fun initialize(config: FirebaseConfig)
}

/**
 * `FirebaseBoot.ensure`'s decision (P2 plan §3.1): idempotent, synchronized, never throws,
 * initialises the default FirebaseApp at most once per process. Called from
 * `Application.onCreate`, the messaging service's `onCreate` (the OS can start it with no
 * Activity) and after a Setup save.
 */
class FirebaseBootCore(private val firebase: FirebaseInitializer) {

    @Synchronized
    fun ensure(stored: FirebaseConfig?): BootResult = try {
        decide(stored)
    } catch (t: Throwable) {
        BootResult.Failed("${t.javaClass.simpleName} while starting Firebase")
    }

    private fun decide(stored: FirebaseConfig?): BootResult {
        if (firebase.hasDefaultApp()) {
            val running = firebase.running()
                ?: return BootResult.Failed("a default FirebaseApp exists with options Portico did not set")
            return BootResult.Ready(running.projectId, restartToApply = stored != null && stored != running)
        }
        if (stored == null) return BootResult.NotConfigured
        return try {
            firebase.initialize(stored)
            BootResult.Ready(stored.projectId)
        } catch (t: Throwable) {
            // Never the message: FirebaseOptions' validation messages can quote a value.
            BootResult.Failed("initializeApp threw ${t.javaClass.simpleName}")
        }
    }
}
