package com.meda.portico.fcm

import com.meda.portico.PorticoApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Portico's FCM endpoint (spec §4.3, P2 plan §3.2-§3.3). A thin shell: every decision is in
 * [MessagingServiceLogic] / [PushReceiver] (JVM-tested) and `:core`.
 *
 * - [onCreate]: [FirebaseBoot.ensure]. The OS can start this service for a push with no
 *   Activity ever run (the old app's `FcmService` lesson); `Application.onCreate` has run first
 *   in any case, so this is the idempotent second call the plan asks for.
 * - [onRegistered] (FID mode, firebase-messaging 25.1.x) and [onNewToken] (token mode; never
 *   fires in FID mode): the value goes to the registration machine on the link's thread, which
 *   sends `portico/push/register` when connected, or keeps it for the next connect.
 * - [onMessageReceived]: a data-only message -> the receive path, on FCM's worker thread. The
 *   call record is committed before the outcome handler runs.
 */
class PorticoMessagingService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        val graph = PorticoApp.graph(this)
        FirebaseBoot.ensure(this) { graph.settings() }
    }

    override fun onRegistered(installationId: String) {
        PorticoApp.graph(this).messaging.onRegistered(installationId)
    }

    @Deprecated("Token mode only; Portico registers by FID (firebase_messaging_installation_id_enabled).")
    override fun onNewToken(token: String) {
        PorticoApp.graph(this).messaging.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        PorticoApp.graph(this).messaging.onMessage(
            message.data,
            System.currentTimeMillis(),
            message.priority,
            message.originalPriority,
        )
    }
}
