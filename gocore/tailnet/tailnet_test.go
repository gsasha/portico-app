package tailnet

import (
	"context"
	"errors"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/gsasha/portico-app/gocore/tsnet"
)

// These tests replace startNode with a fake, so no tsnet node, no network
// and no Tailscale are involved. e2e_test.go covers the real tsnet path
// against a local testcontrol.

type fakeNode struct {
	ip          string
	dial        dialFunc
	stopped     atomic.Bool
	linkChanges atomic.Int32
	peers       []tsnet.PeerRoutes
	running     bool
	peersErr    error
}

func (f *fakeNode) Dial(ctx context.Context, n, a string) (net.Conn, error) { return f.dial(ctx, n, a) }
func (f *fakeNode) Stop() error                                             { f.stopped.Store(true); return nil }
func (f *fakeNode) IPv4() string                                            { return f.ip }
func (f *fakeNode) LinkChanged()                                            { f.linkChanges.Add(1) }
func (f *fakeNode) Peers(context.Context) ([]tsnet.PeerRoutes, bool, error) {
	return f.peers, f.running, f.peersErr
}

// reset puts the package back to a fresh process and installs start.
func reset(t *testing.T, start func(ctx context.Context, cfg tsnet.Config) (node, error)) {
	t.Helper()
	Stop()
	mu.Lock()
	prepared = false
	mu.Unlock()
	ClearLogs()
	orig := startNode
	startNode = start
	t.Cleanup(func() { Stop(); startNode = orig })
	keepEnv(t)
	if err := Prepare(t.TempDir()); err != nil {
		t.Fatal(err)
	}
}

func waitStatus(t *testing.T, prefix string) string {
	t.Helper()
	deadline := time.Now().Add(20 * time.Second) // joins are clamped to >= 10 s
	for time.Now().Before(deadline) {
		if s := Status(); strings.HasPrefix(s, prefix) {
			return s
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("status never became %q (now %q)", prefix, Status())
	return ""
}

const testKey = "tskey-auth-kSECRET123-ABCdef"

// keepEnv restores the three variables Prepare sets when the test ends
// (TMPDIR in particular, which t.TempDir itself uses).
func keepEnv(t *testing.T) {
	t.Helper()
	for _, k := range []string{"TS_LOGS_DIR", "TMPDIR", "HOME"} {
		t.Setenv(k, os.Getenv(k))
	}
}

func TestPrepareSetsTheThreeDirectories(t *testing.T) {
	keepEnv(t)
	base := t.TempDir()
	if err := Prepare(base); err != nil {
		t.Fatal(err)
	}
	for env, sub := range map[string]string{"TS_LOGS_DIR": "logs", "TMPDIR": "tmp", "HOME": "home"} {
		want := filepath.Join(base, sub)
		if got := os.Getenv(env); got != want {
			t.Errorf("%s = %q, want %q", env, got, want)
		}
		// TS_LOGS_DIR is ignored by tsnet unless the directory EXISTS.
		if st, err := os.Stat(want); err != nil || !st.IsDir() {
			t.Errorf("%s directory %s does not exist", env, want)
		}
	}
	if err := Prepare(""); err == nil {
		t.Fatal("Prepare(\"\") succeeded")
	}
}

func TestJoinWithoutPrepareFailsInsteadOfPanicking(t *testing.T) {
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		t.Fatal("startNode must not run before Prepare")
		return nil, nil
	})
	mu.Lock()
	prepared = false
	mu.Unlock()
	if err := Join(testKey, "portico-phone", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	if s := Status(); s != "failed\tnot_prepared\tPrepare was not called before Join" {
		t.Fatalf("Status() = %q", s)
	}
}

func TestJoinIsAsyncAndReportsUp(t *testing.T) {
	release := make(chan struct{})
	var gotCfg tsnet.Config
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		gotCfg = cfg
		<-release
		return &fakeNode{ip: "100.101.102.103"}, nil
	})
	if s := Status(); s != "idle" {
		t.Fatalf("initial Status() = %q", s)
	}
	dir := t.TempDir()
	if err := Join(testKey, "portico-pixel", dir, 30); err != nil {
		t.Fatal(err)
	}
	if s := Status(); s != "joining" {
		t.Fatalf("Status() during join = %q, want joining (Join must not block)", s)
	}
	if err := Join(testKey, "portico-pixel", dir, 30); err == nil {
		t.Fatal("a second Join while joining was accepted")
	}
	close(release)
	if s := waitStatus(t, "up"); s != "up\t100.101.102.103" {
		t.Fatalf("Status() = %q", s)
	}
	if gotCfg.AuthKey != testKey || gotCfg.Hostname != "portico-pixel" || gotCfg.StateDir != dir || gotCfg.ReuseIdentity {
		t.Fatalf("config passed to tsnet: key ok=%v host=%q dir=%q reuse=%v",
			gotCfg.AuthKey == testKey, gotCfg.Hostname, gotCfg.StateDir, gotCfg.ReuseIdentity)
	}
	if gotCfg.ControlURL != "" {
		t.Fatalf("ControlURL = %q, want the default control plane", gotCfg.ControlURL)
	}
}

func TestJoinWithoutKeyReusesIdentity(t *testing.T) {
	var gotCfg tsnet.Config
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		gotCfg = cfg
		return &fakeNode{ip: "100.64.0.9"}, nil
	})
	if err := Join("", "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	waitStatus(t, "up")
	if gotCfg.AuthKey != "" || !gotCfg.ReuseIdentity {
		t.Fatalf("AuthKey empty=%v ReuseIdentity=%v", gotCfg.AuthKey == "", gotCfg.ReuseIdentity)
	}
}

func TestJoinErrorIsReportedWithoutTheKey(t *testing.T) {
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		return nil, errors.New("backend error: invalid key " + cfg.AuthKey + "\nsecond line")
	})
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	s := waitStatus(t, "failed")
	if !strings.HasPrefix(s, "failed\terror\t") {
		t.Fatalf("Status() = %q", s)
	}
	if strings.Contains(s, "kSECRET123") || strings.Contains(s, "\n") {
		t.Fatalf("status leaks the key or spans lines: %q", s)
	}
}

func TestInteractiveLoginFailsFastAsNeedsLogin(t *testing.T) {
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		// What tsnet does with a spent key or a deleted identity: it sits
		// in NeedsLogin with a login URL until the deadline.
		go func() {
			for ctx.Err() == nil {
				cfg.OnState("NeedsLogin", "https://login.example/a/123")
				time.Sleep(20 * time.Millisecond)
			}
		}()
		<-ctx.Done()
		return nil, ctx.Err()
	})
	start := time.Now()
	if err := Join(testKey, "portico-pixel", t.TempDir(), 300); err != nil {
		t.Fatal(err)
	}
	s := waitStatus(t, "failed")
	if !strings.HasPrefix(s, "failed\tneeds_login\t") {
		t.Fatalf("Status() = %q", s)
	}
	if time.Since(start) > 5*time.Second {
		t.Fatalf("took %v: a login URL must end the join at once, not at the deadline", time.Since(start))
	}
}

func TestMachineAuthIsShownWhileWaitingAndReportedAtDeadline(t *testing.T) {
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		cfg.OnState("NeedsMachineAuth", "")
		<-ctx.Done()
		return nil, ctx.Err()
	})
	if err := Join(testKey, "portico-pixel", t.TempDir(), 10); err != nil {
		t.Fatal(err)
	}
	if s := waitStatus(t, "joining\t"); s != "joining\tneeds_approval" {
		t.Fatalf("Status() = %q", s)
	}
	if s := waitStatus(t, "failed"); !strings.HasPrefix(s, "failed\tneeds_approval\t") {
		t.Fatalf("Status() = %q", s)
	}
}

func TestDeadlineIsTimeout(t *testing.T) {
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		<-ctx.Done()
		return nil, ctx.Err()
	})
	if err := Join(testKey, "portico-pixel", t.TempDir(), 1); err != nil { // clamped to 10 s
		t.Fatal(err)
	}
	if s := waitStatus(t, "failed"); !strings.HasPrefix(s, "failed\ttimeout\t") {
		t.Fatalf("Status() = %q", s)
	}
}

func TestPanicInStartIsContained(t *testing.T) {
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		panic("no safe place found to store log state")
	})
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	s := waitStatus(t, "failed")
	if !strings.HasPrefix(s, "failed\tpanic\tgo panic: no safe place") {
		t.Fatalf("Status() = %q", s)
	}
}

func TestStopDuringJoinDiscardsTheLateNode(t *testing.T) {
	release := make(chan struct{})
	n := &fakeNode{ip: "100.64.0.1"}
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		<-release
		return n, nil
	})
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	Stop()
	close(release)
	deadline := time.Now().Add(5 * time.Second)
	for !n.stopped.Load() && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if !n.stopped.Load() {
		t.Fatal("a node that came up after Stop was not stopped")
	}
	if s := Status(); s != "idle" {
		t.Fatalf("Status() = %q, want idle", s)
	}
}

func TestStopEndsNodeAndForwarder(t *testing.T) {
	addr, _ := echoServer(t)
	n := &fakeNode{ip: "100.64.0.1", dial: func(ctx context.Context, nw, a string) (net.Conn, error) { return plainDial(ctx, nw, addr) }}
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) { return n, nil })
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	waitStatus(t, "up")
	res, err := StartForwarder("192.168.1.5:443")
	if err != nil {
		t.Fatal(err)
	}
	port, _ := splitForwarder(t, res)
	Stop()
	// Stop closes the node and forwarder in the background (it must not block
	// the calling thread), so wait for it rather than assuming it is done.
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		c, err := net.Dial("tcp", "127.0.0.1:"+strconv.Itoa(port))
		if n.stopped.Load() && err != nil {
			break
		}
		if err == nil {
			c.Close()
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !n.stopped.Load() {
		t.Fatal("Stop did not stop the node")
	}
	if c, err := net.Dial("tcp", "127.0.0.1:"+strconv.Itoa(port)); err == nil {
		c.Close()
		t.Fatal("forwarder still listening after Stop")
	}
	if s := Status(); s != "idle" {
		t.Fatalf("Status() = %q", s)
	}
}

func splitForwarder(t *testing.T, res string) (int, []byte) {
	t.Helper()
	p, tok, ok := strings.Cut(res, ":")
	if !ok {
		t.Fatalf("StartForwarder() = %q, want port:token", res)
	}
	port, err := strconv.Atoi(p)
	if err != nil || port <= 0 {
		t.Fatalf("bad port in %q", res)
	}
	b := make([]byte, tokenLen)
	if len(tok) != 2*tokenLen {
		t.Fatalf("bad token length in %q", res)
	}
	for i := 0; i < tokenLen; i++ {
		v, err := strconv.ParseUint(tok[2*i:2*i+2], 16, 8)
		if err != nil {
			t.Fatal(err)
		}
		b[i] = byte(v)
	}
	return port, b
}

func TestStartForwarderNeedsANodeAndAValidTarget(t *testing.T) {
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) { return &fakeNode{ip: "100.64.0.1"}, nil })
	if _, err := StartForwarder("192.168.1.5:443"); err == nil {
		t.Fatal("StartForwarder succeeded with no node up")
	}
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	waitStatus(t, "up")
	for _, bad := range []string{"", "192.168.1.5", "192.168.1.5:0", "192.168.1.5:70000", ":443", "192.168.1.5:https"} {
		if _, err := StartForwarder(bad); err == nil {
			t.Errorf("StartForwarder(%q) accepted", bad)
		}
	}
}

func TestForwarderThroughTheNodeAndReuse(t *testing.T) {
	addr, _ := echoServer(t)
	var dialled atomic.Value
	n := &fakeNode{ip: "100.64.0.1", dial: func(ctx context.Context, nw, a string) (net.Conn, error) {
		dialled.Store(a)
		return plainDial(ctx, nw, addr)
	}}
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) { return n, nil })
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	waitStatus(t, "up")
	res, err := StartForwarder("192.168.1.5:443")
	if err != nil {
		t.Fatal(err)
	}
	again, _ := StartForwarder("192.168.1.5:443")
	if again != res {
		t.Fatalf("same target opened a second forwarder: %q vs %q", again, res)
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
	roundTrip(t, c, "GET /api/ HTTP/1.1")
	if dialled.Load() != "192.168.1.5:443" {
		t.Fatalf("node dialled %v", dialled.Load())
	}
	other, err := StartForwarder("192.168.1.6:443")
	if err != nil || other == res {
		t.Fatalf("a new target must get a new forwarder: %q %v", other, err)
	}
	StopForwarder()
	if c2, err := net.Dial("tcp", "127.0.0.1:"+strings.SplitN(other, ":", 2)[0]); err == nil {
		c2.Close()
		t.Fatal("StopForwarder left the listener open")
	}
}

func TestLogRingRedactsKeys(t *testing.T) {
	ClearLogs()
	logLine("control: register with %s failed", testKey)
	if strings.Contains(Logs(), "kSECRET123") {
		t.Fatalf("log ring kept the key: %q", Logs())
	}
	ClearLogs()
	for i := 0; i < logRingMax+50; i++ {
		logLine("line %d", i)
	}
	if n := len(strings.Split(Logs(), "\n")); n != logRingMax {
		t.Fatalf("ring holds %d lines, want %d", n, logRingMax)
	}
}

func TestExportedFunctionsContainPanics(t *testing.T) {
	var err error
	func() {
		defer containPanic(&err)
		panic("boom")
	}()
	if err == nil || !strings.Contains(err.Error(), "go panic: boom") {
		t.Fatalf("err = %v", err)
	}
}

// assertForwarderWorks checks that a plain TCP client on 127.0.0.1:port is
// accepted and bridged with the token, and closed without it.
func assertForwarderWorks(t *testing.T, port int, tok []byte) {
	t.Helper()
	c, err := net.Dial("tcp", "127.0.0.1:"+strconv.Itoa(port))
	if err != nil {
		t.Fatalf("forwarder on 127.0.0.1:%d not accepting: %v", port, err)
	}
	defer c.Close()
	if _, err := c.Write(tok); err != nil {
		t.Fatal(err)
	}
	roundTrip(t, c, "GET /api/ HTTP/1.1")

	bad, err := net.Dial("tcp", "127.0.0.1:"+strconv.Itoa(port))
	if err != nil {
		t.Fatalf("forwarder on 127.0.0.1:%d not accepting: %v", port, err)
	}
	defer bad.Close()
	_, _ = bad.Write(make([]byte, tokenLen))
	_ = bad.SetReadDeadline(time.Now().Add(tokenWait + 2*time.Second))
	if n, err := bad.Read(make([]byte, 1)); err == nil {
		t.Fatalf("a connection without the token was bridged (%d bytes)", n)
	}
}

// The 2026-10-08 failure class "the local forwarder was not listening": a
// forwarder whose listener died underneath must never be handed out again.
func TestStartForwarderRebuildsADeadListener(t *testing.T) {
	addr, _ := echoServer(t)
	n := &fakeNode{ip: "100.64.0.1", dial: func(ctx context.Context, nw, a string) (net.Conn, error) { return plainDial(ctx, nw, addr) }}
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) { return n, nil })
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	waitStatus(t, "up")
	res, err := StartForwarder("192.168.1.5:443")
	if err != nil {
		t.Fatal(err)
	}
	port, _ := splitForwarder(t, res)

	mu.Lock()
	dead := fwd
	mu.Unlock()
	_ = dead.ln.Close() // the listener dies under the forwarder (not via close())
	deadline := time.Now().Add(5 * time.Second)
	for dead.alive() && time.Now().Before(deadline) {
		time.Sleep(5 * time.Millisecond)
	}
	if dead.alive() {
		t.Fatal("serve() ended but the forwarder still says it is alive")
	}

	again, err := StartForwarder("192.168.1.5:443")
	if err != nil {
		t.Fatal(err)
	}
	if again == res {
		t.Fatalf("StartForwarder handed out the dead forwarder again: %q", again)
	}
	port2, tok2 := splitForwarder(t, again)
	if c, err := net.Dial("tcp", "127.0.0.1:"+strconv.Itoa(port)); err == nil && port2 != port {
		c.Close()
		t.Fatal("the dead port still accepts")
	}
	assertForwarderWorks(t, port2, tok2)
}

// sleep() stops the node (and the forwarder); the next wake must never get
// the old port back.
func TestStartForwarderAfterStopAndRejoinIsANewWorkingPort(t *testing.T) {
	addr, _ := echoServer(t)
	n := &fakeNode{ip: "100.64.0.1", dial: func(ctx context.Context, nw, a string) (net.Conn, error) { return plainDial(ctx, nw, addr) }}
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) { return n, nil })
	dir := t.TempDir()
	if err := Join(testKey, "portico-pixel", dir, 30); err != nil {
		t.Fatal(err)
	}
	waitStatus(t, "up")
	res, err := StartForwarder("192.168.1.5:443")
	if err != nil {
		t.Fatal(err)
	}
	Stop()
	if _, err := StartForwarder("192.168.1.5:443"); err == nil {
		t.Fatal("StartForwarder answered while the node is stopped")
	}
	if err := Join("", "portico-pixel", dir, 30); err != nil {
		t.Fatal(err)
	}
	waitStatus(t, "up")
	again, err := StartForwarder("192.168.1.5:443")
	if err != nil {
		t.Fatal(err)
	}
	if again == res {
		t.Fatalf("the forwarder from before Stop was handed out again: %q", again)
	}
	port2, tok2 := splitForwarder(t, again)
	assertForwarderWorks(t, port2, tok2)
}

type panicStopNode struct{ fakeNode }

func (*panicStopNode) Stop() error { panic("boom in Stop") }

// A panic in the node's Close, which runs in its own goroutine, must be
// recovered and logged rather than crash the process.
func TestCloseQuietlyRecoversFromAPanickingStop(t *testing.T) {
	ClearLogs()
	closeQuietly(&panicStopNode{}, nil)
	if l := Logs(); !strings.Contains(l, "panic closing node") || !strings.Contains(l, "boom in Stop") {
		t.Fatalf("panic not logged: %q", l)
	}
}

// ---- DEV-694: WaitChange and the join timeline ----

func TestWaitChangeReturnsAtOnceWhenTheStatusAlreadyDiffers(t *testing.T) {
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) { <-ctx.Done(); return nil, ctx.Err() })
	start := time.Now()
	if got := WaitChange("something else", 5_000); got != "idle" {
		t.Fatalf("WaitChange = %q, want the current status (idle)", got)
	}
	if time.Since(start) > 500*time.Millisecond {
		t.Fatalf("took %v: it must not wait when the status already differs", time.Since(start))
	}
}

func TestWaitChangeWakesWhenTheNodeComesUp(t *testing.T) {
	release := make(chan struct{})
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		<-release
		return &fakeNode{ip: "100.101.102.103"}, nil
	})
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	if s := Status(); s != "joining" {
		t.Fatalf("Status() = %q", s)
	}
	got := make(chan string, 1)
	go func() { got <- WaitChange("joining", 20_000) }()
	time.Sleep(100 * time.Millisecond) // the waiter is parked
	select {
	case s := <-got:
		t.Fatalf("WaitChange returned %q before anything changed", s)
	default:
	}
	released := time.Now()
	close(release)
	select {
	case s := <-got:
		if s != "up\t100.101.102.103" {
			t.Fatalf("WaitChange = %q", s)
		}
		if d := time.Since(released); d > 200*time.Millisecond {
			t.Fatalf("woke %v after the node came up: that is a poll, not a wake", d)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("WaitChange never woke")
	}
}

func TestWaitChangeTimesOutWithTheUnchangedStatus(t *testing.T) {
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) { <-ctx.Done(); return nil, ctx.Err() })
	start := time.Now()
	if got := WaitChange("idle", 150); got != "idle" {
		t.Fatalf("WaitChange = %q", got)
	}
	if d := time.Since(start); d < 120*time.Millisecond || d > 2*time.Second {
		t.Fatalf("waited %v for a 150 ms timeout", d)
	}
}

func TestWaitChangeWakesOnStopAndFailureToo(t *testing.T) {
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) { <-ctx.Done(); return nil, ctx.Err() })
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	got := make(chan string, 1)
	go func() { got <- WaitChange("joining", 20_000) }()
	time.Sleep(100 * time.Millisecond)
	Stop()
	select {
	case s := <-got:
		if s != "idle" {
			t.Fatalf("after Stop WaitChange = %q", s)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Stop did not wake WaitChange")
	}
}

func TestWaitChangeClampsAbsurdTimeouts(t *testing.T) {
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) { <-ctx.Done(); return nil, ctx.Err() })
	start := time.Now()
	if got := WaitChange("idle", -5); got != "idle" || time.Since(start) > time.Second {
		t.Fatalf("a negative timeout: %q after %v", got, time.Since(start))
	}
}

func TestJoinPhasesAreRecordedForThisJoinOnly(t *testing.T) {
	release := make(chan struct{})
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		cfg.OnPhase("starting", 120)
		cfg.OnPhase("netmap", 1450)
		<-release
		cfg.OnPhase("running", 1640)
		return &fakeNode{ip: "100.101.102.103"}, nil
	})
	if JoinPhases() != "" {
		t.Fatalf("phases before any join: %q", JoinPhases())
	}
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for !strings.Contains(JoinPhases(), "netmap+1450ms") && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	close(release)
	waitStatus(t, "up")
	if got := JoinPhases(); got != "starting+120ms netmap+1450ms running+1640ms" {
		t.Fatalf("JoinPhases = %q", got)
	}
	Stop()
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	if got := JoinPhases(); got != "" {
		t.Fatalf("a new join starts a new timeline, got %q", got)
	}
}

func TestAStaleJoinCannotWritePhasesIntoTheNewOne(t *testing.T) {
	late := make(chan struct{})
	entered := make(chan struct{}, 2)
	var calls atomic.Int32
	reset(t, func(ctx context.Context, cfg tsnet.Config) (node, error) {
		first := calls.Add(1) == 1
		entered <- struct{}{}
		if first {
			<-late
			cfg.OnPhase("running", 9999) // the superseded join reports after a newer one began
		}
		<-ctx.Done()
		return nil, ctx.Err()
	})
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	<-entered // the first join is really running before it is superseded
	Stop()
	if err := Join(testKey, "portico-pixel", t.TempDir(), 30); err != nil {
		t.Fatal(err)
	}
	close(late)
	time.Sleep(200 * time.Millisecond)
	if got := JoinPhases(); got != "" {
		t.Fatalf("the old join wrote %q into the new timeline", got)
	}
}

func TestLinkChangedReachesTheRunningNodeAndIsANoOpWithoutOne(t *testing.T) {
	LinkChanged() // no node: nothing happens
	n := &fakeNode{ip: "100.64.0.1"}
	mu.Lock()
	old := cur
	cur = n
	mu.Unlock()
	defer func() { mu.Lock(); cur = old; mu.Unlock() }()
	LinkChanged()
	LinkChanged()
	if got := n.linkChanges.Load(); got != 2 {
		t.Fatalf("node told %d times, want 2", got)
	}
}
