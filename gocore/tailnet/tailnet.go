// Package tailnet is the gomobile-bound surface of Portico's embedded tailnet
// client (DEV-557, docs/tailnet.md). bind.sh compiles it into
// app/libs/gocore.aar as the Java class com.meda.portico.gomobile.tailnet.Tailnet.
//
// It is deliberately small: every exported function is one the app calls,
// and every argument and result is a string, an int or an error, so nothing
// richer than that crosses the gomobile boundary. The shape is the intercom
// app's tsmobile, which ran this exact toolchain on real phones:
//
//   - Prepare(baseDir) before anything else: TS_LOGS_DIR, TMPDIR and HOME
//     under the app's private storage. Without them tsnet PANICS on Android
//     ("no safe place found to store log state"), and TS_LOGS_DIR is ignored
//     unless the directory already exists.
//   - SetInterfaces(table) from Java's NetworkInterface, because Go's
//     net.Interfaces() is blocked on Android 11+ (interfaces.go).
//   - Join(authKey, hostname, stateDir, timeoutSeconds) returns at once; the
//     node comes up on its own goroutine and Status() reports progress.
//   - StartForwarder(target) opens the token-guarded 127.0.0.1 listener that
//     bridges to target through the tailnet (forwarder.go).
//   - Stop() ends the node and the forwarder; the identity in stateDir stays.
//
// ⚠️ Nothing here may block a caller for long: the Kotlin side calls from
// worker threads only, but a status read still must not wait on a join. mu is
// held only to copy or swap state, never across tsnet calls.
package tailnet

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gsasha/portico-app/gocore/tsnet"
)

// node is what Join keeps: the running tailnet identity.
type node interface {
	Dial(ctx context.Context, network, addr string) (net.Conn, error)
	Stop() error
	// LinkChanged tells the node the phone's network changed, so it rebinds and probes again at once.
	LinkChanged()
	IPv4() string
	// Peers is the netmap's peer list with each peer's routes (RouteCheck).
	Peers(ctx context.Context) ([]tsnet.PeerRoutes, bool, error)
}

// startNode brings a node up (tests replace it). It blocks until the node is
// up, ctx ends, or tsnet fails.
var startNode = func(ctx context.Context, cfg tsnet.Config) (node, error) {
	c, err := tsnet.Start(ctx, cfg)
	if err != nil {
		return nil, err
	}
	return c, nil
}

// controlURL is empty in the app (the real coordination server); only the
// hermetic tests point it at a local testcontrol.
var controlURL = ""

// forwardDialTimeout bounds one tailnet dial behind the forwarder.
var forwardDialTimeout = 20 * time.Second

const (
	stateIdle    = "idle"
	stateJoining = "joining"
	stateUp      = "up"
	stateFailed  = "failed"

	// Failure kinds, the second field of a "failed" status. The Kotlin side
	// (:core GoStatus) maps each to a Setup line.
	FailNeedsLogin    = "needs_login"    // tsnet wants a browser login: the key was refused or the identity is dead
	FailKeyRejected   = "key_rejected"   // the control server refused the login (with a key: spent, expired, revoked or wrong)
	FailNeedsApproval = "needs_approval" // the node waited for an admin's approval until the deadline
	FailTimeout       = "timeout"        // the control plane did not answer in time (no network, blocked)
	FailNotPrepared   = "not_prepared"   // Prepare was not called (it would have panicked)
	FailPanic         = "panic"          // a Go panic, contained
	FailError         = "error"          // anything else
)

var (
	mu            sync.Mutex
	prepared      bool
	state         = stateIdle
	failKind      string
	failMsg       string
	needsApproval bool
	ipv4          string
	cur           node
	fwd           *forwarder
	gen           int
	cancelJoin    context.CancelFunc

	// changeCh is closed (and replaced) whenever the status line may have changed, so
	// WaitChange wakes at once instead of the caller polling Status.
	changeCh = make(chan struct{})

	// phases is the latest join's timeline from tsnet's IPN bus (DEV-694), "name+123ms".
	phases []string
)

// notifyLocked wakes every WaitChange. Call it with mu held, after any change to the
// fields Status reads.
func notifyLocked() {
	close(changeCh)
	changeCh = make(chan struct{})
}

// No Touch(): gobind generates a static touch() on every bound class, which
// loads the native library and the Go runtime. The app calls Tailnet.touch()
// on a worker thread, after go.Seq.setContext (an exported Touch here would
// collide with it and fail the bind).

// Prepare creates <baseDir>/logs, /tmp and /home and points TS_LOGS_DIR,
// TMPDIR and HOME at them. Call it once, before Join.
func Prepare(baseDir string) (err error) {
	defer containPanic(&err)
	if baseDir == "" {
		return errors.New("tailnet: Prepare needs the app's private directory")
	}
	for _, d := range []struct{ env, sub string }{
		{"TS_LOGS_DIR", "logs"},
		{"TMPDIR", "tmp"},
		{"HOME", "home"},
	} {
		p := filepath.Join(baseDir, d.sub)
		if err := os.MkdirAll(p, 0o700); err != nil {
			return fmt.Errorf("tailnet: creating %s: %w", d.env, err)
		}
		if err := os.Setenv(d.env, p); err != nil {
			return fmt.Errorf("tailnet: setting %s: %w", d.env, err)
		}
	}
	mu.Lock()
	prepared = true
	mu.Unlock()
	return nil
}

// Join starts bringing the node up and returns at once. authKey is spent on
// the first join of a fresh identity; afterwards the node key stored in
// stateDir authenticates and authKey may be "". Poll Status() for the result.
// An error here means nothing was started (bad arguments, a join already
// running).
func Join(authKey, hostname, stateDir string, timeoutSeconds int) (err error) {
	defer containPanic(&err)
	if hostname == "" || stateDir == "" {
		return errors.New("tailnet: Join needs a hostname and a state directory")
	}
	timeout := time.Duration(clamp(timeoutSeconds, 10, 600)) * time.Second

	mu.Lock()
	if state == stateJoining {
		mu.Unlock()
		return errors.New("tailnet: a join is already running")
	}
	if !prepared {
		setFailedLocked(FailNotPrepared, "Prepare was not called before Join")
		mu.Unlock()
		return nil
	}
	old, oldFwd := detachLocked()
	gen++
	g := gen
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	cancelJoin = cancel
	state, failKind, failMsg, needsApproval, ipv4 = stateJoining, "", "", false, ""
	phases = nil
	notifyLocked()
	mu.Unlock()
	// Off the calling (Java) thread: tsnet's Close can take seconds. The state
	// is already swapped, so nothing can reach the old node meanwhile.
	go closeQuietly(old, oldFwd)

	go runJoin(ctx, cancel, g, authKey, hostname, stateDir)
	return nil
}

func runJoin(ctx context.Context, cancel context.CancelFunc, g int, authKey, hostname, stateDir string) {
	defer cancel()
	var loginWanted sync.Once
	var sawLogin bool
	var loginMu sync.Mutex
	defer func() {
		if r := recover(); r != nil {
			logLine("PANIC in join: %v\n%s", r, debug.Stack())
			finishJoin(g, nil, FailPanic, fmt.Sprintf("go panic: %v", r), authKey)
		}
	}()
	cfg := tsnet.Config{
		StateDir:   stateDir,
		Hostname:   hostname,
		AuthKey:    authKey,
		ControlURL: controlURL,
		Logf:       logLine,
		OnPhase: func(phase string, afterMs int64) {
			mu.Lock()
			if g == gen && len(phases) < maxPhases {
				phases = append(phases, fmt.Sprintf("%s+%dms", phase, afterMs))
			}
			mu.Unlock()
		},
		OnState: func(backend, authURL string) {
			if backend == "NeedsMachineAuth" {
				mu.Lock()
				if g == gen && !needsApproval {
					needsApproval = true
					notifyLocked()
				}
				mu.Unlock()
			}
			if authURL != "" {
				// No amount of waiting resolves an interactive login on a
				// phone: the key was refused (spent, expired, revoked) or the
				// stored identity is gone. Fail now, not at the deadline.
				loginWanted.Do(func() {
					loginMu.Lock()
					sawLogin = true
					loginMu.Unlock()
					logLine("tailnet: the node wants an interactive login; giving up now")
					cancel()
				})
			}
		},
	}
	// No key: come back as the identity stored in stateDir. A missing or
	// deleted identity then surfaces as needs_login, never as a hang.
	cfg.ReuseIdentity = authKey == ""
	n, err := startNode(ctx, cfg)
	if err == nil {
		finishJoin(g, n, "", "", authKey)
		return
	}
	loginMu.Lock()
	login := sawLogin
	loginMu.Unlock()
	mu.Lock()
	approval := needsApproval
	mu.Unlock()
	switch {
	case strings.Contains(err.Error(), "tsnet.Up: backend: ") && authKey != "":
		// The control server answered the login with an error (testcontrol:
		// "invalid authkey"). Waiting will not change its mind.
		finishJoin(g, nil, FailKeyRejected, "the tailnet refused this key: "+backendMsg(err.Error()), authKey)
	case login || strings.Contains(err.Error(), "tsnet.Up: backend: "):
		finishJoin(g, nil, FailNeedsLogin, "the tailnet refused this key or no longer knows this phone", authKey)
	case errors.Is(ctx.Err(), context.DeadlineExceeded) && approval:
		finishJoin(g, nil, FailNeedsApproval, "the node is waiting for approval in the tailnet admin console", authKey)
	case errors.Is(ctx.Err(), context.DeadlineExceeded):
		finishJoin(g, nil, FailTimeout, "the tailnet did not answer in time", authKey)
	default:
		finishJoin(g, nil, FailError, err.Error(), authKey)
	}
}

// finishJoin records a join's result unless Stop or a newer Join superseded it.
func finishJoin(g int, n node, kind, msg, authKey string) {
	mu.Lock()
	if g != gen {
		mu.Unlock()
		if n != nil {
			_ = n.Stop()
		}
		return
	}
	cancelJoin = nil
	if n != nil {
		cur, state, ipv4 = n, stateUp, n.IPv4()
		failKind, failMsg = "", ""
		notifyLocked()
		mu.Unlock()
		logLine("tailnet: up (%s)", n.IPv4())
		return
	}
	setFailedLocked(kind, redactKey(msg, authKey))
	mu.Unlock()
}

func setFailedLocked(kind, msg string) {
	state, failKind, failMsg, ipv4 = stateFailed, kind, oneLine(msg), ""
	notifyLocked()
}

// Status is one tab-separated line:
//
//	idle
//	joining                     (joining\tneeds_approval while an admin must approve)
//	up\t<tailnet IPv4>
//	failed\t<kind>\t<message>
//
// :core's GoStatus parses it. The message never contains the auth key.
func Status() string {
	mu.Lock()
	defer mu.Unlock()
	return statusLocked()
}

// WaitChange blocks until Status differs from prev, or timeoutMillis passes, and
// returns the current Status (DEV-694). It replaces the caller's poll-and-sleep: the
// Kotlin side used to look every 250 ms, so a node that came up was noticed up to
// 250 ms late. It never blocks longer than the timeout (clamped to 60 s) and holds mu
// only to read.
func WaitChange(prev string, timeoutMillis int) string {
	deadline := time.Now().Add(time.Duration(clamp(timeoutMillis, 0, 60_000)) * time.Millisecond)
	for {
		mu.Lock()
		cur, ch := statusLocked(), changeCh
		mu.Unlock()
		if cur != prev {
			return cur
		}
		left := time.Until(deadline)
		if left <= 0 {
			return cur
		}
		t := time.NewTimer(left)
		select {
		case <-ch:
			t.Stop()
		case <-t.C:
			return cur
		}
	}
}

// maxPhases bounds the join timeline (it has about five entries).
const maxPhases = 12

// JoinPhases is the latest join's timeline from tsnet's own IPN bus, for the Journal
// (DEV-694): e.g. "starting+210ms netmap+1450ms running+1640ms", milliseconds since
// the node's start began. Empty before any join. Names and numbers only.
func JoinPhases() string {
	mu.Lock()
	defer mu.Unlock()
	return strings.Join(phases, " ")
}

func statusLocked() string {
	switch state {
	case stateJoining:
		if needsApproval {
			return stateJoining + "\t" + FailNeedsApproval
		}
		return stateJoining
	case stateUp:
		return stateUp + "\t" + ipv4
	case stateFailed:
		return stateFailed + "\t" + failKind + "\t" + failMsg
	default:
		return stateIdle
	}
}

// StartForwarder opens (or reuses, while its listener is alive) the 127.0.0.1 forwarder to target
// ("host:port", dialled through the tailnet) and returns "<port>:<token hex>".
// The caller must send the 32 token bytes first on every connection.
func StartForwarder(target string) (res string, err error) {
	defer containPanic(&err)
	if err := checkTarget(target); err != nil {
		return "", err
	}
	mu.Lock()
	defer mu.Unlock()
	if state != stateUp || cur == nil {
		return "", errors.New("tailnet: the node is not up")
	}
	// Reuse only a forwarder whose listener is still accepting: one whose
	// serve() loop ended (the listener failed underneath) is rebuilt, never
	// handed out as a port where nothing listens.
	if fwd != nil && fwd.target == target && fwd.alive() {
		return fmt.Sprintf("%d:%s", fwd.port(), fwd.tokenHex()), nil
	}
	if fwd != nil {
		old := fwd
		fwd = nil
		go old.close()
	}
	f, err := newForwarder(target, cur.Dial, forwardDialTimeout)
	if err != nil {
		return "", err
	}
	fwd = f
	return fmt.Sprintf("%d:%s", f.port(), f.tokenHex()), nil
}

// LinkChanged tells the running node, if any, that the phone's network changed (call it after
// SetInterfaces and SetNetwork). The node would otherwise notice on its own schedule, and until
// then it keeps sending from the old network. A no-op with no node up.
func LinkChanged() {
	mu.Lock()
	n := cur
	mu.Unlock()
	if n != nil {
		n.LinkChanged()
	}
}

// StopForwarder closes the forwarder and every connection through it.
func StopForwarder() {
	mu.Lock()
	f := fwd
	fwd = nil
	mu.Unlock()
	if f != nil {
		f.close()
	}
}

// Stop ends any join in progress, the forwarder and the node. The identity
// in stateDir is kept, so a later Join("", ...) comes back as the same node.
// The node and forwarder are closed in the background, so the call returns
// at once; Status() is "idle" immediately.
func Stop() {
	mu.Lock()
	old, oldFwd := detachLocked()
	gen++
	state, failKind, failMsg, needsApproval, ipv4 = stateIdle, "", "", false, ""
	notifyLocked()
	mu.Unlock()
	// Off the calling (Java) thread: tsnet's Close can take seconds. The state
	// is already swapped, so nothing can reach the old node meanwhile.
	go closeQuietly(old, oldFwd)
}

// detachLocked takes the node and forwarder out of the package state and
// cancels a running join; the caller closes them outside mu.
func detachLocked() (node, *forwarder) {
	if cancelJoin != nil {
		cancelJoin()
		cancelJoin = nil
	}
	n, f := cur, fwd
	cur, fwd = nil, nil
	return n, f
}

// closeQuietly runs in its own goroutine, where an unrecovered panic from the
// forwarder or from tsnet's Close would take the whole process down. Each step
// is guarded separately so a panic in one still lets the other run.
func closeQuietly(n node, f *forwarder) {
	if f != nil {
		guardedClose("forwarder", f.close)
	}
	if n != nil {
		guardedClose("node", func() { _ = n.Stop() })
	}
}

func guardedClose(what string, fn func()) {
	defer func() {
		if r := recover(); r != nil {
			logLine("tailnet: panic closing %s: %v", what, r)
		}
	}()
	fn()
}

// checkTarget accepts host:port with a numeric port 1-65535 and a non-empty
// host. The app has already validated it (:core TailnetTarget); this is the
// backstop that keeps a malformed string from reaching tsnet.
func checkTarget(target string) error {
	host, port, err := net.SplitHostPort(target)
	if err != nil || host == "" {
		return errors.New("tailnet: forwarder target must be host:port")
	}
	p, err := strconv.Atoi(port)
	if err != nil || p < 1 || p > 65535 {
		return errors.New("tailnet: forwarder target has an invalid port")
	}
	return nil
}

// ---- logging ---------------------------------------------------------------

const logRingMax = 400

var (
	logMu   sync.Mutex
	logRing []string
	keyRe   = regexp.MustCompile(`tskey-[A-Za-z0-9_-]+`)
)

// logLine keeps the tail of tsnet's own logging for a diagnostics view. Any
// tskey-… token is masked on the way in: tsnet does not log its key, but this
// ring may be shown on screen, so it never relies on that.
func logLine(format string, args ...any) {
	line := keyRe.ReplaceAllString(fmt.Sprintf(format, args...), "tskey-<redacted>")
	logMu.Lock()
	defer logMu.Unlock()
	logRing = append(logRing, line)
	if len(logRing) > logRingMax {
		logRing = logRing[len(logRing)-logRingMax:]
	}
}

// Logs returns the captured tsnet log tail, newest last.
func Logs() string {
	logMu.Lock()
	defer logMu.Unlock()
	return strings.Join(logRing, "\n")
}

// ClearLogs empties the tail, so a retry is read on its own.
func ClearLogs() {
	logMu.Lock()
	defer logMu.Unlock()
	logRing = nil
}

// ---- helpers ---------------------------------------------------------------

// containPanic turns a panic in an exported function into an error. A Go
// panic on a gomobile call otherwise aborts the process with no Java
// exception (memory: gomobile-panic-read-own-logcat).
func containPanic(err *error) {
	if r := recover(); r != nil {
		logLine("PANIC contained: %v\n%s", r, debug.Stack())
		*err = fmt.Errorf("go panic: %v", r)
	}
}

func redactKey(msg, authKey string) string {
	if authKey != "" {
		msg = strings.ReplaceAll(msg, authKey, "<key>")
	}
	return keyRe.ReplaceAllString(msg, "tskey-<redacted>")
}

// backendMsg is the control server's own words after "tsnet.Up: backend: ".
func backendMsg(e string) string {
	if _, after, ok := strings.Cut(e, "tsnet.Up: backend: "); ok {
		return after
	}
	return e
}

func oneLine(s string) string {
	s = strings.Join(strings.Fields(s), " ")
	if len(s) > 300 {
		s = s[:300] + "…"
	}
	return s
}

func clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
