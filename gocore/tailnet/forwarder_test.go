package tailnet

import (
	"bytes"
	"context"
	"encoding/hex"
	"errors"
	"io"
	"net"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// echoServer stands in for HA behind the tailnet: a local listener that
// echoes whatever it receives. The forwarder's dialer is a plain net.Dial to
// it, so these tests touch no network beyond 127.0.0.1.
func echoServer(t *testing.T) (addr string, accepted *atomic.Int32) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	accepted = &atomic.Int32{}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			accepted.Add(1)
			go func() { defer c.Close(); _, _ = io.Copy(c, c) }()
		}
	}()
	return ln.Addr().String(), accepted
}

func plainDial(ctx context.Context, network, addr string) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, network, addr)
}

func connectWithToken(t *testing.T, f *forwarder, token []byte) net.Conn {
	t.Helper()
	c, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(f.port())))
	if err != nil {
		t.Fatal(err)
	}
	if token != nil {
		if _, err := c.Write(token); err != nil {
			t.Fatal(err)
		}
	}
	return c
}

func roundTrip(t *testing.T, c net.Conn, msg string) {
	t.Helper()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := io.WriteString(c, msg); err != nil {
		t.Fatalf("write: %v", err)
	}
	got := make([]byte, len(msg))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(got) != msg {
		t.Fatalf("got %q, want %q", got, msg)
	}
}

func expectClosed(t *testing.T, c net.Conn) {
	t.Helper()
	_ = c.SetReadDeadline(time.Now().Add(tokenWait + 3*time.Second))
	n, err := c.Read(make([]byte, 1))
	if err == nil {
		t.Fatalf("connection still open (read %d bytes)", n)
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		t.Fatal("connection was not closed by the forwarder (read timed out)")
	}
}

func TestForwarderBridgesWithToken(t *testing.T) {
	addr, accepted := echoServer(t)
	f, err := newForwarder(addr, plainDial, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer f.close()
	if f.port() <= 0 || len(f.tokenHex()) != 2*tokenLen {
		t.Fatalf("port %d token %q", f.port(), f.tokenHex())
	}
	c := connectWithToken(t, f, f.token)
	defer c.Close()
	for i := 0; i < 3; i++ {
		roundTrip(t, c, "hello-"+strconv.Itoa(i))
	}
	if accepted.Load() != 1 {
		t.Fatalf("upstream accepted %d connections, want 1", accepted.Load())
	}
}

func TestForwarderListensOnLoopbackOnly(t *testing.T) {
	addr, _ := echoServer(t)
	f, err := newForwarder(addr, plainDial, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer f.close()
	if ip := f.ln.Addr().(*net.TCPAddr).IP; !ip.IsLoopback() {
		t.Fatalf("listening on %v, want loopback", ip)
	}
}

func TestForwarderRefusesWrongTokenWithoutDialling(t *testing.T) {
	var dialled atomic.Int32
	dial := func(ctx context.Context, n, a string) (net.Conn, error) {
		dialled.Add(1)
		return nil, errors.New("must not be called")
	}
	f, err := newForwarder("192.168.1.5:443", dial, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer f.close()

	wrong := bytes.Repeat([]byte{0x42}, tokenLen)
	c := connectWithToken(t, f, wrong)
	defer c.Close()
	expectClosed(t, c)

	// A TLS ClientHello instead of the token (another app poking the port).
	c2 := connectWithToken(t, f, append([]byte{0x16, 0x03, 0x01}, bytes.Repeat([]byte{0}, 40)...))
	defer c2.Close()
	expectClosed(t, c2)

	if dialled.Load() != 0 {
		t.Fatalf("dialled %d times for connections without the token", dialled.Load())
	}
}

func TestForwarderClosesSilentConnectionAfterTokenWait(t *testing.T) {
	f, err := newForwarder("192.168.1.5:443", plainDial, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer f.close()
	c := connectWithToken(t, f, f.token[:10]) // short: never completes the token
	defer c.Close()
	expectClosed(t, c)
}

func TestForwarderClosesLocalWhenTailnetDialFails(t *testing.T) {
	dial := func(ctx context.Context, n, a string) (net.Conn, error) {
		return nil, errors.New("i/o timeout (no route)")
	}
	f, err := newForwarder("192.168.1.5:443", dial, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer f.close()
	c := connectWithToken(t, f, f.token)
	defer c.Close()
	expectClosed(t, c)
	if !strings.Contains(Logs(), "tailnet dial failed") {
		t.Fatalf("log tail does not record the failed dial:\n%s", Logs())
	}
}

func TestForwarderDialsTheConfiguredTarget(t *testing.T) {
	addr, _ := echoServer(t)
	var gotNet, gotAddr atomic.Value
	dial := func(ctx context.Context, n, a string) (net.Conn, error) {
		gotNet.Store(n)
		gotAddr.Store(a)
		return plainDial(ctx, n, addr)
	}
	f, err := newForwarder("192.168.1.5:443", dial, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer f.close()
	c := connectWithToken(t, f, f.token)
	defer c.Close()
	roundTrip(t, c, "x")
	if gotNet.Load() != "tcp" || gotAddr.Load() != "192.168.1.5:443" {
		t.Fatalf("dialled %v %v", gotNet.Load(), gotAddr.Load())
	}
}

func TestForwarderCloseEndsOpenConnections(t *testing.T) {
	addr, _ := echoServer(t)
	f, err := newForwarder(addr, plainDial, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	c := connectWithToken(t, f, f.token)
	defer c.Close()
	roundTrip(t, c, "before close")
	if f.active() != 2 {
		t.Fatalf("active = %d, want 2 (local + upstream)", f.active())
	}
	done := make(chan struct{})
	go func() { f.close(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("close() did not return")
	}
	expectClosed(t, c)
	if _, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(f.port()))); err == nil {
		t.Fatal("listener still accepting after close")
	}
	f.close() // twice is safe
}

func TestForwarderCloseCancelsPendingDial(t *testing.T) {
	started := make(chan struct{})
	dial := func(ctx context.Context, n, a string) (net.Conn, error) {
		close(started)
		<-ctx.Done()
		return nil, ctx.Err()
	}
	f, err := newForwarder("192.168.1.5:443", dial, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	c := connectWithToken(t, f, f.token)
	defer c.Close()
	<-started
	done := make(chan struct{})
	go func() { f.close(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("close() waited on a pending tailnet dial")
	}
}

func TestForwarderConcurrentConnections(t *testing.T) {
	addr, accepted := echoServer(t)
	f, err := newForwarder(addr, plainDial, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer f.close()
	const n = 8
	errs := make(chan error, n)
	for i := 0; i < n; i++ {
		go func(i int) {
			c, err := net.Dial("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(f.port())))
			if err != nil {
				errs <- err
				return
			}
			defer c.Close()
			_ = c.SetDeadline(time.Now().Add(5 * time.Second))
			msg := "conn-" + strconv.Itoa(i)
			if _, err := c.Write(append(append([]byte{}, f.token...), msg...)); err != nil {
				errs <- err
				return
			}
			got := make([]byte, len(msg))
			if _, err := io.ReadFull(c, got); err != nil {
				errs <- err
				return
			}
			if string(got) != msg {
				errs <- errors.New("crossed streams: " + string(got))
				return
			}
			errs <- nil
		}(i)
	}
	for i := 0; i < n; i++ {
		if err := <-errs; err != nil {
			t.Fatal(err)
		}
	}
	if accepted.Load() != n {
		t.Fatalf("upstream accepted %d, want %d", accepted.Load(), n)
	}
}

func TestForwarderTokensDifferPerForwarder(t *testing.T) {
	a, _ := newForwarder("192.168.1.5:443", plainDial, time.Second)
	b, _ := newForwarder("192.168.1.5:443", plainDial, time.Second)
	defer a.close()
	defer b.close()
	if a.tokenHex() == b.tokenHex() {
		t.Fatal("two forwarders minted the same token")
	}
	if _, err := hex.DecodeString(a.tokenHex()); err != nil {
		t.Fatal(err)
	}
}

func TestForwarderDropsConnectionsBeyondThePendingCap(t *testing.T) {
	f, err := newForwarder("192.168.1.5:443", plainDial, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	defer f.close()
	var held []net.Conn
	defer func() {
		for _, c := range held {
			_ = c.Close()
		}
	}()
	for i := 0; i < maxPending; i++ {
		held = append(held, connectWithToken(t, f, nil)) // sends nothing: stays pending
	}
	deadline := time.Now().Add(3 * time.Second)
	for len(f.pending) < maxPending && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if len(f.pending) != maxPending {
		t.Fatalf("pending = %d, want %d", len(f.pending), maxPending)
	}
	extra := connectWithToken(t, f, nil)
	defer extra.Close()
	_ = extra.SetReadDeadline(time.Now().Add(2 * time.Second)) // well under tokenWait
	if _, err := extra.Read(make([]byte, 1)); err == nil {
		t.Fatal("read data from a dropped connection")
	} else if ne, ok := err.(net.Error); ok && ne.Timeout() {
		t.Fatal("connection over the cap was not dropped immediately")
	}
}

func TestHedgedDialStartsAnotherWhileTheFirstHangs(t *testing.T) {
	var calls atomic.Int32
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		if calls.Add(1) == 1 {
			<-ctx.Done() // the first dial finds no route and waits for ever
			return nil, ctx.Err()
		}
		a, b := net.Pipe()
		go func() { <-ctx.Done() }()
		_ = b
		return a, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	start := time.Now()
	c, err := hedgedDial(ctx, dial, "tcp", "x:1", 50*time.Millisecond, 3)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if d := time.Since(start); d > time.Second {
		t.Fatalf("took %v: the second dial should have won at about 50 ms", d)
	}
	if calls.Load() != 2 {
		t.Fatalf("dials = %d, want 2", calls.Load())
	}
}

func TestHedgedDialRetriesAtOnceWhenEveryAttemptFailed(t *testing.T) {
	var calls atomic.Int32
	boom := errors.New("connection was refused")
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		calls.Add(1)
		return nil, boom
	}
	start := time.Now()
	_, err := hedgedDial(context.Background(), dial, "tcp", "x:1", time.Hour, 3)
	if !errors.Is(err, boom) {
		t.Fatalf("err = %v, want the dial's own error", err)
	}
	if calls.Load() != 3 || time.Since(start) > time.Second {
		t.Fatalf("dials = %d in %v, want 3 at once", calls.Load(), time.Since(start))
	}
}

func TestHedgedDialClosesALateWinnerAndHonoursTheContext(t *testing.T) {
	closed := make(chan struct{}, 2)
	slow := func(ctx context.Context, network, addr string) (net.Conn, error) {
		a, b := net.Pipe()
		go func() { _, _ = io.Copy(io.Discard, b); closed <- struct{}{} }()
		time.Sleep(150 * time.Millisecond)
		return a, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Millisecond)
	defer cancel()
	if _, err := hedgedDial(ctx, slow, "tcp", "x:1", 20*time.Millisecond, 3); err == nil {
		t.Fatal("expected the context deadline")
	}
	for i := 0; i < 3; i++ { // every connection that arrived late is closed
		select {
		case <-closed:
		case <-time.After(2 * time.Second):
			t.Fatalf("late connection %d was not closed", i+1)
		}
	}
}

// DEV-764: a dial that hangs is cancelled at hedgeAttemptTimeout and replaced by a fresh one, which connects.
func TestHedgedDialCapsAHungDialAndDialsAfresh(t *testing.T) {
	old := hedgeAttemptTimeout
	hedgeAttemptTimeout = 100 * time.Millisecond
	defer func() { hedgeAttemptTimeout = old }()
	var calls atomic.Int32
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		if calls.Add(1) <= 3 { // the whole first wave hangs
			<-ctx.Done()
			return nil, ctx.Err()
		}
		a, _ := net.Pipe()
		return a, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	start := time.Now()
	c, err := hedgedDial(ctx, dial, "tcp", "x:1", 20*time.Millisecond, 3)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	if d := time.Since(start); d > 2*time.Second {
		t.Fatalf("took %v: a fresh dial should have won right after the cap", d)
	}
	if calls.Load() < 4 {
		t.Fatalf("dials = %d, want a fresh one after the hung three", calls.Load())
	}
}
