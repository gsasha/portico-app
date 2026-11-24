package com.meda.portico.core.ha

import com.meda.portico.core.tailnet.ForwarderDial
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The classification table, branch by branch, with the exception texts the JDK, Android and OkHttp produce. */
class LinkFailuresTest {

    private fun t(name: String, msg: String? = null) = ThrowableInfo(name, msg)
    private fun cls(vararg chain: ThrowableInfo, http: Int? = null) = LinkFailures.classify(chain.toList(), http).cls

    @Test
    fun `an HTTP status other than 101 wins, with its code`() {
        assertEquals(LinkFailure(FailureClass.HTTP_STATUS, 404), LinkFailures.classify(listOf(t("ProtocolException", "x")), 404))
        assertEquals(LinkFailure(FailureClass.HTTP_STATUS, 502), LinkFailures.classify(emptyList(), 502))
        // 101 is the upgrade itself: the failure is something else
        assertEquals(FailureClass.CLOSED_EARLY, cls(t("EOFException"), http = 101))
    }

    @Test
    fun `OkHttp's protocol exception carries the status in its text`() {
        val f = LinkFailures.classify(listOf(t("ProtocolException", "Expected HTTP 101 response but was '403 Forbidden'")))
        assertEquals(LinkFailure(FailureClass.HTTP_STATUS, 403), f)
        assertEquals(FailureClass.OTHER, cls(t("ProtocolException", "Too many follow-up requests: 21")))
    }

    @Test
    fun `tailnet not up`() {
        assertEquals(FailureClass.TAILNET_NOT_UP, cls(t("TailnetUnavailableException", "tailnet not up (Joining)")))
    }

    @Test
    fun `name did not resolve`() {
        assertEquals(FailureClass.NAME_NOT_RESOLVED, cls(t("UnknownHostException", "Unable to resolve host \"haos.x\": No address associated with hostname")))
        assertEquals(FailureClass.NAME_NOT_RESOLVED, cls(t("IOException"), t("UnknownHostException")))
    }

    @Test
    fun `hostname, certificate and other TLS failures are told apart`() {
        assertEquals(FailureClass.HOSTNAME, cls(t("SSLPeerUnverifiedException", "Hostname haos.x not verified")))
        assertEquals(FailureClass.CERTIFICATE, cls(t("SSLHandshakeException", "x"), t("CertificateException", "y")))
        assertEquals(FailureClass.CERTIFICATE, cls(t("SSLHandshakeException"), t("CertPathValidatorException")))
        assertEquals(FailureClass.CERTIFICATE, cls(t("CertificateExpiredException")))
        assertEquals(FailureClass.CERTIFICATE, cls(t("CertificateNotYetValidException")))
        assertEquals(FailureClass.CERTIFICATE, cls(t("SunCertPathBuilderException")))
        assertEquals(FailureClass.CERTIFICATE, cls(t("CertPathBuilderException")))
        assertEquals(
            FailureClass.CERTIFICATE,
            cls(t("SSLHandshakeException", "java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.")),
        )
        assertEquals(FailureClass.CERTIFICATE, cls(t("SSLHandshakeException", "PKIX path building failed")))
        assertEquals(FailureClass.CERTIFICATE, cls(t("SSLException", "bad certificate")))
        assertEquals(FailureClass.TLS, cls(t("SSLHandshakeException", "Remote host terminated the handshake"), t("EOFException")))
        assertEquals(FailureClass.TLS, cls(t("SSLProtocolException", "protocol error")))
        assertEquals(FailureClass.TLS, cls(t("SSLException", "Unsupported or unrecognized SSL message")))
    }

    @Test
    fun `refused, from the text Android and the JDK use`() {
        assertEquals(
            FailureClass.REFUSED,
            cls(t("ConnectException", "failed to connect to /192.168.1.5 (port 443) from /10.0.0.9 (port 4) after 15000ms: isConnected failed: ECONNREFUSED (Connection refused)")),
        )
        assertEquals(FailureClass.REFUSED, cls(t("ConnectException", "Connection refused")))
        assertEquals(FailureClass.REFUSED, cls(t("ConnectException", null)))
    }

    @Test
    fun `unreachable`() {
        assertEquals(FailureClass.UNREACHABLE, cls(t("NoRouteToHostException", "No route to host")))
        assertEquals(FailureClass.UNREACHABLE, cls(t("ConnectException", "failed to connect: connect failed: ENETUNREACH (Network is unreachable)")))
        assertEquals(FailureClass.UNREACHABLE, cls(t("ConnectException", "connect failed: EHOSTUNREACH")))
        assertEquals(FailureClass.UNREACHABLE, cls(t("SocketException", "Network is unreachable")))
        assertEquals(FailureClass.UNREACHABLE, cls(t("SocketException", "No route to host")))
    }

    @Test
    fun `timed out`() {
        assertEquals(FailureClass.TIMED_OUT, cls(t("SocketTimeoutException", "failed to connect to /192.168.1.5 (port 443) after 15000ms")))
        assertEquals(FailureClass.TIMED_OUT, cls(t("InterruptedIOException", "timeout")))
        assertEquals(FailureClass.TIMED_OUT, cls(t("IOException", "Connect timed out")))
        assertEquals(LinkFailure(FailureClass.TIMED_OUT), LinkFailures.DIRECT_DEADLINE)
    }

    @Test
    fun `closed early`() {
        assertEquals(FailureClass.CLOSED_EARLY, cls(t("EOFException", null)))
        assertEquals(FailureClass.CLOSED_EARLY, cls(t("IOException", "unexpected end of stream on https://haos.x/...")))
        assertEquals(FailureClass.CLOSED_EARLY, cls(t("SocketException", "Connection reset")))
        assertEquals(FailureClass.CLOSED_EARLY, cls(t("IOException", "Connection closed by peer")))
    }

    @Test
    fun `anything else is OTHER`() {
        assertEquals(FailureClass.OTHER, cls(t("IllegalStateException", "boom")))
        assertEquals(FailureClass.OTHER, cls())
    }

    @Test
    fun `the Go forwarder's kinds`() {
        assertNull(LinkFailures.fromGoDial("ok"))
        assertEquals(FailureClass.NAME_NOT_RESOLVED, LinkFailures.fromGoDial("no_such_host"))
        assertEquals(FailureClass.DIAL_TIMEOUT, LinkFailures.fromGoDial("timeout"))
        assertEquals(FailureClass.REFUSED, LinkFailures.fromGoDial("refused"))
        assertEquals(FailureClass.NO_ROUTE, LinkFailures.fromGoDial("unreachable"))
        assertEquals(FailureClass.FORWARDER_TOKEN, LinkFailures.fromGoDial("token"))
        assertEquals(FailureClass.OTHER, LinkFailures.fromGoDial("error"))
        assertEquals(FailureClass.OTHER, LinkFailures.fromGoDial("something_newer"))
    }

    @Test
    fun `tailnet - a fresh forwarder failure explains the bare end of stream`() {
        val eof = LinkFailure(FailureClass.TLS)
        assertEquals(LinkFailure(FailureClass.DIAL_TIMEOUT), LinkFailures.tailnet(eof, ForwarderDial("timeout", 10_000), 10_000))
        assertEquals(LinkFailure(FailureClass.NAME_NOT_RESOLVED), LinkFailures.tailnet(eof, ForwarderDial("no_such_host", 9_500), 10_000))
        assertEquals(LinkFailure(FailureClass.FORWARDER_TOKEN), LinkFailures.tailnet(eof, ForwarderDial("token", 12_000), 10_000))
    }

    @Test
    fun `tailnet - an old forwarder record is ignored`() {
        val eof = LinkFailure(FailureClass.CLOSED_EARLY)
        assertEquals(eof, LinkFailures.tailnet(eof, ForwarderDial("timeout", 1_000), 10_000))
        assertEquals(eof, LinkFailures.tailnet(eof, null, 10_000))
    }

    @Test
    fun `tailnet - a dial that worked leaves the TLS or HTTP failure`() {
        val tls = LinkFailure(FailureClass.HOSTNAME)
        assertEquals(tls, LinkFailures.tailnet(tls, ForwarderDial("ok", 10_000), 10_000))
        val http = LinkFailure(FailureClass.HTTP_STATUS, 502)
        assertEquals(http, LinkFailures.tailnet(http, ForwarderDial("ok", 10_000), 10_000))
    }

    @Test
    fun `tailnet - a refused connect is the local forwarder's port, never HA's`() {
        assertEquals(LinkFailure(FailureClass.FORWARDER_DOWN), LinkFailures.tailnet(LinkFailure(FailureClass.REFUSED), null, 10_000))
    }

    @Test
    fun `the local forwarder refused - the endpoint tried is kept, as OkHttp wraps it`() {
        val ours = t("ForwarderConnectException", "local forwarder 127.0.0.1:41234 not listening")
        val down = LinkFailure(FailureClass.FORWARDER_DOWN, endpoint = "127.0.0.1:41234")
        assertEquals(down, LinkFailures.classify(listOf(ours, t("ConnectException", "Connection refused"))))
        // OkHttp 5: ConnectException("Failed to connect to <host>/127.0.0.1:443") caused by ours.
        assertEquals(down, LinkFailures.classify(listOf(t("ConnectException", "Failed to connect to haos.x/127.0.0.1:443"), ours)))
        // Only a loopback endpoint is ever shown; any other text gives no endpoint.
        assertEquals(
            LinkFailure(FailureClass.FORWARDER_DOWN),
            LinkFailures.classify(listOf(t("ForwarderConnectException", "local forwarder 192.168.1.5:443 not listening"))),
        )
    }

    @Test
    fun `tailnet - the forwarder's own refusal is not explained away by another dial's record`() {
        val down = LinkFailure(FailureClass.FORWARDER_DOWN, endpoint = "127.0.0.1:41234")
        assertEquals(down, LinkFailures.tailnet(down, ForwarderDial("timeout", 10_000), 10_000))
        assertEquals(down, LinkFailures.tailnet(down, null, 10_000))
    }

    @Test
    fun `tailnet - not up stays not up whatever the forwarder said before`() {
        val notUp = LinkFailure(FailureClass.TAILNET_NOT_UP)
        assertEquals(notUp, LinkFailures.tailnet(notUp, ForwarderDial("timeout", 20_000), 10_000))
    }

    @Test
    fun `diagnostics keep the last attempt per path, and auth rejected lands on the path that opened`() {
        val d = LinkDiagnostics()
        assertNull(d.last(LinkPath.DIRECT))
        d.authRejected(5) // nothing opened yet: ignored
        assertNull(d.last(LinkPath.DIRECT))
        d.record(PathAttempt(LinkPath.DIRECT, 10, LinkFailure(FailureClass.NAME_NOT_RESOLVED)))
        d.record(PathAttempt(LinkPath.TAILNET, 20, null))
        d.authRejected(30)
        assertEquals(PathAttempt(LinkPath.TAILNET, 30, LinkFailure(FailureClass.AUTH_REJECTED)), d.last(LinkPath.TAILNET))
        assertEquals(FailureClass.NAME_NOT_RESOLVED, d.last(LinkPath.DIRECT)!!.failure!!.cls)
        d.record(PathAttempt(LinkPath.DIRECT, 40, null))
        d.authRejected(50)
        assertEquals(FailureClass.AUTH_REJECTED, d.last(LinkPath.DIRECT)!!.failure!!.cls)
    }

    @Test
    fun ago() {
        assertEquals("just now", Ago.text(1_000, 1_000))
        assertEquals("just now", Ago.text(1_000, 5_000)) // a clock step backwards
        assertEquals("2 s ago", Ago.text(3_000, 1_000))
        assertEquals("59 s ago", Ago.text(60_000, 1_000))
        assertEquals("1 min ago", Ago.text(61_000, 1_000))
        assertEquals("3 h ago", Ago.text(3 * 3_600_000L + 5, 0))
        assertEquals("2 d ago", Ago.text(2 * 86_400_000L, 0))
    }
}
