package com.meda.portico.tailnet

import com.meda.portico.core.auth.HttpPost
import com.meda.portico.core.auth.HttpReply
import com.meda.portico.core.ha.Connector
import com.meda.portico.core.ha.WsListener
import com.meda.portico.core.ha.WsTransport
import com.meda.portico.core.net.UrlCheck
import com.meda.portico.core.net.UrlPolicy
import com.meda.portico.core.tailnet.Forwarder
import com.meda.portico.core.tailnet.ForwarderConnectException
import com.meda.portico.core.tailnet.TailnetUnavailableException
import com.meda.portico.signaling.OkHttpConnector
import com.meda.portico.signaling.OkHttpPost
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * The tailnet path's connector (DEV-557, docs/tailnet.md "TLS"). It opens the SAME `ha_url`
 * WebSocket as [OkHttpConnector], with three changes to the OkHttp client and nothing else:
 *
 * - [NoLookupDns]: the URL's host is never resolved (away from home the internal name does not
 *   resolve at all). OkHttp gets a placeholder 127.0.0.1 for it; nothing ever connects to it.
 * - [ForwarderSocketFactory]: every socket connects to the local forwarder instead of the
 *   address OkHttp asks for, and first sends the forwarder's 32-byte token. The forwarder dials
 *   the real target through the tailnet.
 * - no proxy: a system HTTP proxy must not see (or break) the tunnelled connection.
 *
 * OkHttp still layers TLS over that socket for the URL's REAL host name: SNI, the certificate
 * chain against the system CAs, and hostname verification are exactly those of the direct path.
 * The tailnet only carries the bytes; it is never trusted with the server's identity.
 *
 * [forwarder] may block (it waits for the node to come up): [open] runs on FallbackConnector's
 * worker thread, never on the UI or the signaling thread.
 */
class TailnetConnector(
    private val forwarder: (cancelled: () -> Boolean) -> Forwarder?,
    private val whyNot: () -> String,
    private val base: OkHttpClient = OkHttpConnector.defaultClient(),
) : Connector {

    override fun open(url: String, listener: WsListener): WsTransport = open(url, listener) { false }

    override fun open(url: String, listener: WsListener, cancelled: () -> Boolean): WsTransport {
        val check = UrlPolicy.checkWebSocket(url)
        if (check is UrlCheck.Refused) throw OkHttpConnector.RefusedUrlException(check.reason.name)
        val f = forwarder(cancelled) ?: throw TailnetUnavailableException(whyNot())
        return OkHttpConnector(clientFor(f)).open(url, listener)
    }

    internal fun clientFor(f: Forwarder): OkHttpClient = base.newBuilder()
        .dns(NoLookupDns)
        .socketFactory(ForwarderSocketFactory(f.port, f.token()))
        .proxy(Proxy.NO_PROXY)
        .build()
}

/**
 * The local forwarder's address: the EXPLICIT IPv4 loopback 127.0.0.1 ([Forwarder.HOST]), where
 * gocore/tailnet/forwarder.go listens. Never `InetAddress.getLoopbackAddress()`: on Android that
 * is `::1`, nothing listens there, and every connect is refused (the 2026-10-08 field failure).
 * ForwarderLoopbackTest proves the difference on the JVM.
 */
val FORWARDER_LOOPBACK: InetAddress = InetAddress.getByAddress(Forwarder.hostBytes())

/** Answers every name with a placeholder loopback address and never asks a resolver. */
object NoLookupDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        listOf(InetAddress.getByAddress(hostname, Forwarder.hostBytes()))
}

/**
 * Sockets that connect to [FORWARDER_LOOPBACK]`:[port]` whatever address they are asked for, and
 * write [token] before anything else (the forwarder closes a connection without it: any app on
 * the phone can reach 127.0.0.1, only Portico has the token). A refused connect is thrown as a
 * [ForwarderConnectException] naming `127.0.0.1:<port>`, so Setup can say where it looked.
 */
class ForwarderSocketFactory(private val port: Int, token: ByteArray) : SocketFactory() {
    private val token = token.copyOf()

    override fun createSocket(): Socket = ForwarderSocket(port, token)

    override fun createSocket(host: String?, port: Int): Socket =
        createSocket().also { it.connect(InetSocketAddress.createUnresolved(host ?: "", port)) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        createSocket().also { it.connect(InetSocketAddress(host, port)) }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket(address, port)

    private class ForwarderSocket(private val forwarderPort: Int, private val token: ByteArray) : Socket() {
        override fun connect(endpoint: SocketAddress?) = connect(endpoint, 0)

        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            // The endpoint is OkHttp's placeholder for the HA host; the real target is the forwarder's.
            try {
                super.connect(InetSocketAddress(FORWARDER_LOOPBACK, forwarderPort), timeout)
            } catch (e: ConnectException) {
                throw ForwarderConnectException(Forwarder.endpointOf(forwarderPort), e)
            }
            val out = getOutputStream()
            out.write(token)
            out.flush()
        }
    }
}

/**
 * DEV-544: the tailnet path for an HTTP POST (the redemption of a one-time code, HA's `/auth/token`). The same three changes
 * to the OkHttp client as [TailnetConnector] makes for the WebSocket ([NoLookupDns], [ForwarderSocketFactory], no proxy), and
 * TLS still runs over the socket for the URL's real host name, so the certificate checks are those of the direct path.
 *
 * [forwarder] may block while the node comes up: [post] runs on the caller's worker thread (the redemption's or the token
 * refresh's), never the UI's or the signaling thread's. No tailnet means an [IOException], which [PathedHttp] reports as "no path".
 */
class TailnetPost(
    private val forwarder: () -> Forwarder?,
    private val whyNot: () -> String,
    private val base: OkHttpClient = OkHttpPost.defaultClient(),
) : HttpPost {

    override fun post(url: String, contentType: String, body: String): HttpReply {
        if (UrlPolicy.check(url) is UrlCheck.Refused) throw java.io.IOException("refused by the network policy")
        val f = forwarder() ?: throw java.io.IOException("tailnet not up (${whyNot()})")
        val client = base.newBuilder()
            .dns(NoLookupDns)
            .socketFactory(ForwarderSocketFactory(f.port, f.token()))
            .proxy(Proxy.NO_PROXY)
            .build()
        return OkHttpPost(client).post(url, contentType, body)
    }

    override fun toString(): String = "TailnetPost"
}
