@file:Suppress("DEPRECATION") // security-crypto 1.1.0 deprecates its whole API; see the class doc

package com.meda.portico.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.meda.portico.core.auth.CompleteResult
import com.meda.portico.core.auth.EnrollContract
import com.meda.portico.core.auth.LoginStore
import com.meda.portico.core.auth.PendingCodec
import com.meda.portico.core.auth.PendingEnrollment
import com.meda.portico.core.auth.PendingStore
import com.meda.portico.core.enroll.FirebaseConfig
import com.meda.portico.core.enroll.FirebaseConfigResult
import com.meda.portico.core.enroll.SettingsKeys
import com.meda.portico.core.enroll.SettingsSnapshot
import com.meda.portico.core.enroll.SetupResult
import com.meda.portico.core.netpolicy.ConnectionMode
import com.meda.portico.core.netpolicy.NetClass
import com.meda.portico.core.netpolicy.NetworkChoices
import com.meda.portico.core.netpolicy.NetworkPolicy
import com.meda.portico.core.netpolicy.VideoMode
import com.meda.portico.core.tailnet.PendingKey
import com.meda.portico.core.tailnet.PendingKeyStore
import java.security.SecureRandom
import java.util.UUID

/**
 * The app's settings, all in one EncryptedSharedPreferences file (P2 plan §3, §3.2):
 * `ha_url`, `ha_token`, `phone_name`, the optional Firebase config (four values), the local
 * "ring for door calls on this phone" switch (default ON, plan §7 Q2), `installation_id` and the
 * enrollment's optional `enroll_id` (offline registration).
 *
 * Kept thin on purpose: the keys, what a save writes and the status text live in `:core`
 * (`SettingsKeys`, `SetupResult.Ok.writes`, `SettingsSnapshot`) where the JVM tests prove them.
 *
 * DEV-544: a phone enrolled by a one-time code stores `refresh_token` and `client_id` instead of `ha_token`; a scanned
 * code waits as a pending record (`pending_*`, encrypted) until it has been redeemed. See `:core` `auth/`.
 *
 * Rules:
 * - Nothing here logs. The token never leaves this file except through [haToken].
 * - If the encrypted store cannot be opened, [open] returns null. There is no plaintext
 *   fallback: the caller shows an error and saves nothing.
 * - DEV-557: the enrollment's `ha_dial` and its single-use `tailscale_authkey` (with its save
 *   time) are stored by [apply]; the key is read only by [pendingKeys] and deleted once the
 *   tailnet node is up, refused, or 55 minutes old (`:core` `TailnetController`).
 * - `allowBackup="false"` (manifest), so a reinstall starts empty and mints a new
 *   installation id; the server ages the old registration row out (plan §3.2).
 *
 * androidx.security:security-crypto 1.1.0 marks these classes deprecated (Google now points
 * to the Keystore / Tink directly). They still work and are the version the plan names; a
 * migration is a separate decision.
 */
class Settings internal constructor(private val prefs: SharedPreferences) {

    val haUrl: String? get() = prefs.getString(SettingsKeys.HA_URL, null)

    /** The HA long-lived token. Never log it, show it, or put it in an exception. */
    val haToken: String? get() = prefs.getString(SettingsKeys.HA_TOKEN, null)

    /** DEV-544: the refresh token of a one-time-code enrolment. Never log it, show it, or put it in an exception. */
    val refreshToken: String? get() = prefs.getString(SettingsKeys.REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** DEV-544: the client id the refresh token was issued with (HA's `/auth/token` needs it). Never log it. */
    val clientId: String? get() = prefs.getString(SettingsKeys.CLIENT_ID, null)?.takeIf { it.isNotBlank() }

    /** DEV-544: an HA address and a way to log in: the old long-lived token or a refresh token. */
    val enrolled: Boolean get() = !haUrl.isNullOrBlank() && (!haToken.isNullOrBlank() || (refreshToken != null && clientId != null))

    /** DEV-544: the stored login, for `AccessTokens`; its operations are this store's. */
    val loginStore: LoginStore = object : LoginStore {
        override fun refreshToken(): String? = this@Settings.refreshToken
        override fun clientId(): String? = this@Settings.clientId
        override fun signOut() {
            // One commit: forget the login, remember why. The long-lived token is already gone (the redemption removed it).
            val e = prefs.edit()
            for (k in PendingCodec.signOutRemovals) e.remove(k)
            for ((k, v) in PendingCodec.signOutWrites) e.putString(k, v)
            e.commit()
        }

        override fun toString() = "LoginStore(settings)"
    }

    /** DEV-544: the scanned one-time code waiting to be redeemed, and how it is saved and dropped. */
    val pendingStore: PendingStore = object : PendingStore {
        override fun load(): PendingEnrollment? = PendingCodec.read { prefs.getString(it, null) }

        override fun complete(nonce: String, refreshToken: String, clientId: String): CompleteResult = synchronized(LOCK) {
            // Only the record this redemption started with may be completed: a newer scan replaced it.
            if (PendingCodec.read { prefs.getString(it, null) }?.nonce != nonce) return CompleteResult.SUPERSEDED
            val e = prefs.edit()
            for (k in PendingCodec.completionRemovals) e.remove(k)
            for ((k, v) in PendingCodec.completionWrites(refreshToken, clientId)) e.putString(k, v)
            if (e.commit()) CompleteResult.SAVED else CompleteResult.FAILED
        }

        override fun clear(nonce: String) {
            synchronized(LOCK) {
                if (PendingCodec.read { prefs.getString(it, null) }?.nonce != nonce) return
                val e = prefs.edit()
                for (k in PendingCodec.keys) e.remove(k)
                e.commit()
            }
        }

        override fun toString() = "PendingStore(settings)"
    }

    val phoneName: String? get() = prefs.getString(SettingsKeys.PHONE_NAME, null)

    /** Offline registration: HA's `enroll_id` from the last enrollment, null for an older QR. Never log it. */
    val enrollId: String? get() = prefs.getString(SettingsKeys.ENROLL_ID, null)?.takeIf { it.isNotBlank() }

    /** DEV-557: the enrollment's validated `ha_dial` (`host:port`), null when none. */
    val haDial: String? get() = prefs.getString(SettingsKeys.HA_DIAL, null)?.takeIf { it.isNotBlank() }

    /** DEV-557: the enrollment's unredeemed Tailscale key. Never log it, show it, or put it in an exception. */
    val pendingKeys: PendingKeyStore = object : PendingKeyStore {
        override fun load(): PendingKey? {
            val k = prefs.getString(SettingsKeys.TAILNET_KEY, null)?.takeIf { it.isNotBlank() } ?: return null
            // An unreadable time makes the key expire at once (0 is long ago): never keep it by accident.
            val at = prefs.getString(SettingsKeys.TAILNET_KEY_SAVED_AT, null)?.toLongOrNull() ?: 0L
            return PendingKey(k, at)
        }

        override fun clear() {
            prefs.edit().remove(SettingsKeys.TAILNET_KEY).remove(SettingsKeys.TAILNET_KEY_SAVED_AT).commit()
        }

        override fun toString() = "PendingKeyStore(settings)"
    }

    /** The stored Firebase config, or null when none (or an unusable one) is stored. */
    val firebaseConfig: FirebaseConfig?
        get() {
            val v = listOf(
                SettingsKeys.FCM_PROJECT_ID, SettingsKeys.FCM_SENDER_ID, SettingsKeys.FCM_APP_ID, SettingsKeys.FCM_API_KEY,
            ).map { prefs.getString(it, null) ?: return null }
            return (FirebaseConfig.create(v[0], v[1], v[2], v[3]) as? FirebaseConfigResult.Ok)?.config
        }

    /** The local "ring for door calls on this phone" switch (spec §3.4); default ON. */
    var ringOnThisPhone: Boolean
        get() = prefs.getBoolean(SettingsKeys.RING_ON_THIS_PHONE, SettingsKeys.RING_ON_THIS_PHONE_DEFAULT)
        set(value) {
            prefs.edit().putBoolean(SettingsKeys.RING_ON_THIS_PHONE, value).commit()
        }

    /**
     * DEV-807 (D-20261121-73ff): the server's flag decides whether this device is a panel; the old local "Panel mode" switch is gone.
     * Removes what it left behind and returns whether it had been on (the caller journals it once). [houseReceive] is left as it is.
     */
    fun dropLegacyPanelMode(): Boolean {
        val was = prefs.getBoolean(SettingsKeys.LEGACY_PANEL_MODE, false)
        if (prefs.contains(SettingsKeys.LEGACY_PANEL_MODE)) prefs.edit().remove(SettingsKeys.LEGACY_PANEL_MODE).commit()
        return was
    }

    /** The server made this device a panel: its own house-call switch goes on (design section 5 item 3 R9). */
    fun panelTurnedOnByServer() {
        if (!houseReceive) houseReceive = true
    }

    /** "Receive house calls in Portico" (M7 3b-B, HC-2); default OFF. Only shown in Setup while the server says house calls are on. */
    var houseReceive: Boolean
        get() = prefs.getBoolean(SettingsKeys.HOUSE_RECEIVE, SettingsKeys.HOUSE_RECEIVE_DEFAULT)
        set(value) {
            prefs.edit().putBoolean(SettingsKeys.HOUSE_RECEIVE, value).commit()
        }

    /** Tell the house when Android's Do Not Disturb is on (M7 3b-B, HC-4); default ON. */
    var houseShareDnd: Boolean
        get() = prefs.getBoolean(SettingsKeys.HOUSE_SHARE_DND, SettingsKeys.HOUSE_SHARE_DND_DEFAULT)
        set(value) {
            prefs.edit().putBoolean(SettingsKeys.HOUSE_SHARE_DND, value).commit()
        }

    /**
     * "Stay connected" before 0.6.58 (one switch for every network; default ON, NS-3, 2026-10-15). It is no longer written: it is the
     * starting value of the per-network [connection] rows, so a phone upgrading from 0.6.57 behaves as it did until a row is changed.
     */
    val stayConnected: Boolean
        get() = prefs.getBoolean(SettingsKeys.STAY_CONNECTED, SettingsKeys.STAY_CONNECTED_DEFAULT)

    /**
     * 0.6.58: "Stay connected" or "Push notification" for a network class (Wi-Fi, mobile, roaming). Stored per class; until a row has
     * been chosen it is `:core`'s default made from the 0.6.57 switches ([NetworkPolicy.defaults]).
     */
    fun connection(c: NetClass): ConnectionMode =
        ConnectionMode.of(prefs.getString(NET_CONNECTION_PREFIX + c.key, null)) ?: NetworkPolicy.defaults(stayConnected, liveVideo).connection(c)

    fun setConnection(c: NetClass, m: ConnectionMode) {
        prefs.edit().putString(NET_CONNECTION_PREFIX + c.key, m.key).commit()
    }

    /** 0.6.58: what the camera tiles may use on a network class (the cap). Default as [connection]'s. */
    fun video(c: NetClass): VideoMode =
        VideoMode.of(prefs.getString(NET_VIDEO_PREFIX + c.key, null)) ?: NetworkPolicy.defaults(stayConnected, liveVideo).video(c)

    fun setVideo(c: NetClass, m: VideoMode) {
        prefs.edit().putString(NET_VIDEO_PREFIX + c.key, m.key).commit()
    }

    /** All six rows as `:core` reads them. */
    fun networkChoices(): NetworkChoices = NetworkChoices(
        connection = NetClass.entries.associateWith { connection(it) },
        video = NetClass.entries.associateWith { video(it) },
    )

    /**
     * "Live video in this app" before 0.6.58 (DEV-713, the owner's decision 3; default ON). No longer written: the starting value of the
     * per-network [video] rows (on: Live video big on Wi-Fi and mobile; off: Stills).
     */
    val liveVideo: Boolean
        get() = prefs.getBoolean("live_video", true)

    /** A random UUID, minted on first use and never regenerated (the server's upsert key). */
    val installationId: String
        get() = synchronized(LOCK) {
            prefs.getString(SettingsKeys.INSTALLATION_ID, null)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also {
                    prefs.edit().putString(SettingsKeys.INSTALLATION_ID, it).commit()
                }
        }

    /**
     * Applies a validated Setup save in one synchronous commit. Writes only the keys
     * [SetupResult.Ok.writes] names and removes only [SetupResult.Ok.removals] (a stale
     * `enroll_id` when the new enrollment has none). Returns false if the commit failed.
     */
    fun apply(result: SetupResult.Ok, nowMs: Long = System.currentTimeMillis(), nonce: String = newNonce()): Boolean {
        // DEV-557: the tailnet part (ha_dial, the pending key and its save time) in the same commit.
        // DEV-544: and a format-2 QR's one-time code with its nonce (nothing for a format-1 payload).
        val writes = result.writes() + result.tailnetWrites(nowMs) + result.pendingWrites(nonce, nowMs)
        val removals = result.removals() + result.tailnetRemovals()
        if (writes.isEmpty() && removals.isEmpty()) return true
        val editor = prefs.edit()
        for (k in removals) editor.remove(k)
        for ((k, v) in writes) editor.putString(k, v)
        return editor.commit()
    }

    /** Token-free status for display. */
    fun snapshot(): SettingsSnapshot = SettingsSnapshot.from(
        get = { prefs.getString(it, null) },
        ringOnThisPhone = ringOnThisPhone,
        installationId = prefs.getString(SettingsKeys.INSTALLATION_ID, null),
    )

    companion object {
        private const val FILE = "portico_settings"
        private const val NET_CONNECTION_PREFIX = "net_conn_"
        private const val NET_VIDEO_PREFIX = "net_video_"
        private val LOCK = Any()
        private val RANDOM = SecureRandom()

        /** A fresh redemption nonce (`EnrollContract.newNonce`, from the system CSPRNG). */
        internal fun newNonce(): String = EnrollContract.newNonce(RANDOM::nextBytes)

        /**
         * Opens the encrypted store, or returns null if the Keystore or the file is unusable
         * (for example a key lost after a device restore). No exception detail is kept: some
         * crypto exceptions quote file content.
         */
        fun open(context: Context): Settings? = try {
            val app = context.applicationContext
            val key = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                app,
                FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            Settings(prefs)
        } catch (_: Exception) {
            null
        }
    }
}
