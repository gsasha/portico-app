package com.meda.portico.fcm

import android.content.Context
import com.meda.portico.core.enroll.FirebaseConfig
import com.meda.portico.core.enroll.FirebaseConfigResult
import com.meda.portico.core.fcm.BootResult
import com.meda.portico.core.fcm.FirebaseBootCore
import com.meda.portico.core.fcm.FirebaseInitializer
import com.meda.portico.store.Settings
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Runtime Firebase (spec §5.3, P2 plan §3.1): builds `FirebaseOptions` from the four values in
 * [Settings] (PR-C's `FirebaseConfig`) and initialises the DEFAULT FirebaseApp once per process.
 * There is no `google-services.json`, and `FirebaseInitProvider` is removed from the manifest.
 *
 * [ensure] is idempotent, synchronized (in [FirebaseBootCore]) and never throws. Call it from
 * `Application.onCreate`, the messaging service's `onCreate` and after a Setup save. All of its
 * decisions live in `:core` ([FirebaseBootCore], JVM-tested with a fake initialiser); this file
 * is only the SDK calls. It logs nothing; the result's [BootResult.statusLine] carries the
 * project id at most, never the api key.
 *
 * Changed options in a running process: kept as [BootResult.Ready] with `restartToApply`; see
 * [BootResult.Ready].
 */
object FirebaseBoot {

    private class Sdk(private val context: Context) : FirebaseInitializer {
        override fun hasDefaultApp(): Boolean =
            FirebaseApp.getApps(context).any { it.name == FirebaseApp.DEFAULT_APP_NAME }

        override fun running(): FirebaseConfig? {
            val o = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }?.options ?: return null
            val project = o.projectId ?: return null
            val sender = o.gcmSenderId ?: return null
            return (FirebaseConfig.create(project, sender, o.applicationId, o.apiKey) as? FirebaseConfigResult.Ok)?.config
        }

        override fun initialize(config: FirebaseConfig) {
            val options = FirebaseOptions.Builder()
                .setProjectId(config.projectId)
                .setGcmSenderId(config.senderId)
                .setApplicationId(config.appId)
                .setApiKey(config.apiKey)
                .build()
            FirebaseApp.initializeApp(context, options)
        }
    }

    @Volatile
    private var core: FirebaseBootCore? = null

    @Volatile
    var last: BootResult = BootResult.NotConfigured
        private set

    /** [settings] is the process's one shared Settings (`AppGraph.settings`): ensure never opens a second instance (APP-L5). */
    @Synchronized
    fun ensure(context: Context, settings: () -> Settings?): BootResult {
        val app = context.applicationContext
        val c = core ?: FirebaseBootCore(Sdk(app)).also { core = it }
        val stored = try {
            settings()?.firebaseConfig
        } catch (_: Exception) {
            null
        }
        return c.ensure(stored).also { last = it }
    }
}
