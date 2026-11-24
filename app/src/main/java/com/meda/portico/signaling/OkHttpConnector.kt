package com.meda.portico.signaling

import com.meda.portico.core.ha.Connector
import com.meda.portico.core.ha.LinkFailures
import com.meda.portico.core.ha.ThrowableInfo
import com.meda.portico.core.ha.WsListener
import com.meda.portico.core.ha.WsTransport
import com.meda.portico.core.net.UrlCheck
import com.meda.portico.core.net.UrlPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The direct-URL connector: OkHttp's WebSocket straight to `ha_url` (LAN, or whatever it
 * resolves to). DEV-557's `TailnetConnector` reuses it with a redirected socket factory.
 * No `android.*` imports, so it runs in JVM unit tests against MockWebServer.
 *
 * PR-J: the platform permits cleartext for the whole app (`network_security_config.xml`), so this
 * connector re-checks [UrlPolicy] itself as a backstop to [SignalingClient]'s check: a refused URL
 * throws [RefusedUrlException] (its message names the policy's reason only) and nothing is opened.
 */
class OkHttpConnector(private val client: OkHttpClient = defaultClient()) : Connector {

    override fun open(url: String, listener: WsListener): WsTransport {
        val check = UrlPolicy.checkWebSocket(url)
        if (check is UrlCheck.Refused) throw RefusedUrlException(check.reason.name)
        val closed = AtomicBoolean(false)
        fun closeOnce(reason: String) {
            if (closed.compareAndSet(false, true)) listener.onClosed(reason)
        }
        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    closeOnce("closed by server ($code)")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closeOnce("closed ($code)")

                // The exception's class only: its message can carry the URL or a server response.
                // The messages are read once, by :core's classification table, and never kept.
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!closed.get()) {
                        runCatching { listener.onFailure(LinkFailures.classify(chainOf(t), response?.code)) }
                    }
                    closeOnce("failure: ${t.javaClass.simpleName}" + (response?.let { " (HTTP ${it.code})" } ?: ""))
                }
            },
        )
        return object : WsTransport {
            override fun send(text: String): Boolean = ws.send(text)
            override fun queueSize(): Long = ws.queueSize()
            override fun close(code: Int, reason: String) {
                ws.close(code, reason)
            }
        }
    }

    /** [t] and its causes, outermost first (bounded: a cause cycle cannot loop). */
    private fun chainOf(t: Throwable): List<ThrowableInfo> {
        val out = mutableListOf<ThrowableInfo>()
        var c: Throwable? = t
        while (c != null && out.size < 8) {
            out += ThrowableInfo(c.javaClass.simpleName, c.message)
            c = c.cause?.takeIf { it !== c }
        }
        return out
    }

    /** The connector refused a URL under [UrlPolicy]; [message] is the reason's name, never the URL. */
    class RefusedUrlException(reason: String) : IllegalArgumentException("refused by the network policy: $reason")

    companion object {
        /** Pings every 30 s so a dead path (Wi-Fi gone, HA restarted) is noticed and reconnected. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
