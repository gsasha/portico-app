package tailnet

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"
)

// The local forwarder (DEV-557): a TCP listener on 127.0.0.1:<ephemeral>
// that bridges each accepted connection to dial("tcp", target) through the
// tailnet. The Kotlin side points OkHttp's sockets at it, so no net.Conn has
// to cross the gomobile boundary and OkHttp keeps doing TLS (SNI, certificate
// and hostname checks against the system CAs) for the real HA host name.
//
// ⚠️ 127.0.0.1 is reachable by EVERY app on the phone, not only Portico. A
// bare forwarder would let any installed app reach HA's port through this
// phone's tailnet identity. So each connection must open with a 32-byte
// token minted per forwarder (crypto/rand) and handed only to the Kotlin
// side; a connection that does not present it within tokenWait is closed
// before anything is dialled.

const (
	tokenLen  = 32
	tokenWait = 5 * time.Second
	// maxPending caps connections still waiting for their token: each holds a
	// goroutine and an fd for up to tokenWait, and any local app can open them.
	maxPending = 16
)

// errAttemptCapped marks a dial cancelled by hedgeAttemptTimeout (not by the caller).
var errAttemptCapped = errors.New("tailnet dial capped")

type dialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

type forwarder struct {
	ln          net.Listener
	target      string
	token       []byte
	dial        dialFunc
	dialTimeout time.Duration
	ctx         context.Context // cancelled by close(), so a pending dial ends with it
	cancel      context.CancelFunc

	pending chan struct{} // semaphore: connections awaiting the token

	mu     sync.Mutex
	conns  map[net.Conn]struct{}
	closed bool
	wg     sync.WaitGroup

	// served is closed when serve() returns: the listener is gone (closed by
	// close(), or it failed underneath), so the port no longer accepts.
	served chan struct{}
}

func newForwarder(target string, dial dialFunc, dialTimeout time.Duration) (*forwarder, error) {
	if dial == nil {
		return nil, errors.New("forwarder: no dialer")
	}
	tok := make([]byte, tokenLen)
	if _, err := rand.Read(tok); err != nil {
		return nil, fmt.Errorf("forwarder: minting the token: %w", err)
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("forwarder: listening on 127.0.0.1: %w", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	f := &forwarder{
		ctx:         ctx,
		cancel:      cancel,
		ln:          ln,
		target:      target,
		token:       tok,
		dial:        dial,
		dialTimeout: dialTimeout,
		conns:       map[net.Conn]struct{}{},
		served:      make(chan struct{}),
		pending:     make(chan struct{}, maxPending),
	}
	f.wg.Add(1)
	go f.serve()
	return f, nil
}

func (f *forwarder) port() int { return f.ln.Addr().(*net.TCPAddr).Port }

// alive reports whether the forwarder still accepts on its port: not closed,
// and serve() still running. StartForwarder never hands out a dead port.
func (f *forwarder) alive() bool {
	f.mu.Lock()
	closed := f.closed
	f.mu.Unlock()
	if closed {
		return false
	}
	select {
	case <-f.served:
		return false
	default:
		return true
	}
}

func (f *forwarder) tokenHex() string { return hex.EncodeToString(f.token) }

// track registers c so close() can end it; false once the forwarder is closed.
func (f *forwarder) track(c net.Conn) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.closed {
		return false
	}
	f.conns[c] = struct{}{}
	return true
}

func (f *forwarder) isClosed() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.closed
}

func (f *forwarder) untrack(c net.Conn) {
	f.mu.Lock()
	delete(f.conns, c)
	f.mu.Unlock()
}

// active is the number of tracked sockets (local and tailnet side), for tests.
func (f *forwarder) active() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.conns)
}

func (f *forwarder) serve() {
	defer f.wg.Done()
	defer close(f.served)
	for {
		c, err := f.ln.Accept()
		if err != nil {
			if !f.isClosed() {
				logLine("forwarder: listener on 127.0.0.1 ended: %v", err)
			}
			return // listener closed, or failed: alive() is false from here on
		}
		select {
		case f.pending <- struct{}{}:
		default:
			_ = c.Close() // too many unauthenticated connections: drop this one at once
			continue
		}
		if !f.track(c) {
			<-f.pending
			_ = c.Close()
			return
		}
		f.wg.Add(1)
		go f.handle(c)
	}
}

func (f *forwarder) handle(local net.Conn) {
	defer f.wg.Done()
	defer func() {
		// tsnet's netstack runs under this goroutine's dial and copies; a
		// panic here would kill the app with no Java exception.
		if r := recover(); r != nil {
			logLine("forwarder: panic contained: %v", r)
		}
	}()
	defer f.untrack(local)
	defer local.Close()

	_ = local.SetReadDeadline(time.Now().Add(tokenWait))
	got := make([]byte, tokenLen)
	_, err := io.ReadFull(local, got)
	<-f.pending // authenticated or not, no longer waiting for the token
	if err != nil || subtle.ConstantTimeCompare(got, f.token) != 1 {
		logLine("forwarder: refused a local connection without the token")
		recordDial(DialToken, nil, "")
		return
	}
	_ = local.SetReadDeadline(time.Time{})

	ctx, cancel := context.WithTimeout(f.ctx, f.dialTimeout)
	up, err := hedgedDial(ctx, f.dial, "tcp", f.target, hedgeStagger, hedgeAttempts)
	cancel()
	// Recorded BEFORE the local side closes (deferred above), so whoever sees
	// the close can already read why (ForwarderStatus, diag.go).
	recordDial(classifyDialErr(err), err, f.tokenHex())
	if err != nil {
		logLine("forwarder: tailnet dial failed: %v", err)
		return
	}
	if !f.track(up) {
		_ = up.Close()
		return
	}
	defer f.untrack(up)
	defer up.Close()

	// Either direction ending ends both: a WebSocket needs no half-close.
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(up, local); done <- struct{}{} }()
	go func() { _, _ = io.Copy(local, up); done <- struct{}{} }()
	<-done
	_ = local.Close()
	_ = up.Close()
	<-done
}

// close stops accepting, ends every open connection and waits for the
// handlers to return. Safe to call more than once.
func (f *forwarder) close() {
	f.mu.Lock()
	if f.closed {
		f.mu.Unlock()
		return
	}
	f.closed = true
	conns := make([]net.Conn, 0, len(f.conns))
	for c := range f.conns {
		conns = append(conns, c)
	}
	f.mu.Unlock()
	f.cancel()
	_ = f.ln.Close()
	for _, c := range conns {
		_ = c.Close()
	}
	f.wg.Wait()
}

// hedgeStagger is how long one tailnet dial gets before another is started alongside it, and
// hedgeAttempts the most dials per connection. A dial that finds no path to the peer (a node that
// has only just joined) can sit for the whole dial timeout while a fresh dial a moment later
// connects in a second (measured on a phone, DEV-705: 20 s then 1 s), so waiting on one dial
// is the slow choice. The first dial to connect wins; the others are cancelled, and a connection
// that still arrives late is closed.
const hedgeAttempts = 3

var hedgeStagger = 1 * time.Second

// hedgeAttemptTimeout caps ONE dial (DEV-764). Measured on a phone after a doze wake (2026-11-20): the
// dials of the first connection (started 0 s, 1 s, 2 s) all hung until the 20 s bound, while the first
// dial of the NEXT connection, made after that, connected in about 1 s. So a hung dial is never worth
// waiting on: it is cancelled after this long and replaced by a fresh one (which does not use up the
// attempt budget), all still bounded by the overall dial timeout. A healthy tailnet dial answers in well under it.
var hedgeAttemptTimeout = 4 * time.Second

// hedgedDial dials like dial, but starts another attempt every stagger while none has connected
// (and at once when every running attempt has failed), up to attempts, all bounded by ctx.
// It returns the first connection, or the last error.
func hedgedDial(ctx context.Context, dial dialFunc, network, addr string, stagger time.Duration, attempts int) (net.Conn, error) {
	type result struct {
		c   net.Conn
		err error
	}
	out := make(chan result, attempts)
	dctx, cancel := context.WithCancel(ctx)
	defer cancel()
	started, pending := 0, 0
	launch := func() {
		started++
		pending++
		go func() {
			actx, acancel := context.WithTimeout(dctx, hedgeAttemptTimeout)
			defer acancel()
			c, err := dial(actx, network, addr)
			if err != nil && actx.Err() == context.DeadlineExceeded && dctx.Err() == nil {
				err = errAttemptCapped
			}
			out <- result{c, err}
		}()
	}
	// closeLate closes connections that arrive after the winner (or after we gave up).
	closeLate := func(n int) {
		if n == 0 {
			return
		}
		go func() {
			for i := 0; i < n; i++ {
				if r := <-out; r.c != nil {
					_ = r.c.Close()
				}
			}
		}()
	}
	launch()
	next := time.After(stagger)
	var lastErr error
	for {
		select {
		case r := <-out:
			pending--
			if r.err == nil {
				cancel()
				closeLate(pending)
				return r.c, nil
			}
			if errors.Is(r.err, errAttemptCapped) {
				// tailnet_dial_cap: this dial hung; it is replaced, and costs no attempt
				logLine("forwarder: tailnet_dial_cap: a dial hung for %v, dialling afresh", hedgeAttemptTimeout)
				started--
				lastErr = context.DeadlineExceeded
				if pending == 0 && ctx.Err() == nil {
					launch()
					next = time.After(stagger)
				}
				continue
			}
			lastErr = r.err
			if pending == 0 {
				if started >= attempts || ctx.Err() != nil {
					return nil, lastErr
				}
				launch()
				next = time.After(stagger)
			}
		case <-next:
			if started < attempts {
				launch()
				next = time.After(stagger)
			}
		case <-ctx.Done():
			cancel()
			closeLate(pending)
			if lastErr == nil {
				lastErr = ctx.Err()
			}
			return nil, lastErr
		}
	}
}
