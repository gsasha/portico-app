package tsnet

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http/httptest"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
	"tailscale.com/tstest"
	"tailscale.com/tstest/integration"
	"tailscale.com/tstest/integration/testcontrol"
)

// --- Config validation: no network involved, pure logic. ---

func TestConfigValidate(t *testing.T) {
	base := Config{StateDir: "/tmp/x", Hostname: "phone", AuthKey: "tskey-auth-xxx"}

	tests := []struct {
		name    string
		mutate  func(c Config) Config
		wantErr string
	}{
		{
			name:    "valid config passes",
			mutate:  func(c Config) Config { return c },
			wantErr: "",
		},
		{
			name:    "missing auth key",
			mutate:  func(c Config) Config { c.AuthKey = ""; return c },
			wantErr: "AuthKey is required",
		},
		{
			name:    "missing state dir",
			mutate:  func(c Config) Config { c.StateDir = ""; return c },
			wantErr: "StateDir is required",
		},
		{
			name:    "missing hostname",
			mutate:  func(c Config) Config { c.Hostname = ""; return c },
			wantErr: "Hostname is required",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.mutate(base).validate()
			if tt.wantErr == "" {
				if err != nil {
					t.Fatalf("validate() = %v, want nil", err)
				}
				return
			}
			if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
				t.Fatalf("validate() = %v, want error containing %q", err, tt.wantErr)
			}
		})
	}
}

// --- Start(): config rejected before any network I/O happens. ---

func TestStartRejectsInvalidConfigWithoutTouchingNetwork(t *testing.T) {
	_, err := Start(context.Background(), Config{})
	if err == nil {
		t.Fatal("Start() with empty Config = nil error, want an error")
	}
	if !strings.Contains(err.Error(), "AuthKey is required") {
		t.Fatalf("Start() error = %v, want it to name the missing AuthKey", err)
	}
}

// --- Start(): a real attempt against an unreachable control server fails
// clearly and promptly, distinguishing "tailnet never came up" from a Dial
// failure (§4.8). This exercises the real tsnet.Server.Up() call — no
// mocking — just against a coordination server address that can never
// answer, which is the closest to "bad auth key / no network" we can
// safely produce without touching a real tailnet. ---

// A real bug this test caught: an earlier version of this test assumed
// tsnet would fail fast against an unreachable control server. It
// doesn't — Server.Up keeps retrying (LocalBackend treats "can't reach
// control" as transient, the same as a phone briefly losing signal) and
// only gives up when the passed context is done. That is reasonable
// tsnet behavior, but it means our own Start() must never be called with
// context.Background() or any other undeadlined context in production —
// doing so can block indefinitely with no way out. This test now proves
// the real contract: Start() honors ctx's deadline (returns at it, not
// before and not long after) and still wraps the resulting error clearly.
func TestStartFailsClearlyWhenControlServerUnreachable(t *testing.T) {
	dir := t.TempDir()
	const budget = 3 * time.Second
	ctx, cancel := context.WithTimeout(context.Background(), budget)
	defer cancel()

	cfg := Config{
		StateDir: dir,
		Hostname: "test-phone",
		AuthKey:  "tskey" + "-auth-doesnotmatter",
		// Port 1 on loopback: nothing will ever accept there, so this
		// can only resolve via ctx's own deadline, never a real reply.
		ControlURL: "http://127.0.0.1:1",
	}

	start := time.Now()
	c, err := Start(ctx, cfg)
	elapsed := time.Since(start)

	if err == nil {
		if c != nil {
			c.Stop()
		}
		t.Fatal("Start() against an unreachable control server = nil error, want an error")
	}
	if !strings.Contains(err.Error(), "tailnet connection failed to come up") {
		t.Fatalf("Start() error = %v, want it to say the tailnet failed to come up (distinct from a Dial-address failure, §4.8)", err)
	}
	// Must not return suspiciously early (that would mean it isn't really
	// retrying/blocking the way production traffic will observe) and must
	// not meaningfully outlive the context deadline it was given.
	if elapsed < budget-500*time.Millisecond {
		t.Fatalf("Start() returned in %v, well before its %v context budget — didn't actually block on ctx", elapsed, budget)
	}
	if elapsed > budget+3*time.Second {
		t.Fatalf("Start() took %v, well past its %v context budget — ctx cancellation isn't being honored promptly", elapsed, budget)
	}
	t.Logf("Start() correctly honored its context deadline (%v elapsed vs %v budget) and returned: %v", elapsed, budget, err)
}

// --- Dial()/Stop() on a Client that never got past Start(). ---

func TestDialWithoutStartReturnsErrNotStarted(t *testing.T) {
	var c *Client // never returned by Start — the zero value a caller could hold onto by mistake

	_, err := c.Dial(context.Background(), "tcp", "100.64.0.1:80")
	if !errors.Is(err, ErrNotStarted) {
		t.Fatalf("Dial() on unstarted Client = %v, want ErrNotStarted", err)
	}
}

func TestStopIsSafeOnUnstartedOrNilClient(t *testing.T) {
	var nilClient *Client
	if err := nilClient.Stop(); err != nil {
		t.Fatalf("Stop() on nil *Client = %v, want nil", err)
	}

	zero := &Client{}
	if err := zero.Stop(); err != nil {
		t.Fatalf("Stop() on never-started Client = %v, want nil", err)
	}
	// Calling it again must still be safe.
	if err := zero.Stop(); err != nil {
		t.Fatalf("second Stop() on never-started Client = %v, want nil", err)
	}
}

// --- Long-lived dial, end to end, against a fully local fake control
// plane (testcontrol + a local DERP/STUN pair) — no real Tailscale
// account or tailnet is touched, per the project's standing rule; this is
// the same harness tsnet's own test suite uses to test itself. It proves
// two things together: (1) our Start() correctly plumbs Config.AuthKey
// through to a real tsnet.Server (the fake control server requires and
// checks it), and (2) a connection returned by our Dial() is not silently
// torn down by anything in this package — it is used, with real delay,
// well past when a naive request/response dial would have been closed. ---

func TestLongLivedDialSurvives(t *testing.T) {
	if testing.Short() {
		t.Skip("brings up a local WireGuard netstack; skip with -short")
	}
	tstest.ResourceCheck(t)

	// Corp#4520: tsnet's own test suite disables netns for tests too.
	netns.SetEnabled(false)
	t.Cleanup(func() { netns.SetEnabled(true) })

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	derpMap := integration.RunDERPAndSTUN(t, t.Logf, "127.0.0.1")
	control := &testcontrol.Server{
		DERPMap:        derpMap,
		RequireAuthKey: "tskey" + "-auth-portico-test",
		DNSConfig:      &tailcfg.DNSConfig{Proxied: true},
		MagicDNSDomain: "tail-scale.ts.net",
	}
	control.HTTPTestServer = httptest.NewUnstartedServer(control)
	control.HTTPTestServer.Start()
	t.Cleanup(control.HTTPTestServer.Close)
	controlURL := control.HTTPTestServer.URL

	// "door-station": the listener side, standing in for the device the
	// app would eventually dial through the tailnet (§4.8).
	doorDir := filepath.Join(t.TempDir(), "door-station")
	must(os.MkdirAll(doorDir, 0o755))
	door, err := Start(ctx, Config{
		StateDir:   doorDir,
		Hostname:   "door-station",
		AuthKey:    control.RequireAuthKey,
		ControlURL: controlURL,
		Logf:       func(string, ...any) {},
	})
	if err != nil {
		t.Fatalf("Start(door-station) = %v", err)
	}
	t.Cleanup(func() { door.Stop() })

	ln, err := door.srv.Listen("tcp", ":9443")
	if err != nil {
		t.Fatalf("door.srv.Listen() = %v", err)
	}
	defer ln.Close()

	// "phone": the caller side, using this package's own Config/Start/Dial
	// exactly as gocore would.
	phoneDir := filepath.Join(t.TempDir(), "phone")
	must(os.MkdirAll(phoneDir, 0o755))
	phone, err := Start(ctx, Config{
		StateDir:   phoneDir,
		Hostname:   "phone",
		AuthKey:    control.RequireAuthKey,
		ControlURL: controlURL,
		Logf:       func(string, ...any) {},
	})
	if err != nil {
		t.Fatalf("Start(phone) = %v", err)
	}
	t.Cleanup(func() { phone.Stop() })

	doorIP := waitForTailscaleIP(t, ctx, door)

	conn, err := phone.Dial(ctx, "tcp", netip.AddrPortFrom(doorIP, 9443).String())
	if err != nil {
		t.Fatalf("phone.Dial(door-station) = %v", err)
	}
	defer conn.Close()

	accepted, err := ln.Accept()
	if err != nil {
		t.Fatalf("door-station Accept() = %v", err)
	}
	defer accepted.Close()

	// Prove the connection is not torn down by anything this package adds:
	// hold it open across several real, spaced-out round trips rather than
	// one immediate write/read (which a connection that dies "eventually"
	// would still pass).
	const rounds = 5
	const gap = 400 * time.Millisecond
	for i := 0; i < rounds; i++ {
		msg := fmt.Sprintf("keepalive-%d", i)
		if _, err := io.WriteString(conn, msg); err != nil {
			t.Fatalf("round %d: write failed — connection was torn down early: %v", i, err)
		}
		got := make([]byte, len(msg))
		if _, err := io.ReadFull(accepted, got); err != nil {
			t.Fatalf("round %d: read failed — connection was torn down early: %v", i, err)
		}
		if string(got) != msg {
			t.Fatalf("round %d: got %q, want %q", i, got, msg)
		}
		time.Sleep(gap)
	}
	t.Logf("connection survived %d spaced round trips (%v total) with no interference from this package", rounds, rounds*gap)
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func waitForTailscaleIP(t *testing.T, ctx context.Context, c *Client) netip.Addr {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		lc, err := c.srv.LocalClient()
		if err == nil {
			st, err := lc.StatusWithoutPeers(ctx)
			if err == nil && len(st.TailscaleIPs) > 0 {
				return st.TailscaleIPs[0]
			}
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatal("timed out waiting for tailscale IP assignment")
	return netip.Addr{}
}
