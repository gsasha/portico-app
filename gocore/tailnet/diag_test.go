package tailnet

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/gsasha/portico-app/gocore/tsnet"
)

// Diagnostics (Setup's "why can't it reach HA through the tailnet"): the
// forwarder's last dial, classified, and the route check. No tsnet node here;
// e2e_test.go checks the route check against real nodes and testcontrol.

func TestClassifyDialErr(t *testing.T) {
	dnsNotFound := &net.DNSError{Err: "no such host", Name: "haos.example.internal", IsNotFound: true}
	dnsTimeout := &net.DNSError{Err: "i/o timeout", Name: "haos.example.internal", IsTimeout: true}
	cases := []struct {
		name string
		err  error
		want string
	}{
		{"nil is ok", nil, DialOK},
		{"dns not found", fmt.Errorf("tsnet: dial: %w", dnsNotFound), DialNoSuchHost},
		{"dns timeout is still a name problem", dnsTimeout, DialNoSuchHost},
		{"no such host text", errors.New("lookup haos: no such host"), DialNoSuchHost},
		{"syscall refused", &net.OpError{Op: "dial", Net: "tcp", Err: os.NewSyscallError("connect", syscall.ECONNREFUSED)}, DialRefused},
		{"gvisor refused", errors.New("connect tcp 192.168.1.5:443: connection was refused"), DialRefused},
		{"plain refused", errors.New("dial tcp 192.168.1.5:443: connect: connection refused"), DialRefused},
		{"syscall host unreachable", os.NewSyscallError("connect", syscall.EHOSTUNREACH), DialUnreachable},
		{"syscall net unreachable", os.NewSyscallError("connect", syscall.ENETUNREACH), DialUnreachable},
		{"gvisor no route", errors.New("connect tcp 192.168.1.5:443: no route to host"), DialUnreachable},
		{"network unreachable text", errors.New("dial tcp 192.168.1.5:443: connect: network is unreachable"), DialUnreachable},
		{"deadline", fmt.Errorf("tsnet: dial: %w", context.DeadlineExceeded), DialTimeout},
		{"io timeout text", errors.New("dial tcp 192.168.1.5:443: i/o timeout"), DialTimeout},
		{"gvisor timed out", errors.New("connect tcp 192.168.1.5:443: operation timed out"), DialTimeout},
		{"net.Error timeout", timeoutErr{}, DialTimeout},
		{"anything else", errors.New("tsnet: client not started"), DialError},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := classifyDialErr(c.err); got != c.want {
				t.Fatalf("classifyDialErr(%v) = %q, want %q", c.err, got, c.want)
			}
		})
	}
}

type timeoutErr struct{}

func (timeoutErr) Error() string   { return "something slow" }
func (timeoutErr) Timeout() bool   { return true }
func (timeoutErr) Temporary() bool { return true }

func TestForwarderStatusStartsAsNone(t *testing.T) {
	resetDial()
	if s := ForwarderStatus(); s != "none" {
		t.Fatalf("ForwarderStatus() = %q", s)
	}
}

func TestForwarderStatusRecordsEachDialOutcome(t *testing.T) {
	addr, _ := echoServer(t)
	cases := []struct {
		name string
		dial dialFunc
		want string
	}{
		{"ok", plainDial, DialOK},
		{"timeout", func(context.Context, string, string) (net.Conn, error) {
			return nil, fmt.Errorf("tsnet: dial tcp %s failed: %w", addr, context.DeadlineExceeded)
		}, DialTimeout},
		{"no such host", func(context.Context, string, string) (net.Conn, error) {
			return nil, &net.DNSError{Err: "no such host", Name: "haos.example.internal", IsNotFound: true}
		}, DialNoSuchHost},
		{"refused", func(context.Context, string, string) (net.Conn, error) {
			return nil, errors.New("connect tcp 192.168.1.5:443: connection was refused")
		}, DialRefused},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			resetDial()
			before := time.Now().UnixMilli()
			f, err := newForwarder(addr, c.dial, time.Second)
			if err != nil {
				t.Fatal(err)
			}
			defer f.close()
			conn := connectWithToken(t, f, f.token)
			defer conn.Close()
			if c.want == DialOK {
				roundTrip(t, conn, "hello")
			} else {
				expectClosed(t, conn)
			}
			s := waitForwarderStatus(t, c.want)
			fields := strings.Split(s, "\t")
			if len(fields) < 2 {
				t.Fatalf("ForwarderStatus() = %q", s)
			}
			at, err := strconv.ParseInt(fields[1], 10, 64)
			if err != nil || at < before || at > time.Now().UnixMilli() {
				t.Fatalf("ForwarderStatus() time %q not within the test (%d..now)", fields[1], before)
			}
		})
	}
}

func TestForwarderStatusRecordsARefusedToken(t *testing.T) {
	resetDial()
	f, err := newForwarder("192.168.1.5:443", plainDial, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer f.close()
	bad := make([]byte, tokenLen)
	c := connectWithToken(t, f, bad)
	defer c.Close()
	expectClosed(t, c)
	waitForwarderStatus(t, DialToken)
}

func TestForwarderStatusDetailIsOneShortLineWithoutTheToken(t *testing.T) {
	resetDial()
	var f *forwarder
	long := strings.Repeat("x", 1000)
	dial := func(context.Context, string, string) (net.Conn, error) {
		return nil, fmt.Errorf("tsnet: dial failed — long prose\nsecond line: %w", errors.New("i/o timeout "+long+" "+f.tokenHex()))
	}
	f, err := newForwarder("192.168.1.5:443", dial, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer f.close()
	c := connectWithToken(t, f, f.token)
	defer c.Close()
	expectClosed(t, c)
	s := waitForwarderStatus(t, DialTimeout)
	if strings.Contains(s, "\n") || len(s) > 260 {
		t.Fatalf("ForwarderStatus() not one short line (%d bytes): %q", len(s), s)
	}
	if strings.Contains(s, f.tokenHex()[:16]) {
		t.Fatalf("ForwarderStatus() quotes the token: %q", s)
	}
	if strings.Contains(s, "long prose") {
		t.Fatalf("ForwarderStatus() keeps the wrapper's prose instead of the cause: %q", s)
	}
}

func waitForwarderStatus(t *testing.T, kind string) string {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if s := ForwarderStatus(); strings.HasPrefix(s, kind+"\t") {
			return s
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("ForwarderStatus() never became %q (now %q)", kind, ForwarderStatus())
	return ""
}

func pfx(s string) netip.Prefix { return netip.MustParsePrefix(s) }

func TestRouteVerdict(t *testing.T) {
	router := tsnet.PeerRoutes{Name: "example-router", PrimaryRoutes: []netip.Prefix{pfx("192.168.0.0/16")},
		AllowedIPs: []netip.Prefix{pfx("100.64.0.7/32"), pfx("192.168.0.0/16")}, TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.7")}}
	exit := tsnet.PeerRoutes{Name: "exit-node", AllowedIPs: []netip.Prefix{pfx("100.64.0.9/32"), pfx("0.0.0.0/0"), pfx("::/0")},
		TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.9")}}
	haNode := tsnet.PeerRoutes{Name: "haos", TailscaleIPs: []netip.Addr{netip.MustParseAddr("100.64.0.5")}, AllowedIPs: []netip.Prefix{pfx("100.64.0.5/32")}}
	unprimary := tsnet.PeerRoutes{Name: "standby-router", AllowedIPs: []netip.Prefix{pfx("10.3.0.0/16")}}
	cases := []struct {
		name    string
		ip      string
		peers   []tsnet.PeerRoutes
		running bool
		want    string
	}{
		{"not running", "192.168.1.5", []tsnet.PeerRoutes{router}, false, "unknown"},
		{"subnet route", "192.168.1.5", []tsnet.PeerRoutes{exit, router}, true, "via\texample-router\t192.168.0.0/16"},
		{"no route", "10.9.0.5", []tsnet.PeerRoutes{exit, router}, true, "none\t10.9.0.5\t2"},
		{"no peers at all", "192.168.1.5", nil, true, "none\t192.168.1.5\t0"},
		{"an exit node is not a route to the LAN", "192.168.1.5", []tsnet.PeerRoutes{exit}, true, "none\t192.168.1.5\t1"},
		{"HA is itself a tailnet node", "100.64.0.5", []tsnet.PeerRoutes{router, haNode}, true, "via\thaos\t100.64.0.5/32"},
		{"allowed but not primary still routes", "10.3.1.1", []tsnet.PeerRoutes{unprimary}, true, "via\tstandby-router\t10.3.0.0/16"},
		{"longest prefix wins", "192.168.1.5", []tsnet.PeerRoutes{
			{Name: "wide", PrimaryRoutes: []netip.Prefix{pfx("10.0.0.0/8")}},
			{Name: "narrow", PrimaryRoutes: []netip.Prefix{pfx("192.168.0.0/16")}},
		}, true, "via\tnarrow\t192.168.0.0/16"},
		{"a peer name with a tab cannot break the line", "192.168.1.5", []tsnet.PeerRoutes{
			{Name: "bad\tname", PrimaryRoutes: []netip.Prefix{pfx("192.168.0.0/16")}},
		}, true, "via\tbad name\t192.168.0.0/16"},
		{"a peer without a name", "192.168.1.5", []tsnet.PeerRoutes{
			{PrimaryRoutes: []netip.Prefix{pfx("192.168.0.0/16")}},
		}, true, "via\t(unnamed peer)\t192.168.0.0/16"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := routeVerdict(netip.MustParseAddr(c.ip), c.peers, c.running)
			if got != c.want {
				t.Fatalf("routeVerdict = %q, want %q", got, c.want)
			}
		})
	}
}

func TestRouteCheckThroughTheNode(t *testing.T) {
	routes := []tsnet.PeerRoutes{{Name: "example-router", PrimaryRoutes: []netip.Prefix{pfx("192.168.0.0/16")}}}
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		return &fakeNode{ip: "100.64.0.2", dial: plainDial, peers: routes, running: true}, nil
	})
	if s := RouteCheck("192.168.1.5:443"); s != "unknown" {
		t.Fatalf("before a join: RouteCheck = %q, want unknown", s)
	}
	if s := RouteCheck("haos.example.internal:443"); s != "not_ip" {
		t.Fatalf("a name: RouteCheck = %q, want not_ip", s)
	}
	if s := RouteCheck("garbage"); s != "not_ip" {
		t.Fatalf("garbage: RouteCheck = %q, want not_ip", s)
	}
	if err := Join("tskey-auth-x", "portico-phone", t.TempDir(), 10); err != nil {
		t.Fatal(err)
	}
	waitStatus(t, "up")
	if s := RouteCheck("192.168.1.5:443"); s != "via\texample-router\t192.168.0.0/16" {
		t.Fatalf("RouteCheck = %q", s)
	}
	if s := RouteCheck("[fd7a:115c:a1e0::5]:443"); s != "none\tfd7a:115c:a1e0::5\t1" {
		t.Fatalf("RouteCheck v6 = %q", s)
	}
}

func TestRouteCheckWhenTheStatusFails(t *testing.T) {
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		return &fakeNode{ip: "100.64.0.2", dial: plainDial, peersErr: errors.New("local API down")}, nil
	})
	if err := Join("tskey-auth-x", "portico-phone", t.TempDir(), 10); err != nil {
		t.Fatal(err)
	}
	waitStatus(t, "up")
	if s := RouteCheck("192.168.1.5:443"); s != "unknown" {
		t.Fatalf("RouteCheck = %q, want unknown", s)
	}
}

func TestRefusedTokenDoesNotHideTheLastDialFailure(t *testing.T) {
	resetDial()
	recordDial(DialRefused, errors.New("connection refused"), "")
	recordDial(DialToken, nil, "")
	if s := ForwarderStatus(); !strings.HasPrefix(s, DialRefused+"\t") || !strings.Contains(s, "connection refused") {
		t.Fatalf("a refused local connection overwrote the dial failure: %q", s)
	}
}
