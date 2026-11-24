// Package rtsp is Portico's RTSP/H.264 video client (DEV-713 S1b): it pulls one
// camera stream from go2rtc over a dialled TCP connection (in the app, the
// tailnet node's Dial) and hands H.264 NAL units to a Sink for MediaCodec.
//
// It is the old intercom app's client (tsmobile/rtspvideo.go, proven on a phone
// since 0.30.1-alpha) with what that one lacked:
//   - a WATCHDOG: no NAL unit for Config.Watchdog (5 s) ends the session;
//   - a CLEAN STOP: Stop closes the connection (after a best-effort TEARDOWN)
//     and every goroutine exits, none left behind;
//   - a BOUNDED queue to the sink: when the sink lags, non-reference pictures are
//     dropped, and when that is not enough the queue empties itself and waits
//     for the next IDR; a NAL unit over MaxNAL is a lost picture;
//   - nothing that names the stream: no error, state word or stat carries the URL,
//     the host or a credential.
//
// One Session is ONE connection attempt. It never reconnects by itself: when it
// ends it says why (OnState "ended:<kind>") and the caller (the app's RTSP live
// session, in Kotlin) decides about the 2 s / 5 s / 15 s backoff.
package rtsp

import (
	"context"
	"errors"
	"net"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Sink receives what a session pulled. Every method is called from ONE goroutine
// of the session, in order, and never after Stop has returned (a call already in
// flight may finish). The slices are freshly allocated and never reused.
type Sink interface {
	// OnFormat delivers the parameter sets (Annex-B, start code included) and the
	// picture size, before any picture and again when the camera changes them.
	OnFormat(sps, pps []byte, width, height int)
	// OnNal delivers one Annex-B NAL unit (start code included); key is an IDR slice.
	OnNal(nal []byte, ptsUs int64, key bool)
	// OnState: "connecting", "live", and, once, "ended:<kind>" when the connection
	// ends on its own (never after Stop). The kinds: dial, rtsp, protocol, stalled, lost.
	OnState(state string)
}

// Dial is the dialler: the tailnet node's Dial in the app, a loopback dial in the tests.
type Dial func(ctx context.Context, network, addr string) (net.Conn, error)

// Config holds every timing and size, so the tests can run it in milliseconds.
type Config struct {
	DialTimeout    time.Duration // the whole dial
	RequestTimeout time.Duration // one RTSP request and its answer
	Watchdog       time.Duration // no NAL unit for this long after PLAY ends the session
	Keepalive      time.Duration // an OPTIONS this often keeps go2rtc's 60 s session timeout away
	ReadTimeout    time.Duration // backstop: one blocked read
	TeardownWait   time.Duration // best-effort TEARDOWN write deadline on Stop
	QueueItems     int           // units waiting for a slow sink
	QueueBytes     int
}

// DefaultConfig is what the app uses.
func DefaultConfig() Config {
	return Config{
		DialTimeout:    12 * time.Second,
		RequestTimeout: 6 * time.Second,
		Watchdog:       5 * time.Second,
		Keepalive:      20 * time.Second,
		ReadTimeout:    20 * time.Second,
		TeardownWait:   300 * time.Millisecond,
		QueueItems:     90,
		QueueBytes:     4 << 20,
	}
}

// Session is one pull. Safe for use from any goroutine.
type Session struct {
	cfg  Config
	dial Dial
	sink Sink

	host, base, user, pass string

	ctx    context.Context
	cancel context.CancelFunc

	mu       sync.Mutex
	conn     net.Conn
	rtspSess string
	rtspBase string

	stopped  atomic.Bool
	finished atomic.Bool
	endKind  atomic.Value // string: why it ended on its own
	lastNAL  atomic.Int64 // unix nanoseconds of the last NAL unit
	nals     atomic.Int64
	bytes    atomic.Int64
	q        *queue
	wg       sync.WaitGroup
}

var errBadURL = errors.New("rtsp: not a usable rtsp address")

// Start validates the address and begins a pull in the background; it returns at
// once. The address may carry user:password (Basic and Digest are answered), but
// the app's own addresses never do. The error never contains the address.
func Start(rawURL string, dial Dial, sink Sink, cfg Config) (*Session, error) {
	if sink == nil || dial == nil {
		return nil, errors.New("rtsp: no sink or dialler")
	}
	host, base, user, pass, err := parseURL(rawURL)
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	s := &Session{cfg: cfg, dial: dial, sink: sink, host: host, base: base, user: user, pass: pass, ctx: ctx, cancel: cancel}
	s.q = newQueue(cfg.QueueItems, cfg.QueueBytes)
	s.lastNAL.Store(time.Now().UnixNano())
	s.wg.Add(2)
	go s.deliver()
	go s.run()
	return s, nil
}

// parseURL accepts rtsp://[user[:password]@]host[:port]/path and nothing else; the
// Kotlin side has already allowed only rtsp://host[:port]/name, this is the backstop.
func parseURL(raw string) (host, base, user, pass string, err error) {
	if len(raw) == 0 || len(raw) > 512 || strings.ContainsAny(raw, " \t\r\n\x00") {
		return "", "", "", "", errBadURL
	}
	u, perr := url.Parse(raw)
	if perr != nil || u.Scheme != "rtsp" || u.Hostname() == "" || u.Fragment != "" || u.Opaque != "" {
		return "", "", "", "", errBadURL
	}
	host = u.Host
	if u.Port() == "" {
		host = net.JoinHostPort(u.Hostname(), "554")
	}
	if u.User != nil {
		user = u.User.Username()
		pass, _ = u.User.Password()
		// The credential is stripped from the request line: some servers 400 it, and every one logs it.
		stripped := *u
		stripped.User = nil
		base = stripped.String()
	} else {
		base = u.String()
	}
	return host, base, user, pass, nil
}

// Stop ends the session: the connection is torn down (best effort) and closed,
// the dial in flight is cancelled, and every goroutine exits. Idempotent, safe
// from any thread, and it never waits for the sink.
func (s *Session) Stop() {
	if s == nil {
		return
	}
	if !s.stopped.CompareAndSwap(false, true) {
		return
	}
	s.cancel()
	s.closeConn(true)
	s.q.close()
}

// Wait blocks until every goroutine of the session has exited, or the timeout (for tests and leak checks).
func (s *Session) Wait(d time.Duration) bool {
	done := make(chan struct{})
	go func() { s.wg.Wait(); close(done) }()
	select {
	case <-done:
		return true
	case <-time.After(d):
		return false
	}
}

// Stats is "N units, K KB, D dropped, R resyncs": counts only, never a name.
func (s *Session) Stats() string {
	if s == nil {
		return "0 units"
	}
	d, r := s.q.counts()
	return fmtCounts(s.nals.Load(), s.bytes.Load(), d, r)
}

// end records why the session ended on its own, once, and closes everything.
func (s *Session) end(kind string) {
	if s.stopped.Load() || !s.finished.CompareAndSwap(false, true) {
		return
	}
	s.endKind.Store(kind)
	s.cancel()
	s.closeConn(false)
	s.q.close()
}

func (s *Session) closeConn(withTeardown bool) {
	s.mu.Lock()
	c, sess, base := s.conn, s.rtspSess, s.rtspBase
	s.conn = nil
	s.mu.Unlock()
	if c == nil {
		return
	}
	if withTeardown {
		teardown(c, base, sess, s.cfg.TeardownWait)
	}
	_ = c.Close()
}

// run connects, negotiates, and reads until the connection ends.
func (s *Session) run() {
	defer s.wg.Done()
	defer func() {
		if r := recover(); r != nil {
			s.end("protocol")
		}
	}()
	s.q.pushState("connecting")

	dctx, dcancel := context.WithTimeout(s.ctx, s.cfg.DialTimeout)
	c, err := s.dial(dctx, "tcp", s.host)
	dcancel()
	if err != nil {
		s.end("dial")
		return
	}
	s.mu.Lock()
	if s.stopped.Load() || s.finished.Load() {
		s.mu.Unlock()
		_ = c.Close()
		return
	}
	s.conn = c
	s.mu.Unlock()

	cl := newClient(c, s.base, s.user, s.pass, s.cfg.RequestTimeout)
	sdp, err := cl.describe()
	if err != nil {
		s.handshakeFailed(err)
		return
	}
	track, err := parseVideoTrack(sdp, cl.base)
	if err != nil {
		s.end("rtsp")
		return
	}
	if err = cl.setup(track.control); err != nil {
		s.handshakeFailed(err)
		return
	}
	s.mu.Lock()
	s.rtspSess, s.rtspBase = cl.session, cl.base
	s.mu.Unlock()
	if err = cl.play(); err != nil {
		s.handshakeFailed(err)
		return
	}

	rd := &reader{s: s, cl: cl, pt: track.payloadType}
	// The parameter sets from the SDP come BEFORE any picture, so the decoder can
	// configure at once; they also arrive in-band with every IDR.
	if len(track.sps) > 0 && len(track.pps) > 0 {
		rd.setSets(track.sps, track.pps)
	}
	s.lastNAL.Store(time.Now().UnixNano())
	s.q.pushState("live")
	s.wg.Add(1)
	go s.watchdog()
	rd.loop()
}

func (s *Session) handshakeFailed(err error) {
	var se *statusError
	if errors.As(err, &se) {
		s.end("rtsp") // the server answered, and said no
		return
	}
	var ne net.Error
	if errors.As(err, &ne) || errors.Is(err, context.Canceled) {
		s.end("lost")
		return
	}
	s.end("protocol")
}

// watchdog ends a session whose NAL units stopped: a held socket that has gone quiet looks the same
// as a working one from the outside.
func (s *Session) watchdog() {
	defer s.wg.Done()
	step := s.cfg.Watchdog / 4
	if step < time.Millisecond {
		step = time.Millisecond
	}
	t := time.NewTicker(step)
	defer t.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-t.C:
			if time.Since(time.Unix(0, s.lastNAL.Load())) >= s.cfg.Watchdog {
				s.end("stalled")
				return
			}
		}
	}
}

// reader turns interleaved RTP into units for the queue.
type reader struct {
	s        *Session
	cl       *rtspClient
	pt       int
	dp       h264Depack
	sps, pps []byte // the sets last sent to the sink
	sps0     []byte // the latest in-band ones
	pps0     []byte
	sentFmt  bool
}

func (r *reader) setSets(sps, pps []byte) {
	if r.sentFmt && string(sps) == string(r.sps) && string(pps) == string(r.pps) {
		return
	}
	r.sps, r.pps = sps, pps
	r.sentFmt = true
	w, h := spsGeometry(sps)
	r.s.q.pushFormat(annexB(sps), annexB(pps), w, h)
}

func (r *reader) loop() {
	s := r.s
	lastKeepalive := time.Now()
	for {
		s.mu.Lock()
		c := s.conn
		s.mu.Unlock()
		if c == nil || s.stopped.Load() || s.finished.Load() {
			return
		}
		_ = c.SetReadDeadline(time.Now().Add(s.cfg.ReadTimeout))
		ch, pkt, isRTSP, err := r.cl.readInterleaved()
		if err != nil {
			if !s.stopped.Load() && !s.finished.Load() {
				var ne net.Error
				if errors.As(err, &ne) && ne.Timeout() {
					s.end("stalled")
				} else {
					s.end("lost")
				}
			}
			return
		}
		if isRTSP {
			continue // the answer to our own keepalive; it had to be consumed, it shares the socket
		}
		if ch != r.cl.rtpCh {
			continue // RTCP, or a track we did not ask for (the channel is the server's, from the SETUP reply)
		}
		nals, ptsUs, ok := r.dp.push(pkt, r.pt)
		if r.dp.lost {
			r.dp.lost = false
			s.q.loss()
		}
		if !ok {
			continue
		}
		for _, n := range nals {
			if len(n) == 0 {
				continue
			}
			s.lastNAL.Store(time.Now().UnixNano())
			switch nalType(n) {
			case nalSPS, nalPPS:
				// A camera that changes resolution (night mode on some) re-announces them in-band: forwarded when they change.
				if nalType(n) == nalSPS {
					r.sps0 = append([]byte(nil), n...)
				} else {
					r.pps0 = append([]byte(nil), n...)
				}
				if len(r.sps0) > 0 && len(r.pps0) > 0 {
					r.setSets(r.sps0, r.pps0)
				}
			case nalSlice, nalIDR:
				if nalType(n) == nalIDR && !r.sentFmt {
					s.q.loss() // an IDR before any parameter set cannot be decoded
					continue
				}
				s.nals.Add(1)
				s.bytes.Add(int64(len(n)))
				s.q.pushNal(annexB(n), ptsUs, nalType(n) == nalIDR, nalRef(n) != 0)
			}
		}
		// go2rtc's RTSP timeout is 60 s and a pull-only client sends nothing: without this a tile watched
		// for a minute is dropped by the server. OPTIONS is the cheapest legal keepalive.
		if time.Since(lastKeepalive) > s.cfg.Keepalive {
			lastKeepalive = time.Now()
			if err := r.cl.keepalive(); err != nil {
				s.end("lost")
				return
			}
		}
	}
}

// deliver is the only goroutine that calls the sink.
func (s *Session) deliver() {
	defer s.wg.Done()
	for {
		it, ok := s.q.pop()
		if !ok {
			break
		}
		if s.stopped.Load() {
			return
		}
		func() {
			defer func() { _ = recover() }() // a sink that panics must not take the process down
			switch it.kind {
			case kindFormat:
				s.sink.OnFormat(it.sps, it.pps, it.w, it.h)
			case kindNal:
				s.sink.OnNal(it.nal, it.pts, it.key)
			case kindState:
				s.sink.OnState(it.state)
			}
		}()
	}
	if k, _ := s.endKind.Load().(string); k != "" && !s.stopped.Load() {
		func() {
			defer func() { _ = recover() }()
			s.sink.OnState("ended:" + k)
		}()
	}
}
