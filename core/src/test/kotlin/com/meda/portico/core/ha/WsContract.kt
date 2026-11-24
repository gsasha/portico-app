package com.meda.portico.core.ha

import com.meda.portico.core.Hex
import com.meda.portico.core.push.MiniJson
import java.io.File
import java.security.MessageDigest

/**
 * The vendored contract-3 fixtures: byte copies of `portico-ha`'s `contract/` files, recorded from the integration's
 * real handlers (M6 E3, branch `m6/e3-contract-3`, head `fa64e8b`). Read with the test-side [MiniJson]; no JSON
 * dependency. See docs/reference/websocket-contract.md for how to re-vendor them and update the pins.
 *
 * - `ws_v3.json`: one case per request, with every frame HA sent back (380 cases);
 * - `ws_media_v2.json`: the away-media exchange ([WsMediaContractTest]);
 * - `app_commands_v3.txt`: the commands the app sends, one a line, sorted ([WsContractTest]);
 * - `push_v2.json`: the push messages (`ContractTest`).
 *
 * Message ids: the file's `_meta` says they are "per connection, as recorded", so they are NOT part of the contract. A
 * replay compares a request with its `id` removed and answers with the file's frames renumbered to the id the app
 * actually used ([Case.receiveFor]).
 */
object WsContract {
    /**
     * DEV-807 slice 2: re-vendored from portico-ha main after PR #357 (`9f719d4`; 419 cases: the 380 below plus the panel's `wall heartbeat` cases (5), `phones make_panel_screen` (8),
     * and the server-only `camera webrtc_offer`, `elements`, `pages migrate`, `phones set` and `ui settings set` cases that landed on main meanwhile; a few recorded
     * replies changed (doors, phones revoke, connlog get/clear, content preview). `app_commands_v3.txt` gains `portico/wall/heartbeat`; `ws_media_v2.json` and
     * `push_v2.json` are byte-identical to main's.
     * Before that, DEV-762 (A1): re-vendored from portico-ha main after PR #197 (H1; 380 cases: the 363 below plus 17 `connlog diag ...` cases: ring-decision
     * Journal lines accepted, the `crash` and `exit` items, and the administrator's `get` kinds `diag` / `crash` / `exit`; the invalid-`kind` message
     * of `get` changed; `app_commands_v3.txt` is unchanged). The app sends no `crash` / `exit` item yet (A2 to A4).
     * Before that, sha256 `95f2e3e1...2d8` of `ws_v3.json` as vendored from portico-ha main after PR #190 (contract 3; 363 cases: the 341 below plus 22 `connlog journal ...`
     * cases, DEV-713 / DEV-705: the `kind: journal` item of `portico/connlog/report` carrying the app's `live` and `audio` Journal lines, and
     * the administrator's `get` filters and `clear`; `app_commands_v3.txt` is unchanged, `portico/connlog/report` was already in it).
     * Before that, PR #189 (341 cases: the 333 the app replayed before,
     * plus 8 of DEV-713's S2 away-from-home video on the ring and in-call screens: the CALLER's cameras in `content/get` ->
     * `callers.<id>.cameras[]` now carry the same additive `live_rtsp` object ({main, sub?}) as a page camera, and still never a `live` key;
     * three of the eight are the admin's `ui_settings` which the app never sends). `app_commands_v3.txt` is unchanged (no new command);
     * `ws_media_v2.json` and `push_v2.json` are byte-identical to main's.
     */
    // DEV-836: the estate-specific values in the vendored copies (addresses, device and person names) were replaced with
    // generic ones before publication, so ws_v3.json, ws_media_v2.json and push_v2.json are no longer byte-identical to the
    // server repo copies (same cases, same shapes); the pins below are for these copies.
    const val PINNED_SHA256 = "9baf8c307c0181520acd579b20fbfc887e65c2d9ec5c23f35892d4df6b8c0d99"
    const val EXPECTED_CASES = 424
    const val RESOURCE = "/ws_v3.json"

    /** sha256 of `ws_media_v2.json` (the away-media exchange, contract 3), same source. */
    const val MEDIA_PINNED_SHA256 = "c3549abd75df1480e078bd29f59e4c0a44844fab603c9552ef112f9db7d884f1"
    const val MEDIA_RESOURCE = "/ws_media_v2.json"

    /** sha256 of `app_commands_v3.txt` (the commands the app sends), portico-ha PR #187 (the live commands; see [NOT_YET_SENT]). */
    const val COMMANDS_PINNED_SHA256 = "66a0bf014b1aa91e8622fb299335552aa7066d6cc193753ea28683157d4a4ab2"
    const val COMMANDS_RESOURCE = "/app_commands_v3.txt"

    class Case(
        val name: String,
        val caller: String,
        val send: Map<String, Any?>,
        val receive: List<Map<String, Any?>>,
        val auth: List<String> = emptyList(),
    ) {
        val type: String get() = send["type"] as String

        /** The request as the app must send it, without its connection-local `id`. */
        val request: Map<String, Any?> get() = send - "id"

        /** Every frame HA sent back, renumbered to [id], as wire text. */
        fun receiveFor(id: Int): List<String> = receive.map { encode(it + ("id" to id.toLong())) }

        /** The request's string field [key]. */
        fun str(key: String): String = send[key] as String

        override fun toString() = name
    }

    fun resourceBytes(resource: String): ByteArray =
        requireNotNull(WsContract::class.java.getResourceAsStream(resource)) { "$resource not on the test classpath" }
            .use { it.readBytes() }

    fun sha256Of(bytes: ByteArray): String = Hex.encode(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun bytes(): ByteArray = resourceBytes(RESOURCE)

    fun sha256(): String = sha256Of(bytes())

    @Suppress("UNCHECKED_CAST")
    fun root(): Map<String, Any?> = MiniJson.parse(String(bytes(), Charsets.UTF_8)) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val cases: List<Case> by lazy {
        (root()["cases"] as List<Map<String, Any?>>).map {
            Case(it["name"] as String, it["as"] as String, it["send"] as Map<String, Any?>, it["receive"] as List<Map<String, Any?>>, it["auth"] as List<String>)
        }
    }

    fun case(name: String): Case = cases.firstOrNull { it.name == name } ?: error("no case '$name' in ws_v3.json")

    /** [name]'s recorded server frames, renumbered to [id]: for tests that script a connection. */
    fun frames(name: String, id: Int): List<String> = case(name).receiveFor(id)

    /** The single result frame of [name], renumbered to [id]. */
    fun reply(name: String, id: Int): String = frames(name, id).first()

    /** The commands the app sends (`app_commands_v3.txt`), in the file's order. */
    val appCommands: List<String> by lazy {
        String(resourceBytes(COMMANDS_RESOURCE), Charsets.UTF_8).lines().filter { it.isNotBlank() }
    }

    /**
     * Commands the vendored list says the app MAY send that no code of the app builds yet. Empty since DEV-713 stage S1 built the three
     * live commands (`LiveCommands`): the source scan and the client-call check now require every command of the list in the sources,
     * and the other way round. A later stage that vendors commands before building them lists them here again.
     */
    val NOT_YET_SENT: Set<String> = emptySet()

    // ---- the phone's side: the app's own configuration in these tests ------------------
    // Literals on purpose, NOT read from the file: the replay checks that the request the app
    // builds from them equals the file's request, so a drifted file field turns it red.

    const val INSTALLATION = "inst-0f1e2d3c4b5a"
    const val PROJECT = "portico-test"

    /** The version the file's accepted register carries. The shipped app's own is not bumped until A3 (0.5.0-alpha). */
    const val APP_VERSION = "0.5.0-alpha"
    const val REGISTRATION = "FAKE-fcm-registration-0001"
    const val REGISTRATION_ROTATED = "FAKE-fcm-registration-0002"

    /** What HA answers for the file's first registration (the file's deterministic counter). */
    const val REG_ID = "00000000000000000000000000000001"

    /** The doors as the file names them, as the app must cache them. */
    const val GATE = "http://192.168.1.7:8091"
    const val GATE_REF = "d-355c9550e870bcbabb4daac53cb4320f"
    const val GATE_ENDPOINT = "fake:http://192.168.1.7:8091"
    const val FRONT = "vto-front.example"
    const val FRONT_REF = "d-34e65cd09db1499f5c992b6b77346362"
    const val FRONT_ENDPOINT = "fake:vto-front.example"
    val DOORS = listOf(
        Door(GATE, GATE_REF, GATE_ENDPOINT, "gate_sip_observer", "Vehicle gate"),
        Door(FRONT, FRONT_REF, FRONT_ENDPOINT, "fake", "Front door"),
    )

    /** The enrolled phone's endpoint, and the call id of the subscribe case. */
    const val PHONE_ENDPOINT = "ep-alice"
    const val FRONT_CALL = "front-call-1"

    /** The enrolled phone's `portico/version` and `portico/doors` results: what every replayed connect is answered with. */
    val VERSION_PHONE get() = case("version: phone")
    val DOORS_PHONE get() = case("doors: enrolled phone")

    // ---- HA core's auth handshake: NOT in the contract (it starts after auth_ok) ----------

    /** A fake HA long-lived access token (only the no-leak tests care what it looks like). */
    const val HA_TOKEN = "fake-jwt-header.ha-long-lived-access-token.SIGNATURE-0123456789"
    const val AUTH_REQUIRED = """{"type":"auth_required","ha_version":"2026.10.0"}"""
    const val AUTH_OK = """{"type":"auth_ok","ha_version":"2026.10.0"}"""
    const val AUTH_INVALID = """{"type":"auth_invalid","message":"Invalid access token or password"}"""

    // ---- the connect sequence's command ids (the app numbers its commands 1, 2, 3 ... per connection) ----

    /** `portico/version`, then `portico/doors`, then `portico/push/register`, the two subscriptions and `portico/content/get`. */
    const val ID_VERSION = 1
    const val ID_DOORS = 2
    const val ID_REGISTER = 3
    const val ID_CALLS = 4
    const val ID_CONTENT_SUBSCRIBE = 5
    const val ID_CONTENT_GET = 6

    /** The two replies that open a connection, as wire text: `version` (id 1) then `doors` (id 2). */
    fun handshakeReplies(): List<String> = listOf(reply("version: phone", ID_VERSION), reply("doors: enrolled phone", ID_DOORS))

    // ---- a tiny encoder for MiniJson's tree (Map / List / String / Long / Boolean / null) --

    fun encode(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> {
                sb.append('"')
                for (c in v) when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c < ' ' -> sb.append("\\u%04x".format(c.code))
                    else -> sb.append(c)
                }
                sb.append('"')
            }
            is Long, is Int, is Boolean -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                v.entries.forEachIndexed { i, (k, x) ->
                    if (i > 0) sb.append(',')
                    write(sb, k as String)
                    sb.append(':')
                    write(sb, x)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x ->
                    if (i > 0) sb.append(',')
                    write(sb, x)
                }
                sb.append(']')
            }
            else -> error("cannot encode ${v::class}")
        }
    }

    /**
     * The repository root, found from the working directory (`core/` under Gradle): for the one test that reads the app's
     * own sources to learn which commands it sends.
     */
    fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "core").isDirectory) return dir
            dir = dir.parentFile
        }
        error("the repository root (settings.gradle.kts) is not above ${System.getProperty("user.dir")}")
    }
}
