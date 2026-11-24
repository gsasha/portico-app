package rtsp

import (
	"bufio"
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"
)

// ---- pure pieces, ported from the old intercom app's tests ------------------------------------

// The expected geometries are NOT invented: each was decoded on 2026-09-14 from the sprop-parameter-sets the LIVE go2rtc
// put in its own SDP, by an independent decoder written in Python.
var spsCases = []struct {
	name  string
	sprop string
	w, h  int
}{
	{"gate_intercom_sub", "Z0IADekCwS0IAAAfSAAGHBAgAA==", 352, 288},
	{"gate_intercom", "Z0IAH+kAoAt0IAAAfSAAGHBAgAA=", 1280, 720},
	{"entrance_sub", "Z01AHqaAoD2QAA==", 640, 480},
	{"doorbell_sub", "Z01AH42NQHgLf+AtwEBAUAAAPoAACcQJ2giEag==", 960, 720},
	{"doorbell_package", "Z01AKI2NQDIBL/4C3AQEBQAAAwPoAAAPoJ2giEag", 1600, 1200},
}

func TestSPSGeometry(t *testing.T) {
	for _, c := range spsCases {
		sps := b64(c.sprop)
		if len(sps) == 0 {
			t.Fatalf("%s: sprop did not decode at all", c.name)
		}
		w, h := spsGeometry(sps)
		if w != c.w || h != c.h {
			t.Errorf("%s: got %dx%d, want %dx%d", c.name, w, h, c.w, c.h)
		}
	}
	if w, h := spsGeometry([]byte{0x67, 0x42}); w != 0 || h != 0 {
		t.Errorf("a truncated SPS gave %dx%d", w, h)
	}
	if w, h := spsGeometry(nil); w != 0 || h != 0 {
		t.Errorf("a nil SPS gave %dx%d", w, h)
	}
}

func TestB64BothPaddings(t *testing.T) {
	if len(b64("Z01AKI2NQDIBL/4C3AQEBQAAAwPoAAAPoJ2giEag")) == 0 {
		t.Error("unpadded base64 did not decode")
	}
	if len(b64("Z0IADekCwS0IAAAfSAAGHBAgAA==")) == 0 {
		t.Error("padded base64 did not decode")
	}
	if b64("!!!not base64!!!") != nil {
		t.Error("garbage decoded to something")
	}
}

func TestSpropSetsBothSeparatorStyles(t *testing.T) {
	gate := "a=fmtp:96 packetization-mode=1;profile-level-id=42000D;sprop-parameter-sets=Z0IADekCwS0IAAAfSAAGHBAgAA==,aMqPIAA="
	bell := "a=fmtp:96 profile-level-id=4d401f; packetization-mode=1; sprop-parameter-sets=Z01AH42NQHgLf+AtwEBAUAAAPoAACcQJ2giEag==,aO44gA=="
	for name, line := range map[string]string{"no spaces (gate)": gate, "spaces (doorbell)": bell} {
		sps, pps := spropSets(line)
		if len(sps) == 0 || len(pps) == 0 {
			t.Errorf("%s: sps=%d pps=%d, wanted both", name, len(sps), len(pps))
			continue
		}
		if nalType(sps) != nalSPS || nalType(pps) != nalPPS {
			t.Errorf("%s: types %d,%d", name, nalType(sps), nalType(pps))
		}
	}
}

// Three of the four cameras carry audio as well: the video section must be the one picked.
func TestParseVideoTrackPicksVideoNotAudio(t *testing.T) {
	sdp := "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n" +
		"a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z0IADekCwS0IAAAfSAAGHBAgAA==,aMqPIAA=\r\na=control:trackID=0\r\n" +
		"m=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/48000\r\na=control:trackID=1\r\n"
	tr, err := parseVideoTrack(sdp, "rtsp://h:8554/doorbell_sub")
	if err != nil {
		t.Fatal(err)
	}
	if tr.payloadType != 96 {
		t.Errorf("payload type %d, want 96", tr.payloadType)
	}
	if want := "rtsp://h:8554/doorbell_sub/trackID=0"; tr.control != want {
		t.Errorf("control %q, want %q", tr.control, want)
	}
	if len(tr.sps) == 0 || len(tr.pps) == 0 {
		t.Error("parameter sets lost")
	}
	if _, err := parseVideoTrack("m=audio 0 RTP/AVP 97\r\na=control:trackID=0\r\n", "rtsp://x/y"); err == nil {
		t.Error("an audio-only stream was accepted as video")
	}
	abs, err := parseVideoTrack("m=video 0 RTP/AVP 96\r\na=control:rtsp://elsewhere/x\r\n", "rtsp://h/s")
	if err != nil || abs.control != "rtsp://elsewhere/x" {
		t.Errorf("an absolute control url was rewritten: %q %v", abs.control, err)
	}
}

func rtp(pt int, ts uint32, seq uint16, payload []byte) []byte {
	p := make([]byte, 12+len(payload))
	p[0] = 0x80
	p[1] = byte(pt)
	binary.BigEndian.PutUint16(p[2:4], seq)
	binary.BigEndian.PutUint32(p[4:8], ts)
	copy(p[12:], payload)
	return p
}

func TestDepackSingleSTAPAndFU(t *testing.T) {
	d := &h264Depack{}
	nals, _, ok := d.push(rtp(96, 900, 1, []byte{0x61, 0xaa, 0xbb}), 96)
	if !ok || len(nals) != 1 || nalType(nals[0]) != 1 {
		t.Fatalf("single NAL: ok=%v %v", ok, nals)
	}
	if _, _, ok := d.push(rtp(97, 900, 1, []byte{0x61, 0xaa}), 96); ok {
		t.Error("a packet from the audio track was accepted as video")
	}
	stap := []byte{24}
	for _, n := range [][]byte{{0x67, 0x42, 0x00, 0x0d}, {0x68, 0xca}} {
		var l [2]byte
		binary.BigEndian.PutUint16(l[:], uint16(len(n)))
		stap = append(stap, l[0], l[1])
		stap = append(stap, n...)
	}
	nals, _, ok = d.push(rtp(96, 900, 2, stap), 96)
	if !ok || len(nals) != 2 || nalType(nals[0]) != 7 || nalType(nals[1]) != 8 {
		t.Fatalf("STAP-A: ok=%v n=%d", ok, len(nals))
	}
	// FU-A: an IDR in three fragments, nri 3
	if _, _, ok := d.push(rtp(96, 900, 3, []byte{0x7c, 0x85, 1, 2, 3}), 96); ok {
		t.Error("a NAL was emitted from the FIRST fragment")
	}
	if _, _, ok := d.push(rtp(96, 900, 4, []byte{0x7c, 0x05, 4, 5, 6}), 96); ok {
		t.Error("a NAL was emitted from a MIDDLE fragment")
	}
	nals, _, ok = d.push(rtp(96, 900, 5, []byte{0x7c, 0x45, 7, 8, 9}), 96)
	if !ok || len(nals) != 1 || nals[0][0] != 0x65 || len(nals[0]) != 10 {
		t.Fatalf("FU-A did not complete: ok=%v %v", ok, nals)
	}
	if nalRef(nals[0]) != 3 {
		t.Errorf("nri lost: %02x", nals[0][0])
	}
}

func TestDepackJoinedMidNALIsDroppedAndFlagged(t *testing.T) {
	d := &h264Depack{}
	if _, _, ok := d.push(rtp(96, 900, 1, []byte{0x7c, 0x05, 4, 5, 6}), 96); ok || !d.lost {
		t.Errorf("a fragment with no start: ok=%v lost=%v", ok, d.lost)
	}
	d.lost = false
	if _, _, ok := d.push(rtp(96, 900, 2, []byte{0x7c, 0x45, 7, 8, 9}), 96); ok || !d.lost {
		t.Error("a half NAL was emitted or not flagged")
	}
}

func TestDepackTimestampWrapAndExtension(t *testing.T) {
	d := &h264Depack{}
	_, first, _ := d.push(rtp(96, 0xFFFFFF00, 1, []byte{0x61, 1}), 96)
	_, after, _ := d.push(rtp(96, 0x00000100, 2, []byte{0x61, 1}), 96)
	if after <= first || d.tsWrap != 1 {
		t.Errorf("pts went backwards across the wrap: %d then %d (wraps %d)", first, after, d.tsWrap)
	}
	p := rtp(96, 900, 1, nil)
	p[0] |= 0x10
	p = append(p, 0xbe, 0xde, 0x00, 0x01, 0, 0, 0, 0, 0x61, 0xaa)
	nals, _, ok := d.push(p, 96)
	if !ok || len(nals) != 1 || nalType(nals[0]) != 1 {
		t.Fatalf("an RTP extension header desynchronised the payload: ok=%v", ok)
	}
}

func TestDepackCapsAnOversizeUnit(t *testing.T) {
	d := &h264Depack{}
	frag := make([]byte, 60000)
	frag[0], frag[1] = 0x7c, 0x85
	if _, _, ok := d.push(rtp(96, 1, 1, frag), 96); ok {
		t.Fatal("start fragment emitted")
	}
	mid := make([]byte, 60000)
	mid[0], mid[1] = 0x7c, 0x05
	seq := uint16(2)
	for i := 0; i < 40 && !d.lost; i++ {
		d.push(rtp(96, 1, seq, mid), 96)
		seq++
	}
	if !d.lost {
		t.Fatal("2 MB of fragments never tripped the cap")
	}
	if d.fuActive || len(d.fu) > MaxNAL {
		t.Errorf("the oversize unit is still being collected: active=%v len=%d", d.fuActive, len(d.fu))
	}
	end := []byte{0x7c, 0x45, 1}
	if _, _, ok := d.push(rtp(96, 1, seq, end), 96); ok {
		t.Error("the tail of a discarded unit was emitted")
	}
}

func TestAnnexB(t *testing.T) {
	got := annexB([]byte{0x67, 0x42})
	want := []byte{0, 0, 0, 1, 0x67, 0x42}
	if string(got) != string(want) {
		t.Fatalf("start code wrong: %v", got)
	}
}

func TestParseURL(t *testing.T) {
	ok := map[string][3]string{
		"rtsp://192.168.1.6:8554/gate_intercom":     {"192.168.1.6:8554", "rtsp://192.168.1.6:8554/gate_intercom", ""},
		"rtsp://go2rtc/gate":                     {"go2rtc:554", "rtsp://go2rtc/gate", ""},
		"rtsp://admin:s3cret@192.168.1.6:8554/gate": {"192.168.1.6:8554", "rtsp://192.168.1.6:8554/gate", "admin"},
	}
	for raw, w := range ok {
		host, base, user, _, err := parseURL(raw)
		if err != nil || host != w[0] || base != w[1] || user != w[2] {
			t.Errorf("%q: host=%q base=%q user=%q err=%v", raw, host, base, user, err)
		}
		if strings.Contains(base, "s3cret") || strings.Contains(base, "@") {
			t.Errorf("%q: the credential is in the request URL %q", raw, base)
		}
	}
	for _, bad := range []string{"", "http://192.168.1.6:8554/x", "rtsps://h/x", "rtsp://", "rtsp:///x", "rtsp://h/x#f", "rtsp://h/ x", "rtsp://h/x\n", "rtsp:h/x", strings.Repeat("a", 600)} {
		if _, _, _, _, err := parseURL(bad); err == nil {
			t.Errorf("%q was accepted", bad)
		} else if strings.Contains(err.Error(), "rtsp://h") {
			t.Errorf("the error names the address: %v", err)
		}
	}
}

// ---- the bounded queue ------------------------------------------------------------------------

func nalN(n int) []byte { return make([]byte, n) }

func drain(q *queue) []item {
	var out []item
	for {
		q.mu.Lock()
		empty := len(q.items) == 0
		q.mu.Unlock()
		if empty {
			return out
		}
		it, _ := q.pop()
		out = append(out, it)
	}
}

func TestQueueStartsAtAnIDR(t *testing.T) {
	q := newQueue(8, 1<<20)
	q.pushNal(nalN(10), 1, false, true)
	q.pushNal(nalN(10), 2, true, true)
	q.pushNal(nalN(10), 3, false, true)
	got := drain(q)
	if len(got) != 2 || !got[0].key || got[0].pts != 2 {
		t.Fatalf("got %d items, the first key=%v", len(got), len(got) > 0 && got[0].key)
	}
}

func TestQueueDropsNonReferenceWhenFullWithoutResync(t *testing.T) {
	q := newQueue(4, 1<<20)
	q.pushNal(nalN(10), 0, true, true)
	for i := 1; i < 4; i++ {
		q.pushNal(nalN(10), int64(i), false, true)
	}
	q.pushNal(nalN(10), 4, false, false) // full, non-reference: dropped
	q.pushNal(nalN(10), 5, false, false)
	d, r := q.counts()
	if d != 2 || r != 0 {
		t.Fatalf("dropped=%d resyncs=%d, want 2 and 0", d, r)
	}
	if n, _ := q.len(); n != 4 {
		t.Fatalf("len %d", n)
	}
	got := drain(q)
	if got[0].pts != 0 || got[3].pts != 3 {
		t.Fatal("the queued reference pictures were disturbed")
	}
}

func TestQueueResyncsWhenAReferenceUnitDoesNotFit(t *testing.T) {
	q := newQueue(4, 1<<20)
	q.pushFormat([]byte{1}, []byte{2}, 640, 480)
	q.pushNal(nalN(10), 0, true, true)
	q.pushNal(nalN(10), 1, false, true)
	q.pushNal(nalN(10), 2, false, true)
	q.pushNal(nalN(10), 3, false, true) // full: format + 3 pictures... this one is refused
	if _, r := q.counts(); r != 1 {
		t.Fatalf("resyncs %d, want 1", r)
	}
	q.pushNal(nalN(10), 4, false, true) // still waiting for an IDR
	q.pushNal(nalN(10), 5, true, true)
	q.pushNal(nalN(10), 6, false, true)
	got := drain(q)
	if got[0].kind != kindFormat {
		t.Fatal("the parameter sets did not survive the resync")
	}
	var pts []int64
	for _, it := range got[1:] {
		pts = append(pts, it.pts)
	}
	if len(pts) != 2 || pts[0] != 5 || pts[1] != 6 {
		t.Fatalf("after a resync the stream restarts at the IDR: %v", pts)
	}
}

func TestQueueNeverGrowsPastItsCaps(t *testing.T) {
	q := newQueue(20, 1000)
	for i := 0; i < 20000; i++ {
		q.pushNal(nalN(37), int64(i), i%25 == 0, i%3 != 0)
		n, b := q.len()
		if n > 20 || b > 1000 {
			t.Fatalf("grew to %d items, %d bytes", n, b)
		}
	}
}

func TestQueueCloseWakesTheConsumerAndDropsWhatIsLeft(t *testing.T) {
	q := newQueue(4, 100)
	done := make(chan bool)
	go func() { _, ok := q.pop(); done <- ok }()
	time.Sleep(20 * time.Millisecond)
	q.close()
	select {
	case ok := <-done:
		if ok {
			t.Fatal("pop returned an item from a closed queue")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("close did not wake the consumer")
	}
	q.pushNal(nalN(5), 1, true, true)
	if n, _ := q.len(); n != 0 {
		t.Fatal("a closed queue took an item")
	}
}

// ---- a fake go2rtc on loopback ----------------------------------------------------------------

type serverOpts struct {
	challenge   bool                 // answer the first DESCRIBE with 401 + a Basic challenge
	describeErr int                  // answer DESCRIBE with this status instead
	onPlay      func(w *frameWriter) // what to stream after PLAY
	closeAfter  bool                 // close right after PLAY
	transport   string               // the SETUP reply's Transport header value; default interleaved=0-1
	rtpChannel  byte                 // the channel the fake sends video on (must match transport)
}

type frameWriter struct {
	mu   sync.Mutex
	conn net.Conn
	seq  uint16
	ts   uint32
	ch   byte // the interleaved channel video goes out on
}

func (w *frameWriter) raw(b []byte) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	_, err := w.conn.Write(b)
	return err
}

func (w *frameWriter) packet(payload []byte, ts uint32) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.seq++
	pkt := rtp(96, ts, w.seq, payload)
	out := make([]byte, 4+len(pkt))
	out[0], out[1] = '$', w.ch
	binary.BigEndian.PutUint16(out[2:4], uint16(len(pkt)))
	copy(out[4:], pkt)
	_, err := w.conn.Write(out)
	return err
}

// single sends one whole NAL unit in one packet.
func (w *frameWriter) single(nal []byte) error {
	w.ts += 3600
	return w.packet(nal, w.ts)
}

// idr sends an IDR in two FU-A fragments, preceded by SPS+PPS in a STAP-A, as go2rtc does.
func (w *frameWriter) idr(sps, pps []byte) error {
	w.ts += 3600
	stap := []byte{24}
	for _, n := range [][]byte{sps, pps} {
		var l [2]byte
		binary.BigEndian.PutUint16(l[:], uint16(len(n)))
		stap = append(stap, l[0], l[1])
		stap = append(stap, n...)
	}
	if err := w.packet(stap, w.ts); err != nil {
		return err
	}
	if err := w.packet([]byte{0x7c, 0x85, 1, 2, 3, 4}, w.ts); err != nil {
		return err
	}
	return w.packet([]byte{0x7c, 0x45, 5, 6, 7, 8}, w.ts)
}

func (w *frameWriter) pRef() error   { return w.single([]byte{0x41, 9, 9, 9}) }
func (w *frameWriter) pNoRef() error { return w.single([]byte{0x01, 9, 9, 9}) }

type server struct {
	t        *testing.T
	ln       net.Listener
	opts     serverOpts
	mu       sync.Mutex
	requests []string
	gotAuth  []string
	teardown chan struct{}
	closed   chan struct{} // the client's connection ended (EOF or error on read)
	sps, pps []byte
}

func newServer(t *testing.T, o serverOpts) *server {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &server{t: t, ln: ln, opts: o, teardown: make(chan struct{}, 1), closed: make(chan struct{})}
	s.sps, s.pps = spropSets("a=fmtp:96 sprop-parameter-sets=Z0IADekCwS0IAAAfSAAGHBAgAA==,aMqPIAA=")
	go s.serve()
	t.Cleanup(func() { ln.Close() })
	return s
}

func (s *server) url(path string) string { return "rtsp://" + s.ln.Addr().String() + "/" + path }

func (s *server) requestLines() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.requests...)
}

func (s *server) count(method string) int {
	n := 0
	for _, l := range s.requestLines() {
		if strings.HasPrefix(l, method+" ") {
			n++
		}
	}
	return n
}

func (s *server) serve() {
	c, err := s.ln.Accept()
	if err != nil {
		return
	}
	defer close(s.closed)
	defer c.Close()
	w := &frameWriter{conn: c, ch: s.opts.rtpChannel}
	rd := bufio.NewReader(c)
	challenged := false
	for {
		line, err := rd.ReadString('\n')
		if err != nil {
			return
		}
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		hdr := map[string]string{}
		for {
			h, err := rd.ReadString('\n')
			if err != nil {
				return
			}
			h = strings.TrimSpace(h)
			if h == "" {
				break
			}
			if i := strings.Index(h, ":"); i > 0 {
				hdr[strings.ToLower(h[:i])] = strings.TrimSpace(h[i+1:])
			}
		}
		s.mu.Lock()
		s.requests = append(s.requests, line)
		if a := hdr["authorization"]; a != "" {
			s.gotAuth = append(s.gotAuth, a)
		}
		s.mu.Unlock()
		cseq := hdr["cseq"]
		method := strings.Fields(line)[0]
		reply := func(code int, extra, body string) {
			resp := fmt.Sprintf("RTSP/1.0 %d X\r\nCSeq: %s\r\n%s", code, cseq, extra)
			if body != "" {
				resp += fmt.Sprintf("Content-Length: %d\r\n", len(body))
			}
			resp += "\r\n" + body
			_ = w.raw([]byte(resp))
		}
		switch method {
		case "OPTIONS":
			reply(200, "Public: DESCRIBE, SETUP, PLAY\r\n", "")
		case "DESCRIBE":
			switch {
			case s.opts.describeErr != 0:
				reply(s.opts.describeErr, "", "")
			case s.opts.challenge && !challenged:
				challenged = true
				reply(401, "WWW-Authenticate: Basic realm=\"go2rtc\"\r\n", "")
			default:
				sdp := "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n" +
					"a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z0IADekCwS0IAAAfSAAGHBAgAA==,aMqPIAA=\r\na=control:trackID=0\r\n"
				reply(200, "Content-Type: application/sdp\r\n", sdp)
			}
		case "SETUP":
			tr := s.opts.transport
			if tr == "" {
				tr = "RTP/AVP/TCP;unicast;interleaved=0-1"
			}
			reply(200, "Session: abc123;timeout=60\r\nTransport: "+tr+"\r\n", "")
		case "PLAY":
			reply(200, "Session: abc123\r\n", "")
			if s.opts.closeAfter {
				return
			}
			if s.opts.onPlay != nil {
				go s.opts.onPlay(w)
			}
		case "TEARDOWN":
			select {
			case s.teardown <- struct{}{}:
			default:
			}
			return
		}
	}
}

// ---- a sink that records ----------------------------------------------------------------------

type rec struct {
	key bool
	pts int64
	b0  byte
}

type collect struct {
	mu      sync.Mutex
	order   []string // "fmt", "nal", "state:<s>"
	nals    []rec
	formats [][2]int
	states  []string
	gate    chan struct{} // when non-nil, OnNal waits on it
	calls   int
}

func (c *collect) OnFormat(sps, pps []byte, w, h int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.order = append(c.order, "fmt")
	c.formats = append(c.formats, [2]int{w, h})
}

func (c *collect) OnNal(nal []byte, pts int64, key bool) {
	c.mu.Lock()
	g := c.gate
	c.mu.Unlock()
	if g != nil {
		<-g
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(nal) < 5 || nal[3] != 1 {
		panic("not Annex-B")
	}
	c.calls++
	c.order = append(c.order, "nal")
	c.nals = append(c.nals, rec{key, pts, nal[4]})
}

func (c *collect) OnState(s string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.order = append(c.order, "state:"+s)
	c.states = append(c.states, s)
}

func (c *collect) snapshot() (nals []rec, states []string, order []string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]rec(nil), c.nals...), append([]string(nil), c.states...), append([]string(nil), c.order...)
}

func eventually(t *testing.T, d time.Duration, what string, f func() bool) {
	t.Helper()
	end := time.Now().Add(d)
	for time.Now().Before(end) {
		if f() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func loopbackDial(ctx context.Context, network, addr string) (net.Conn, error) {
	var d net.Dialer
	return d.DialContext(ctx, network, addr)
}

func testCfg() Config {
	return Config{
		DialTimeout: 2 * time.Second, RequestTimeout: 2 * time.Second, Watchdog: 300 * time.Millisecond,
		Keepalive: time.Hour, ReadTimeout: 5 * time.Second, TeardownWait: 200 * time.Millisecond, QueueItems: 90, QueueBytes: 4 << 20,
	}
}

func hasState(c *collect, s string) bool {
	_, states, _ := c.snapshot()
	for _, x := range states {
		if x == s {
			return true
		}
	}
	return false
}

// ---- the session against the fake server ------------------------------------------------------

func streamSome(w *frameWriter, sps, pps []byte) {
	_ = w.idr(sps, pps)
	for i := 0; i < 5; i++ {
		_ = w.pRef()
	}
}

func TestSessionDeliversFormatThenAnIDRFirst(t *testing.T) {
	srv := newServer(t, serverOpts{})
	srv.opts.onPlay = func(w *frameWriter) { streamSome(w, srv.sps, srv.pps) }
	sink := &collect{}
	s, err := Start(srv.url("gate_intercom_sub"), loopbackDial, sink, testCfg())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	eventually(t, 3*time.Second, "6 NAL units", func() bool { n, _, _ := sink.snapshot(); return len(n) >= 6 })
	nals, states, order := sink.snapshot()
	if len(sink.formats) == 0 || sink.formats[0] != [2]int{352, 288} {
		t.Fatalf("formats %v: the size comes from the SPS", sink.formats)
	}
	if order[0] != "state:connecting" {
		t.Errorf("first event %q", order[0])
	}
	firstFmt, firstNal := -1, -1
	for i, o := range order {
		if o == "fmt" && firstFmt < 0 {
			firstFmt = i
		}
		if o == "nal" && firstNal < 0 {
			firstNal = i
		}
	}
	if firstFmt < 0 || firstFmt > firstNal {
		t.Errorf("the parameter sets must precede the first picture: fmt@%d nal@%d", firstFmt, firstNal)
	}
	if !nals[0].key || nals[0].b0 != 0x65 {
		t.Errorf("the first unit is not an IDR: %+v", nals[0])
	}
	for i, n := range nals[1:] {
		if n.key || n.b0 != 0x41 {
			t.Errorf("unit %d: %+v", i+1, n)
		}
	}
	if len(states) < 2 || states[0] != "connecting" || states[1] != "live" {
		t.Errorf("states %v", states)
	}
	if !strings.Contains(s.Stats(), "units") {
		t.Errorf("stats %q", s.Stats())
	}
}

func TestStopTearsDownClosesAndLeaksNothing(t *testing.T) {
	before := runtime.NumGoroutine()
	srv := newServer(t, serverOpts{})
	srv.opts.onPlay = func(w *frameWriter) {
		_ = w.idr(srv.sps, srv.pps)
		for i := 0; i < 400; i++ {
			if w.pRef() != nil {
				return
			}
			time.Sleep(5 * time.Millisecond)
		}
	}
	sink := &collect{}
	s, err := Start(srv.url("gate"), loopbackDial, sink, testCfg())
	if err != nil {
		t.Fatal(err)
	}
	eventually(t, 3*time.Second, "units", func() bool { n, _, _ := sink.snapshot(); return len(n) >= 3 })
	s.Stop()
	s.Stop() // idempotent
	select {
	case <-srv.teardown:
	case <-time.After(2 * time.Second):
		t.Fatal("go2rtc was never told (no TEARDOWN): it would keep sending until its own timeout")
	}
	select {
	case <-srv.closed:
	case <-time.After(2 * time.Second):
		t.Fatal("the connection was not closed")
	}
	if !s.Wait(2 * time.Second) {
		t.Fatal("the session's goroutines did not exit")
	}
	n1, _, _ := sink.snapshot()
	time.Sleep(50 * time.Millisecond)
	n2, states, _ := sink.snapshot()
	if len(n1) != len(n2) {
		t.Errorf("the sink was called after Stop returned and the goroutines exited: %d then %d", len(n1), len(n2))
	}
	for _, st := range states {
		if strings.HasPrefix(st, "ended:") {
			t.Errorf("Stop reported %q: it is not an end on its own", st)
		}
	}
	eventually(t, 2*time.Second, "the goroutine count back to where it was", func() bool { return runtime.NumGoroutine() <= before+2 })
}

func TestStopDuringTheDialCancelsIt(t *testing.T) {
	started := make(chan struct{})
	returned := make(chan struct{})
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		close(started)
		<-ctx.Done()
		close(returned)
		return nil, ctx.Err()
	}
	sink := &collect{}
	s, err := Start("rtsp://192.168.1.6:8554/x", dial, sink, testCfg())
	if err != nil {
		t.Fatal(err)
	}
	<-started
	s.Stop()
	select {
	case <-returned:
	case <-time.After(2 * time.Second):
		t.Fatal("the dial in flight was not cancelled")
	}
	if !s.Wait(2 * time.Second) {
		t.Fatal("goroutines left")
	}
	if _, states, _ := sink.snapshot(); len(states) > 0 && states[len(states)-1] != "connecting" {
		t.Errorf("states %v", states)
	}
}

func TestADialThatReturnsAfterStopDoesNotLeakItsConnection(t *testing.T) {
	srv := newServer(t, serverOpts{})
	release := make(chan struct{})
	dial := func(ctx context.Context, network, addr string) (net.Conn, error) {
		<-release
		return loopbackDial(context.Background(), network, srv.ln.Addr().String()) // ignores ctx, like a dialler that cannot cancel
	}
	s, err := Start("rtsp://192.168.1.6:8554/x", dial, &collect{}, testCfg())
	if err != nil {
		t.Fatal(err)
	}
	s.Stop()
	close(release)
	if !s.Wait(2 * time.Second) {
		t.Fatal("goroutines left")
	}
	select {
	case <-srv.closed:
	case <-time.After(2 * time.Second):
		t.Fatal("the connection that arrived after Stop was kept open")
	}
}

func TestEndKinds(t *testing.T) {
	cases := []struct {
		name string
		o    serverOpts
		dial Dial
		want string
	}{
		{"dial fails", serverOpts{}, func(context.Context, string, string) (net.Conn, error) {
			return nil, fmt.Errorf("no route to 192.168.1.6")
		}, "dial"},
		{"server says no", serverOpts{describeErr: 404}, nil, "rtsp"},
		{"server closes after PLAY", serverOpts{closeAfter: true}, nil, "lost"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			srv := newServer(t, c.o)
			dial := c.dial
			if dial == nil {
				dial = loopbackDial
			}
			sink := &collect{}
			s, err := Start(srv.url("x"), dial, sink, testCfg())
			if err != nil {
				t.Fatal(err)
			}
			defer s.Stop()
			eventually(t, 3*time.Second, "ended:"+c.want, func() bool { return hasState(sink, "ended:"+c.want) })
			if !s.Wait(2 * time.Second) {
				t.Fatal("goroutines left after the end")
			}
			_, states, _ := sink.snapshot()
			n := 0
			for _, st := range states {
				if strings.HasPrefix(st, "ended:") {
					n++
				}
			}
			if n != 1 {
				t.Errorf("%d ended events: %v", n, states)
			}
			if states[len(states)-1] != "ended:"+c.want {
				t.Errorf("ended is not last: %v", states)
			}
			for _, st := range states {
				if strings.Contains(st, "192.168.1.6") || strings.Contains(st, "127.0.0.1") {
					t.Errorf("a state word names a host: %q", st)
				}
			}
		})
	}
}

func TestWatchdogEndsAStalledStreamAndClosesTheConnection(t *testing.T) {
	srv := newServer(t, serverOpts{})
	srv.opts.onPlay = func(w *frameWriter) {
		_ = w.idr(srv.sps, srv.pps)
		_ = w.pRef()
		// then silence, with the socket still open (RTCP-like traffic would not reset it either)
		for i := 0; i < 100; i++ {
			_ = w.raw([]byte{'$', 1, 0, 8, 0x80, 0xc8, 0, 1, 0, 0, 0, 0})
			time.Sleep(20 * time.Millisecond)
		}
	}
	cfg := testCfg()
	cfg.Watchdog = 250 * time.Millisecond
	sink := &collect{}
	s, err := Start(srv.url("x"), loopbackDial, sink, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	eventually(t, 3*time.Second, "the first units", func() bool { n, _, _ := sink.snapshot(); return len(n) >= 2 })
	t0 := time.Now()
	eventually(t, 3*time.Second, "ended:stalled", func() bool { return hasState(sink, "ended:stalled") })
	if d := time.Since(t0); d < 150*time.Millisecond {
		t.Errorf("ended after %v, before the watchdog", d)
	}
	select {
	case <-srv.closed:
	case <-time.After(2 * time.Second):
		t.Fatal("the stalled connection was not closed")
	}
	if !s.Wait(2 * time.Second) {
		t.Fatal("goroutines left")
	}
}

func TestAHealthyStreamIsNotKilledByTheWatchdog(t *testing.T) {
	srv := newServer(t, serverOpts{})
	srv.opts.onPlay = func(w *frameWriter) {
		_ = w.idr(srv.sps, srv.pps)
		for i := 0; i < 40; i++ {
			if w.pRef() != nil {
				return
			}
			time.Sleep(40 * time.Millisecond) // 1.6 s in all, 6 watchdog periods
		}
	}
	cfg := testCfg()
	cfg.Watchdog = 250 * time.Millisecond
	sink := &collect{}
	s, err := Start(srv.url("x"), loopbackDial, sink, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	eventually(t, 5*time.Second, "30 units", func() bool { n, _, _ := sink.snapshot(); return len(n) >= 30 })
	if hasState(sink, "ended:stalled") {
		t.Fatal("a healthy stream was ended")
	}
}

func TestAKeepaliveIsSentAndItsAnswerAbsorbed(t *testing.T) {
	srv := newServer(t, serverOpts{})
	srv.opts.onPlay = func(w *frameWriter) {
		_ = w.idr(srv.sps, srv.pps)
		for i := 0; i < 60; i++ {
			if w.pRef() != nil {
				return
			}
			time.Sleep(20 * time.Millisecond)
		}
	}
	cfg := testCfg()
	cfg.Keepalive = 100 * time.Millisecond
	sink := &collect{}
	s, err := Start(srv.url("x"), loopbackDial, sink, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	eventually(t, 5*time.Second, "an OPTIONS", func() bool { return srv.count("OPTIONS") >= 1 })
	eventually(t, 5*time.Second, "30 units after it", func() bool { n, _, _ := sink.snapshot(); return len(n) >= 30 })
	if hasState(sink, "ended:lost") || hasState(sink, "ended:protocol") {
		t.Fatal("the keepalive's answer desynchronised the stream")
	}
}

func TestASlowSinkNeverGrowsTheQueueAndTheStreamResyncsAtAnIDR(t *testing.T) {
	srv := newServer(t, serverOpts{})
	flooded := make(chan struct{})
	srv.opts.onPlay = func(w *frameWriter) {
		_ = w.idr(srv.sps, srv.pps)
		for i := 0; i < 3000; i++ { // reference pictures: the queue must resync, not drop one
			if w.pRef() != nil {
				return
			}
		}
		_ = w.idr(srv.sps, srv.pps)
		_ = w.pRef()
		close(flooded)
	}
	cfg := testCfg()
	cfg.QueueItems = 16
	cfg.Watchdog = 10 * time.Second
	sink := &collect{gate: make(chan struct{})}
	s, err := Start(srv.url("x"), loopbackDial, sink, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	maxItems := 0
	deadline := time.After(10 * time.Second)
wait:
	for {
		select {
		case <-flooded:
			break wait
		case <-deadline:
			t.Fatal("the flood never finished")
		default:
			if n, _ := s.q.len(); n > maxItems {
				maxItems = n
			}
			time.Sleep(time.Millisecond)
		}
	}
	if n, _ := s.q.len(); n > maxItems {
		maxItems = n
	}
	if maxItems > cfg.QueueItems {
		t.Fatalf("the queue grew to %d items (cap %d)", maxItems, cfg.QueueItems)
	}
	if d, r := s.q.counts(); d == 0 || r == 0 {
		t.Fatalf("dropped=%d resyncs=%d: nothing was shed", d, r)
	}
	close(sink.gate) // the sink catches up
	eventually(t, 5*time.Second, "the last picture", func() bool {
		n, _, _ := sink.snapshot()
		return len(n) > 0 && !n[len(n)-1].key && n[len(n)-1].b0 == 0x41
	})
	nals, _, _ := sink.snapshot()
	// what reaches the sink: one blocked unit from before, then (after the resync) the newest IDR run
	last := -1
	for i, n := range nals {
		if n.key {
			last = i
		}
	}
	if last < 0 {
		t.Fatal("no IDR reached the sink")
	}
	if len(nals) > cfg.QueueItems+2 {
		t.Errorf("%d units reached the sink out of 3000: the shed ones came back", len(nals))
	}
}

func TestNonReferenceUnitsAreDroppedFirstWithoutAResync(t *testing.T) {
	srv := newServer(t, serverOpts{})
	flooded := make(chan struct{})
	srv.opts.onPlay = func(w *frameWriter) {
		_ = w.idr(srv.sps, srv.pps)
		for i := 0; i < 400; i++ {
			if w.pNoRef() != nil {
				return
			}
		}
		close(flooded)
	}
	cfg := testCfg()
	cfg.QueueItems = 16
	cfg.Watchdog = 10 * time.Second
	sink := &collect{gate: make(chan struct{})}
	s, err := Start(srv.url("x"), loopbackDial, sink, cfg)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	select {
	case <-flooded:
	case <-time.After(10 * time.Second):
		t.Fatal("the flood never finished")
	}
	time.Sleep(50 * time.Millisecond)
	d, r := s.q.counts()
	if d == 0 {
		t.Fatal("nothing was dropped")
	}
	if r != 0 {
		t.Fatalf("%d resyncs: dropping non-reference pictures needs none", r)
	}
	close(sink.gate)
}

func TestAnOversizeUnitIsDroppedAndTheStreamWaitsForAnIDR(t *testing.T) {
	srv := newServer(t, serverOpts{})
	done := make(chan struct{})
	srv.opts.onPlay = func(w *frameWriter) {
		_ = w.idr(srv.sps, srv.pps)
		_ = w.pRef()
		// a 1.3 MB P picture in FU-A fragments of 60 KB
		w.ts += 3600
		frag := func(h1 byte) []byte { b := make([]byte, 60000); b[0], b[1] = 0x5c, h1; return b }
		_ = w.packet(frag(0x81), w.ts)
		for i := 0; i < 20; i++ {
			_ = w.packet(frag(0x01), w.ts)
		}
		_ = w.packet([]byte{0x5c, 0x41, 1}, w.ts)
		_ = w.pRef() // after the loss: must be dropped until an IDR
		_ = w.pRef()
		_ = w.idr(srv.sps, srv.pps)
		_ = w.pRef()
		close(done)
	}
	sink := &collect{}
	s, err := Start(srv.url("x"), loopbackDial, sink, testCfg())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("server never finished")
	}
	eventually(t, 3*time.Second, "the pictures after the second IDR", func() bool { n, _, _ := sink.snapshot(); return len(n) >= 4 })
	time.Sleep(100 * time.Millisecond)
	nals, _, _ := sink.snapshot()
	got := ""
	for _, n := range nals {
		if n.key {
			got += "I"
		} else {
			got += "P"
		}
	}
	if got != "IPIP" {
		t.Errorf("delivered %q, want IPIP: the first run, nothing between the loss and the second IDR, then that run", got)
	}
}

func TestBasicAuthIsAnsweredOnceAndNeverInTheRequestLine(t *testing.T) {
	srv := newServer(t, serverOpts{challenge: true})
	srv.opts.onPlay = func(w *frameWriter) { _ = w.idr(srv.sps, srv.pps) }
	sink := &collect{}
	u := "rtsp://admin:s3cret@" + srv.ln.Addr().String() + "/x"
	s, err := Start(u, loopbackDial, sink, testCfg())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	eventually(t, 3*time.Second, "the first unit", func() bool { n, _, _ := sink.snapshot(); return len(n) >= 1 })
	srv.mu.Lock()
	auth := append([]string(nil), srv.gotAuth...)
	srv.mu.Unlock()
	if len(auth) == 0 || !strings.HasPrefix(auth[0], "Basic ") {
		t.Fatalf("no Basic answer: %v", auth)
	}
	for _, l := range srv.requestLines() {
		if strings.Contains(l, "s3cret") || strings.Contains(l, "@") {
			t.Errorf("the credential is in a request line: %q", l)
		}
	}
	_, states, _ := sink.snapshot()
	for _, st := range states {
		if strings.Contains(st, "s3cret") {
			t.Errorf("a state word carries the credential: %q", st)
		}
	}
	if strings.Contains(s.Stats(), "s3cret") {
		t.Error("stats carry the credential")
	}
}

func TestASinkThatPanicsDoesNotTakeTheProcessDown(t *testing.T) {
	srv := newServer(t, serverOpts{})
	srv.opts.onPlay = func(w *frameWriter) { _ = w.idr(srv.sps, srv.pps); _ = w.pRef() }
	sink := &panicSink{}
	s, err := Start(srv.url("x"), loopbackDial, sink, testCfg())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	eventually(t, 3*time.Second, "calls", func() bool { sink.mu.Lock(); defer sink.mu.Unlock(); return sink.n >= 2 })
}

type panicSink struct {
	mu sync.Mutex
	n  int
}

func (p *panicSink) bump()                                 { p.mu.Lock(); p.n++; p.mu.Unlock(); panic("boom") }
func (p *panicSink) OnFormat(sps, pps []byte, w, h int)    { p.bump() }
func (p *panicSink) OnNal(nal []byte, pts int64, key bool) { p.bump() }
func (p *panicSink) OnState(s string)                      { p.bump() }

func TestStartRefusesNonsenseWithoutNamingIt(t *testing.T) {
	for _, bad := range []string{"http://192.168.1.6:8554/x", "rtsp://", ""} {
		_, err := Start(bad, loopbackDial, &collect{}, testCfg())
		if err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
	if _, err := Start("rtsp://h/x", loopbackDial, nil, testCfg()); err == nil {
		t.Error("a nil sink was accepted")
	}
	if _, err := Start("rtsp://h/x", nil, &collect{}, testCfg()); err == nil {
		t.Error("a nil dialler was accepted")
	}
	var nilSession *Session
	nilSession.Stop() // the not-started case
}

func TestParseInterleaved(t *testing.T) {
	cases := []struct {
		in       string
		rtp, rtc int
		ok       bool
	}{
		{"RTP/AVP/TCP;unicast;interleaved=0-1", 0, 1, true},
		{"RTP/AVP/TCP;unicast;interleaved=2-3", 2, 3, true},
		{"RTP/AVP/TCP;unicast;interleaved=4;ssrc=1", 4, 5, true},
		{"RTP/AVP/TCP;UNICAST; Interleaved=2-3;mode=play", 2, 3, true},
		{"RTP/AVP/TCP;unicast", 0, 0, false},
		{"", 0, 0, false},
		{"RTP/AVP/TCP;interleaved=x-y", 0, 0, false},
		{"RTP/AVP/TCP;interleaved=999-1000", 0, 0, false},
	}
	for _, c := range cases {
		rtp, rtcp, ok := parseInterleaved(c.in)
		if rtp != c.rtp || rtcp != c.rtc || ok != c.ok {
			t.Errorf("%q: got %d,%d,%v want %d,%d,%v", c.in, rtp, rtcp, ok, c.rtp, c.rtc, c.ok)
		}
	}
}

// go2rtc numbers channels by track index and ignores the request: video behind
// an audio track arrives on 2-3. The old app showed a black pane for this
// (0.52.1-alpha); the session must follow the reply.
func TestSessionFollowsTheSetupReplyChannel(t *testing.T) {
	for _, tc := range []struct {
		name, transport string
		ch              byte
	}{
		{"nonzero 2-3", "RTP/AVP/TCP;unicast;interleaved=2-3", 2},
		{"default 0-1", "RTP/AVP/TCP;unicast;interleaved=0-1", 0},
		{"no interleaved in reply", "RTP/AVP/TCP;unicast", 0},
	} {
		t.Run(tc.name, func(t *testing.T) {
			srv := newServer(t, serverOpts{transport: tc.transport, rtpChannel: tc.ch})
			srv.opts.onPlay = func(w *frameWriter) {
				// RTCP on the neighbouring channel must be ignored, not decoded.
				_ = w.raw([]byte{'$', tc.ch + 1, 0, 2, 0x80, 0xc8})
				streamSome(w, srv.sps, srv.pps)
			}
			sink := &collect{}
			s, err := Start(srv.url("gate_intercom"), loopbackDial, sink, testCfg())
			if err != nil {
				t.Fatal(err)
			}
			defer s.Stop()
			eventually(t, 3*time.Second, "6 NAL units", func() bool { n, _, _ := sink.snapshot(); return len(n) >= 6 })
			nals, _, _ := sink.snapshot()
			if !nals[0].key {
				t.Errorf("first unit is not an IDR: %+v", nals[0])
			}
		})
	}
}

// Video on a channel other than the one the reply named is still dropped.
func TestSessionIgnoresOtherChannels(t *testing.T) {
	srv := newServer(t, serverOpts{transport: "RTP/AVP/TCP;unicast;interleaved=2-3", rtpChannel: 0})
	srv.opts.onPlay = func(w *frameWriter) { streamSome(w, srv.sps, srv.pps) }
	sink := &collect{}
	s, err := Start(srv.url("gate_intercom"), loopbackDial, sink, testCfg())
	if err != nil {
		t.Fatal(err)
	}
	defer s.Stop()
	time.Sleep(400 * time.Millisecond)
	if n, _, _ := sink.snapshot(); len(n) != 0 {
		t.Fatalf("%d units delivered from a channel the server did not announce", len(n))
	}
}
