package tailnet

import (
	"context"
	"io"
	"net"
	"net/http/httptest"
	"net/netip"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
	"tailscale.com/tstest/integration"
	"tailscale.com/tstest/integration/testcontrol"

	"github.com/gsasha/portico-app/gocore/tsnet"
)

// End to end through REAL tsnet nodes against a fully local control plane
// (testcontrol + a local DERP/STUN on 127.0.0.1), the harness tsnet's own
// tests use. No Tailscale account, no control plane on the internet, no
// network beyond loopback. Skipped with -short.

const e2eKey = "tskey" + "-auth-portico-e2e"

func startControl(t *testing.T, machineAuth bool) string {
	return startControlWith(t, e2eKey, false, machineAuth)
}

// startControlWith: requireKey "" accepts any login without a key check
// (testcontrol checks RequireAuthKey on EVERY register, even a known node
// key, which the real control server does not); requireAuth makes a
// keyless login interactive (a login URL).
func startControlWith(t *testing.T, requireKey string, requireAuth, machineAuth bool) string {
	t.Helper()
	return startControlServer(t, requireKey, requireAuth, machineAuth).HTTPTestServer.URL
}

func startControlServer(t *testing.T, requireKey string, requireAuth, machineAuth bool) *testcontrol.Server {
	t.Helper()
	// The package's netmon getter serves ONLY the table from SetInterfaces
	// (as on Android 11+), so feed it this host's real table in the Java
	// side's format: the same path the phone takes.
	SetInterfaces(hostTable(t))
	netns.SetEnabled(false) // as tsnet's own tests do (corp#4520)
	t.Cleanup(func() { netns.SetEnabled(true) })
	derpMap := integration.RunDERPAndSTUN(t, t.Logf, "127.0.0.1")
	control := &testcontrol.Server{
		DERPMap:            derpMap,
		RequireAuthKey:     requireKey,
		RequireAuth:        requireAuth,
		RequireMachineAuth: machineAuth,
		DNSConfig:          &tailcfg.DNSConfig{Proxied: true},
		MagicDNSDomain:     "tail-scale.ts.net",
	}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Start()
	t.Cleanup(control.HTTPTestServer.Close)
	orig := controlURL
	controlURL = control.HTTPTestServer.URL
	t.Cleanup(func() { controlURL = orig })
	return control
}

func TestE2EJoinAndForwardThroughTheTailnet(t *testing.T) {
	if testing.Short() {
		t.Skip("brings up two local WireGuard netstacks")
	}
	url := startControl(t, false)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	// "HA": a tailnet node listening on :8443, echoing.
	ha, err := tsnet.Start(ctx, tsnet.Config{
		StateDir: filepath.Join(t.TempDir(), "ha"), Hostname: "ha", AuthKey: e2eKey,
		ControlURL: url, Logf: func(string, ...any) {},
	})
	if err != nil {
		t.Fatalf("start ha node: %v", err)
	}
	t.Cleanup(func() { ha.Stop() })
	haIP := ha.IPv4()
	if _, err := netip.ParseAddr(haIP); err != nil {
		t.Fatalf("ha node IPv4 %q", haIP)
	}
	ln, err := ha.Listen("tcp", ":8443")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { defer c.Close(); _, _ = io.Copy(c, c) }()
		}
	}()

	// The phone: exactly the bound API the app calls.
	reset(t, startNode)
	stateDir := filepath.Join(t.TempDir(), "phone")
	if err := Join(e2eKey, "portico-e2e-phone", stateDir, 60); err != nil {
		t.Fatal(err)
	}
	s := waitStatusFor(t, "up", 60*time.Second)
	if !strings.HasPrefix(s, "up\t100.") {
		t.Fatalf("Status() = %q", s)
	}

	res, err := StartForwarder(net.JoinHostPort(haIP, "8443"))
	if err != nil {
		t.Fatal(err)
	}
	port, tok := splitForwarder(t, res)
	c, err := net.Dial("tcp", "127.0.0.1:"+strconv.Itoa(port))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if _, err := c.Write(tok); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 3; i++ {
		roundTrip(t, c, "through the tailnet "+strconv.Itoa(i))
		time.Sleep(200 * time.Millisecond)
	}

}

func TestE2ERejoinWithoutKeyKeepsTheIdentity(t *testing.T) {
	if testing.Short() {
		t.Skip("brings up a local WireGuard netstack")
	}
	startControlWith(t, "", false, false)
	reset(t, startNode)
	stateDir := filepath.Join(t.TempDir(), "phone")
	if err := Join(e2eKey, "portico-e2e-phone", stateDir, 60); err != nil {
		t.Fatal(err)
	}
	first := waitStatusFor(t, "up", 60*time.Second)
	// The single-use key is spent: the stored node key must be enough.
	Stop()
	if err := Join("", "portico-e2e-phone", stateDir, 60); err != nil {
		t.Fatal(err)
	}
	if again := waitStatusFor(t, "up", 60*time.Second); again != first {
		t.Fatalf("rejoin without a key: %q, first join was %q", again, first)
	}
}

func TestE2EWrongKeyFailsWithoutWaitingOutTheDeadline(t *testing.T) {
	if testing.Short() {
		t.Skip("brings up a local WireGuard netstack")
	}
	startControl(t, false)
	reset(t, startNode)
	start := time.Now()
	if err := Join("tskey"+"-auth-not-the-right-one", "portico-e2e-phone", filepath.Join(t.TempDir(), "p"), 120); err != nil {
		t.Fatal(err)
	}
	s := waitStatusFor(t, "failed", 120*time.Second)
	t.Logf("wrong key: %q after %v", s, time.Since(start))
	if !strings.HasPrefix(s, "failed\tkey_rejected\t") || !strings.Contains(s, "invalid authkey") {
		t.Fatalf("Status() = %q", s)
	}
	if strings.Contains(s, "not-the-right-one") {
		t.Fatalf("status quotes the key: %q", s)
	}
	if time.Since(start) > 60*time.Second {
		t.Fatalf("took %v", time.Since(start))
	}
}

func TestE2ENoKeyAndNoIdentityIsNeedsLogin(t *testing.T) {
	if testing.Short() {
		t.Skip("brings up a local WireGuard netstack")
	}
	startControlWith(t, "", true, false) // a keyless login is interactive
	reset(t, startNode)
	if err := Join("", "portico-e2e-phone", filepath.Join(t.TempDir(), "empty"), 120); err != nil {
		t.Fatal(err)
	}
	s := waitStatusFor(t, "failed", 120*time.Second)
	if !strings.HasPrefix(s, "failed\tneeds_login\t") {
		t.Fatalf("Status() = %q", s)
	}
}

func TestE2EMachineAuthShowsNeedsApproval(t *testing.T) {
	if testing.Short() {
		t.Skip("brings up a local WireGuard netstack")
	}
	startControl(t, true)
	reset(t, startNode)
	if err := Join(e2eKey, "portico-e2e-phone", filepath.Join(t.TempDir(), "p"), 20); err != nil {
		t.Fatal(err)
	}
	if s := waitStatusFor(t, "joining\t", 30*time.Second); s != "joining\tneeds_approval" {
		t.Fatalf("Status() = %q", s)
	}
	if s := waitStatusFor(t, "failed", 40*time.Second); !strings.HasPrefix(s, "failed\tneeds_approval\t") {
		t.Fatalf("Status() = %q", s)
	}
}

// The route check against real nodes: a subnet-router peer advertises
// 192.168.0.0/16 (testcontrol approves it as a primary route), and the phone's
// RouteCheck names it for 192.168.1.5 and finds no peer for 10.9.0.5.
func TestE2ERouteCheckFindsTheSubnetRouter(t *testing.T) {
	if testing.Short() {
		t.Skip("brings up two local WireGuard netstacks")
	}
	control := startControlServer(t, e2eKey, false, false)
	url := control.HTTPTestServer.URL
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	// The phone first, while it is the only node: no peers known means "none".
	reset(t, startNode)
	if s := RouteCheck("192.168.1.5:443"); s != "unknown" {
		t.Fatalf("before the join: RouteCheck = %q, want unknown", s)
	}
	if err := Join(e2eKey, "portico-e2e-phone", filepath.Join(t.TempDir(), "phone"), 60); err != nil {
		t.Fatal(err)
	}
	waitStatusFor(t, "up", 60*time.Second)
	if s := RouteCheck("192.168.1.5:443"); s != "none\t192.168.1.5\t0" {
		t.Fatalf("alone on the tailnet: RouteCheck = %q", s)
	}

	router, err := tsnet.Start(ctx, tsnet.Config{
		StateDir: filepath.Join(t.TempDir(), "router"), Hostname: "estate-router", AuthKey: e2eKey,
		ControlURL: url, Logf: func(string, ...any) {},
	})
	if err != nil {
		t.Fatalf("start router node: %v", err)
	}
	t.Cleanup(func() { router.Stop() })
	st, err := router.Status(ctx)
	if err != nil || st.Self == nil {
		t.Fatalf("router status: %v", err)
	}

	// A peer with no routes: still "none", now with one peer visible.
	if s := waitRouteCheck(t, "192.168.1.5:443", "none\t192.168.1.5\t1", 30*time.Second); s == "" {
		t.Fatal("unreachable")
	}

	control.SetSubnetRoutes(st.Self.PublicKey, []netip.Prefix{netip.MustParsePrefix("192.168.0.0/16")})
	waitRouteCheck(t, "192.168.1.5:443", "via\testate-router\t192.168.0.0/16", 30*time.Second)
	if s := RouteCheck("10.9.0.5:443"); s != "none\t10.9.0.5\t1" {
		t.Fatalf("outside the advertised prefix: RouteCheck = %q", s)
	}
	// The router's own tailnet address is reachable "via" the router itself.
	if s := RouteCheck(net.JoinHostPort(router.IPv4(), "443")); !strings.HasPrefix(s, "via\testate-router\t"+router.IPv4()+"/32") {
		t.Fatalf("the router's own address: RouteCheck = %q", s)
	}
}

func waitRouteCheck(t *testing.T, target, want string, d time.Duration) string {
	t.Helper()
	deadline := time.Now().Add(d)
	var s string
	for time.Now().Before(deadline) {
		if s = RouteCheck(target); s == want {
			return s
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("RouteCheck(%q) never became %q (now %q)", target, want, s)
	return ""
}

// hostTable renders net.Interfaces() as TailnetInterfaces.kt does.
func hostTable(t *testing.T) string {
	t.Helper()
	ifs, err := net.Interfaces()
	if err != nil {
		t.Fatal(err)
	}
	var b strings.Builder
	for _, ni := range ifs {
		addrs, _ := ni.Addrs()
		var as []string
		for _, a := range addrs {
			as = append(as, a.String())
		}
		b.WriteString(ni.Name + "\t" + strconv.Itoa(ni.Index) + "\t" + strconv.Itoa(ni.MTU) + "\t" +
			strconv.Itoa(int(ni.Flags)) + "\t" + strings.Join(as, ",") + "\n")
	}
	return b.String()
}

func waitStatusFor(t *testing.T, prefix string, d time.Duration) string {
	t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if s := Status(); strings.HasPrefix(s, prefix) {
			return s
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("status never became %q (now %q)\nlog tail:\n%s", prefix, Status(), tail(Logs(), 40))
	return ""
}

func tail(s string, n int) string {
	lines := strings.Split(s, "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}

// DEV-694: a real tsnet node reports its start through the IPN bus.
func TestE2EJoinRecordsItsPhases(t *testing.T) {
	if testing.Short() {
		t.Skip("brings up a local WireGuard netstack")
	}
	startControlWith(t, "", false, false)
	reset(t, startNode)
	if err := Join(e2eKey, "portico-e2e-phone", filepath.Join(t.TempDir(), "phone"), 60); err != nil {
		t.Fatal(err)
	}
	waitStatusFor(t, "up", 60*time.Second)
	deadline := time.Now().Add(5 * time.Second)
	for !strings.Contains(JoinPhases(), "running+") && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
	}
	p := JoinPhases()
	t.Logf("phases: %s", p)
	// A netmap entry depends on the tsnet version's notifications; the states always arrive.
	if !strings.Contains(p, "starting+") || !strings.Contains(p, "running+") {
		t.Fatalf("phases = %q, want the starting and running states", p)
	}
	if strings.Contains(p, "tskey") {
		t.Fatalf("phases leak a key: %q", p)
	}
}
