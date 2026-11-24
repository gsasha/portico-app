package com.meda.portico.tailnet

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.meda.portico.core.ha.HaLog
import com.meda.portico.core.ha.HaLog.Level
import com.meda.portico.core.tailnet.TailnetEngine
import com.meda.portico.gomobile.tailnet.Tailnet
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [TailnetEngine] over `gocore.aar` (DEV-557). Only compiled when the library is present.
 *
 * The intercom app's hazards, each handled here (memory notes of the same names):
 * - `gomobile-load-must-be-off-main-thread`: the library loads on its own thread, `go.Seq.setContext`
 *   FIRST, and nothing calls into Go before [goReady]; the network callback checks it too.
 *   Nothing here is ever called from the UI thread (TailnetController's contract).
 * - `tsnet-on-android-needs-ts-logs-dir`: `Tailnet.prepare` creates TS_LOGS_DIR/TMPDIR/HOME under
 *   [baseDir] before any join (Go refuses to join without it rather than panicking).
 * - `go-net-interfaces-blocked-android11`: the interface table goes to Go from [TailnetInterfaces],
 *   before every join and on every network change.
 * - `gomobile-panic-read-own-logcat`: Go contains panics at its exported functions; a panic on
 *   one of tsnet's own goroutines still kills the process (GoLog in logcat).
 *
 * The node's identity lives in `noBackupFilesDir/tailnet/state` (app-private, never backed up,
 * gone with an uninstall).
 */
class GoTailnetEngine(private val app: Context, private val log: HaLog) : TailnetEngine {

    override val built = true

    private val baseDir = File(app.noBackupFilesDir, "tailnet")
    private val stateDir = File(baseDir, "state")

    private val loadStarted = AtomicBoolean(false)
    private val loadDone = CountDownLatch(1)

    @Volatile
    private var goReady = false

    @Volatile
    private var loadError: String? = null

    override val started: Boolean get() = loadStarted.get()

    override fun beginLoad() {
        if (loadStarted.compareAndSet(false, true)) {
            Thread({ load() }, "portico-tailnet-load").apply { isDaemon = true }.start()
        }
    }

    /** The latch has reached zero: the load finished, successfully or not ([ready] then answers at once). */
    override val loaded: Boolean get() = loadDone.count == 0L

    override fun ready(): String? {
        beginLoad()
        if (!loadDone.await(LOAD_WAIT_S, TimeUnit.SECONDS)) return "the tailnet library is still loading"
        return loadError
    }

    private fun load() {
        try {
            go.Seq.setContext(app) // ⚠️ FIRST, before any other Go call
            Tailnet.touch()
            baseDir.mkdirs()
            Tailnet.prepare(baseDir.absolutePath)
            goReady = true
            pushNetwork()
            watchNetwork()
            log.log(Level.INFO, "tailnet: library loaded")
        } catch (t: Throwable) {
            loadError = t.javaClass.simpleName + (t.message?.let { ": " + it.take(200) } ?: "")
            log.log(Level.ERROR, "tailnet: library failed to load: ${t.javaClass.simpleName}")
        } finally {
            loadDone.countDown()
        }
    }

    override fun join(authKey: String?, hostname: String) {
        ready()?.let { throw IllegalStateException(it) }
        pushNetwork() // the freshest table and network, right before dialling
        stateDir.mkdirs()
        Tailnet.join(authKey ?: "", hostname, stateDir.absolutePath, JOIN_TIMEOUT_S)
    }

    @Volatile
    private var lastStatus = ""

    override fun status(): String = if (goReady) Tailnet.status().also { lastStatus = it } else "idle"

    /** Blocks in Go until the status line differs from the last one [status] returned, or [timeoutMs] (DEV-694). */
    override fun awaitStatusChange(timeoutMs: Long): Boolean {
        if (!goReady) return false
        lastStatus = Tailnet.waitChange(lastStatus, timeoutMs)
        return true
    }

    override fun joinPhases(): String = if (goReady) Tailnet.joinPhases() else ""

    override fun startForwarder(target: String): String {
        check(goReady) { "the tailnet library is not loaded" }
        return Tailnet.startForwarder(target)
    }

    override fun stop() {
        if (goReady) Tailnet.stop()
    }

    override fun routeCheck(target: String): String = if (goReady) Tailnet.routeCheck(target) else "unknown"

    override fun forwarderStatus(): String = if (goReady) Tailnet.forwarderStatus() else "none"

    override fun hasIdentity(): Boolean = File(stateDir, "tailscaled.state").length() > 0

    override fun wipeIdentity() {
        stateDir.deleteRecursively()
    }

    // ---- network: the interface table and the socket-marking handle --------------------------

    private var lastNetworkSignature: String? = null
    private var lastTable: String? = null
    private var lastHandle = 0L
    private val confirming = AtomicBoolean(false)
    private val confirmer by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "iface-confirm").apply { isDaemon = true }
        }
    }

    /** The default network's interface and its 464XLAT `v4-` translator; empty when undeterminable (nothing is restricted). */
    private fun defaultInterfaces(): Set<String> = try {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val name = cm?.getLinkProperties(cm.activeNetwork)?.interfaceName
        if (name == null) emptySet() else setOf(name, "v4-$name")
    } catch (_: Throwable) {
        emptySet()
    }

    private fun pushNetwork(confirmed: Boolean = false) {
        if (!goReady) return // ⚠️ the intercom app's 0.12.0 black screen, in one line
        var table = ""
        var handle = 0L
        try {
            // A-08: tsnet is told about the default network only (the intercom app's 0.34.0).
            table = TailnetInterfaces.restrict(TailnetInterfaces.table(), defaultInterfaces())
        } catch (t: Throwable) {
            log.log(Level.WARN, "tailnet: interface table failed: ${t.javaClass.simpleName}")
        }
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            handle = cm?.activeNetwork?.networkHandle ?: 0L
        } catch (t: Throwable) {
            log.log(Level.WARN, "tailnet: network handle failed: ${t.javaClass.simpleName}")
        }
        // A-08 (0.33.8): an interface that vanishes on the SAME default network waits 2.5 s to be confirmed, then the answer
        // stands either way. A different default network (a real switch, DEV-705) is acted on at once.
        if (!confirmed && handle == lastHandle && TailnetInterfaces.vanished(lastTable, table) && confirming.compareAndSet(false, true)) {
            confirmer.schedule({
                confirming.set(false)
                pushNetwork(confirmed = true)
            }, VANISH_CONFIRM_MS, TimeUnit.MILLISECONDS)
            return
        }
        try {
            if (table.isNotEmpty()) Tailnet.setInterfaces(table)
            Tailnet.setNetwork(handle)
        } catch (t: Throwable) {
            log.log(Level.WARN, "tailnet: network push failed: ${t.javaClass.simpleName}")
        }
        // DEV-705: tell the node NOW. Its network monitor re-reads the table on its own schedule, and a node that kept its old
        // sockets after Wi-Fi to mobile data sent into the void for 20 to 55 s. Only when the network MEANT a change (A-08, 0.33.7:
        // the signature ignores a rotating IPv6 privacy address): signal-strength callbacks arrive here too, and every nudge is a rebind.
        val signature = "$handle|${TailnetInterfaces.signature(table)}"
        val changed = synchronized(this) {
            val c = signature != lastNetworkSignature
            lastNetworkSignature = signature
            lastTable = table
            lastHandle = handle
            c
        }
        if (changed) {
            try {
                Tailnet.linkChanged()
            } catch (t: Throwable) {
                log.log(Level.WARN, "tailnet: link change failed: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun watchNetwork() {
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = pushNetwork()
                    override fun onLost(network: Network) = pushNetwork()
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = pushNetwork()
                    override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = pushNetwork()
                },
            )
        } catch (t: Throwable) {
            log.log(Level.WARN, "tailnet: network callback failed: ${t.javaClass.simpleName}")
        }
    }

    companion object {
        private const val LOAD_WAIT_S = 30L
        private const val VANISH_CONFIRM_MS = 2_500L

        /** Go's join deadline. A user-owned node waiting for approval shows it meanwhile. */
        private const val JOIN_TIMEOUT_S = 90L
    }
}
