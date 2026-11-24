package com.meda.portico.store

import android.content.SharedPreferences
import com.meda.portico.core.ha.Door
import com.meda.portico.core.ha.DoorCodec
import com.meda.portico.core.ha.DoorsCacheStore
import com.meda.portico.core.auth.AccessTokens
import com.meda.portico.core.ha.HaConnectionConfig
import com.meda.portico.core.reg.RegKind
import com.meda.portico.core.reg.RegValue
import com.meda.portico.core.reg.RegistrationStore

/**
 * Android implementations of PR-D's persistence ports, on one plain prefs file ([FILE]).
 *
 * Plain, not the encrypted [Settings] file, for the same reasons as [PrefsCallStore]: these are
 * read on the push hot path in a possibly cold process, and they hold nothing that sends a push
 * on its own. The doors cache is opaque ids, driver names and door names (contract 3's `portico/doors`
 * carries no secrets, and [Door] has no field for any other key it may add). The cached FID/token is what Firebase itself
 * keeps in its own plain prefs; it is useless without the installer's service account, and it
 * is never logged ([RegValue.toString] is `sha256[:8]`). Every write is a synchronous `commit()`.
 */
object PrefsStateFile {
    const val FILE = "portico_state"
}

/**
 * The persisted `door_id -> door_ref` cache (P2 plan §3.3: a cold push must still know the doors).
 *
 * Contract 3 renamed the key: contract 2's `devices_v1` held `entry_id`s, which push v2's `door_ref`s are not, so
 * it is never read; the first [load] removes it (an old cache is discarded, not migrated). The server's first
 * `portico/doors` after the update fills the new one on the first connect.
 */
class PrefsDoorsCacheStore(private val prefs: SharedPreferences) : DoorsCacheStore {
    override fun load(): List<Door> {
        if (prefs.contains(OLD_KEY)) prefs.edit().remove(OLD_KEY).commit()
        return DoorCodec.decode(prefs.getString(KEY, null))
    }

    override fun save(doors: List<Door>) {
        prefs.edit().putString(KEY, DoorCodec.encode(doors)).commit()
    }

    companion object {
        const val KEY = "doors_v3"

        /** Contract 2's key (`device_id` and `entry_id` pairs); dropped on first read. */
        const val OLD_KEY = "devices_v1"
    }
}

/**
 * D's [RegistrationStore]: the last FID/token and the pending-unregister flag.
 *
 * The value is scoped to the Firebase project it was issued under ([runningProjectId], the
 * project the default FirebaseApp runs with): an FID from another project is useless to the new
 * one, so after a project change (applied at a restart, P2 plan §3.1) the old value reads as
 * absent and the machine asks Firebase for a fresh one instead of registering a stale value.
 */
class PrefsRegistrationStore(
    private val prefs: SharedPreferences,
    private val runningProjectId: () -> String?,
) : RegistrationStore {

    override fun loadValue(): RegValue? {
        val project = runningProjectId() ?: return null
        if (prefs.getString(K_PROJECT, null) != project) return null
        val value = prefs.getString(K_VALUE, null) ?: return null
        val kind = when (prefs.getString(K_KIND, null)) {
            RegKind.FID.wire -> RegKind.FID
            RegKind.TOKEN.wire -> RegKind.TOKEN
            else -> return null
        }
        return RegValue(kind, value)
    }

    override fun saveValue(value: RegValue?) {
        val e = prefs.edit()
        val project = runningProjectId()
        if (value == null || project == null) {
            e.remove(K_KIND).remove(K_VALUE).remove(K_PROJECT)
        } else {
            e.putString(K_KIND, value.kind.wire).putString(K_VALUE, value.value).putString(K_PROJECT, project)
        }
        e.commit()
    }

    override fun loadPendingUnregister(): Boolean = prefs.getBoolean(K_PENDING_UNREGISTER, false)

    override fun savePendingUnregister(pending: Boolean) {
        prefs.edit().putBoolean(K_PENDING_UNREGISTER, pending).commit()
    }

    override fun toString(): String = "PrefsRegistrationStore(value=${loadValue()})"

    companion object {
        const val K_KIND = "reg_kind"
        const val K_VALUE = "reg_value"
        const val K_PROJECT = "reg_project"
        const val K_PENDING_UNREGISTER = "reg_pending_unregister"
    }
}

/**
 * D's [HaConnectionConfig] from PR-C's [Settings], read on every connect so a Setup save takes
 * effect on the next one. Never renders the token.
 */
class SettingsConnectionConfig(
    /** DEV-544: the refresh-token login, used only while Settings hold a refresh token. */
    private val tokens: AccessTokens? = null,
    private val settings: () -> Settings?,
) : HaConnectionConfig {
    override val haUrl: String? get() = settings()?.haUrl
    override val accessToken: String? get() = settings()?.haToken

    /** DEV-544: non-null only while a refresh token is stored; the old long-lived token path never sees it. */
    override val login: AccessTokens? get() = if (settings()?.let { it.refreshToken != null && it.clientId != null } == true) tokens else null
    override fun toString(): String =
        "SettingsConnectionConfig(haUrl=${if (haUrl != null) "set" else "none"}, token=${if (accessToken.isNullOrBlank()) "absent" else "present"}, login=${if (login != null) "refresh" else "none"})"
}
